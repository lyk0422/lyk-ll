package com.example.starter.calibration.repository;

import com.example.starter.calibration.domain.CanaryVehicle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 灰度车辆条目仓储，并维护进行中灰度车辆占用锁（数据库级冲突检测）。
 */
@Repository
public class CanaryVehicleJdbc {

    private final JdbcTemplate jdbc;

    public CanaryVehicleJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 批量写入灰度车辆条目（外层事务保证原子性）。 */
    public void insertBatch(long releasePk, List<CanaryVehicle> vehicles) {
        jdbc.batchUpdate("""
                INSERT INTO canary_vehicle (release_id, vehicle_id, previous_version_id)
                VALUES (?, ?, ?)
                """,
                vehicles,
                vehicles.size(),
                (ps, v) -> {
                    ps.setLong(1, releasePk);
                    ps.setString(2, v.getVehicleId());
                    if (v.getPreviousVersionId() == null) {
                        ps.setNull(3, java.sql.Types.BIGINT);
                    } else {
                        ps.setLong(3, v.getPreviousVersionId());
                    }
                });
    }

    public List<CanaryVehicle> findByReleaseId(long releasePk) {
        return jdbc.query(
                "SELECT id, release_id, vehicle_id, previous_version_id FROM canary_vehicle WHERE release_id = ? ORDER BY id",
                rowMapper(), releasePk);
    }

    /**
     * 尝试占用车辆：成功返回 true；该参数键下车辆已在其他进行中灰度中时返回 false。
     * 调用方必须处于事务内，失败会触发整批回滚。
     */
    public boolean tryLock(String paramKey, String vehicleId) {
        int updated = jdbc.update(
                "INSERT INTO canary_vehicle_lock (param_key, vehicle_id) VALUES (?, ?)",
                paramKey, vehicleId);
        return updated == 1;
    }

    /** 释放参数键下一批车辆的占用锁（转正/回滚时调用）。 */
    public void releaseLocks(String paramKey, List<String> vehicleIds) {
        if (vehicleIds.isEmpty()) {
            return;
        }
        String placeholders = String.join(",", vehicleIds.stream().map(v -> "?").toList());
        Object[] params = new Object[vehicleIds.size() + 1];
        params[0] = paramKey;
        for (int i = 0; i < vehicleIds.size(); i++) {
            params[i + 1] = vehicleIds.get(i);
        }
        jdbc.update("DELETE FROM canary_vehicle_lock WHERE param_key = ? AND vehicle_id IN ("
                + placeholders + ")", params);
    }

    static RowMapper<CanaryVehicle> rowMapper() {
        return (rs, n) -> {
            CanaryVehicle v = new CanaryVehicle();
            v.setId(rs.getLong("id"));
            v.setReleaseId(rs.getLong("release_id"));
            v.setVehicleId(rs.getString("vehicle_id"));
            long previous = rs.getLong("previous_version_id");
            v.setPreviousVersionId(rs.wasNull() ? null : previous);
            return v;
        };
    }
}
