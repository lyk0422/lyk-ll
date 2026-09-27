package com.example.starter.persistence;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.domain.CollectionPacketStatus;
import com.example.starter.domain.CollectionSessionStatus;

/**
 * 工具采集会话、绑定紧固点清单、原始采集包与上传批次的 JDBC 访问。
 * 采集包仅追加（append-only）；水位线与版本通过条件更新做乐观并发守卫。
 */
@Repository
public class CollectionRepository {

    private final JdbcTemplate jdbcTemplate;

    public CollectionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 采集会话行。 */
    public record SessionRow(
            long id,
            String sessionNo,
            String cardNo,
            String toolCode,
            LocalDate calibrationDate,
            CollectionSessionStatus status,
            int watermark,
            int version,
            Instant createdAt,
            Instant closedAt) {
    }

    /** 采集包行。 */
    public record PacketRow(
            long id,
            long sessionId,
            int seq,
            String pointCode,
            BigDecimal measuredTorque,
            Instant measuredAt,
            CollectionPacketStatus status,
            String contentHash,
            String requestId,
            Instant createdAt) {
    }

    /** 上传批次行。 */
    public record BatchRow(
            long id,
            long sessionId,
            String requestId,
            int packetCount,
            Instant createdAt) {
    }

    private static final RowMapper<SessionRow> SESSION_MAPPER = (rs, n) -> new SessionRow(
            rs.getLong("id"),
            rs.getString("session_no"),
            rs.getString("card_no"),
            rs.getString("tool_code"),
            rs.getDate("calibration_date").toLocalDate(),
            CollectionSessionStatus.valueOf(rs.getString("status")),
            rs.getInt("watermark"),
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
            CollectionPacketStatus.valueOf(rs.getString("status")),
            rs.getString("content_hash"),
            rs.getString("request_id"),
            rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<BatchRow> BATCH_MAPPER = (rs, n) -> new BatchRow(
            rs.getLong("id"),
            rs.getLong("session_id"),
            rs.getString("request_id"),
            rs.getInt("packet_count"),
            rs.getTimestamp("created_at").toInstant());

    private static final String SESSION_COLUMNS =
            "id, session_no, card_no, tool_code, calibration_date, status, watermark, version, "
                    + "created_at, closed_at";

    // ------------------------------------------------------------------
    // 会话
    // ------------------------------------------------------------------

    /** 插入采集会话，初始水位线 0、版本 1、状态 OPEN。 */
    public long insertSession(String sessionNo, String cardNo, String toolCode,
                              LocalDate calibrationDate, Instant now) {
        jdbcTemplate.update(
                "INSERT INTO collection_session (session_no, card_no, tool_code, calibration_date, "
                        + "status, watermark, version, created_at) VALUES (?, ?, ?, ?, ?, 0, 1, ?)",
                sessionNo, cardNo, toolCode, java.sql.Date.valueOf(calibrationDate),
                CollectionSessionStatus.OPEN.name(), Timestamp.from(now));
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM collection_session WHERE session_no = ?", Long.class, sessionNo);
        return id == null ? 0L : id;
    }

    public SessionRow findSessionByNo(String sessionNo) {
        List<SessionRow> rows = jdbcTemplate.query(
                "SELECT " + SESSION_COLUMNS + " FROM collection_session WHERE session_no = ?",
                SESSION_MAPPER, sessionNo);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 统计工序卡名下未关闭（OPEN）的采集会话数，归档前必须为 0。 */
    public int countOpenSessionsByCard(String cardNo) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM collection_session WHERE card_no = ? AND status = ?",
                Integer.class, cardNo, CollectionSessionStatus.OPEN.name());
        return count == null ? 0 : count;
    }

    /**
     * 乐观守卫推进水位线并版本加 1：仅当当前版本等于 expectedVersion 才生效。
     *
     * @return true 表示更新成功（赢得并发），false 表示版本已漂移
     */
    public boolean advanceWatermark(long sessionId, int expectedVersion, int newWatermark) {
        return jdbcTemplate.update(
                "UPDATE collection_session SET watermark = ?, version = version + 1 "
                        + "WHERE id = ? AND version = ?",
                newWatermark, sessionId, expectedVersion) == 1;
    }

