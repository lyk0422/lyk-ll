package com.example.starter.weight.repo;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.weight.repo.po.DeviceStatusRow;

/**
 * 非现场称重设备状态记录仓储：设备状态与校准有效期查询。
 */
@Repository
public class DeviceStatusRepository {

    private static final RowMapper<DeviceStatusRow> MAPPER = (rs, n) -> new DeviceStatusRow(
            rs.getString("device_id"),
            rs.getString("lane_no"),
            rs.getString("status"),
            rs.getTimestamp("calibrated_from").toInstant(),
            rs.getTimestamp("calibrated_until").toInstant(),
            rs.getTimestamp("recorded_at").toInstant());

    private final JdbcTemplate jdbc;

    public DeviceStatusRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按设备编号查询设备状态记录（含校准有效期）；不存在返回 null。
     */
    public DeviceStatusRow findByDeviceId(String deviceId) {
        try {
            return jdbc.queryForObject("SELECT * FROM offsite_device WHERE device_id = ?", MAPPER, deviceId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }
}
