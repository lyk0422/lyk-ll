package com.example.starter.tailings.repo;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 泄流指令存取：下达 → 签收 → 执行 → 回执 全链路状态。
 */
@Repository
public class CommandRepository {

    private final JdbcTemplate jdbc;

    public CommandRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 泄流指令行。时间字段为 NULL 表示该环节尚未发生。
     *
     * @param id               主键
     * @param requestId        幂等请求ID
     * @param planId           泄流方案ID
     * @param pondId           尾矿库ID
     * @param status           指令状态
     * @param issuedAt         下达时刻
     * @param ackDeadline      签收时限(下达后15分钟)
     * @param ackedAt          签收时刻,NULL未签收
     * @param executedAt       执行时刻,NULL未执行
     * @param receiptedAt      回执时刻,NULL未回执
     * @param actualOrifices   回执实际孔口组合JSON,NULL未回执
     * @param actualDischarge  回执实际泄量(立方米/秒),NULL未回执
     * @param deviationPct     回执与方案泄量偏差百分比,NULL未回执
     * @param deviationReason  偏差超10%的原因说明,否则NULL
     * @param escalated        签收超时是否已升级值班领导
     */
    public record CommandRow(long id, String requestId, long planId, long pondId, String status,
                             LocalDateTime issuedAt, LocalDateTime ackDeadline,
                             LocalDateTime ackedAt, LocalDateTime executedAt, LocalDateTime receiptedAt,
                             String actualOrifices, BigDecimal actualDischarge, BigDecimal deviationPct,
                             String deviationReason, boolean escalated) {
    }

    public long insert(String requestId, long planId, long pondId, String status,
                       LocalDateTime issuedAt, LocalDateTime ackDeadline) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO discharge_command (request_id, plan_id, pond_id, status, issued_at, ack_deadline)"
                            + " VALUES (?, ?, ?, ?, ?, ?)",
                    new String[]{"id"});
            ps.setString(1, requestId);
            ps.setLong(2, planId);
            ps.setLong(3, pondId);
            ps.setString(4, status);
            ps.setTimestamp(5, Timestamp.valueOf(issuedAt));
            ps.setTimestamp(6, Timestamp.valueOf(ackDeadline));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<CommandRow> findById(long id) {
        return jdbc.query(
                        "SELECT id, request_id, plan_id, pond_id, status, issued_at, ack_deadline, acked_at,"
                                + " executed_at, receipted_at, actual_orifices, actual_discharge, deviation_pct,"
                                + " deviation_reason, escalated FROM discharge_command WHERE id = ?",
                        (rs, rowNum) -> new CommandRow(
                                rs.getLong("id"),
                                rs.getString("request_id"),
                                rs.getLong("plan_id"),
                                rs.getLong("pond_id"),
                                rs.getString("status"),
                                rs.getObject("issued_at", LocalDateTime.class),
                                rs.getObject("ack_deadline", LocalDateTime.class),
                                rs.getObject("acked_at", LocalDateTime.class),
                                rs.getObject("executed_at", LocalDateTime.class),
                                rs.getObject("receipted_at", LocalDateTime.class),
                                rs.getString("actual_orifices"),
                                rs.getBigDecimal("actual_discharge"),
                                rs.getBigDecimal("deviation_pct"),
                                rs.getString("deviation_reason"),
                                rs.getInt("escalated") == 1),
                        id)
                .stream()
                .findFirst();
    }

    public Optional<CommandRow> findByPlanId(long planId) {
        return jdbc.query(
                        "SELECT id, request_id, plan_id, pond_id, status, issued_at, ack_deadline, acked_at,"
                                + " executed_at, receipted_at, actual_orifices, actual_discharge, deviation_pct,"
                                + " deviation_reason, escalated FROM discharge_command WHERE plan_id = ?",
                        (rs, rowNum) -> new CommandRow(
                                rs.getLong("id"),
                                rs.getString("request_id"),
                                rs.getLong("plan_id"),
                                rs.getLong("pond_id"),
                                rs.getString("status"),
                                rs.getObject("issued_at", LocalDateTime.class),
                                rs.getObject("ack_deadline", LocalDateTime.class),
                                rs.getObject("acked_at", LocalDateTime.class),
                                rs.getObject("executed_at", LocalDateTime.class),
                                rs.getObject("receipted_at", LocalDateTime.class),
                                rs.getString("actual_orifices"),
                                rs.getBigDecimal("actual_discharge"),
                                rs.getBigDecimal("deviation_pct"),
                                rs.getString("deviation_reason"),
                                rs.getInt("escalated") == 1),
                        planId)
                .stream()
                .findFirst();
    }

    public void updateAcked(long id, LocalDateTime ackedAt) {
        jdbc.update("UPDATE discharge_command SET status = 'ACKED', acked_at = ? WHERE id = ?",
                Timestamp.valueOf(ackedAt), id);
    }

    public void updateEscalated(long id) {
        jdbc.update("UPDATE discharge_command SET status = 'ESCALATED', escalated = 1 WHERE id = ?", id);
    }

    public void updateExecuting(long id, LocalDateTime executedAt) {
        jdbc.update("UPDATE discharge_command SET status = 'EXECUTING', executed_at = ? WHERE id = ?",
                Timestamp.valueOf(executedAt), id);
    }

    public void updateReceipted(long id, LocalDateTime receiptedAt, String actualOrifices,
                                BigDecimal actualDischarge, BigDecimal deviationPct, String deviationReason) {
        jdbc.update("UPDATE discharge_command SET status = 'RECEIPTED', receipted_at = ?, actual_orifices = ?,"
                        + " actual_discharge = ?, deviation_pct = ?, deviation_reason = ? WHERE id = ?",
                Timestamp.valueOf(receiptedAt), actualOrifices, actualDischarge, deviationPct,
                deviationReason, id);
    }
}
