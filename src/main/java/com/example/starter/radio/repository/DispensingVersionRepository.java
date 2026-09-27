package com.example.starter.radio.repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

import com.example.starter.radio.domain.DispensingVersionStatus;
import com.example.starter.radio.model.DispensingVersion;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 分装计算版本证据数据访问。初算写入版本 1；每次给药时刻变更生效新增一个版本，
 * 旧版本置 SUPERSEDED 但永久保留，可按衰变公式由快照复算。
 */
@Repository
public class DispensingVersionRepository {

    private final JdbcTemplate jdbc;

    public DispensingVersionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(DispensingVersion v, long createdAtEpoch) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO dispensing_version (dispensing_id, version, administration_time_epoch,"
                            + " dispensed_activity_bq, deviation_ratio, mother_liquor_id,"
                            + " mother_initial_activity_bq, mother_reference_time_epoch, half_life_seconds,"
                            + " source, change_order_id, status, created_at_epoch)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, v.dispensingId());
            ps.setInt(2, v.version());
            ps.setLong(3, v.administrationTimeEpoch());
            if (v.dispensedActivityBq() == null) {
                ps.setNull(4, java.sql.Types.DOUBLE);
            } else {
                ps.setDouble(4, v.dispensedActivityBq());
            }
            if (v.deviationRatio() == null) {
                ps.setNull(5, java.sql.Types.DOUBLE);
            } else {
                ps.setDouble(5, v.deviationRatio());
            }
            ps.setLong(6, v.motherLiquorId());
            ps.setDouble(7, v.motherInitialActivityBq());
            ps.setLong(8, v.motherReferenceTimeEpoch());
            ps.setLong(9, v.halfLifeSeconds());
            ps.setString(10, v.source());
            if (v.changeOrderId() == null) {
                ps.setNull(11, java.sql.Types.BIGINT);
            } else {
                ps.setLong(11, v.changeOrderId());
            }
            ps.setString(12, v.status().name());
            ps.setLong(13, createdAtEpoch);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public List<DispensingVersion> findByDispensing(long dispensingId) {
        return jdbc.query("SELECT * FROM dispensing_version WHERE dispensing_id = ? ORDER BY version",
                this::map, dispensingId);
    }

    public Optional<DispensingVersion> findByDispensingAndVersion(long dispensingId, int version) {
        return jdbc.query("SELECT * FROM dispensing_version WHERE dispensing_id = ? AND version = ?",
                this::map, dispensingId, version).stream().findFirst();
    }

    /**
     * 变更生效时将旧有效版本置为 SUPERSEDED。
     */
    public int markSuperseded(long dispensingId, int version) {
        return jdbc.update(
                "UPDATE dispensing_version SET status = 'SUPERSEDED' WHERE dispensing_id = ? AND version = ?",
                dispensingId, version);
    }

    private DispensingVersion map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        double activity = rs.getDouble("dispensed_activity_bq");
        Double dispensedActivityBq = rs.wasNull() ? null : activity;
        double deviation = rs.getDouble("deviation_ratio");
        Double deviationRatio = rs.wasNull() ? null : deviation;
        long changeOrder = rs.getLong("change_order_id");
        Long changeOrderId = rs.wasNull() ? null : changeOrder;
        return new DispensingVersion(
                rs.getLong("id"),
                rs.getLong("dispensing_id"),
                rs.getInt("version"),
                rs.getLong("administration_time_epoch"),
                dispensedActivityBq,
                deviationRatio,
                rs.getLong("mother_liquor_id"),
                rs.getDouble("mother_initial_activity_bq"),
                rs.getLong("mother_reference_time_epoch"),
                rs.getLong("half_life_seconds"),
                rs.getString("source"),
                changeOrderId,
                DispensingVersionStatus.valueOf(rs.getString("status")),
                rs.getLong("created_at_epoch"));
    }
}
