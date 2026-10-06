package com.example.starter.discrepancy;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 差异单行级守卫：供红冲流程在同事务内调用。
 * 未结差异（OPEN 待答复 / ESCALATED 已升级人工）只暂停对应行的新增红冲；
 * 提交满 72 小时（UTC）未获销方答复的差异，恰满即升级为 ESCALATED，
 * 升级与审计事件在调用方事务内一并提交。
 */
@Component
public class DiscrepancyGuard {

    /**
     * 差异未答复升级人工的时限：72 小时。
     */
    static final Duration ESCALATION_AFTER = Duration.ofHours(72);

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public DiscrepancyGuard(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * 将指定蓝票下已满 72 小时（恰满即升级）的 OPEN 差异升级为 ESCALATED，
     * 并同事务写入 ESCALATED 审计事件；返回升级数量。
     */
    public int escalateExpired(long blueInvoiceId) {
        Timestamp threshold = Timestamp.from(clock.instant().minus(ESCALATION_AFTER));
        List<Long> expired = jdbc.queryForList(
                "SELECT id FROM discrepancy"
                        + " WHERE blue_invoice_id = ? AND status = 'OPEN' AND created_at <= ?",
                Long.class, blueInvoiceId, threshold);
        for (Long id : expired) {
            jdbc.update("UPDATE discrepancy SET status = 'ESCALATED' WHERE id = ? AND status = 'OPEN'", id);
            insertEvent(id, "ESCALATED");
        }
        return expired.size();
    }

    /**
     * 单张差异单若已满 72 小时未答复则升级；返回是否发生了升级。
     */
    public boolean escalateIfExpired(long discrepancyId) {
        Timestamp threshold = Timestamp.from(clock.instant().minus(ESCALATION_AFTER));
        int updated = jdbc.update(
                "UPDATE discrepancy SET status = 'ESCALATED'"
                        + " WHERE id = ? AND status = 'OPEN' AND created_at <= ?",
                discrepancyId, threshold);
        if (updated > 0) {
            insertEvent(discrepancyId, "ESCALATED");
            return true;
        }
        return false;
    }

    /**
     * 蓝票是否存在未结差异（OPEN/ESCALATED），存在则全额红冲暂停。
     */
    public boolean hasBlocking(long blueInvoiceId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM discrepancy"
                        + " WHERE blue_invoice_id = ? AND status IN ('OPEN', 'ESCALATED')",
                Integer.class, blueInvoiceId);
        return count != null && count > 0;
    }

    /**
     * 指定行是否存在未结差异（OPEN/ESCALATED），存在则该行新增红冲暂停。
     */
    public boolean isLineBlocked(long blueInvoiceId, int lineNo) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM discrepancy"
                        + " WHERE blue_invoice_id = ? AND line_no = ? AND status IN ('OPEN', 'ESCALATED')",
                Integer.class, blueInvoiceId, lineNo);
        return count != null && count > 0;
    }

    /**
     * 写入差异单审计事件（须在事务内调用，随状态变更同事务提交）。
     */
    public void insertEvent(long discrepancyId, String event) {
        jdbc.update("INSERT INTO discrepancy_event (discrepancy_id, event, created_at) VALUES (?, ?, ?)",
                discrepancyId, event, Timestamp.from(clock.instant()));
    }
}
