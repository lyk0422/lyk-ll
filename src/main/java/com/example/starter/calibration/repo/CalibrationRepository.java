package com.example.starter.calibration.repo;

import com.example.starter.calibration.domain.CalibrationParam;
import com.example.starter.calibration.domain.CanaryRelease;
import com.example.starter.calibration.domain.CanaryVehicle;
import com.example.starter.calibration.domain.ParamStatus;
import com.example.starter.calibration.domain.ReleaseStatus;
import com.example.starter.calibration.domain.ResolutionRecord;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * 标定参数数据访问（H2 MODE=MySQL，参数化 SQL）。
 */
@Repository
public class CalibrationRepository {

    private final JdbcTemplate jdbc;

    public CalibrationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<CalibrationParam> PARAM_MAPPER = (rs, n) -> {
        CalibrationParam p = new CalibrationParam();
        p.setId(rs.getLong("id"));
        p.setParamKey(rs.getString("param_key"));
        p.setKind(com.example.starter.calibration.domain.CalibrationKind.valueOf(rs.getString("kind")));
        p.setSensorType(com.example.starter.calibration.domain.SensorType.valueOf(rs.getString("sensor_type")));
        p.setVersion(rs.getString("version"));
        p.setContentJson(rs.getString("content_json"));
        p.setContentDigest(rs.getString("content_digest"));
        p.setStatus(ParamStatus.valueOf(rs.getString("status")));
        Timestamp from = rs.getTimestamp("effective_from");
        p.setEffectiveFrom(from == null ? null : from.toInstant());
        Timestamp to = rs.getTimestamp("effective_to");
        p.setEffectiveTo(to == null ? null : to.toInstant());
        p.setVehicleScopeJson(rs.getString("vehicle_scope_json"));
        p.setSensorScopeJson(rs.getString("sensor_scope_json"));
        p.setMapVersionMin(rs.getString("map_version_min"));
        p.setMapVersionMax(rs.getString("map_version_max"));
        p.setCreatedBy(rs.getString("created_by"));
        Timestamp createdAt = rs.getTimestamp("created_at");
        p.setCreatedAt(createdAt == null ? null : createdAt.toInstant());
        p.setApprovedBy(rs.getString("approved_by"));
        Timestamp approvedAt = rs.getTimestamp("approved_at");
        p.setApprovedAt(approvedAt == null ? null : approvedAt.toInstant());
        return p;
    };

    private static final RowMapper<CanaryRelease> RELEASE_MAPPER = (rs, n) -> {
        CanaryRelease r = new CanaryRelease();
        r.setId(rs.getLong("id"));
        r.setReleaseId(rs.getString("release_id"));
        r.setParamId(rs.getLong("param_id"));
        r.setParamKey(rs.getString("param_key"));
        r.setNewVersion(rs.getString("new_version"));
        r.setVehiclesJson(rs.getString("vehicles_json"));
        r.setStatus(ReleaseStatus.valueOf(rs.getString("status")));
        r.setCreatedBy(rs.getString("created_by"));
        r.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        String finishedBy = rs.getString("finished_by");
        r.setFinishedBy(finishedBy);
        Timestamp finishedAt = rs.getTimestamp("finished_at");
        r.setFinishedAt(finishedAt == null ? null : finishedAt.toInstant());
        r.setRemark(rs.getString("remark"));
        return r;
    };

    private static final RowMapper<CanaryVehicle> VEHICLE_MAPPER = (rs, n) -> {
        CanaryVehicle v = new CanaryVehicle();
        v.setId(rs.getLong("id"));
        v.setReleaseId(rs.getLong("release_id"));
        v.setVehicleId(rs.getString("vehicle_id"));
        long prev = rs.getLong("previous_param_id");
        v.setPreviousParamId(rs.wasNull() ? null : prev);
        v.setPreviousVersion(rs.getString("previous_version"));
        return v;
    };

