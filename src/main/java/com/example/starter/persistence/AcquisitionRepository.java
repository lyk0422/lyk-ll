package com.example.starter.persistence;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 本地力矩采集会话、紧固点清单、批次证据与原始包的 JDBC 访问。
 *
 * <p>原始包仅追加，{@code (session_id, seq)} 唯一约束兜底并发重复序号；
 * 水位线与乐观版本通过条件更新（{@code WHERE version = ?}）推进。
 */
@Repository
public class AcquisitionRepository {

    private final JdbcTemplate jdbcTemplate;

    public AcquisitionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 采集会话行。 */
    public record SessionRow(
            long id,
            String sessionNo,
            String cardNo,
            String toolCode,
            LocalDate calibrationDate,
            int watermark,
            String status,
            int version,
            Instant createdAt,
            Instant closedAt) {
    }

    /** 原始采集包行。 */
    public record PacketRow(
            long id,
            long sessionId,
            int seq,
            String pointCode,
            BigDecimal measuredTorque,
            Instant measuredAt,
            String status,
            String contentHash,
            String batchNo,
            Instant createdAt) {
    }

    /** 批次证据行。 */
    public record BatchRow(
            long id,
            String batchNo,
            long sessionId,
            int packetCount,
            int acceptedCount,
            int replayedCount,
            int committedCount,
            int lateCount,
            int watermarkAfter,
            Instant createdAt) {
    }

