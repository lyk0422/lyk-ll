package com.example.starter.radio.repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Optional;

import com.example.starter.radio.model.Prescription;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 处方数据访问。
 */
@Repository
public class PrescriptionRepository {

    private final JdbcTemplate jdbc;

    public PrescriptionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(String code, long nuclideId, double prescribedActivityBq,
                       long administrationTimeEpoch, long createdAtEpoch) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO prescription (code, nuclide_id, prescribed_activity_bq,"
                            + " administration_time_epoch, created_at_epoch) VALUES (?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, code);
            ps.setLong(2, nuclideId);
            ps.setDouble(3, prescribedActivityBq);
            ps.setLong(4, administrationTimeEpoch);
            ps.setLong(5, createdAtEpoch);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public Optional<Prescription> findById(long id) {
        return jdbc.query("SELECT * FROM prescription WHERE id = ?",
                (rs, i) -> new Prescription(
                        rs.getLong("id"),
                        rs.getString("code"),
                        rs.getLong("nuclide_id"),
                        rs.getDouble("prescribed_activity_bq"),
                        rs.getLong("administration_time_epoch")),
                id).stream().findFirst();
    }
}