    private static final RowMapper<ResolutionRecord> RESOLUTION_MAPPER = (rs, n) -> {
        ResolutionRecord r = new ResolutionRecord();
        r.setId(rs.getLong("id"));
        r.setResolutionId(rs.getString("resolution_id"));
        r.setParamKey(rs.getString("param_key"));
        r.setVehicleId(rs.getString("vehicle_id"));
        r.setSensorId(rs.getString("sensor_id"));
        r.setMapVersion(rs.getString("map_version"));
        r.setResolveTime(rs.getTimestamp("resolve_time").toInstant());
        r.setResolvedParamId(rs.getLong("resolved_param_id"));
        r.setResolvedVersion(rs.getString("resolved_version"));
        r.setContentDigest(rs.getString("content_digest"));
        r.setContentJson(rs.getString("content_json"));
        return r;
    };

    // ---------- calibration_param ----------

    /** 插入参数版本并回填自增主键。 */
    public void insertParam(CalibrationParam p) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO calibration_param (param_key, kind, sensor_type, version, content_json, " +
                            "content_digest, status, effective_from, effective_to, vehicle_scope_json, " +
                            "sensor_scope_json, map_version_min, map_version_max, created_by, created_at, " +
                            "approved_by, approved_at) " +
                            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            int i = 1;
            ps.setString(i++, p.getParamKey());
            ps.setString(i++, p.getKind().name());
            ps.setString(i++, p.getSensorType().name());
            ps.setString(i++, p.getVersion());
            ps.setString(i++, p.getContentJson());
            ps.setString(i++, p.getContentDigest());
            ps.setString(i++, p.getStatus().name());
            setTimestamp(ps, i++, p.getEffectiveFrom());
            setTimestamp(ps, i++, p.getEffectiveTo());
            ps.setString(i++, p.getVehicleScopeJson());
            ps.setString(i++, p.getSensorScopeJson());
            ps.setString(i++, p.getMapVersionMin());
            ps.setString(i++, p.getMapVersionMax());
            ps.setString(i++, p.getCreatedBy());
            setTimestamp(ps, i++, p.getCreatedAt());
            ps.setString(i++, p.getApprovedBy());
            setTimestamp(ps, i, p.getApprovedAt());
            return ps;
        }, keyHolder);
        p.setId(keyHolder.getKeyAs(Long.class));
    }

    public CalibrationParam findParamById(Long id) {
        try {
            return jdbc.queryForObject(
                    "SELECT * FROM calibration_param WHERE id = ?", PARAM_MAPPER, id);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    public CalibrationParam findParamByKeyAndVersion(String paramKey, String version) {
        try {
            return jdbc.queryForObject(
                    "SELECT * FROM calibration_param WHERE param_key = ? AND version = ?",
                    PARAM_MAPPER, paramKey, version);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    public List<CalibrationParam> listByParamKey(String paramKey) {
        return jdbc.query("SELECT * FROM calibration_param WHERE param_key = ? ORDER BY id",
                PARAM_MAPPER, paramKey);
    }

    /**
     * 锁定同一参数键的全部版本行（SELECT ... FOR UPDATE）。
     * 必须在事务内调用，用于串行化同键的审批/灰度/回滚。
     */
    public void lockParamKey(String paramKey) {
        jdbc.query("SELECT id FROM calibration_param WHERE param_key = ? FOR UPDATE",
                rs -> { }, paramKey);
    }

    public int updateParamStatus(Long id, ParamStatus status) {
        return jdbc.update("UPDATE calibration_param SET status = ? WHERE id = ?",
                status.name(), id);
    }

    public int updateApproval(Long id, ParamStatus status, String approvedBy, Instant approvedAt) {
        return jdbc.update(
                "UPDATE calibration_param SET status = ?, approved_by = ?, approved_at = ? WHERE id = ?",
                status.name(), approvedBy, Timestamp.from(approvedAt), id);
    }

    /** 收敛/放开版本的车辆范围（灰度发布时收敛为显式车辆集合，全量推广时放开）。 */
    public int updateVehicleScope(Long id, String vehicleScopeJson) {
        return jdbc.update("UPDATE calibration_param SET vehicle_scope_json = ? WHERE id = ?",
                vehicleScopeJson, id);
    }

    // ---------- canary_release / canary_vehicle ----------

    public void insertRelease(CanaryRelease release) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO canary_release (release_id, param_id, param_key, new_version, " +
                            "vehicles_json, status, created_by, created_at, finished_by, finished_at, remark) " +
                            "VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            int i = 1;
            ps.setString(i++, release.getReleaseId());
            ps.setLong(i++, release.getParamId());
            ps.setString(i++, release.getParamKey());
            ps.setString(i++, release.getNewVersion());
            ps.setString(i++, release.getVehiclesJson());
            ps.setString(i++, release.getStatus().name());
            ps.setString(i++, release.getCreatedBy());
            setTimestamp(ps, i++, release.getCreatedAt());
            ps.setString(i++, release.getFinishedBy());
            setTimestamp(ps, i++, release.getFinishedAt());
            ps.setString(i, release.getRemark());
            return ps;
        }, keyHolder);
        release.setId(keyHolder.getKeyAs(Long.class));
    }

    public CanaryRelease findReleaseByReleaseId(String releaseId) {
        try {
            return jdbc.queryForObject("SELECT * FROM canary_release WHERE release_id = ?",
                    RELEASE_MAPPER, releaseId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 锁定发布单行（SELECT ... FOR UPDATE）。 */
    public CanaryRelease lockRelease(String releaseId) {
        try {
            return jdbc.queryForObject("SELECT * FROM canary_release WHERE release_id = ? FOR UPDATE",
                    RELEASE_MAPPER, releaseId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    public List<CanaryRelease> listActiveReleasesByParamKey(String paramKey) {
        return jdbc.query(
                "SELECT * FROM canary_release WHERE param_key = ? AND status = 'ACTIVE' ORDER BY id",
                RELEASE_MAPPER, paramKey);
    }

    public void insertCanaryVehicles(List<CanaryVehicle> vehicles) {
        jdbc.batchUpdate(
                "INSERT INTO canary_vehicle (release_id, vehicle_id, previous_param_id, previous_version) " +
                        "VALUES (?,?,?,?)",
                vehicles, vehicles.size(), (ps, v) -> {
                    ps.setLong(1, v.getReleaseId());
                    ps.setString(2, v.getVehicleId());
                    if (v.getPreviousParamId() == null) {
                        ps.setNull(3, java.sql.Types.BIGINT);
                    } else {
                        ps.setLong(3, v.getPreviousParamId());
                    }
                    ps.setString(4, v.getPreviousVersion());
                });
    }

    public List<CanaryVehicle> listCanaryVehicles(Long releaseId) {
        return jdbc.query(
                "SELECT * FROM canary_vehicle WHERE release_id = ? ORDER BY id",
                VEHICLE_MAPPER, releaseId);
    }

    public int updateReleaseStatus(Long id, ReleaseStatus status, String finishedBy, Instant finishedAt) {
        return jdbc.update(
                "UPDATE canary_release SET status = ?, finished_by = ?, finished_at = ? WHERE id = ?",
                status.name(), finishedBy, finishedAt == null ? null : Timestamp.from(finishedAt), id);
    }

    // ---------- resolution_record ----------

    public void insertResolution(ResolutionRecord record) {
        jdbc.update("INSERT INTO resolution_record (resolution_id, param_key, vehicle_id, sensor_id, " +
                        "map_version, resolve_time, resolved_param_id, resolved_version, content_digest, content_json) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?)",
                record.getResolutionId(),
                record.getParamKey(),
                record.getVehicleId(),
                record.getSensorId(),
                record.getMapVersion(),
                Timestamp.from(record.getResolveTime()),
                record.getResolvedParamId(),
                record.getResolvedVersion(),
                record.getContentDigest(),
                record.getContentJson());
    }

    public ResolutionRecord findResolution(String resolutionId) {
        try {
            return jdbc.queryForObject("SELECT * FROM resolution_record WHERE resolution_id = ?",
                    RESOLUTION_MAPPER, resolutionId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    // ---------- param_status_log ----------

    public void insertStatusLog(Long paramId, String fromStatus, String toStatus,
                                String operator, Instant operatedAt,
                                String releaseRef, String remark) {
        jdbc.update("INSERT INTO param_status_log (param_id, from_status, to_status, operator, " +
                        "operated_at, release_ref, remark) VALUES (?,?,?,?,?,?,?)",
                paramId, fromStatus, toStatus, operator, Timestamp.from(operatedAt), releaseRef, remark);
    }

    private static void setTimestamp(PreparedStatement ps, int index, Instant value) throws java.sql.SQLException {
        if (value == null) {
            ps.setTimestamp(index, null);
        } else {
            ps.setTimestamp(index, Timestamp.from(value));
        }
    }
}
