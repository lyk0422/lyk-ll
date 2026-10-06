package com.example.starter.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.starter.domain.Rows.CurtailmentRow;
import com.example.starter.domain.Rows.DefectRow;
import com.example.starter.web.dto.ApiDtos.BladeStatusResponse;
import com.example.starter.web.dto.ApiDtos.CurtailmentView;
import com.example.starter.web.dto.ApiDtos.DefectDetailResponse;
import com.example.starter.web.dto.ApiDtos.EscalationView;
import com.example.starter.web.dto.ApiDtos.RecurrenceDefectPoint;
import com.example.starter.web.dto.ApiDtos.RecurrenceStatsResponse;
import com.example.starter.web.dto.ApiDtos.RecurrenceTypeStats;
import com.example.starter.web.dto.ApiDtos.ReinspectView;
import com.example.starter.web.dto.ApiDtos.RepairLedgerView;
import com.example.starter.web.dto.ApiDtos.RepairRecordResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 查询服务：限功率链、维修台账、复发统计均可由底表复算，结果按稳定键排序，重复查询一致。
 * 查询前惰性执行逾期升级扫描，保证状态及时且幂等。
 */
@Service
public class QueryService {

    private final JdbcClient jdbc;
    private final DbSupport db;
    private final EscalationService escalationService;

    public QueryService(JdbcClient jdbc, DbSupport db, EscalationService escalationService) {
        this.jdbc = jdbc;
        this.db = db;
        this.escalationService = escalationService;
    }

    /**
     * 叶片限功率状态：存在任一未撤销限功率即为限功率中。
     */
    @Transactional
    public BladeStatusResponse bladeStatus(long bladeId) {
        db.requireBlade(bladeId);
        escalationService.escalateOverdueOpenDefects();
        List<CurtailmentView> active = activeCurtailments(bladeId);
        return new BladeStatusResponse(bladeId, !active.isEmpty(), active);
    }

    /**
     * 限功率链：按开始时刻与主键稳定排序。
     */
    @Transactional
    public List<CurtailmentView> curtailmentChain(long bladeId) {
        db.requireBlade(bladeId);
        escalationService.escalateOverdueOpenDefects();
        return jdbc.sql("""
                SELECT id, blade_id, reason, source_defect_id, source_evaluation_id, started_at, lifted_at
                FROM curtailment WHERE blade_id = ? ORDER BY started_at, id
                """)
                .param(bladeId)
                .query((rs, rowNum) -> toView(db.mapCurtailment(rs, rowNum)))
                .list();
    }

    /**
     * 维修台账：维修单按建单时刻与主键排序，维修记录与复检记录随行附出。
     */
    @Transactional
    public List<RepairLedgerView> repairLedger(long bladeId) {
        db.requireBlade(bladeId);
        escalationService.escalateOverdueOpenDefects();
        List<RepairLedgerView> orders = jdbc.sql("""
                SELECT id, defect_id, status, deadline_at, created_at, closed_at
                FROM repair_order WHERE blade_id = ? ORDER BY created_at, id
                """)
                .param(bladeId)
                .query((rs, rowNum) -> new RepairLedgerView(
                        rs.getLong("id"),
                        rs.getLong("defect_id"),
                        rs.getString("status"),
                        rs.getTimestamp("deadline_at").toLocalDateTime(),
                        rs.getTimestamp("created_at").toLocalDateTime(),
                        rs.getTimestamp("closed_at") == null ? null : rs.getTimestamp("closed_at").toLocalDateTime(),
                        List.of(), List.of()))
                .list();
        List<RepairLedgerView> result = new ArrayList<>(orders.size());
        for (RepairLedgerView order : orders) {
            List<RepairRecordResponse> records = jdbc.sql("""
                    SELECT id, repair_order_id, technician, description, repaired_at
                    FROM repair_record WHERE repair_order_id = ? ORDER BY repaired_at, id
                    """)
                    .param(order.orderId())
                    .query((rs, rowNum) -> new RepairRecordResponse(
                            rs.getLong("id"),
                            rs.getLong("repair_order_id"),
                            rs.getString("technician"),
                            rs.getString("description"),
                            rs.getTimestamp("repaired_at").toLocalDateTime()))
                    .list();
            List<ReinspectView> reinspections = jdbc.sql("""
                    SELECT id, result, inspector, inspected_at
                    FROM reinspection_record WHERE repair_order_id = ? ORDER BY inspected_at, id
                    """)
                    .param(order.orderId())
                    .query((rs, rowNum) -> new ReinspectView(
                            rs.getLong("id"),
                            rs.getString("result"),
                            rs.getString("inspector"),
                            rs.getTimestamp("inspected_at").toLocalDateTime()))
                    .list();
            result.add(new RepairLedgerView(order.orderId(), order.defectId(), order.status(),
                    order.deadlineAt(), order.createdAt(), order.closedAt(), records, reinspections));
        }
        return result;
    }

