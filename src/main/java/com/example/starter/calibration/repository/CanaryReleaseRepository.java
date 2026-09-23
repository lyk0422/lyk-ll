package com.example.starter.calibration.repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.domain.CanaryRelease;
import com.example.starter.calibration.domain.CanaryVehicle;
import com.example.starter.calibration.domain.ReleaseStatus;

/**
 * 灰度发布单、车辆明细与活跃灰度占用表的数据访问。
 */
@Repository
public class CanaryReleaseRepository {

    private final JdbcTemplate jdbc;

    public CanaryReleaseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 插入发布单，回填主键。 */
    public void insertRelease(CanaryRelease release) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO canary_release
                        (calibration_key, new_version_id, status, operator, created_at)
                    VALUES (?,?,?,?,?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, release.getCalibrationKey());
            ps.setLong(2, release.getNewVersionId());
            ps.setString(3, release.getStatus().name());
            ps.setString(4, release.getOperator());
            ps.setTimestamp(5, Timestamp.from(release.getCreatedAt()));
            return ps;
        }, keyHolder);
        release.setId(keyHolder.getKeyAs(Long.class));
    }

    public CanaryRelease findReleaseById(long id) {
        List<CanaryRelease> list = jdbc.query(
                "SELECT * FROM canary_release WHERE id = ?", new ReleaseRowMapper(), id);
        return list.isEmpty() ? null : list.get(0);
    }

    public List<CanaryVehicle> findVehiclesByReleaseId(long releaseId) {
        return jdbc.query("SELECT * FROM canary_vehicle WHERE release_id = ? ORDER BY id",
                new VehicleRowMapper(), releaseId);
    }

    public void insertVehicle(CanaryVehicle vehicle) {
        jdbc.update("""
                INSERT INTO canary_vehicle (release_id, vehicle_id, previous_version_id)
                VALUES (?,?,?)
                """,
                vehicle.getReleaseId(), vehicle.getVehicleId(), vehicle.getPreviousVersionId());
    }

    /**
     * 占用活跃灰度车辆槽位；同一定位键 + 车辆已存在活跃灰度时数据库抛主键冲突，
     * 由服务层转换为整批回滚。
     */
    public void insertActiveVehicle(String calibrationKey, String vehicleId, long releaseId) {
        jdbc.update("""
                INSERT INTO active_canary_vehicle (calibration_key, vehicle_id, release_id)
                VALUES (?,?,?)
                """, calibrationKey, vehicleId, releaseId);
    }

    public void deleteActiveVehiclesByReleaseId(long releaseId) {
        jdbc.update("DELETE FROM active_canary_vehicle WHERE release_id = ?", releaseId);
    }

    /** 查询当前覆盖某车辆的活跃发布单 ID，无则返回 null。 */
    public Long findActiveReleaseId(String calibrationKey, String vehicleId) {
        List<Long> ids = jdbc.query("""
                SELECT release_id FROM active_canary_vehicle
                WHERE calibration_key = ? AND vehicle_id = ?
                """,
                (rs, rowNum) -> rs.getLong("release_id"), calibrationKey, vehicleId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /** 回滚发布单：记录操作者、时间与原因。 */
    public void markRolledBack(long releaseId, String operator, Instant at, String reason) {
        jdbc.update("""
                UPDATE canary_release
                SET status = 'ROLLED_BACK', rollback_operator = ?, rolled_back_at = ?, reason = ?
                WHERE id = ?
                """, operator, Timestamp.from(at), reason, releaseId);
    }

    private static class ReleaseRowMapper implements RowMapper<CanaryRelease> {
        @Override
        public CanaryRelease mapRow(ResultSet rs, int rowNum) throws SQLException {
            CanaryRelease r = new CanaryRelease();
            r.setId(rs.getLong("id"));
            r.setCalibrationKey(rs.getString("calibration_key"));
            r.setNewVersionId(rs.getLong("new_version_id"));
            r.setStatus(ReleaseStatus.valueOf(rs.getString("status")));
            r.setOperator(rs.getString("operator"));
            r.setCreatedAt(rs.getTimestamp("created_at").toInstant());
            r.setRollbackOperator(rs.getString("rollback_operator"));
            Timestamp at = rs.getTimestamp("rolled_back_at");
            r.setRolledBackAt(at == null ? null : at.toInstant());
            r.setReason(rs.getString("reason"));
            return r;
        }
    }

    private static class VehicleRowMapper implements RowMapper<CanaryVehicle> {
        @Override
        public CanaryVehicle mapRow(ResultSet rs, int rowNum) throws SQLException {
            CanaryVehicle v = new CanaryVehicle();
            v.setId(rs.getLong("id"));
            v.setReleaseId(rs.getLong("release_id"));
            v.setVehicleId(rs.getString("vehicle_id"));
            long previousId = rs.getLong("previous_version_id");
            v.setPreviousVersionId(rs.wasNull() ? null : previousId);
            return v;
        }
    }
}