    /** 乐观守卫仅推进版本（水位线不变的成功变更，如纯缓冲或迟到登记）。 */
    public boolean bumpVersion(long sessionId, int expectedVersion) {
        return jdbcTemplate.update(
                "UPDATE collection_session SET version = version + 1 WHERE id = ? AND version = ?",
                sessionId, expectedVersion) == 1;
    }

    /** 乐观守卫关闭会话：仅 OPEN 且版本匹配才生效。 */
    public boolean closeSession(long sessionId, int expectedVersion, Instant closedAt) {
        return jdbcTemplate.update(
                "UPDATE collection_session SET status = ?, closed_at = ?, version = version + 1 "
                        + "WHERE id = ? AND version = ? AND status = ?",
                CollectionSessionStatus.CLOSED.name(), Timestamp.from(closedAt),
                sessionId, expectedVersion, CollectionSessionStatus.OPEN.name()) == 1;
    }

    // ------------------------------------------------------------------
    // 绑定紧固点清单
    // ------------------------------------------------------------------

    public void insertSessionPoint(long sessionId, String pointCode) {
        jdbcTemplate.update(
                "INSERT INTO collection_session_point (session_id, point_code) VALUES (?, ?)",
                sessionId, pointCode);
    }

    public List<String> listSessionPoints(long sessionId) {
        return jdbcTemplate.queryForList(
                "SELECT point_code FROM collection_session_point WHERE session_id = ? ORDER BY id",
                String.class, sessionId);
    }

    // ------------------------------------------------------------------
    // 采集包
    // ------------------------------------------------------------------

    /** 追加原始采集包；(session_id, seq) 唯一约束兜底并发重复。 */
    public void insertPacket(long sessionId, int seq, String pointCode, BigDecimal measuredTorque,
                             Instant measuredAt, CollectionPacketStatus status, String contentHash,
                             String requestId, Instant now) {
        jdbcTemplate.update(
                "INSERT INTO collection_packet (session_id, seq, point_code, measured_torque, measured_at, "
                        + "status, content_hash, request_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                sessionId, seq, pointCode, measuredTorque, Timestamp.from(measuredAt),
                status.name(), contentHash, requestId, Timestamp.from(now));
    }

    public PacketRow findPacket(long sessionId, int seq) {
        List<PacketRow> rows = jdbcTemplate.query(
                "SELECT id, session_id, seq, point_code, measured_torque, measured_at, status, "
                        + "content_hash, request_id, created_at FROM collection_packet "
                        + "WHERE session_id = ? AND seq = ?",
                PACKET_MAPPER, sessionId, seq);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<PacketRow> listPackets(long sessionId) {
        return jdbcTemplate.query(
                "SELECT id, session_id, seq, point_code, measured_torque, measured_at, status, "
                        + "content_hash, request_id, created_at FROM collection_packet "
                        + "WHERE session_id = ? ORDER BY seq",
                PACKET_MAPPER, sessionId);
    }

    public int countPacketsByStatus(long sessionId, CollectionPacketStatus status) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM collection_packet WHERE session_id = ? AND status = ?",
                Integer.class, sessionId, status.name());
        return count == null ? 0 : count;
    }

    /** 标记包已入账（前缀消费）。 */
    public void markPacketConsumed(long packetId) {
        int updated = jdbcTemplate.update(
                "UPDATE collection_packet SET status = ? WHERE id = ? AND status = ?",
                CollectionPacketStatus.CONSUMED.name(), packetId, CollectionPacketStatus.BUFFERED.name());
        if (updated != 1) {
            throw new IllegalStateException("采集包状态推进失败: " + packetId);
        }
    }

    // ------------------------------------------------------------------
    // 上传批次
    // ------------------------------------------------------------------

    public void insertBatch(long sessionId, String requestId, int packetCount, Instant now) {
        jdbcTemplate.update(
                "INSERT INTO collection_batch (session_id, request_id, packet_count, created_at) "
                        + "VALUES (?, ?, ?, ?)",
                sessionId, requestId, packetCount, Timestamp.from(now));
    }

    public List<BatchRow> listBatches(long sessionId) {
        return jdbcTemplate.query(
                "SELECT id, session_id, request_id, packet_count, created_at FROM collection_batch "
                        + "WHERE session_id = ? ORDER BY id",
                BATCH_MAPPER, sessionId);
    }
}
