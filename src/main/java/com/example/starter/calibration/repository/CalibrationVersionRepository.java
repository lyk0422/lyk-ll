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

import com.example.starter.calibration.domain.CalibrationStatus;
import com.example.starter.calibration.domain.CalibrationVersion;
import com.example.starter.calibration.domain.SensorType;
import com.example.starter.calibration.domain.VehicleScope;

/**
 * 标定参数版本数据访问。
 */
@Repository
public class CalibrationVersionRepository {

    private static final String COLUMNS = """
            id, calibration_key, sensor_type, version, content_digest, payload, status,
            effective_from, effective_to, vehicle_range, sensor_id, map_version_from, map_version_to,
            validation_error, created_by, created_at, approved_by, approved_at,
            activated_by, activated_at, rolled_back_by, rolled_back_at, canary_release_id
            """;

    private final JdbcTemplate jdbc;

    public CalibrationVersionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 插入新版本，回填自增主键。 */
    public void insert(CalibrationVersion v) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO calibration_version (
                        calibration_key, sensor_type, version, content_digest, payload, status,
                        effective_from, effective_to, vehicle_range, sensor_id,
                        map_version_from, map_version_to, validation_error,
                        created_by, created_at, canary_release_id
                    ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, v.getCalibrationKey());
            ps.setString(2, v.getSensorType().name());
            ps.setString(3, v.getVersion());
            ps.setString(4, v.getContentDigest());
            ps.setString(5, v.getPayload());
            ps.setString(6, v.getStatus().name());
            ps.setTimestamp(7, Timestamp.from(v.getEffectiveFrom()));
            ps.setTimestamp(8, v.getEffectiveTo() == null ? null : Timestamp.from(v.getEffectiveTo()));
            ps.setString(9, v.getVehicleScope().encode());
            ps.setString(10, v.getSensorId());
            ps.setObject(11, v.getMapVersionFrom());
            ps.setObject(12, v.getMapVersionTo());
            ps.setString(13, v.getValidationError());
            ps.setString(14, v.getCreatedBy());
            ps.setTimestamp(15, Timestamp.from(v.getCreatedAt()));
            ps.setObject(16, v.getCanaryReleaseId());
            return ps;
        }, keyHolder);
        v.setId(keyHolder.getKeyAs(Long.class));
    }

    public CalibrationVersion findById(long id) {
        List<CalibrationVersion> list = jdbc.query(
                "SELECT " + COLUMNS + " FROM calibration_version WHERE id = ?",
                new VersionRowMapper(), id);
        return list.isEmpty() ? null : list.get(0);
    }

    public CalibrationVersion findByKeyAndVersion(String key, String version) {
        List<CalibrationVersion> list = jdbc.query(
                "SELECT " + COLUMNS + " FROM calibration_version WHERE calibration_key = ? AND version = ?",
                new VersionRowMapper(), key, version);
        return list.isEmpty() ? null : list.get(0);
    }

    public List<CalibrationVersion> findAllByKey(String key) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM calibration_version WHERE calibration_key = ? ORDER BY id",
                new VersionRowMapper(), key);
    }

    /**
     * 查询某标定键下可能参与解析的版本（已批准/已生效/灰度中），范围与时间的精细匹配在领域层完成。
     */
    public List<CalibrationVersion> findResolutionCandidates(String key, String sensorId,
            Integer mapVersion, Instant time) {
        return jdbc.query("""
                SELECT %s FROM calibration_version
                WHERE calibration_key = ?
                  AND sensor_id = ?
                  AND status IN ('APPROVED','EFFECTIVE','IN_CANARY')
                  AND effective_from <= ?
                  AND (effective_to IS NULL OR effective_to > ?)
                  AND (map_version_from IS NULL OR ? IS NULL OR map_version_from <= ?)
                  AND (map_version_to IS NULL OR ? IS NULL OR ? <= map_version_to)
                ORDER BY id
                """.formatted(COLUMNS),
                new VersionRowMapper(),
                key, sensorId,
                Timestamp.from(time), Timestamp.from(time),
                mapVersion, mapVersion,
                mapVersion, mapVersion);
    }

    /**
     * 查询同一定位键（标定键 + 传感器）下、与给定状态集合和范围可能冲突的可用版本。
     * 时间/地图区间重叠的精确判定在领域层完成。
     */
    public List<CalibrationVersion> findOverlappingCandidates(String key, String sensorId,
            List<CalibrationStatus> statuses) {
        String placeholders = String.join(",", statuses.stream().map(s -> "?").toList());
        return jdbc.query("""
                SELECT %s FROM calibration_version
                WHERE calibration_key = ? AND sensor_id = ? AND status IN (%s)
                ORDER BY id
                """.formatted(COLUMNS, placeholders),
                new VersionRowMapper(),
                key, sensorId,
                statuses.stream().map(Enum::name).toArray());
    }

    /** 全量生效：置为 EFFECTIVE，记录操作者与时间。 */
    public void markEffective(long id, String operator, Instant at) {
        jdbc.update("""
                UPDATE calibration_version
                SET status = 'EFFECTIVE', activated_by = ?, activated_at = ?
                WHERE id = ?
                """, operator, Timestamp.from(at), id);
    }

    /** 审批通过：置为 APPROVED，清空校验失败原因，记录操作者与时间。 */
    public void markApproved(long id, String operator, Instant at) {
        jdbc.update("""
                UPDATE calibration_version
                SET status = 'APPROVED', validation_error = NULL, approved_by = ?, approved_at = ?
                WHERE id = ?
                """, operator, Timestamp.from(at), id);
    }

    /** 灰度发布：置为 IN_CANARY，关联发布单。 */
    public void markInCanary(long id, long releaseId) {
        jdbc.update("UPDATE calibration_version SET status = 'IN_CANARY', canary_release_id = ? WHERE id = ?",
                releaseId, id);
    }

    /** 回滚：置为 ROLLED_BACK，记录操作者与时间，解除灰度关联。 */
    public void markRolledBack(long id, String operator, Instant at) {
        jdbc.update("""
                UPDATE calibration_version
                SET status = 'ROLLED_BACK', rolled_back_by = ?, rolled_back_at = ?, canary_release_id = NULL
                WHERE id = ?
                """, operator, Timestamp.from(at), id);
    }

    /** 灰度回滚后把版本恢复为已批准，可再次发布。 */
    public void markApprovedState(long id) {
        jdbc.update("UPDATE calibration_version SET status = 'APPROVED', canary_release_id = NULL WHERE id = ?",
                id);
    }

    /** 记录草稿最近一次校验失败原因，状态保持 DRAFT。 */
    public void updateValidationError(long id, String error) {
        jdbc.update("UPDATE calibration_version SET validation_error = ? WHERE id = ?", error, id);
    }

    private static class VersionRowMapper implements RowMapper<CalibrationVersion> {
        @Override
        public CalibrationVersion mapRow(ResultSet rs, int rowNum) throws SQLException {
            CalibrationVersion v = new CalibrationVersion();
            v.setId(rs.getLong("id"));
            v.setCalibrationKey(rs.getString("calibration_key"));
            v.setSensorType(SensorType.valueOf(rs.getString("sensor_type")));
            v.setVersion(rs.getString("version"));
            v.setContentDigest(rs.getString("content_digest"));
            v.setPayload(rs.getString("payload"));
            v.setStatus(CalibrationStatus.valueOf(rs.getString("status")));
            v.setEffectiveFrom(rs.getTimestamp("effective_from").toInstant());
            Timestamp to = rs.getTimestamp("effective_to");
            v.setEffectiveTo(to == null ? null : to.toInstant());
            v.setVehicleScope(VehicleScope.decode(rs.getString("vehicle_range")));
            v.setSensorId(rs.getString("sensor_id"));
            int mapFrom = rs.getInt("map_version_from");
            v.setMapVersionFrom(rs.wasNull() ? null : mapFrom);
            int mapTo = rs.getInt("map_version_to");
            v.setMapVersionTo(rs.wasNull() ? null : mapTo);
            v.setValidationError(rs.getString("validation_error"));
            v.setCreatedBy(rs.getString("created_by"));
            v.setCreatedAt(rs.getTimestamp("created_at").toInstant());
            v.setApprovedBy(rs.getString("approved_by"));
            Timestamp approvedAt = rs.getTimestamp("approved_at");
            v.setApprovedAt(approvedAt == null ? null : approvedAt.toInstant());
            v.setActivatedBy(rs.getString("activated_by"));
            Timestamp activatedAt = rs.getTimestamp("activated_at");
            v.setActivatedAt(activatedAt == null ? null : activatedAt.toInstant());
            v.setRolledBackBy(rs.getString("rolled_back_by"));
            Timestamp rolledBackAt = rs.getTimestamp("rolled_back_at");
            v.setRolledBackAt(rolledBackAt == null ? null : rolledBackAt.toInstant());
            long releaseId = rs.getLong("canary_release_id");
            v.setCanaryReleaseId(rs.wasNull() ? null : releaseId);
            return v;
        }
    }
}
