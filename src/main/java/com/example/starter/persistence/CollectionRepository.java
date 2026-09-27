package com.example.starter.persistence;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.domain.CollectionStatus;
import com.example.starter.domain.PacketState;

/**
 * 本地力矩采集会话、绑定点清单与原始包证据的 JDBC 访问。
 *
 * <p>原始包仅追加（同会话同序号唯一约束兜底并发）；缓冲转前缀为状态更新，不删除原始行。
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
            long cardId,
            String toolCode,
            LocalDate calibrationDate,
            CollectionStatus status,
            int version,
            int watermark,
            int expectedCount,
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
            PacketState state,
            Integer attemptNo,
            String requestId,
            Instant receivedAt,
            Instant admittedAt) {
    }

    private static final RowMapper<SessionRow> SESSION_MAPPER = (rs, n) -> new SessionRow(
            rs.getLong("id"),
            rs.getString("session_no"),
            rs.getLong("card_id"),
            rs.getString("tool_code"),
            rs.getDate("calibration_date").toLocalDate(),
            CollectionStatus.valueOf(rs.getString("status")),
            rs.getInt("version"),
            rs.getInt("watermark"),
            rs.getInt("expected_count"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("closed_at") == null ? null : rs.getTimestamp("closed_at").toInstant());

    private static final RowMapper<PacketRow> PACKET_MAPPER = (rs, n) -> new PacketRow(
            rs.getLong("id"),
            rs.getLong("session_id"),
            rs.getInt("seq"),
            rs.getString("point_code"),
            rs.getBigDecimal("measured_torque"),
            rs.getTimestamp("measured_at").toInstant(),
            PacketState.valueOf(rs.getString("state")),
            (Integer) rs.getObject("attempt_no"),
            rs.getString("request_id"),
            rs.getTimestamp("received_at").toInstant(),
            rs.getTimestamp("admitted_at") == null ? null : rs.getTimestamp("admitted_at").toInstant());

    private static final String SESSION_COLUMNS =
            "id, session_no, card_id, tool_code, calibration_date, status, version, watermark, "
                    + "expected_count, created_at, closed_at";
    private static final String PACKET_COLUMNS =
            "id, session_id, seq, point_code, measured_torque, measured_at, state, attempt_no, "
                    + "request_id, received_at, admitted_at";

    /** 创建会话，初始版本 1、水位线 0、状态 OPEN。 */
    public long insertSession(String sessionNo, long cardId, String toolCode, LocalDate calibrationDate,
                              int expectedCount, Instant createdAt) {
        jdbcTemplate.update(
                "INSERT INTO collection_session (session_no, card_id, tool_code, calibration_date, status, "
                        + "version, watermark, expected_count, created_at) VALUES (?, ?, ?, ?, ?, 1, 0, ?, ?)",
                sessionNo, cardId, toolCode, Date.valueOf(calibrationDate),
                CollectionStatus.OPEN.name(), expectedCount, Timestamp.from(createdAt));
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM collection_session WHERE session_no = ?", Long.class, sessionNo);
        return id == null ? 0L : id;
    }

    /** 绑定紧固点清单。 */
    public void insertSessionPoint(long sessionId, String pointCode) {
        jdbcTemplate.update(
                "INSERT INTO collection_session_point (session_id, point_code) VALUES (?, ?)",
                sessionId, pointCode);
    }

    /** 按会话号查询（普通读）。 */
    public SessionRow findBySessionNo(String sessionNo) {
        List<SessionRow> rows = jdbcTemplate.query(
                "SELECT " + SESSION_COLUMNS + " FROM collection_session WHERE session_no = ?",
                SESSION_MAPPER, sessionNo);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 按会话号查询并对会话行加写锁（FOR UPDATE），须在事务内调用。 */
    public SessionRow findBySessionNoForUpdate(String sessionNo) {
        List<SessionRow> rows = jdbcTemplate.query(
                "SELECT " + SESSION_COLUMNS + " FROM collection_session WHERE session_no = ? FOR UPDATE",
                SESSION_MAPPER, sessionNo);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 条件推进版本与水位线（乐观守卫）：仅当当前版本等于 expectedVersion 时更新。
     *
     * @return 是否更新成功
     */
    public boolean advanceVersion(long sessionId, int expectedVersion, int newVersion, int newWatermark) {
        return jdbcTemplate.update(
                "UPDATE collection_session SET version = ?, watermark = ? WHERE id = ? AND version = ?",
                newVersion, newWatermark, sessionId, expectedVersion) == 1;
    }

    /**
     * 条件关闭会话：仅 OPEN 且版本匹配时关闭，版本 +1。
     *
     * @return 是否关闭成功
     */
    public boolean closeSession(long sessionId, int expectedVersion, int newVersion, Instant closedAt) {
        return jdbcTemplate.update(
                "UPDATE collection_session SET status = ?, version = ?, closed_at = ? "
                        + "WHERE id = ? AND status = 'OPEN' AND version = ?",
                CollectionStatus.CLOSED.name(), newVersion, Timestamp.from(closedAt),
                sessionId, expectedVersion) == 1;
    }

    /** 追加原始包；同会话同序号同状态唯一约束兜底并发重复。返回新行主键。 */
    public long insertPacket(PacketRow row) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            java.sql.PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO collection_packet (session_id, seq, point_code, measured_torque, measured_at, "
                            + "state, attempt_no, request_id, received_at, admitted_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    new String[]{"id"});
            ps.setLong(1, row.sessionId());
            ps.setInt(2, row.seq());
            ps.setString(3, row.pointCode());
            ps.setBigDecimal(4, row.measuredTorque());
            ps.setTimestamp(5, Timestamp.from(row.measuredAt()));
            ps.setString(6, row.state().name());
            if (row.attemptNo() == null) {
                ps.setObject(7, null);
            } else {
                ps.setInt(7, row.attemptNo());
            }
            ps.setString(8, row.requestId());
            ps.setTimestamp(9, Timestamp.from(row.receivedAt()));
            ps.setTimestamp(10, row.admittedAt() == null ? null : Timestamp.from(row.admittedAt()));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? 0L : key.longValue();
    }

    /** 按主键查询原始包。 */
    public PacketRow findPacketById(long id) {
        List<PacketRow> rows = jdbcTemplate.query(
                "SELECT " + PACKET_COLUMNS + " FROM collection_packet WHERE id = ?", PACKET_MAPPER, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 缓冲包随前缀入账：置 ADMITTED、记录力矩测量序号与入账时刻。 */
    public void markPacketAdmitted(long packetId, Integer attemptNo, Instant admittedAt) {
        int updated = jdbcTemplate.update(
                "UPDATE collection_packet SET state = 'ADMITTED', attempt_no = ?, admitted_at = ? WHERE id = ?",
                attemptNo, Timestamp.from(admittedAt), packetId);
        if (updated != 1) {
            throw new IllegalStateException("缓冲包入账更新失败: " + packetId);
        }
    }

    public List<PacketRow> listPackets(long sessionId) {
        return jdbcTemplate.query(
                "SELECT " + PACKET_COLUMNS + " FROM collection_packet WHERE session_id = ? ORDER BY seq",
                PACKET_MAPPER, sessionId);
    }

    public List<PacketRow> listPacketsByState(long sessionId, PacketState state) {
        return jdbcTemplate.query(
                "SELECT " + PACKET_COLUMNS + " FROM collection_packet WHERE session_id = ? AND state = ? "
                        + "ORDER BY seq",
                PACKET_MAPPER, sessionId, state.name());
    }

    public List<String> listPointCodes(long sessionId) {
        return jdbcTemplate.queryForList(
                "SELECT point_code FROM collection_session_point WHERE session_id = ? ORDER BY id",
                String.class, sessionId);
    }

    /** 工序卡是否存在未关闭采集会话（归档前置条件：全部会话须 CLOSED）。 */
    public boolean existsOpenByCard(long cardId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM collection_session WHERE card_id = ? AND status = 'OPEN'",
                Integer.class, cardId);
        return count != null && count > 0;
    }

    /**
     * 工序卡是否存在尚未采全的开放会话（水位线未达计划总数）。
     * 用于禁止在有缺号/缓冲未消费时把卡片推进到力矩紧固完成。
     */
    public boolean existsIncompleteOpenByCard(long cardId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM collection_session WHERE card_id = ? "
                        + "AND status = 'OPEN' AND watermark < expected_count",
                Integer.class, cardId);
        return count != null && count > 0;
    }
}
