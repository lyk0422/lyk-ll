package com.example.starter.persistence;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 力矩紧固记录、返工台账与归档后迟到事件的 JDBC 访问。
 * 所有记录仅追加（append-only），不提供更新/删除原始记录的方法。
 */
@Repository
public class TorqueRepository {

    private final JdbcTemplate jdbcTemplate;

    public TorqueRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 力矩记录行。 */
    public record TorqueRow(
            long id,
            long cardId,
            long fastenerId,
            int attemptNo,
            BigDecimal measuredTorque,
            BigDecimal deviationPct,
            boolean inTolerance,
            String operator,
            String toolCode,
            Instant measuredAt,
            String requestId) {
    }

    /** 返工台账行。 */
    public record ReworkRow(
            long id,
            long cardId,
            long fastenerId,
            int reworkNo,
            String requestId,
            String reason,
            Instant createdAt) {
    }

    private static final RowMapper<TorqueRow> TORQUE_MAPPER = (rs, n) -> new TorqueRow(
            rs.getLong("id"),
            rs.getLong("card_id"),
            rs.getLong("fastener_id"),
            rs.getInt("attempt_no"),
            rs.getBigDecimal("measured_torque"),
            rs.getBigDecimal("deviation_pct"),
            rs.getInt("in_tolerance") == 1,
            rs.getString("operator"),
            rs.getString("tool_code"),
            rs.getTimestamp("measured_at").toInstant(),
            rs.getString("request_id"));

    private static final RowMapper<ReworkRow> REWORK_MAPPER = (rs, n) -> new ReworkRow(
            rs.getLong("id"),
            rs.getLong("card_id"),
            rs.getLong("fastener_id"),
            rs.getInt("rework_no"),
            rs.getString("request_id"),
            rs.getString("reason"),
            rs.getTimestamp("created_at").toInstant());

    /** 追加力矩记录；attempt 唯一约束兜底并发重复提交。 */
    public void insertTorque(TorqueRow row) {
        jdbcTemplate.update(
                "INSERT INTO torque_record (card_id, fastener_id, attempt_no, measured_torque, deviation_pct, "
                        + "in_tolerance, operator, tool_code, measured_at, request_id, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.cardId(), row.fastenerId(), row.attemptNo(), row.measuredTorque(), row.deviationPct(),
                row.inTolerance() ? 1 : 0, row.operator(), row.toolCode(),
                Timestamp.from(row.measuredAt()), row.requestId(), Timestamp.from(row.measuredAt()));
    }

    /** 追加返工台账。 */
    public void insertRework(ReworkRow row, Instant createdAt) {
        jdbcTemplate.update(
                "INSERT INTO rework_record (card_id, fastener_id, rework_no, request_id, reason, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                row.cardId(), row.fastenerId(), row.reworkNo(), row.requestId(), row.reason(),
                Timestamp.from(createdAt));
    }

    /** 登记归档后迟到测量事件（不进入 attempt 链）。 */
    public void insertLateEvent(long cardId, String pointCode, BigDecimal measuredTorque, String operator,
                                String toolCode, Instant measuredAt, String requestId) {
        jdbcTemplate.update(
                "INSERT INTO late_measurement_event (card_id, point_code, measured_torque, operator, tool_code, "
                        + "measured_at, request_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                cardId, pointCode, measuredTorque, operator, toolCode,
                Timestamp.from(measuredAt), requestId, Timestamp.from(measuredAt));
    }

    public int countAttempts(long cardId, long fastenerId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM torque_record WHERE card_id = ? AND fastener_id = ?",
                Integer.class, cardId, fastenerId);
        return count == null ? 0 : count;
    }

    public int countReworks(long cardId, long fastenerId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rework_record WHERE card_id = ? AND fastener_id = ?",
                Integer.class, cardId, fastenerId);
        return count == null ? 0 : count;
    }

    public List<TorqueRow> listTorqueByCard(long cardId) {
        return jdbcTemplate.query(
                "SELECT id, card_id, fastener_id, attempt_no, measured_torque, deviation_pct, in_tolerance, "
                        + "operator, tool_code, measured_at, request_id FROM torque_record "
                        + "WHERE card_id = ? ORDER BY fastener_id, attempt_no",
                TORQUE_MAPPER, cardId);
    }

    public List<ReworkRow> listReworksByCard(long cardId) {
        return jdbcTemplate.query(
                "SELECT id, card_id, fastener_id, rework_no, request_id, reason, created_at "
                        + "FROM rework_record WHERE card_id = ? ORDER BY fastener_id, rework_no",
                REWORK_MAPPER, cardId);
    }

    /** 统计全卡各紧固点“最近一次测量均合格”是否成立，用于判定力矩紧固阶段完成。 */
    public boolean allFastenersLastAttemptPassing(long cardId, int fastenerCount) {
        if (fastenerCount == 0) {
            return false;
        }
        // 每个紧固点的最大 attempt_no 是否合格；合格紧固点数应等于工艺紧固点总数。
        Integer passing = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM (SELECT fastener_id, MAX(attempt_no) AS max_attempt FROM torque_record "
                        + "WHERE card_id = ? GROUP BY fastener_id) t "
                        + "JOIN torque_record r ON r.card_id = ? AND r.fastener_id = t.fastener_id "
                        + "AND r.attempt_no = t.max_attempt WHERE r.in_tolerance = 1",
                Integer.class, cardId, cardId);
        return passing != null && passing == fastenerCount;
    }
}
