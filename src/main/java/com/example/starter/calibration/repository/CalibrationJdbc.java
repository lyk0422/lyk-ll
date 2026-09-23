package com.example.starter.calibration.repository;

import com.example.starter.calibration.domain.CalibrationStatus;
import com.example.starter.calibration.domain.CalibrationVersion;
import com.example.starter.calibration.domain.ParamKind;
import com.example.starter.calibration.domain.SensorType;
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
import java.util.Optional;

/**
 * 标定版本仓储（H2 MODE=MySQL，参数化 SQL）。
 */
@Repository
public class CalibrationJdbc {

    private static final String COLUMNS = """
            id, param_key, sensor_type, kind, version, content_sha256, content_json, status,
            effective_from, effective_to,
            vehicle_from, vehicle_to, sensor_from, sensor_to, map_from, map_to,
            created_by, created_at, approved_by, approved_at,
            activated_by, activated_at, rolled_back_by, rolled_back_at
            """;

    private final JdbcTemplate jdbc;

    public CalibrationJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 新增版本，回填自增主键。 */
    public long insert(CalibrationVersion v) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO calibration_version (
                        param_key, sensor_type, kind, version, content_sha256, content_json, status,
                        effective_from, effective_to,
                        vehicle_from, vehicle_to, sensor_from, sensor_to, map_from, map_to,
                        created_by, created_at, approved_by, approved_at,
                        activated_by, activated_at, rolled_back_by, rolled_back_at
                    ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, Statement.RETURN_GENERATED_KEYS);
            int i = 1;
            ps.setString(i++, v.getParamKey());
            ps.setString(i++, v.getSensorType().name());
            ps.setString(i++, v.getKind().name());
            ps.setString(i++, v.getVersion());
            ps.setString(i++, v.getContentSha256());
            ps.setString(i++, v.getContentJson());
            ps.setString(i++, v.getStatus().name());
            setTimestamp(ps, i++, v.getEffectiveFrom());
            setTimestamp(ps, i++, v.getEffectiveTo());
            ps.setString(i++, v.getVehicleFrom());
            ps.setString(i++, v.getVehicleTo());
            ps.setString(i++, v.getSensorFrom());
            ps.setString(i++, v.getSensorTo());
            ps.setString(i++, v.getMapFrom());
            ps.setString(i++, v.getMapTo());
            ps.setString(i++, v.getCreatedBy());
            setTimestamp(ps, i++, v.getCreatedAt());
            ps.setString(i++, v.getApprovedBy());
            setTimestamp(ps, i++, v.getApprovedAt());
            ps.setString(i++, v.getActivatedBy());
            setTimestamp(ps, i++, v.getActivatedAt());
            ps.setString(i++, v.getRolledBackBy());
            setTimestamp(ps, i, v.getRolledBackAt());
            return ps;
        }, keyHolder);
        long id = keyHolder.getKey().longValue();
        v.setId(id);
        return id;
    }

    /** 按主键查询。 */
    public Optional<CalibrationVersion> findById(long id) {
        List<CalibrationVersion> list = jdbc.query(
                "SELECT " + COLUMNS + " FROM calibration_version WHERE id = ?", rowMapper(), id);
        return list.stream().findFirst();
    }

    /** 按参数键与版本号查询（唯一约束）。 */
    public Optional<CalibrationVersion> findByKeyAndVersion(String paramKey, String version) {
        List<CalibrationVersion> list = jdbc.query(
                "SELECT " + COLUMNS + " FROM calibration_version WHERE param_key = ? AND version = ?",
                rowMapper(), paramKey, version);
        return list.stream().findFirst();
    }

    /** 查询某参数键下指定状态集合的全部版本。 */
    public List<CalibrationVersion> findByParamKeyAndStatuses(String paramKey, List<CalibrationStatus> statuses) {
        if (statuses.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", statuses.stream().map(s -> "?").toList());
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM calibration_version WHERE param_key = ? AND status IN ("
                        + placeholders + ") ORDER BY id",
                rowMapper(), prepend(paramKey, statuses));
    }

    /** 查询某参数键下可作基线（ACTIVE）的版本。 */
    public List<CalibrationVersion> findBaselineCandidates(String paramKey) {
        return jdbc.query(
                "SELECT " + COLUMNS
                        + " FROM calibration_version WHERE param_key = ? AND status = 'ACTIVE' ORDER BY id",
                rowMapper(), paramKey);
    }

    /** 更新状态及审批/生效/回滚操作者与时间。 */
    public int updateStatus(CalibrationVersion v) {
        return jdbc.update("""
                UPDATE calibration_version SET
                    status = ?,
                    approved_by = ?, approved_at = ?,
                    activated_by = ?, activated_at = ?,
                    rolled_back_by = ?, rolled_back_at = ?
                WHERE id = ?
                """,
                v.getStatus().name(),
                v.getApprovedBy(), ts(v.getApprovedAt()),
                v.getActivatedBy(), ts(v.getActivatedAt()),
                v.getRolledBackBy(), ts(v.getRolledBackAt()),
                v.getId());
    }

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private Object[] prepend(String first, List<CalibrationStatus> rest) {
        Object[] params = new Object[rest.size() + 1];
        params[0] = first;
        for (int i = 0; i < rest.size(); i++) {
            params[i + 1] = rest.get(i).name();
        }
        return params;
    }

    private static void setTimestamp(PreparedStatement ps, int index, Instant instant) throws java.sql.SQLException {
        if (instant == null) {
            ps.setTimestamp(index, null);
        } else {
            ps.setTimestamp(index, Timestamp.from(instant));
        }
    }

    static RowMapper<CalibrationVersion> rowMapper() {
        return (rs, n) -> {
            CalibrationVersion v = new CalibrationVersion();
            v.setId(rs.getLong("id"));
            v.setParamKey(rs.getString("param_key"));
            v.setSensorType(SensorType.valueOf(rs.getString("sensor_type")));
            v.setKind(ParamKind.valueOf(rs.getString("kind")));
            v.setVersion(rs.getString("version"));
            v.setContentSha256(rs.getString("content_sha256"));
            v.setContentJson(rs.getString("content_json"));
            v.setStatus(CalibrationStatus.valueOf(rs.getString("status")));
            Timestamp from = rs.getTimestamp("effective_from");
            v.setEffectiveFrom(from == null ? null : from.toInstant());
            Timestamp to = rs.getTimestamp("effective_to");
            v.setEffectiveTo(to == null ? null : to.toInstant());
            v.setVehicleFrom(rs.getString("vehicle_from"));
            v.setVehicleTo(rs.getString("vehicle_to"));
            v.setSensorFrom(rs.getString("sensor_from"));
            v.setSensorTo(rs.getString("sensor_to"));
            v.setMapFrom(rs.getString("map_from"));
            v.setMapTo(rs.getString("map_to"));
            v.setCreatedBy(rs.getString("created_by"));
            Timestamp createdAt = rs.getTimestamp("created_at");
            v.setCreatedAt(createdAt == null ? null : createdAt.toInstant());
            v.setApprovedBy(rs.getString("approved_by"));
            Timestamp approvedAt = rs.getTimestamp("approved_at");
            v.setApprovedAt(approvedAt == null ? null : approvedAt.toInstant());
            v.setActivatedBy(rs.getString("activated_by"));
            Timestamp activatedAt = rs.getTimestamp("activated_at");
            v.setActivatedAt(activatedAt == null ? null : activatedAt.toInstant());
            v.setRolledBackBy(rs.getString("rolled_back_by"));
            Timestamp rolledBackAt = rs.getTimestamp("rolled_back_at");
            v.setRolledBackAt(rolledBackAt == null ? null : rolledBackAt.toInstant());
            return v;
        };
    }
}
