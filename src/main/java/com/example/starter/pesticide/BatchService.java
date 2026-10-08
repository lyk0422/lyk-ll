package com.example.starter.pesticide;

import com.example.starter.common.ApiException;
import com.example.starter.common.IdempotentExecutor;
import com.example.starter.pest.PestCaseService;
import com.example.starter.treatment.PlacementService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

/**
 * 药剂批号服务：批号查询与批号召回。
 *
 * <p>召回以批号为粒度、一经生效不得撤回；召回与投放在同一事务内通过批号行锁互斥，
 * 原子阻断该批号新投放，并联动全部关联处置链：在途链转待补投，已解除归档链仅追加风险。
 */
@Service
public class BatchService {

    /** 批号可用。 */
    public static final String STATUS_ACTIVE = "ACTIVE";
    /** 批号已召回（不可撤回）。 */
    public static final String STATUS_RECALLED = "RECALLED";

    /** 在途链转待补投。 */
    public static final String IMPACT_PENDING_REPLACEMENT = "PENDING_REPLACEMENT";
    /** 已解除归档链仅追加风险。 */
    public static final String IMPACT_ARCHIVED_RISK = "ARCHIVED_RISK";

    private static final RowMapper<BatchRow> MAPPER = (rs, rowNum) -> new BatchRow(
            rs.getLong("id"),
            rs.getLong("pesticide_id"),
            rs.getString("batch_no"),
            rs.getBigDecimal("quantity"),
            rs.getObject("expiry_date", LocalDate.class),
            rs.getString("status"),
            rs.getInt("version"),
            rs.getString("recall_reason"),
            rs.getObject("recalled_at", LocalDateTime.class),
            rs.getObject("created_at", LocalDateTime.class));

    private final JdbcClient jdbc;
    private final IdempotentExecutor idempotent;
    private final Clock clock;

    public BatchService(JdbcClient jdbc, IdempotentExecutor idempotent, Clock clock) {
        this.jdbc = jdbc;
        this.idempotent = idempotent;
        this.clock = clock;
    }

