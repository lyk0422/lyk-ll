package com.example.starter.ventilation.repo;

import com.example.starter.ventilation.domain.DeviceRow;
import com.example.starter.ventilation.domain.FireZoneRow;
import com.example.starter.ventilation.domain.ZoneDamperRefRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 隧道分区/设备合成主数据查询。
 */
@Repository
public class MasterDataRepository {

    private static final RowMapper<FireZoneRow> ZONE_MAPPER = (rs, n) ->
            new FireZoneRow(rs.getLong("id"), rs.getString("zone_code"), rs.getString("zone_name"));

    private static final RowMapper<DeviceRow> DEVICE_MAPPER = (rs, n) ->
            new DeviceRow(
                    rs.getLong("id"),
                    rs.getString("device_code"),
                    rs.getString("device_name"),
                    com.example.starter.ventilation.DeviceType.valueOf(rs.getString("device_type")),
                    rs.getString("home_zone"),
                    rs.getInt("shared_flag") == 1);

    private static final RowMapper<ZoneDamperRefRow> REF_MAPPER = (rs, n) ->
            new ZoneDamperRefRow(rs.getLong("id"), rs.getString("zone_code"),
                    rs.getString("damper_code"), rs.getString("bias"));

    private final JdbcTemplate jdbc;

    public MasterDataRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 按编码查分区，不存在返回 null。 */
    public FireZoneRow findZone(String zoneCode) {
        List<FireZoneRow> list = jdbc.query(
                "SELECT id, zone_code, zone_name FROM fire_zone WHERE zone_code = ?",
                ZONE_MAPPER, zoneCode);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 查询分区全部风机。 */
    public List<DeviceRow> findFans(String zoneCode) {
        return jdbc.query(
                "SELECT id, device_code, device_name, device_type, home_zone, shared_flag "
                        + "FROM device WHERE home_zone = ? AND device_type = 'FAN'",
                DEVICE_MAPPER, zoneCode);
    }

    /** 按编码查设备，不存在返回 null。 */
    public DeviceRow findDevice(String deviceCode) {
        List<DeviceRow> list = jdbc.query(
                "SELECT id, device_code, device_name, device_type, home_zone, shared_flag "
                        + "FROM device WHERE device_code = ?",
                DEVICE_MAPPER, deviceCode);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 查询分区风阀编组（含共享风阀）及方向偏置。 */
    public List<ZoneDamperRefRow> findDamperRefs(String zoneCode) {
        return jdbc.query(
                "SELECT id, zone_code, damper_code, bias FROM zone_damper_ref WHERE zone_code = ?",
                REF_MAPPER, zoneCode);
    }
}
