package com.example.starter.persistence;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.domain.AcquisitionPacketStatus;
import com.example.starter.domain.AcquisitionSessionStatus;

/**
 * 工具采集会话、绑定清单、上传批次与原始包的 JDBC 访问。
 * 原始包仅追加（状态字段随入账推进），不提供修改包内容的方法。
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
            long cardId,
            String toolCode,
            LocalDate calibrationDate,
            AcquisitionSessionStatus status,
            int watermark,
            int version,
            Instant createdAt,
            Instant closedAt) {
    }

    /** 采集原始包行。 */
    public record PacketRow(
            long id,
            long sessionId,
            int seq,
            String pointCode,
            BigDecimal measuredTorque,
            Instant measuredAt,
            String contentHash,
            AcquisitionPacketStatus status,
            long batchId,
            String requestId,
            Instant createdAt,
            Instant consumedAt) {
    }

    /** 上传批次行。 */
    public record BatchRow(
            long id,
            long sessionId,
            String requestId,
            int packetCount,
            int insertedCount,
            int consumedCount,
            int lateCount,
            int watermarkAfter,
            Instant createdAt) {
    }

    private static final RowMapper<SessionRow> SESSION_MAPPER = (rs, n) -> new SessionRow(
            rs.getLong("id"),
            rs.getString("session_no"),
            rs.getLong("card_id"),
            rs.getString("tool_code"),
            rs.getDate("calibration_date").toLocalDate(),
            AcquisitionSessionStatus.valueOf(rs.getString("status")),
            rs.getInt("watermark"),
            rs.getInt("version"),
            rs.getTimestamp("created_at").toInstant(),
            ts(rs.getTimestamp("closed_at")));

    private static final RowMapper<PacketRow> PACKET_MAPPER = (rs, n) -> new PacketRow(
            rs.getLong("id"),
            rs.getLong("session_id"),
            rs.getInt("seq"),
            rs.getString("point_code"),
            rs.getBigDecimal("measured_torque"),
            rs.getTimestamp("measured_at").toInstant(),
            rs.getString("content_hash"),
            AcquisitionPacketStatus.valueOf(rs.getString("status")),
            rs.getLong("batch_id"),
            rs.getString("request_id"),
            rs.getTimestamp("created_at").toInstant(),
            ts(rs.getTimestamp("consumed_at")));

    private static final RowMapper<BatchRow> BATCH_MAPPER = (rs, n) -> new BatchRow(
            rs.getLong("id"),
            rs.getLong("session_id"),
            rs.getString("request_id"),
            rs.getInt("packet_count"),
            rs.getInt("inserted_count"),
            rs.getInt("consumed_count"),
            rs.getInt("late_count"),
            rs.getInt("watermark_after"),
            rs.getTimestamp("created_at").toInstant());

    private static Instant ts(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    // ------------------------------------------------------------------
    // 会话
    // ------------------------------------------------------------------

    /**
     * 创建采集会话（状态 OPEN、水位线 0、版本 1）。
     *
     * @throws org.springframework.dao.DuplicateKeyException 会话号已存在
     */
    public long insertSession(String sessionNo, long cardId, String toolCode,
                              LocalDate calibrationDate, Instant now) {
        jdbcTemplate.update(
                "INSERT INTO acquisition_session (session_no, card_id, tool_code, calibration_date, status, "
                        + "watermark, version, created_at) VALUES (?, ?, ?, ?, ?, 0, 1, ?)",
                sessionNo, cardId, toolCode, Date.valueOf(calibrationDate),
                AcquisitionSessionStatus.OPEN.name(), Timestamp.from(now));
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM acquisition_session WHERE session_no = ?", Long.class, sessionNo);
        return id == null ? 0L : id;
    }

    public SessionRow findSession(String sessionNo) {
        List<SessionRow> rows = jdbcTemplate.query(
                "SELECT id, session_no, card_id, tool_code, calibration_date, status, watermark, version, "
                        + "created_at, closed_at FROM acquisition_session WHERE session_no = ?",
                SESSION_MAPPER, sessionNo);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 按会话号加行锁读取（串行化同会话的并发上传/关闭）。 */
    public SessionRow findSessionForUpdate(String sessionNo) {
        List<SessionRow> rows = jdbcTemplate.query(
                "SELECT id, session_no, card_id, tool_code, calibration_date, status, watermark, version, "
                        + "created_at, closed_at FROM acquisition_session WHERE session_no = ? FOR UPDATE",
                SESSION_MAPPER, sessionNo);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 推进水位线并递增版本（成功变更）。 */
    public void advanceSession(long sessionId, int watermark, int version) {
        jdbcTemplate.update(
                "UPDATE acquisition_session SET watermark = ?, version = ? WHERE id = ?",
                watermark, version, sessionId);
    }

    /** 仅递增版本（迟到登记等不改变水位线的成功变更）。 */
    public void bumpVersion(long sessionId, int version) {
        jdbcTemplate.update(
                "UPDATE acquisition_session SET version = ? WHERE id = ?",
                version, sessionId);
    }

    /** 关闭会话并递增版本。 */
    public void closeSession(long sessionId, int version, Instant closedAt) {
        jdbcTemplate.update(
                "UPDATE acquisition_session SET status = ?, version = ?, closed_at = ? WHERE id = ?",
                AcquisitionSessionStatus.CLOSED.name(), version, Timestamp.from(closedAt), sessionId);
    }

    /** 统计工序卡下仍未关闭的采集会话数（归档前须为 0）。 */
    public int countOpenByCard(long cardId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM acquisition_session WHERE card_id = ? AND status = ?",
                Integer.class, cardId, AcquisitionSessionStatus.OPEN.name());
        return count == null ? 0 : count;
    }

    // ------------------------------------------------------------------
    // 绑定紧固点清单快照
    // ------------------------------------------------------------------

    public void insertPoint(long sessionId, String pointCode) {
        jdbcTemplate.update(
                "INSERT INTO acquisition_session_point (session_id, point_code) VALUES (?, ?)",
                sessionId, pointCode);
    }

    public List<String> listPoints(long sessionId) {
        return jdbcTemplate.query(
                "SELECT point_code FROM acquisition_session_point WHERE session_id = ? ORDER BY point_code",
                (rs, n) -> rs.getString("point_code"), sessionId);
    }

    public boolean pointExists(long sessionId, String pointCode) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM acquisition_session_point WHERE session_id = ? AND point_code = ?",
                Integer.class, sessionId, pointCode);
        return count != null && count > 0;
    }

    // ------------------------------------------------------------------
    // 上传批次
    // ------------------------------------------------------------------

    /** 登记批次（计数先置 0，事务末尾由 {@link #finishBatch} 回填）。 */
    public long insertBatch(long sessionId, String requestId, int packetCount, int watermarkAfter, Instant now) {
        jdbcTemplate.update(
                "INSERT INTO acquisition_batch (session_id, request_id, packet_count, inserted_count, "
                        + "consumed_count, late_count, watermark_after, created_at) "
                        + "VALUES (?, ?, ?, 0, 0, 0, ?, ?)",
                sessionId, requestId, packetCount, watermarkAfter, Timestamp.from(now));
        Long id = jdbcTemplate.queryForObject(
                "SELECT MAX(id) FROM acquisition_batch WHERE session_id = ? AND request_id = ?",
                Long.class, sessionId, requestId);
        return id == null ? 0L : id;
    }

    /** 回填批次最终计数与处理后水位线。 */
    public void finishBatch(long batchId, int inserted, int consumed, int late, int watermarkAfter) {
        jdbcTemplate.update(
                "UPDATE acquisition_batch SET inserted_count = ?, consumed_count = ?, late_count = ?, "
                        + "watermark_after = ? WHERE id = ?",
                inserted, consumed, late, watermarkAfter, batchId);
    }

    public List<BatchRow> listBatches(long sessionId) {
        return jdbcTemplate.query(
                "SELECT id, session_id, request_id, packet_count, inserted_count, consumed_count, late_count, "
                        + "watermark_after, created_at FROM acquisition_batch WHERE session_id = ? ORDER BY id",
                BATCH_MAPPER, sessionId);
    }

    // ------------------------------------------------------------------
    // 原始包
    // ------------------------------------------------------------------

    /** 追加原始包；同会话序号唯一约束兜底并发重复接收。 */
    public void insertPacket(long sessionId, int seq, String pointCode, BigDecimal measuredTorque,
                             Instant measuredAt, String contentHash, AcquisitionPacketStatus status,
                             long batchId, String requestId, Instant now) {
        jdbcTemplate.update(
                "INSERT INTO acquisition_packet (session_id, seq, point_code, measured_torque, measured_at, "
                        + "content_hash, status, batch_id, request_id, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                sessionId, seq, pointCode, measuredTorque, Timestamp.from(measuredAt), contentHash,
                status.name(), batchId, requestId, Timestamp.from(now));
    }

    public PacketRow findPacket(long sessionId, int seq) {
        List<PacketRow> rows = jdbcTemplate.query(
                "SELECT id, session_id, seq, point_code, measured_torque, measured_at, content_hash, status, "
                        + "batch_id, request_id, created_at, consumed_at FROM acquisition_packet "
                        + "WHERE session_id = ? AND seq = ?",
                PACKET_MAPPER, sessionId, seq);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 按序号升序列出仍在缓冲的包。 */
    public List<PacketRow> listBuffered(long sessionId) {
        return jdbcTemplate.query(
                "SELECT id, session_id, seq, point_code, measured_torque, measured_at, content_hash, status, "
                        + "batch_id, request_id, created_at, consumed_at FROM acquisition_packet "
                        + "WHERE session_id = ? AND status = ? ORDER BY seq",
                PACKET_MAPPER, sessionId, AcquisitionPacketStatus.BUFFERED.name());
    }

    /** 按序号升序列出全部包（包证据查询）。 */
    public List<PacketRow> listPackets(long sessionId) {
        return jdbcTemplate.query(
                "SELECT id, session_id, seq, point_code, measured_torque, measured_at, content_hash, status, "
                        + "batch_id, request_id, created_at, consumed_at FROM acquisition_packet "
                        + "WHERE session_id = ? ORDER BY seq",
                PACKET_MAPPER, sessionId);
    }

    /** 标记包已入账或记迟到。 */
    public void markPacket(long packetId, AcquisitionPacketStatus status, Instant consumedAt) {
        jdbcTemplate.update(
                "UPDATE acquisition_packet SET status = ?, consumed_at = ? WHERE id = ?",
                status.name(), Timestamp.from(consumedAt), packetId);
    }

    public int countBuffered(long sessionId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM acquisition_packet WHERE session_id = ? AND status = ?",
                Integer.class, sessionId, AcquisitionPacketStatus.BUFFERED.name());
        return count == null ? 0 : count;
    }
}
