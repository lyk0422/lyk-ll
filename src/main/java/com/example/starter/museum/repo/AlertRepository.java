package com.example.starter.museum.repo;

import com.example.starter.museum.domain.AlertStatus;
import com.example.starter.museum.domain.po.EnvironmentAlertPo;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

/** 环境异常仓储：一个展柜同一时段只允许一个未关闭（OPEN/URGENT/AWAIT_REVIEW）异常。 */
@Repository
public class AlertRepository {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public AlertRepository(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public Long insert(EnvironmentAlertPo po) {
        jdbc.update("""
                INSERT INTO environment_alert
                  (alert_no, showcase_id, contract_id, trigger_reading_id, trigger_time,
                   status, created_at, escalated_at, recovered_at, resolved_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, po.alertNo, po.showcaseId, po.contractId, po.triggerReadingId,
                Sql.ts(po.triggerTime), po.status.name(), Sql.ts(po.createdAt),
                Sql.ts(po.escalatedAt), Sql.ts(po.recoveredAt), Sql.ts(po.resolvedAt));
        return jdbc.queryForObject("SELECT id FROM environment_alert WHERE alert_no = ?",
                Long.class, po.alertNo);
    }

    public Optional<EnvironmentAlertPo> findByNo(String alertNo) {
        return jdbc.query("SELECT * FROM environment_alert WHERE alert_no = ?",
                RowMappers.ALERT, alertNo).stream().findFirst();
    }

    public Optional<EnvironmentAlertPo> findById(Long id) {
        return jdbc.query("SELECT * FROM environment_alert WHERE id = ?",
                RowMappers.ALERT, id).stream().findFirst();
    }

    /** 展柜当前未关闭异常（OPEN/URGENT/AWAIT_REVIEW），无则空。 */
    public Optional<EnvironmentAlertPo> findOpenByShowcase(Long showcaseId) {
        return jdbc.query("""
                        SELECT * FROM environment_alert
                        WHERE showcase_id = ? AND status IN ('OPEN', 'URGENT', 'AWAIT_REVIEW')
                        ORDER BY id LIMIT 1
                        """, RowMappers.ALERT, showcaseId)
                .stream().findFirst();
    }

    public List<EnvironmentAlertPo> findByContract(Long contractId) {
        return jdbc.query(
                "SELECT * FROM environment_alert WHERE contract_id = ? ORDER BY id",
                RowMappers.ALERT, contractId);
    }

    public List<EnvironmentAlertPo> findByStatuses(AlertStatus... statuses) {
        String in = String.join(",", java.util.Collections.nCopies(statuses.length, "?"));
        Object[] args = new Object[statuses.length];
        for (int i = 0; i < statuses.length; i++) {
            args[i] = statuses[i].name();
        }
        return jdbc.query(
                "SELECT * FROM environment_alert WHERE status IN (" + in + ") ORDER BY id",
                RowMappers.ALERT, args);
    }

    /** 条件状态迁移：仅当前状态匹配时更新，返回是否生效（并发安全）。 */
    public int compareAndSetStatus(Long id, AlertStatus expected, AlertStatus target) {
        return jdbc.update(
                "UPDATE environment_alert SET status = ? WHERE id = ? AND status = ?",
                target.name(), id, expected.name());
    }

    public int markEscalated(Long id) {
        return jdbc.update(
                "UPDATE environment_alert SET status = 'URGENT', escalated_at = ? WHERE id = ? AND status = 'OPEN'",
                Sql.ts(clock.instant()), id);
    }

    public int markRecovered(Long id) {
        return jdbc.update(
                "UPDATE environment_alert SET status = 'AWAIT_REVIEW', recovered_at = ? WHERE id = ? AND status IN ('OPEN','URGENT')",
                Sql.ts(clock.instant()), id);
    }

    public int markResolved(Long id) {
        return jdbc.update(
                "UPDATE environment_alert SET status = 'RESOLVED', resolved_at = ? WHERE id = ? AND status = 'AWAIT_REVIEW'",
                Sql.ts(clock.instant()), id);
    }
}
