package com.example.starter.hazmat.repo;

import com.example.starter.hazmat.domain.AlertType;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 告警事件仓储，告警一经写入不可变，供路线判定与冻结记录复算。
 */
@Repository
public class AlertRepository {

    private final JdbcTemplate jdbc;

    public AlertRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Long waybillId, String vehicleCode, AlertType type, String message,
                       LocalDateTime now) {
        jdbc.update(
                "INSERT INTO alert (waybill_id, vehicle_code, type, message, created_at)"
                        + " VALUES (?, ?, ?, ?, ?)",
                waybillId, vehicleCode, type.name(), message, Timestamp.valueOf(now));
    }

    public long countByWaybillAndType(long waybillId, AlertType type) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM alert WHERE waybill_id = ? AND type = ?",
                Long.class, waybillId, type.name());
        return count == null ? 0 : count;
    }

    public List<String> findMessagesByVehicle(String vehicleCode) {
        return jdbc.query(
                "SELECT message FROM alert WHERE vehicle_code = ? ORDER BY id",
                (rs, i) -> rs.getString("message"), vehicleCode);
    }
}
