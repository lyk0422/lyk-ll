package com.example.starter.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import com.example.starter.domain.DefectLevel;
import com.example.starter.domain.DefectStatus;
import com.example.starter.domain.Rows.DefectRow;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 逾期升级：结构级缺陷超过首次登记 + 7 日未闭环即升级，
 * 告警记录保留首次缺陷登记时刻，每缺陷仅一条。
 */
@Service
public class EscalationService {

    /** 结构级缺陷维修期限：7 日，恰第 7 日闭环有效。 */
    static final int REPAIR_DEADLINE_DAYS = 7;

    private final JdbcClient jdbc;
    private final Clock clock;

    public EscalationService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * 扫描所有开放结构级缺陷，对逾期者补建升级告警（幂等）。供查询入口惰性触发。
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public int escalateOverdueOpenDefects() {
        LocalDateTime now = LocalDateTime.now(clock);
        List<Long> openStructuralIds = jdbc.sql("""
                SELECT id FROM defect WHERE level = 'STRUCTURAL' AND status = 'OPEN' ORDER BY id
                """)
                .query(Long.class)
                .list();
        int created = 0;
        for (Long defectId : openStructuralIds) {
            DefectRow defect = loadDefect(defectId);
            if (ensureEscalationIfOverdue(defect, now)) {
                created++;
            }
        }
        return created;
    }

    /**
     * 若缺陷已逾期（now 晚于 首次登记+7日）且尚无告警，则写入升级告警；返回是否新建。
     * 恰第 7 日（now == 登记+7日）不算逾期。
     */
    public boolean ensureEscalationIfOverdue(DefectRow defect, LocalDateTime now) {
        if (defect.level() != DefectLevel.STRUCTURAL) {
            return false;
        }
        LocalDateTime deadline = defect.registeredAt().plusDays(REPAIR_DEADLINE_DAYS);
        if (!now.isAfter(deadline)) {
            return false;
        }
        boolean exists = jdbc.sql("SELECT COUNT(*) FROM escalation_alert WHERE defect_id = ?")
                .param(defect.id())
                .query(Long.class)
                .single() > 0;
        if (exists) {
            return false;
        }
        jdbc.sql("""
                INSERT INTO escalation_alert (defect_id, first_registered_at, escalated_at, message)
                VALUES (?, ?, ?, ?)
                """)
                .params(defect.id(), defect.registeredAt(), now,
                        "结构级缺陷逾期未闭环，限功率维持并升级处理")
                .update();
        return true;
    }

    private DefectRow loadDefect(long defectId) {
        return jdbc.sql("""
                SELECT id, inspection_id, blade_id, position, defect_type, length_mm, depth_ratio,
                       level, status, registered_at, closed_at
                FROM defect WHERE id = ?
                """)
                .param(defectId)
                .query((rs, rowNum) -> new DefectRow(
                        rs.getLong("id"),
                        rs.getObject("inspection_id", Long.class),
                        rs.getLong("blade_id"),
                        rs.getString("position"),
                        rs.getString("defect_type"),
                        rs.getBigDecimal("length_mm"),
                        rs.getBigDecimal("depth_ratio"),
                        DefectLevel.valueOf(rs.getString("level")),
                        DefectStatus.valueOf(rs.getString("status")),
                        rs.getTimestamp("registered_at").toLocalDateTime(),
                        rs.getTimestamp("closed_at") == null ? null : rs.getTimestamp("closed_at").toLocalDateTime()))
                .single();
    }
}
