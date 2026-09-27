package com.example.starter.radio.repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

import com.example.starter.radio.model.DispensingVersion;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 分装计算版本证据数据访问。旧版本永久保留，仅新增、不覆盖。
 */
@Repository
public class VersionRepository {

    private final JdbcTemplate jdbc;

    public VersionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(DispensingVersion v) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO dispensing_version (dispensing_id, version_no, administration_time_epoch,"
                            + " dispensed_activity_bq, deviation_ratio, mother_initial_activity_bq,"
                            + " mother_reference_time_epoch, half_life_seconds, source, created_at_epoch)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, v.dispensingId());
            ps.setLong(2, v.versionNo());
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
            ps.setDouble(6, v.motherInitialActivityBq());
            ps.setLong(7, v.motherReferenceTimeEpoch());
            ps.setLong(8, v.halfLifeSeconds());
            ps.setString(9, v.source());
            ps.setLong(10, v.createdAtEpoch());
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public Optional<DispensingVersion> findByDispensingAndVersion(long dispensingId, long versionNo) {
        return jdbc.query("SELECT * FROM dispensing_version WHERE dispensing_id = ? AND version_no = ?",
                this::map, dispensingId, versionNo).stream().findFirst();
    }

    public List<DispensingVersion> findByDispensing(long dispensingId) {
        return jdbc.query("SELECT * FROM dispensing_version WHERE dispensing_id = ? ORDER BY version_no",
                this::map, dispensingId);
    }

    private DispensingVersion map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        double activity = rs.getDouble("dispensed_activity_bq");
        Double dispensedActivity = rs.wasNull() ? null : activity;
        double deviation = rs.getDouble("deviation_ratio");
        Double deviationRatio = rs.wasNull() ? null : deviation;
        return new DispensingVersion(
                rs.getLong("id"),
                rs.getLong("dispensing_id"),
                rs.getLong("version_no"),
                rs.getLong("administration_time_epoch"),
                dispensedActivity,
                deviationRatio,
                rs.getDouble("mother_initial_activity_bq"),
                rs.getLong("mother_reference_time_epoch"),
                rs.getLong("half_life_seconds"),
                rs.getString("source"),
                rs.getLong("created_at_epoch"));
    }
}