    /**
     * 复发统计：按缺陷类型分组，逐次计算 90 日窗口内累计次数，恰第 2 次为触发点。
     */
    @Transactional
    public RecurrenceStatsResponse recurrenceStats(long bladeId) {
        db.requireBlade(bladeId);
        List<DefectRow> defects = jdbc.sql("""
                SELECT id, inspection_id, blade_id, position, defect_type, length_mm, depth_ratio,
                       level, status, registered_at, closed_at
                FROM defect WHERE blade_id = ? ORDER BY registered_at, id
                """)
                .param(bladeId)
                .query((rs, rowNum) -> new DefectRow(
                        rs.getLong("id"),
                        rs.getObject("inspection_id", Long.class),
                        rs.getLong("blade_id"),
                        rs.getString("position"),
                        rs.getString("defect_type"),
                        rs.getBigDecimal("length_mm"),
                        rs.getBigDecimal("depth_ratio"),
                        com.example.starter.domain.DefectLevel.valueOf(rs.getString("level")),
                        com.example.starter.domain.DefectStatus.valueOf(rs.getString("status")),
                        rs.getTimestamp("registered_at").toLocalDateTime(),
                        rs.getTimestamp("closed_at") == null ? null : rs.getTimestamp("closed_at").toLocalDateTime()))
                .list();

        Map<Long, Long> evaluationByTriggerDefect = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT id, trigger_defect_id FROM root_cause_evaluation WHERE blade_id = ? ORDER BY id
                """)
                .param(bladeId)
                .query((rs, rowNum) -> Map.entry(rs.getLong("id"), rs.getLong("trigger_defect_id")))
                .list()
                .forEach(e -> evaluationByTriggerDefect.put(e.getValue(), e.getKey()));

        Map<String, List<DefectRow>> byType = new LinkedHashMap<>();
        for (DefectRow defect : defects) {
            byType.computeIfAbsent(defect.defectType(), k -> new ArrayList<>()).add(defect);
        }

        List<RecurrenceTypeStats> stats = new ArrayList<>();
        for (Map.Entry<String, List<DefectRow>> entry : byType.entrySet()) {
            List<DefectRow> group = entry.getValue();
            List<RecurrenceDefectPoint> points = new ArrayList<>(group.size());
            Long evaluationId = null;
            for (DefectRow defect : group) {
                LocalDateTime windowStart = defect.registeredAt()
                        .minusDays(DefectService.RECURRENCE_WINDOW_DAYS);
                int windowCount = 0;
                for (DefectRow other : group) {
                    if (!other.registeredAt().isBefore(windowStart)
                            && !other.registeredAt().isAfter(defect.registeredAt())) {
                        windowCount++;
                    }
                }
                boolean triggerPoint = windowCount == 2;
                if (triggerPoint && evaluationByTriggerDefect.containsKey(defect.id())) {
                    evaluationId = evaluationByTriggerDefect.get(defect.id());
                }
                points.add(new RecurrenceDefectPoint(defect.id(), defect.registeredAt(),
                        windowCount, triggerPoint));
            }
            stats.add(new RecurrenceTypeStats(entry.getKey(), group.size(), points,
                    evaluationId != null, evaluationId));
        }
        return new RecurrenceStatsResponse(bladeId, stats);
    }

    /**
     * 缺陷详情：含逾期升级告警记录。
     */
    @Transactional
    public DefectDetailResponse defectDetail(long defectId) {
        escalationService.escalateOverdueOpenDefects();
        DefectRow defect = db.requireDefect(defectId);
        List<EscalationView> escalations = jdbc.sql("""
                SELECT id, defect_id, first_registered_at, escalated_at, message
                FROM escalation_alert WHERE defect_id = ? ORDER BY id
                """)
                .param(defectId)
                .query((rs, rowNum) -> new EscalationView(
                        rs.getLong("id"),
                        rs.getLong("defect_id"),
                        rs.getTimestamp("first_registered_at").toLocalDateTime(),
                        rs.getTimestamp("escalated_at").toLocalDateTime(),
                        rs.getString("message")))
                .list();
        return new DefectDetailResponse(defect.id(), defect.bladeId(), defect.position(),
                defect.defectType(), defect.level().name(), defect.status().name(),
                defect.registeredAt(), defect.closedAt(), escalations);
    }

    private List<CurtailmentView> activeCurtailments(long bladeId) {
        return jdbc.sql("""
                SELECT id, blade_id, reason, source_defect_id, source_evaluation_id, started_at, lifted_at
                FROM curtailment WHERE blade_id = ? AND lifted_at IS NULL ORDER BY started_at, id
                """)
                .param(bladeId)
                .query((rs, rowNum) -> toView(db.mapCurtailment(rs, rowNum)))
                .list();
    }

    private CurtailmentView toView(CurtailmentRow row) {
        return new CurtailmentView(row.id(), row.bladeId(), row.reason().name(),
                row.sourceDefectId(), row.sourceEvaluationId(), row.startedAt(), row.liftedAt());
    }
}
