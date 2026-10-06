package com.example.starter.weight.repo;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.weight.repo.po.WeighDeviceRow;

/**
 * 非现场称重设备状态记录仓储：设备状态与校准有效期查询。
 */
@Repository
public class WeighDeviceRepository {

    private static final RowMapper<WeighDeviceRow> MAPPER = (rs, n) -> new WeighDeviceRow(
            rs.getString("device_code"),
            rs.getString("lane_code"),
            rs.getString("station_code"),
            rs.getString("status"),
            rs.getTimestamp("calibration_valid_from").toInstant(),
            rs.getTimestamp("calibration_valid_until").toInstant());

    private final JdbcTemplate jdbc;

    public WeighDeviceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按设备编码查询设备状态记录；不存在返回 null。
     */
    public WeighDeviceRow findByDeviceCode(String deviceCode) {
        try {
            return jdbc.queryForObject("SELECT * FROM weigh_device WHERE device_code = ?", MAPPER, deviceCode);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }
}
