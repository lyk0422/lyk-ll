package com.example.starter.weigh;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;

/**
 * 检测记录数据访问：参数化 SQL，唯一约束保证幂等与去重。
 */
@Repository
public class WeighRecordRepository {

    private static final RowMapper<WeighRecord> MAPPER = WeighRecordRepository::mapRow;

    private final JdbcTemplate jdbc;

    public WeighRecordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<WeighRecord> findByRequestId(String requestId) {
        return jdbc.query("SELECT * FROM weigh_record WHERE request_id = ?", MAPPER, requestId)
                .stream().findFirst();
    }

    public Optional<WeighRecord> findById(long id) {
        return jdbc.query("SELECT * FROM weigh_record WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 按“车牌+检测站+过磅时刻+称重方式”查去重记录。
     */
    public Optional<WeighRecord> findByDedupKey(String plateNo, String stationId, Instant weighedAt, String weighType) {
        return jdbc.query(
                "SELECT * FROM weigh_record WHERE plate_no = ? AND station_id = ? AND weighed_at = ? AND weigh_type = ?",
                MAPPER, plateNo, stationId, Timestamp.from(weighedAt), weighType)
                .stream().findFirst();
    }

    /**
     * 查同一“车牌+检测站+过磅时刻”的静态复检记录。
     */
    public Optional<WeighRecord> findStaticAt(String plateNo, String stationId, Instant weighedAt) {
        return findByDedupKey(plateNo, stationId, weighedAt, "STATIC");
    }

    public long insert(WeighRecord record) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO weigh_record (request_id, fingerprint, plate_no, station_id, weighed_at,"
                            + " axle_type, weigh_type, total_weight_kg, limit_weight_kg, over_rate,"
                            + " over_limit, authoritative, late_evidence, created_at)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, record.requestId());
            ps.setString(2, record.fingerprint());
            ps.setString(3, record.plateNo());
            ps.setString(4, record.stationId());
            ps.setTimestamp(5, Timestamp.from(record.weighedAt()));
            ps.setString(6, record.axleType());
            ps.setString(7, record.weighType());
            ps.setInt(8, record.totalWeightKg());
            ps.setInt(9, record.limitWeightKg());
            ps.setBigDecimal(10, record.overRate());
            ps.setBoolean(11, record.overLimit());
            ps.setBoolean(12, record.authoritative());
            ps.setBoolean(13, record.lateEvidence());
            ps.setTimestamp(14, Timestamp.from(Instant.now()));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 将同一时刻的动态记录标记为被静态复检取代（不再作为判定依据，记录保留可查）。
     */
    public int supersedeDynamicAt(String plateNo, String stationId, Instant weighedAt) {
        return jdbc.update(
                "UPDATE weigh_record SET authoritative = FALSE"
                        + " WHERE plate_no = ? AND station_id = ? AND weighed_at = ? AND weigh_type = 'DYNAMIC'",
                plateNo, stationId, Timestamp.from(weighedAt));
    }

    /**
     * 查轴型限值，单位千克。
     */
    public Optional<Integer> findLimitKg(String axleType) {
        return jdbc.query("SELECT limit_weight_kg FROM axle_limit WHERE axle_type = ?",
                (rs, n) -> rs.getInt(1), axleType).stream().findFirst();
    }

    private static WeighRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new WeighRecord(
                rs.getLong("id"),
                rs.getString("request_id"),
                rs.getString("fingerprint"),
                rs.getString("plate_no"),
                rs.getString("station_id"),
                rs.getTimestamp("weighed_at").toInstant(),
                rs.getString("axle_type"),
                rs.getString("weigh_type"),
                rs.getInt("total_weight_kg"),
                rs.getInt("limit_weight_kg"),
                rs.getBigDecimal("over_rate"),
                rs.getBoolean("over_limit"),
                rs.getBoolean("authoritative"),
                rs.getBoolean("late_evidence"));
    }
}
