package com.example.starter.db;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 通风作业数据访问：作业排程、作业窗重叠查询与完成登记。
 */
@Repository
public class VentilationRepository {

    private static final RowMapper<VentilationOperationPo> MAPPER = (rs, n) -> new VentilationOperationPo(
            rs.getLong("id"),
            rs.getLong("silo_id"),
            rs.getLong("fan_id"),
            rs.getString("request_id"),
            rs.getString("purpose"),
            rs.getInt("round_no"),
            rs.getDouble("ambient_humidity"),
            rs.getTimestamp("start_time").toLocalDateTime(),
            rs.getTimestamp("end_time").toLocalDateTime(),
            rs.getString("status"),
            rs.getTimestamp("started_at") == null ? null : rs.getTimestamp("started_at").toLocalDateTime(),
            rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toLocalDateTime(),
            rs.getTimestamp("created_at").toLocalDateTime()
    );

    private final JdbcTemplate jdbc;

    public VentilationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public VentilationOperationPo insert(long siloId, long fanId, String requestId, String purpose, int roundNo,
                                         double ambientHumidity, LocalDateTime startTime, LocalDateTime endTime,
                                         LocalDateTime startedAt) {
        LocalDateTime now = LocalDateTime.now();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO ventilation_operation (silo_id, fan_id, request_id, purpose, round_no, "
                            + "ambient_humidity, start_time, end_time, status, started_at, completed_at, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'EXECUTING', ?, NULL, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, siloId);
            ps.setLong(2, fanId);
            ps.setString(3, requestId);
            ps.setString(4, purpose);
            ps.setInt(5, roundNo);
            ps.setDouble(6, ambientHumidity);
            ps.setTimestamp(7, Timestamp.valueOf(startTime));
            ps.setTimestamp(8, Timestamp.valueOf(endTime));
            ps.setTimestamp(9, Timestamp.valueOf(startedAt));
            ps.setTimestamp(10, Timestamp.valueOf(now));
            return ps;
        }, keyHolder);
        long id = keyHolder.getKey() == null ? 0L : keyHolder.getKey().longValue();
        return new VentilationOperationPo(id, siloId, fanId, requestId, purpose, roundNo, ambientHumidity,
                startTime, endTime, "EXECUTING", startedAt, null, now);
    }

    public Optional<VentilationOperationPo> findByRequestId(String requestId) {
        List<VentilationOperationPo> rows = jdbc.query(
                "SELECT * FROM ventilation_operation WHERE request_id = ?", MAPPER, requestId);
        return rows.stream().findFirst();
    }

    public Optional<VentilationOperationPo> findActiveBySilo(long siloId) {
        List<VentilationOperationPo> rows = jdbc.query(
                "SELECT * FROM ventilation_operation WHERE silo_id = ? AND status = 'EXECUTING' "
                        + "ORDER BY id DESC LIMIT 1", MAPPER, siloId);
        return rows.stream().findFirst();
    }

    public List<VentilationOperationPo> findBySilo(long siloId) {
        return jdbc.query("SELECT * FROM ventilation_operation WHERE silo_id = ? ORDER BY id", MAPPER, siloId);
    }

    /**
     * 作业窗重叠判定：半开区间 [start,end)。
     * 两区间重叠当且仅当 {@code existing.start < new.end AND existing.end > new.start}；
     * 端点相接（existing.end == new.start 或反向）时两个严格不等式均不成立，故不冲突。
     */
    public boolean windowOverlaps(long fanId, LocalDateTime start, LocalDateTime end) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ventilation_operation WHERE fan_id = ? "
                        + "AND start_time < ? AND end_time > ?",
                Integer.class, fanId, Timestamp.valueOf(end), Timestamp.valueOf(start));
        return count != null && count > 0;
    }

    public void complete(long operationId) {
        jdbc.update("UPDATE ventilation_operation SET status = 'COMPLETED', completed_at = ? WHERE id = ?",
                Timestamp.valueOf(LocalDateTime.now()), operationId);
    }
}