    /**
     * 批号召回：版本匹配方可生效，成功后版本加 1；同事务内阻断该批号新投放，
     * 找出全部关联处置链——在途链转待补投（原投放证据保留但不得用于观察解除），
     * 已解除归档链仅追加风险、不重开旧链。召回一旦生效不得撤回。
     *
     * <p>锁顺序与投放/补投一致（先处置链行锁、再批号行锁），避免与补投、解除竞争时死锁。
     */
    public BatchDtos.RecallResponse recall(long batchId, BatchDtos.RecallRequest request) {
        BatchRow snapshot = mustFind(batchId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("batchId", batchId);
        payload.put("batchNo", snapshot.batchNo());
        payload.put("reason", request.reason());
        payload.put("operator", request.operator());
        payload.put("expectedVersion", request.expectedVersion());
        return idempotent.execute(request.requestId(), payload, BatchDtos.RecallResponse.class, () -> {
            // 先按当前已提交数据发现关联处置链并加行锁（与补投、解除的锁顺序一致）
            for (Long caseId : affectedCaseIds(batchId)) {
                jdbc.sql("SELECT id FROM pest_case WHERE id = ? FOR UPDATE")
                        .param(caseId)
                        .query(Long.class)
                        .single();
            }
            BatchRow batch = findForUpdate(batchId);
            if (batch.version() != request.expectedVersion()) {
                throw ApiException.conflict("expectedVersion 已过期，请查询最新版本后重试");
            }
            if (!STATUS_ACTIVE.equals(batch.status())) {
                throw ApiException.conflict("批号已召回，召回一旦生效不得撤回或重复召回");
            }
            LocalDateTime now = LocalDateTime.now(clock);
            jdbc.sql("UPDATE pesticide_batch SET status = ?, version = version + 1,"
                            + " recall_reason = ?, recalled_at = ? WHERE id = ?")
                    .param(STATUS_RECALLED).param(request.reason()).param(now).param(batchId)
                    .update();
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbc.sql("INSERT INTO batch_recall (batch_id, reason, operator, created_at)"
                            + " VALUES (?, ?, ?, ?)")
                    .param(batchId).param(request.reason()).param(request.operator()).param(now)
                    .update(keyHolder, "id");
            long recallId = keyHolder.getKey().longValue();
            // 持有批号行锁后重查：投放生效须持有同一批号行锁，此刻关联集合已稳定
            List<long[]> affected = jdbc.sql("SELECT id, case_id FROM treatment_placement"
                            + " WHERE batch_id = ? AND status = ? AND recall_status = ?")
                    .param(batchId).param(PlacementService.STATUS_EFFECTIVE)
                    .param(PlacementService.RECALL_NONE)
                    .query((rs, rowNum) -> new long[]{rs.getLong(1), rs.getLong(2)})
                    .list();
            List<BatchDtos.RecallImpactResponse> impacts = new ArrayList<>();
            for (long[] pair : affected) {
                long placementId = pair[0];
                long caseId = pair[1];
                jdbc.sql("UPDATE treatment_placement SET recall_status = ? WHERE id = ?")
                        .param(PlacementService.RECALL_RECALLED).param(placementId)
                        .update();
                String caseStatus = jdbc.sql("SELECT status FROM pest_case WHERE id = ? FOR UPDATE")
                        .param(caseId)
                        .query(String.class)
                        .single();
                String impactType;
                if (PestCaseService.STATUS_RELEASED.equals(caseStatus)) {
                    impactType = IMPACT_ARCHIVED_RISK;
                } else {
                    impactType = IMPACT_PENDING_REPLACEMENT;
                    if (!PestCaseService.STATUS_PENDING_REPLACEMENT.equals(caseStatus)) {
                        jdbc.sql("UPDATE pest_case SET status = ?, release_applied_at = NULL,"
                                        + " disinfect_confirmed_at = NULL,"
                                        + " storage_confirmed_at = NULL WHERE id = ?")
                                .param(PestCaseService.STATUS_PENDING_REPLACEMENT).param(caseId)
                                .update();
                    }
                }
                jdbc.sql("INSERT INTO recall_impact (recall_id, placement_id, case_id, impact_type,"
                                + " created_at) VALUES (?, ?, ?, ?, ?)")
                        .param(recallId).param(placementId).param(caseId).param(impactType).param(now)
                        .update();
                impacts.add(new BatchDtos.RecallImpactResponse(placementId, caseId, impactType));
            }
            return new BatchDtos.RecallResponse(recallId, batchId, batch.batchNo(),
                    STATUS_RECALLED, batch.version() + 1, impacts);
        });
    }

    /**
     * 当前已生效且未召回的该批号投放所属处置链（用于召回前按序加处置链行锁）。
     */
    private List<Long> affectedCaseIds(long batchId) {
        return jdbc.sql("SELECT DISTINCT case_id FROM treatment_placement"
                        + " WHERE batch_id = ? AND status = ? AND recall_status = ? ORDER BY case_id")
                .param(batchId).param(PlacementService.STATUS_EFFECTIVE)
                .param(PlacementService.RECALL_NONE)
                .query(Long.class)
                .list();
    }

    /**
     * 药剂批号列表。
     */
    public List<BatchDtos.BatchResponse> listByPesticide(long pesticideId) {
        return jdbc.sql("SELECT * FROM pesticide_batch WHERE pesticide_id = ? ORDER BY id")
                .param(pesticideId)
                .query(MAPPER)
                .list()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * 召回详情：含全部关联处置链影响（原投放、召回、补投对应关系）。
     */
    public BatchDtos.RecallResponse recallDetail(long recallId) {
        Map<String, Object> recall = jdbc.sql("SELECT r.id, r.batch_id, b.batch_no, b.status,"
                        + " b.version FROM batch_recall r JOIN pesticide_batch b ON r.batch_id = b.id"
                        + " WHERE r.id = ?")
                .param(recallId)
                .query((rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<String, Object>();
                    row.put("id", rs.getLong("id"));
                    row.put("batchId", rs.getLong("batch_id"));
                    row.put("batchNo", rs.getString("batch_no"));
                    row.put("status", rs.getString("status"));
                    row.put("version", rs.getInt("version"));
                    return row;
                })
                .optional()
                .orElseThrow(() -> ApiException.notFound("召回事件不存在: " + recallId));
        List<BatchDtos.RecallImpactResponse> impacts = jdbc.sql(
                        "SELECT placement_id, case_id, impact_type FROM recall_impact"
                                + " WHERE recall_id = ? ORDER BY id")
                .param(recallId)
                .query((rs, rowNum) -> new BatchDtos.RecallImpactResponse(
                        rs.getLong("placement_id"), rs.getLong("case_id"),
                        rs.getString("impact_type")))
                .list();
        return new BatchDtos.RecallResponse(
                (Long) recall.get("id"),
                (Long) recall.get("batchId"),
                (String) recall.get("batchNo"),
                (String) recall.get("status"),
                (Integer) recall.get("version"),
                impacts);
    }

    /**
     * 按主键查询批号，不存在时抛出 404。
     */
    public BatchRow mustFind(long batchId) {
        return jdbc.sql("SELECT * FROM pesticide_batch WHERE id = ?")
                .param(batchId)
                .query(MAPPER)
                .optional()
                .orElseThrow(() -> ApiException.notFound("批号不存在: " + batchId));
    }

    /**
     * 行级锁定读取批号（用于同事务内重查召回状态与扣减批号库存）。
     */
    public BatchRow findForUpdate(long batchId) {
        return jdbc.sql("SELECT * FROM pesticide_batch WHERE id = ? FOR UPDATE")
                .param(batchId)
                .query(MAPPER)
                .optional()
                .orElseThrow(() -> ApiException.notFound("批号不存在: " + batchId));
    }

    private BatchDtos.BatchResponse toResponse(BatchRow row) {
        return new BatchDtos.BatchResponse(row.id(), row.pesticideId(), row.batchNo(),
                row.quantity(), row.expiryDate(), row.status(), row.version(),
                row.recallReason(), row.recalledAt());
    }
}
