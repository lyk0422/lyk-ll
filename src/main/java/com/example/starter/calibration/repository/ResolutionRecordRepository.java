package com.example.starter.calibration.repository;

import java.sql.Timestamp;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.domain.ResolutionRecord;

/**
 * 解析历史数据访问：仅追加与按 resolutionId 查询，不提供更新接口，保证历史不可改写。
 */
@Repository
public class ResolutionRecordRepository {

    private final JdbcTemplate jdbc;

    public ResolutionRecordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(ResolutionRecord record) {
        jdbc.update("""
                INSERT INTO resolution_record (
                    resolution_id, calibration_key, sensor_id, vehicle_id, map_version,
                    resolved_time, resolved_at, selected_version_id, selected_version,
                    content_digest, payload, canary_release_id
                ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                record.getResolutionId(), record.getCalibrationKey(), record.getSensorId(),
                record.getVehicleId(), record.getMapVersion(),
                Timestamp.from(record.getResolvedTime()), Timestamp.from(record.getResolvedAt()),
                record.getSelectedVersionId(), record.getSelectedVersion(),
                record.getContentDigest(), record.getPayload(), record.getCanaryReleaseId());
    }

    public ResolutionRecord findByResolutionId(String resolutionId) {
        List<ResolutionRecord> list = jdbc.query(
                "SELECT * FROM resolution_record WHERE resolution_id = ?",
                (rs, rowNum) -> {
                    ResolutionRecord r = new ResolutionRecord();
                    r.setResolutionId(rs.getString("resolution_id"));
                    r.setCalibrationKey(rs.getString("calibration_key"));
                    r.setSensorId(rs.getString("sensor_id"));
                    r.setVehicleId(rs.getString("vehicle_id"));
                    int mapVersion = rs.getInt("map_version");
                    r.setMapVersion(rs.wasNull() ? null : mapVersion);
                    r.setResolvedTime(rs.getTimestamp("resolved_time").toInstant());
                    r.setResolvedAt(rs.getTimestamp("resolved_at").toInstant());
                    r.setSelectedVersionId(rs.getLong("selected_version_id"));
                    r.setSelectedVersion(rs.getString("selected_version"));
                    r.setContentDigest(rs.getString("content_digest"));
                    r.setPayload(rs.getString("payload"));
                    long releaseId = rs.getLong("canary_release_id");
                    r.setCanaryReleaseId(rs.wasNull() ? null : releaseId);
                    return r;
                }, resolutionId);
        return list.isEmpty() ? null : list.get(0);
    }
}
