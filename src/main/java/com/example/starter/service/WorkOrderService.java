package com.example.starter.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.web.ConflictException;
import com.example.starter.web.dto.MergeRequest;
import com.example.starter.web.dto.MergeResponse;
import com.example.starter.web.dto.RestoreChainView;
import com.example.starter.web.dto.SealResponse;
import com.example.starter.web.dto.WorkOrderView;

/**
 * 工单服务：工单合并（原单引用新单并保留轨迹）、日结封存（终态）、详情与复电确认链复算。
 */
@Service
public class WorkOrderService {

    private static final Map<String, Integer> LEVEL_RANK = Map.of(
            "NONE", 0, "SECOND", 1, "FIRST", 2, "SPECIAL", 3);

    private final JdbcTemplate jdbc;
    private final WorkOrderStore store;
    private final EscalationService escalation;
    private final GeneratorDispatchService generatorDispatches;

    public WorkOrderService(JdbcTemplate jdbc, WorkOrderStore store, EscalationService escalation,
            GeneratorDispatchService generatorDispatches) {
        this.jdbc = jdbc;
        this.store = store;
        this.escalation = escalation;
        this.generatorDispatches = generatorDispatches;
    }

    /**
     * 合并工单：源单须为同馈线的待派单/挂单；合并生成新单，源单置为 MERGED 并引用新单。
     */
    @Transactional
    public MergeResponse merge(MergeRequest req) {
        List<Long> ids = req.sourceOrderIds().stream().distinct().toList();
        if (ids.size() < 2) {
            throw new IllegalArgumentException("合并至少需要两张源工单");
        }
        List<WorkOrderRow> sources = ids.stream().map(store::requireById).toList();
        for (WorkOrderRow source : sources) {
            if (!"PENDING".equals(source.status()) && !"QUEUED".equals(source.status())) {
                throw new ConflictException("工单状态不可合并: id=" + source.id() + " status=" + source.status());
            }
        }
        String feeder = sources.get(0).feederId();
        if (sources.stream().anyMatch(o -> !feeder.equals(o.feederId()))) {
            throw new ConflictException("仅同馈线工单可合并");
        }
        String faultPoint = sources.stream().map(WorkOrderRow::faultPoint)
                .collect(Collectors.joining("+"));
        int affected = sources.stream().mapToInt(WorkOrderRow::affectedUsers).sum();
        String level = sources.stream().map(WorkOrderRow::importantLevel)
                .max((a, b) -> Integer.compare(LEVEL_RANK.get(a), LEVEL_RANK.get(b))).orElse("NONE");
        LocalDateTime outageStart = sources.stream().map(WorkOrderRow::outageStartAt)
                .min(LocalDateTime::compareTo).orElseThrow();

        long newId = store.insertOrder(feeder, faultPoint, affected, level, "PENDING", outageStart);
        store.addEvent(newId, "CREATED_BY_MERGE", "由工单" + ids + "合并生成");
        for (WorkOrderRow source : sources) {
            jdbc.update("UPDATE work_order SET status = 'MERGED', merged_into_id = ?, version = version + 1"
                    + " WHERE id = ?", newId, source.id());
            store.addEvent(source.id(), "MERGED", "合并至工单" + newId);
            // 工单合并：原子撤销未结束发电车调度并释放车辆，保留供电证据
            generatorDispatches.cancelForWorkOrder(source.id(), "工单合并至" + newId + ", 撤销未结束发电车调度");
        }
        return new MergeResponse(newId, ids);
    }

    /**
     * 日结封存：仅已复电工单可封存；封存为终态，重复封存返回幂等成功。
     */
    @Transactional
    public SealResponse seal(long workOrderId) {
        WorkOrderRow order = store.requireById(workOrderId);
        if ("SEALED".equals(order.status())) {
            return new SealResponse(workOrderId, "SEALED", true);
        }
        if (!"RESTORED".equals(order.status())) {
            throw new ConflictException("仅已复电工单可日结封存: " + order.status());
        }
        jdbc.update("UPDATE work_order SET status = 'SEALED', version = version + 1 WHERE id = ?", workOrderId);
        store.addEvent(workOrderId, "SEALED", "日结封存");
        return new SealResponse(workOrderId, "SEALED", false);
    }

    /**
     * 工单详情：查询前评估发电车升级，返回状态轨迹与发电车事件。
     */
    @Transactional
    public WorkOrderView view(long workOrderId) {
        escalation.evaluate(workOrderId);
        WorkOrderRow order = store.requireById(workOrderId);
        return new WorkOrderView(order.id(), order.feederId(), order.faultPoint(), order.affectedUsers(),
                order.importantLevel(), order.status(), order.teamId(), order.outageStartAt(),
                order.restoredAt(), order.mergedIntoId(), escalation.generatorStartedAt(workOrderId),
                order.version(), store.listEvents(workOrderId));
    }

    /**
     * 复电确认链复算：确认记录按时间升序，附负荷恢复入账信息。
     */
    @Transactional(readOnly = true)
    public RestoreChainView restoreChain(long workOrderId) {
        store.requireById(workOrderId);
        List<String> confirmations = jdbc.query(
                "SELECT confirmer_role, confirmer, created_at FROM restore_confirmation"
                + " WHERE work_order_id = ? ORDER BY created_at ASC, id ASC",
                (rs, rowNum) -> rs.getString("confirmer_role") + ":" + rs.getString("confirmer")
                        + "@" + rs.getTimestamp("created_at").toLocalDateTime(),
                workOrderId);
        List<Object[]> recovery = jdbc.query(
                "SELECT load_kw, recorded_at FROM load_recovery WHERE work_order_id = ?",
                (rs, rowNum) -> new Object[] {rs.getBigDecimal(1), rs.getTimestamp(2).toLocalDateTime()},
                workOrderId);
        BigDecimal loadKw = recovery.isEmpty() ? null : (BigDecimal) recovery.get(0)[0];
        LocalDateTime recordedAt = recovery.isEmpty() ? null : (LocalDateTime) recovery.get(0)[1];
        boolean restored = "RESTORED".equals(store.requireById(workOrderId).status())
                || "SEALED".equals(store.requireById(workOrderId).status());
        return new RestoreChainView(workOrderId, restored, confirmations, loadKw, recordedAt);
    }
}