    private static final RowMapper<SessionRow> SESSION_MAPPER = (rs, n) -> new SessionRow(
            rs.getLong("id"),
            rs.getString("session_no"),
            rs.getString("card_no"),
            rs.getString("tool_code"),
            rs.getDate("calibration_date").toLocalDate(),
            rs.getInt("watermark"),
            rs.getString("status"),
            rs.getInt("version"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("closed_at") == null ? null : rs.getTimestamp("closed_at").toInstant());

    private static final RowMapper<PacketRow> PACKET_MAPPER = (rs, n) -> new PacketRow(
            rs.getLong("id"),
            rs.getLong("session_id"),
            rs.getInt("seq"),
            rs.getString("point_code"),
            rs.getBigDecimal("measured_torque"),
            rs.getTimestamp("measured_at").toInstant(),
            rs.getString("status"),
            rs.getString("content_hash"),
            rs.getString("batch_no"),
            rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<BatchRow> BATCH_MAPPER = (rs, n) -> new BatchRow(
            rs.getLong("id"),
            rs.getString("batch_no"),
            rs.getLong("session_id"),
            rs.getInt("packet_count"),
            rs.getInt("accepted_count"),
            rs.getInt("replayed_count"),
            rs.getInt("committed_count"),
            rs.getInt("late_count"),
            rs.getInt("watermark_after"),
            rs.getTimestamp("created_at").toInstant());

    /** 创建会话（初始版本 1、水位线 0），会话号冲突由唯一约束抛出。 */
    public long insertSession(String sessionNo, String cardNo, String toolCode,
                              LocalDate calibrationDate, Instant now) {
        jdbcTemplate.update(
                "INSERT INTO acquisition_session (session_no, card_no, tool_code, calibration_date, "
                        + "watermark, status, version, created_at, closed_at) "
                        + "VALUES (?, ?, ?, ?, 0, 'OPEN', 1, ?, NULL)",
                sessionNo, cardNo, toolCode, java.sql.Date.valueOf(calibrationDate), Timestamp.from(now));
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM acquisition_session WHERE session_no = ?", Long.class, sessionNo);
        return id == null ? 0L : id;
    }

    public void insertPoint(long sessionId, String pointCode) {
        jdbcTemplate.update(
                "INSERT INTO acquisition_session_point (session_id, point_code) VALUES (?, ?)",
                sessionId, pointCode);
    }

    public List<String> listPoints(long sessionId) {
        return jdbcTemplate.queryForList(
                "SELECT point_code FROM acquisition_session_point WHERE session_id = ? ORDER BY id",
                String.class, sessionId);
    }

    /** 行级加锁读取会话，串行化同会话并发上传，保证前缀不被重复消耗。 */
    public SessionRow findSessionForUpdate(String sessionNo) {
        List<SessionRow> rows = jdbcTemplate.query(selectColumns() + " FROM acquisition_session "
                + "WHERE session_no = ? FOR UPDATE", SESSION_MAPPER, sessionNo);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public SessionRow findSession(String sessionNo) {
        List<SessionRow> rows = jdbcTemplate.query(
                selectColumns() + " FROM acquisition_session WHERE session_no = ?",
                SESSION_MAPPER, sessionNo);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static String selectColumns() {
        return "SELECT id, session_no, card_no, tool_code, calibration_date, watermark, status, version, "
                + "created_at, closed_at";
    }

    /** 乐观推进水位线并把版本加 1；返回是否更新成功。 */
    public boolean advanceWatermark(long sessionId, int expectedVersion, int newWatermark) {
        return jdbcTemplate.update(
                "UPDATE acquisition_session SET watermark = ?, version = version + 1 "
                        + "WHERE id = ? AND version = ?",
                newWatermark, sessionId, expectedVersion) == 1;
    }

    /** 关闭会话（终态），乐观版本守卫；返回是否更新成功。 */
    public boolean closeSession(long sessionId, int expectedVersion, Instant now) {
        return jdbcTemplate.update(
                "UPDATE acquisition_session SET status = 'CLOSED', closed_at = ?, version = version + 1 "
                        + "WHERE id = ? AND version = ? AND status = 'OPEN'",
                Timestamp.from(now), sessionId, expectedVersion) == 1;
    }

    public void insertPacket(PacketRow row) {
        jdbcTemplate.update(
                "INSERT INTO acquisition_packet (session_id, seq, point_code, measured_torque, measured_at, "
                        + "status, content_hash, batch_no, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.sessionId(), row.seq(), row.pointCode(), row.measuredTorque(),
                Timestamp.from(row.measuredAt()), row.status(), row.contentHash(), row.batchNo(),
                Timestamp.from(row.createdAt()));
    }

    public PacketRow findPacket(long sessionId, int seq) {
        List<PacketRow> rows = jdbcTemplate.query(
                "SELECT id, session_id, seq, point_code, measured_torque, measured_at, status, content_hash, "
                        + "batch_no, created_at FROM acquisition_packet WHERE session_id = ? AND seq = ?",
                PACKET_MAPPER, sessionId, seq);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<PacketRow> listPackets(long sessionId) {
        return jdbcTemplate.query(
                "SELECT id, session_id, seq, point_code, measured_torque, measured_at, status, content_hash, "
                        + "batch_no, created_at FROM acquisition_packet WHERE session_id = ? ORDER BY seq",
                PACKET_MAPPER, sessionId);
    }

    /** 标记前缀包已入账（原始包保留，状态由 BUFFERED 转 COMMITTED）。 */
    public void markPacketCommitted(long packetId) {
        jdbcTemplate.update("UPDATE acquisition_packet SET status = 'COMMITTED' WHERE id = ?", packetId);
    }

    public void insertBatch(BatchRow row, Instant now) {
        jdbcTemplate.update(
                "INSERT INTO acquisition_batch (batch_no, session_id, packet_count, accepted_count, "
                        + "replayed_count, committed_count, late_count, watermark_after, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.batchNo(), row.sessionId(), row.packetCount(), row.acceptedCount(),
                row.replayedCount(), row.committedCount(), row.lateCount(), row.watermarkAfter(),
                Timestamp.from(now));
    }

    public List<BatchRow> listBatches(long sessionId) {
        return jdbcTemplate.query(
                "SELECT id, batch_no, session_id, packet_count, accepted_count, replayed_count, "
                        + "committed_count, late_count, watermark_after, created_at "
                        + "FROM acquisition_batch WHERE session_id = ? ORDER BY id",
                BATCH_MAPPER, sessionId);
    }

    /** 统计工序卡仍处于打开状态的采集会话数，归档前必须为 0。 */
    public int countOpenByCard(String cardNo) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM acquisition_session WHERE card_no = ? AND status = 'OPEN'",
                Integer.class, cardNo);
        return count == null ? 0 : count;
    }
}
