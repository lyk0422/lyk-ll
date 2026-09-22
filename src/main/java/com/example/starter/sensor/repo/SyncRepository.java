package com.example.starter.sensor.repo;

import com.example.starter.sensor.po.CorrectionRow;
import com.example.starter.sensor.po.DroppedRow;
import com.example.starter.sensor.po.FrameInputRow;
import com.example.starter.sensor.po.FrameRow;
import com.example.starter.sensor.po.MessageRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * 同步服务的 H2 事件仓储：消息事件、校正版本、融合帧证据与丢弃记录。
 *
 * <p>所有表由 schema.sql 在启动时自动创建。
 */
@Repository
public class SyncRepository {

    private final JdbcTemplate jdbc;

    public SyncRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---- 消息事件 ----

    /** 判断同一传感器内消息 ID 是否已存在（重复检测）。 */
    public boolean existsMessage(String sensor, String messageId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM sensor_message WHERE sensor = ? AND message_id = ?",
                Integer.class, sensor, messageId);
        return count != null && count > 0;
    }

    /** 插入一条原始消息事件。 */
    public void insertMessage(MessageRow row) {
        jdbc.update(
                "INSERT INTO sensor_message (sensor, message_id, raw_ts, payload, received_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                row.sensor(), row.messageId(), row.rawTs(), row.payload(), row.receivedAt());
    }

    /** 取某传感器全部消息，按原始时戳、到达次序排序。 */
    public List<MessageRow> findMessages(String sensor) {
        return jdbc.query(
                "SELECT sensor, message_id, raw_ts, payload, received_at FROM sensor_message "
                        + "WHERE sensor = ? ORDER BY raw_ts ASC, received_at ASC, message_id ASC",
                MESSAGE_MAPPER, sensor);
    }

    /** 取全部传感器消息，按传感器、原始时戳排序。 */
    public List<MessageRow> findAllMessages() {
        return jdbc.query(
                "SELECT sensor, message_id, raw_ts, payload, received_at FROM sensor_message "
                        + "ORDER BY sensor ASC, raw_ts ASC, received_at ASC, message_id ASC",
                MESSAGE_MAPPER);
    }

    // ---- 漂移校正版本 ----

    /** 取下一个校正版本号。 */
    public long nextCorrectionVersion(String sensor) {
        Long max = jdbc.queryForObject(
                "SELECT MAX(version) FROM clock_correction WHERE sensor = ?",
                Long.class, sensor);
        return (max == null ? 0L : max) + 1L;
    }

    /** 写入一个校正版本，返回记录 ID。 */
    public long insertCorrection(CorrectionRow row) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO clock_correction (sensor, version, slope, offset_nanos, "
                            + "effective_at, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, row.sensor());
            ps.setLong(2, row.version());
            ps.setDouble(3, row.slope());
            ps.setLong(4, row.offsetNanos());
            ps.setLong(5, row.effectiveAt());
            ps.setLong(6, row.createdAt());
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? 0L : key.longValue();
    }

    /** 取某传感器全部校正版本，按生效时刻、版本排序。 */
    public List<CorrectionRow> findCorrections(String sensor) {
        return jdbc.query(
                "SELECT id, sensor, version, slope, offset_nanos, effective_at, created_at "
                        + "FROM clock_correction WHERE sensor = ? "
                        + "ORDER BY effective_at ASC, version ASC",
                CORRECTION_MAPPER, sensor);
    }

    /** 取某传感器在指定墙钟时刻（含）之前已存在的校正版本（历史重建截止线）。 */
    public List<CorrectionRow> findCorrectionsCreatedBefore(String sensor, long cutoffMs) {
        return jdbc.query(
                "SELECT id, sensor, version, slope, offset_nanos, effective_at, created_at "
                        + "FROM clock_correction WHERE sensor = ? AND created_at <= ? "
                        + "ORDER BY effective_at ASC, version ASC",
                CORRECTION_MAPPER, sensor, cutoffMs);
    }

    // ---- 融合帧与证据 ----

    /** 按帧 ID 查帧，不存在返回 null。 */
    public FrameRow findFrame(String frameId) {
        List<FrameRow> rows = jdbc.query(
                "SELECT frame_id, target_ts, status, committed_at, reason, reason_detail, correction_cutoff "
                        + "FROM fusion_frame WHERE frame_id = ?",
                FRAME_MAPPER, frameId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 插入融合帧。 */
    public void insertFrame(FrameRow row) {
        jdbc.update(
                "INSERT INTO fusion_frame (frame_id, target_ts, status, committed_at, reason, "
                        + "reason_detail, correction_cutoff) VALUES (?, ?, ?, ?, ?, ?, ?)",
                row.frameId(), row.targetTs(), row.status(), row.committedAt(),
                row.reason(), row.reasonDetail(), row.correctionCutoff());
    }

    /** 删除融合帧及其输入证据（仅用于 WAITING 拒绝记录被后续重试覆盖）。 */
    public void deleteFrame(String frameId) {
        jdbc.update("DELETE FROM frame_input WHERE frame_id = ?", frameId);
        jdbc.update("DELETE FROM fusion_frame WHERE frame_id = ?", frameId);
    }

    /** 插入一条输入证据。 */
    public void insertFrameInput(FrameInputRow row) {
        jdbc.update(
                "INSERT INTO frame_input (frame_id, sensor, message_id, raw_ts, corrected_ts, "
                        + "time_delta_nanos, interpolated, anchor_message_ids, correction_version, payload) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.frameId(), row.sensor(), row.messageId(), row.rawTs(), row.correctedTs(),
                row.timeDeltaNanos(), row.interpolated(), row.anchorMessageIds(),
                row.correctionVersion(), row.payload());
    }

    /** 取帧的全部输入证据，按传感器排序。 */
    public List<FrameInputRow> findFrameInputs(String frameId) {
        return jdbc.query(
                "SELECT id, frame_id, sensor, message_id, raw_ts, corrected_ts, time_delta_nanos, "
                        + "interpolated, anchor_message_ids, correction_version, payload "
                        + "FROM frame_input WHERE frame_id = ? ORDER BY sensor ASC",
                INPUT_MAPPER, frameId);
    }

    // ---- 丢弃记录 ----

    /** 记录一条丢弃消息。 */
    public void insertDropped(DroppedRow row) {
        jdbc.update(
                "INSERT INTO dropped_message (sensor, message_id, raw_ts, received_at, reason, reason_detail) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                row.sensor(), row.messageId(), row.rawTs(), row.receivedAt(),
                row.reason(), row.reasonDetail());
    }

    /** 取某传感器的丢弃记录，按 ID 排序。 */
    public List<DroppedRow> findDropped(String sensor) {
        return jdbc.query(
                "SELECT id, sensor, message_id, raw_ts, received_at, reason, reason_detail "
                        + "FROM dropped_message WHERE sensor = ? ORDER BY id ASC",
                DROPPED_MAPPER, sensor);
    }

    /** 取全部丢弃记录。 */
    public List<DroppedRow> findAllDropped() {
        return jdbc.query(
                "SELECT id, sensor, message_id, raw_ts, received_at, reason, reason_detail "
                        + "FROM dropped_message ORDER BY id ASC",
                DROPPED_MAPPER);
    }

    // ---- RowMapper ----

    private static final RowMapper<MessageRow> MESSAGE_MAPPER = new RowMapper<>() {
        @Override
        public MessageRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new MessageRow(
                    rs.getString("sensor"),
                    rs.getString("message_id"),
                    rs.getLong("raw_ts"),
                    rs.getString("payload"),
                    rs.getLong("received_at"));
        }
    };

    private static final RowMapper<CorrectionRow> CORRECTION_MAPPER = new RowMapper<>() {
        @Override
        public CorrectionRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new CorrectionRow(
                    rs.getLong("id"),
                    rs.getString("sensor"),
                    rs.getLong("version"),
                    rs.getDouble("slope"),
                    rs.getLong("offset_nanos"),
                    rs.getLong("effective_at"),
                    rs.getLong("created_at"));
        }
    };

    private static final RowMapper<FrameRow> FRAME_MAPPER = new RowMapper<>() {
        @Override
        public FrameRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            long cutoff = rs.getLong("correction_cutoff");
            return new FrameRow(
                    rs.getString("frame_id"),
                    rs.getLong("target_ts"),
                    rs.getString("status"),
                    rs.getLong("committed_at"),
                    rs.getString("reason"),
                    rs.getString("reason_detail"),
                    rs.wasNull() ? null : cutoff);
        }
    };

    private static final RowMapper<FrameInputRow> INPUT_MAPPER = new RowMapper<>() {
        @Override
        public FrameInputRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new FrameInputRow(
                    rs.getLong("id"),
                    rs.getString("frame_id"),
                    rs.getString("sensor"),
                    rs.getString("message_id"),
                    rs.getLong("raw_ts"),
                    rs.getLong("corrected_ts"),
                    rs.getLong("time_delta_nanos"),
                    rs.getBoolean("interpolated"),
                    rs.getString("anchor_message_ids"),
                    rs.getLong("correction_version"),
                    rs.getString("payload"));
        }
    };

    private static final RowMapper<DroppedRow> DROPPED_MAPPER = new RowMapper<>() {
        @Override
        public DroppedRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            long rawTs = rs.getLong("raw_ts");
            return new DroppedRow(
                    rs.getLong("id"),
                    rs.getString("sensor"),
                    rs.getString("message_id"),
                    rs.wasNull() ? null : rawTs,
                    rs.getLong("received_at"),
                    rs.getString("reason"),
                    rs.getString("reason_detail"));
        }
    };
}
