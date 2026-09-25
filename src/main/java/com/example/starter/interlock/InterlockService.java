package com.example.starter.interlock;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 防误闭锁判定：基于闭锁规则库与设备实时状态，对单步操作做闭锁与顺序校验。
 */
@Service
public class InterlockService {

    /** 带负荷拉合刀闸。 */
    public static final String R_LOAD_DISCONNECTOR = "R_LOAD_DISCONNECTOR";
    /** 带电挂接地线。 */
    public static final String R_LIVE_GROUND = "R_LIVE_GROUND";
    /** 带地线送电。 */
    public static final String R_GROUND_ENERGIZE = "R_GROUND_ENERGIZE";
    /** 母线停电顺序违例。 */
    public static final String R_BUS_SEQUENCE = "R_BUS_SEQUENCE";

    private final JdbcTemplate jdbc;

    public InterlockService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 闭锁命中结果。 */
    public record Hit(String ruleCode, String detail) {
    }

    /**
     * 校验一步操作是否被闭锁或顺序违例；返回命中信息，未命中为空。
     *
     * @param deviceId 操作设备
     * @param action   操作类型
     */
    public Optional<Hit> check(String deviceId, String action) {
        DeviceRow device = findDevice(deviceId);
        if (device == null) {
            return Optional.of(new Hit("R_DEVICE_UNKNOWN", "设备不存在: " + deviceId));
        }
        return switch (action) {
            case "OPEN_DISCONNECTOR", "CLOSE_DISCONNECTOR" -> checkLoadDisconnector(device);
            case "ATTACH_GROUND" -> checkLiveGround(device);
            case "CLOSE_BREAKER" -> checkGroundEnergize(device);
            case "BUS_OUTAGE" -> checkBusSequence(device);
            default -> Optional.empty();
        };
    }

    /** 带负荷拉合刀闸：间隔内存在合位开关时闭锁。 */
    private Optional<Hit> checkLoadDisconnector(DeviceRow device) {
        Integer onBreakers = jdbc.queryForObject(
                "SELECT COUNT(*) FROM device_state "
                        + "WHERE interval_id = ? AND device_type = 'BREAKER' AND state = 'ON'",
                Integer.class, device.intervalId());
        if (onBreakers != null && onBreakers > 0) {
            return Optional.of(new Hit(R_LOAD_DISCONNECTOR,
                    "间隔 " + device.intervalId() + " 内开关合位，禁止带负荷拉合刀闸 " + device.deviceId()));
        }
        return Optional.empty();
    }

    /** 带电挂接地线：间隔内存在合位开关时闭锁。 */
    private Optional<Hit> checkLiveGround(DeviceRow device) {
        Integer onBreakers = jdbc.queryForObject(
                "SELECT COUNT(*) FROM device_state "
                        + "WHERE interval_id = ? AND device_type = 'BREAKER' AND state = 'ON'",
                Integer.class, device.intervalId());
        if (onBreakers != null && onBreakers > 0) {
            return Optional.of(new Hit(R_LIVE_GROUND,
                    "间隔 " + device.intervalId() + " 内开关合位，禁止带电挂接地线 " + device.deviceId()));
        }
        return Optional.empty();
    }

    /** 带地线送电：间隔内接地线未拆除时禁止合开关。 */
    private Optional<Hit> checkGroundEnergize(DeviceRow device) {
        Integer attached = jdbc.queryForObject(
                "SELECT COUNT(*) FROM device_state "
                        + "WHERE interval_id = ? AND device_type = 'GROUND_WIRE' AND state = 'ATTACHED'",
                Integer.class, device.intervalId());
        if (attached != null && attached > 0) {
            return Optional.of(new Hit(R_GROUND_ENERGIZE,
                    "间隔 " + device.intervalId() + " 内接地线未拆除，禁止合开关送电 " + device.deviceId()));
        }
        return Optional.empty();
    }

    /** 母线停电顺序：须先断开该母线全部出线开关，否则整步拒绝。 */
    private Optional<Hit> checkBusSequence(DeviceRow device) {
        Integer onOutgoing = jdbc.queryForObject(
                "SELECT COUNT(*) FROM device_state "
                        + "WHERE bus_id = ? AND device_type = 'BREAKER' AND state = 'ON'",
                Integer.class, device.deviceId());
        if (onOutgoing != null && onOutgoing > 0) {
            return Optional.of(new Hit(R_BUS_SEQUENCE,
                    "母线 " + device.deviceId() + " 尚有 " + onOutgoing + " 个出线开关未断开，禁止母线停电"));
        }
        return Optional.empty();
    }

    private DeviceRow findDevice(String deviceId) {
        List<DeviceRow> rows = jdbc.query(
                "SELECT device_id, interval_id, device_type, bus_id, state "
                        + "FROM device_state WHERE device_id = ?",
                (rs, rowNum) -> new DeviceRow(
                        rs.getString("device_id"), rs.getString("interval_id"),
                        rs.getString("device_type"), rs.getString("bus_id"),
                        rs.getString("state")),
                deviceId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private record DeviceRow(String deviceId, String intervalId, String deviceType,
                             String busId, String state) {
    }
}
