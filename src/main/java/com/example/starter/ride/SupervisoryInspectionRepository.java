package com.example.starter.ride;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.example.starter.ride.dto.RideDtos.CheckItem;

/**
 * 大修监督检验仓储：记录绑定大修版本，新登记大修后旧记录保留但不再匹配当前版本。
 */
@Repository
public class SupervisoryInspectionRepository {

    private final JdbcTemplate jdbc;

    public SupervisoryInspectionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 插入一次监督检验及清单明细，返回记录ID。 */
    public long insert(long rideId, String requestId, int overhaulVersion,
            String inspectorA, String inspectorB, boolean allPassed,
            List<CheckItem> items) {
        jdbc.update("INSERT INTO supervisory_inspection (ride_id, request_id, overhaul_version,"
                        + " inspector_a, inspector_b, all_passed) VALUES (?, ?, ?, ?, ?, ?)",
                rideId, requestId, overhaulVersion, inspectorA, inspectorB, allPassed);
        Long inspectionId = jdbc.queryForObject(
                "SELECT MAX(id) FROM supervisory_inspection WHERE ride_id = ?",
                Long.class, rideId);
        for (CheckItem item : items) {
            jdbc.update("INSERT INTO supervisory_check_item (inspection_id, item_name, passed)"
                    + " VALUES (?, ?, ?)", inspectionId, item.itemName(), item.passed());
        }
        return inspectionId;
    }

    public List<Map<String, Object>> listByRide(long rideId) {
        return jdbc.queryForList(
                "SELECT id, request_id, overhaul_version, inspector_a, inspector_b,"
                        + " all_passed, created_at FROM supervisory_inspection"
                        + " WHERE ride_id = ? ORDER BY id", rideId);
    }

    public List<Map<String, Object>> listItems(long inspectionId) {
        return jdbc.queryForList(
                "SELECT item_name, passed FROM supervisory_check_item"
                        + " WHERE inspection_id = ? ORDER BY id", inspectionId);
    }
}
