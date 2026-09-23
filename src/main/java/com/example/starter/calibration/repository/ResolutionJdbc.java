package com.example.starter.calibration.repository;

import com.example.starter.calibration.domain.ResolutionRecord;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.sql.Types;
import java.util.Optional;

/**
 * 解析历史记录仓储。记录只追加，不提供更新/删除，保证 resolutionId 历史引用不可变。
 */
@Repository
public class ResolutionJdbc {

    private final JdbcTemplate jdbc;

    public ResolutionJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(ResolutionRecord record) {
        jdbc.update(connection -> {
            var ps = connection.prepareStatement("""
                    INSERT INTO resolution_record (
                        resolution_id, param_key, vehicle_id, sensor_id, map_version,
                        resolved_at, selected_version_id, canary_release_pk
                    ) VALUES (?,?,?,?,?,?,?,?)
                    """);
            ps.setString(1, record.getResolutionId());
            ps.setString(2, record.getParamKey());
            ps.setString(3, record.getVehicleId());
            ps.setString(4, record.getSensorId());
            ps.setString(5, record.getMapVersion());
            ps.setTimestamp(6, Timestamp.from(record.getResolvedAt()));
            ps.setLong(7, record.getSelectedVersionId());
            if (record.getCanaryReleaseId() == null) {
                ps.setNull(8, Types.BIGINT);
            } else {
                ps.setLong(8, record.getCanaryReleaseId());
            }
            return ps;
        });
    }

    public Optional<ResolutionRecord> findByResolutionId(String resolutionId) {
        try {
            return Optional.of(jdbc.queryForObject(
                    """
                    SELECT id, resolution_id, param_key, vehicle_id, sensor_id, map_version,
                           resolved_at, selected_version_id, canary_release_pk
                    FROM resolution_record WHERE resolution_id = ?
                    """,
                    rowMapper(), resolutionId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    static RowMapper<ResolutionRecord> rowMapper() {
        return (rs, n) -> {
            ResolutionRecord r = new ResolutionRecord();
            r.setId(rs.getLong("id"));
            r.setResolutionId(rs.getString("resolution_id"));
            r.setParamKey(rs.getString("param_key"));
            r.setVehicleId(rs.getString("vehicle_id"));
            r.setSensorId(rs.getString("sensor_id"));
            r.setMapVersion(rs.getString("map_version"));
            r.setResolvedAt(rs.getTimestamp("resolved_at").toInstant());
            r.setSelectedVersionId(rs.getLong("selected_version_id"));
            long canaryPk = rs.getLong("canary_release_pk");
            r.setCanaryReleaseId(rs.wasNull() ? null : canaryPk);
            return r;
        };
    }
}
