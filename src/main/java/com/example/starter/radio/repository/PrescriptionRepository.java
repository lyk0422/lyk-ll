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
 * 处方数据访问。给药时刻变更以新增版本行实现，旧版本保留作为证据。
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
        long now = createdAtEpoch;
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO prescription (code, nuclide_id, prescribed_activity_bq,"
                            + " administration_time_epoch, version_no, root_prescription_id, created_at_epoch)"
                            + " VALUES (?, ?, ?, ?, 1, NULL, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, code);
            ps.setLong(2, nuclideId);
            ps.setDouble(3, prescribedActivityBq);
            ps.setLong(4, administrationTimeEpoch);
            ps.setLong(5, now);
            return ps;
        }, keys);
        long id = keys.getKey().longValue();
        // 首版本根指向自身
        jdbc.update("UPDATE prescription SET root_prescription_id = id WHERE id = ? AND root_prescription_id IS NULL",
                id);
        return id;
    }

    /**
     * 追加处方新版本（给药时刻变更），沿用同一根、核素与处方活度，返回新版本主键。
     */
    public long insertVersion(String code, long rootPrescriptionId, long nuclideId, double prescribedActivityBq,
                              long administrationTimeEpoch, int versionNo, long createdAtEpoch) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO prescription (code, nuclide_id, prescribed_activity_bq,"
                            + " administration_time_epoch, version_no, root_prescription_id, created_at_epoch)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, code);
            ps.setLong(2, nuclideId);
            ps.setDouble(3, prescribedActivityBq);
            ps.setLong(4, administrationTimeEpoch);
            ps.setInt(5, versionNo);
            ps.setLong(6, rootPrescriptionId);
            ps.setLong(7, createdAtEpoch);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public Optional<Prescription> findById(long id) {
        return jdbc.query("SELECT * FROM prescription WHERE id = ?",
                this::map, id).stream().findFirst();
    }

    /**
     * 取处方族（root_prescription_id）内版本号最大的当前有效版本。
     */
    public Optional<Prescription> findLatestByRoot(long rootPrescriptionId) {
        return jdbc.query(
                "SELECT * FROM prescription WHERE COALESCE(root_prescription_id, id) = ?"
                        + " ORDER BY version_no DESC LIMIT 1",
                this::map, rootPrescriptionId).stream().findFirst();
    }

    private Prescription map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Prescription(
                rs.getLong("id"),
                rs.getString("code"),
                rs.getLong("nuclide_id"),
                rs.getDouble("prescribed_activity_bq"),
                rs.getLong("administration_time_epoch"),
                rs.getInt("version_no"),
                (Long) rs.getObject("root_prescription_id"));
    }
}
