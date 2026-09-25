package com.example.starter.ventilation.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 设备到位反馈流水仓储。
 */
@Repository
public class DeviceFeedbackRepository {

    private final JdbcTemplate jdbc;

    public DeviceFeedbackRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 记录一条反馈（含归档后迟到反馈）。 */
    public void insert(String requestId, Long sessionId, Long commandId, String zoneCode,
                       String deviceCode, String result, boolean late, long receivedAt) {
        jdbc.update(
                "INSERT INTO device_feedback (request_id, session_id, command_id, zone_code, "
                        + "device_code, result, late_flag, received_at) VALUES (?,?,?,?,?,?,?,?)",
                requestId, sessionId, commandId, zoneCode, deviceCode, result,
                late ? 1 : 0, receivedAt);
    }
}
