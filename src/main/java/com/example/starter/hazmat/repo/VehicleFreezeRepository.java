package com.example.starter.hazmat.repo;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 车辆冻结记录仓储；vehicle_code 为主键，一车至多一条冻结记录。
 */
@Repository
public class VehicleFreezeRepository {

    private final JdbcTemplate jdbc;

    public VehicleFreezeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 写入冻结记录；已冻结时返回 false（幂等）。
     */
    public boolean freeze(String vehicleCode, long waybillId, String reason, LocalDateTime now) {
        try {
            jdbc.update(
                    "INSERT INTO vehicle_freeze (vehicle_code, waybill_id, reason, frozen_at)"
                            + " VALUES (?, ?, ?, ?)",
                    vehicleCode, waybillId, reason, Timestamp.valueOf(now));
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    public boolean isFrozen(String vehicleCode) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM vehicle_freeze WHERE vehicle_code = ?",
                Long.class, vehicleCode);
        return count != null && count > 0;
    }

    public Optional<LocalDateTime> frozenAt(String vehicleCode) {
        return jdbc.query(
                "SELECT frozen_at FROM vehicle_freeze WHERE vehicle_code = ?",
                (rs, i) -> rs.getTimestamp("frozen_at").toLocalDateTime(), vehicleCode)
                .stream().findFirst();
    }
}
