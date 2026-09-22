package com.example.starter.sync.repo;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Clock;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.sync.SensorType;
import com.example.starter.sync.domain.FrameInput;
import com.example.starter.sync.domain.FusionFrame;

/**
 * 融合帧仓储。fusion_frame.target_ts 唯一，保证同一目标时刻只提交一帧（不可变）。
 */
@Repository
public class FrameRepository {

    private static final RowMapper<FrameInput> INPUT_MAPPER = (rs, n) -> new FrameInput(
            SensorType.valueOf(rs.getString("sensor_type")),
            rs.getString("message_id"),
            rs.getLong("raw_ts"),
            rs.getLong("corrected_ts"),
            rs.getLong("delta_ms"),
            rs.getBoolean("interpolated"));

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public FrameRepository(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * 插入帧头，返回帧ID；目标时刻已存在时抛出 DuplicateKeyException。
     */
    public long insertFrame(long targetTs) {
        String sql = "INSERT INTO fusion_frame (target_ts, status, created_at) VALUES (?, 'COMMITTED', ?)";
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, targetTs);
            ps.setLong(2, clock.millis());
            return ps;
        }, keys);
        Number key = keys.getKey();
        return key == null ? -1L : key.longValue();
    }

    /**
     * 插入帧头并容忍并发重复：目标时刻已存在时返回 empty。
     */
    public Optional<Long> tryInsertFrame(long targetTs) {
        try {
            return Optional.of(insertFrame(targetTs));
        } catch (DuplicateKeyException e) {
            return Optional.empty();
        }
    }

    /**
     * 插入一条帧输入证据。
     */
    public void insertInput(long frameId, FrameInput input) {
        jdbc.update("INSERT INTO fusion_frame_input (frame_id, sensor_type, message_id, raw_ts,"
                        + " corrected_ts, delta_ms, interpolated) VALUES (?, ?, ?, ?, ?, ?, ?)",
                frameId, input.sensorType().name(), input.messageId(), input.rawTs(),
                input.correctedTs(), input.deltaMs(), input.interpolated());
    }

    /**
     * 按目标时刻查询已提交帧及其输入证据。
     */
    public Optional<FusionFrame> findFrame(long targetTs) {
        Long frameId;
        try {
            frameId = jdbc.queryForObject(
                    "SELECT id FROM fusion_frame WHERE target_ts = ?", Long.class, targetTs);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
        List<FrameInput> inputs = jdbc.query(
                "SELECT * FROM fusion_frame_input WHERE frame_id = ? ORDER BY sensor_type",
                INPUT_MAPPER, frameId);
        return Optional.of(new FusionFrame(frameId, targetTs, inputs));
    }
}
