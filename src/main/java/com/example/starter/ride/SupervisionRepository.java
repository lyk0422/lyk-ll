package com.example.starter.ride;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.example.starter.ride.dto.RideDtos.CheckItem;

/**
 * 监督检验与大修记录仓储。
 * 监督检验绑定检验周期版本（大修版本），两名不同检验人共同确认全清单合格；
 * 大修记录为登记事件的不可变流水，可复算。
 */
@Repository
public class SupervisionRepository {

    private final JdbcTemplate jdbc;

    public SupervisionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 登记一次大修，记录递增后的大修版本，返回记录ID。 */
    public long insertOverhaul(long rideId, String requestId, long overhaulVersion) {
        jdbc.update("INSERT INTO overhaul_record (ride_id, request_id, overhaul_version)"
                + " VALUES (?, ?, ?)", rideId, requestId, overhaulVersion);
        Long id = jdbc.queryForObject(
                "SELECT MAX(id) FROM overhaul_record WHERE ride_id = ?", Long.class, rideId);
        return id;
    }

    /** 确认一次监督检验：绑定大修版本、两名检验人与全清单明细，返回检验ID。 */
    public long insertInspection(long rideId, String requestId, long overhaulVersion,
            String inspectorOne, String inspectorTwo, List<CheckItem> items) {
        boolean allPassed = items.stream().allMatch(CheckItem::passed);
        jdbc.update("INSERT INTO supervision_inspection"
                        + " (ride_id, request_id, overhaul_version, inspector_one, inspector_two, all_passed)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                rideId, requestId, overhaulVersion, inspectorOne, inspectorTwo, allPassed);
        Long inspectionId = jdbc.queryForObject(
                "SELECT MAX(id) FROM supervision_inspection WHERE ride_id = ?",
                Long.class, rideId);
        for (CheckItem item : items) {
            jdbc.update("INSERT INTO supervision_inspection_item"
                            + " (inspection_id, item_name, passed) VALUES (?, ?, ?)",
                    inspectionId, item.itemName(), item.passed());
        }
        return inspectionId;
    }

    /** 当前大修版本是否已有全清单合格的监督检验（开放资格证据）。 */
    public boolean existsConfirmed(long rideId, long overhaulVersion) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM supervision_inspection"
                        + " WHERE ride_id = ? AND overhaul_version = ? AND all_passed = TRUE",
                Integer.class, rideId, overhaulVersion);
        return count != null && count > 0;
    }

    /** 设备全部监督检验记录（含明细），按确认顺序排列，可复算。 */
    public List<Map<String, Object>> listInspections(long rideId) {
        List<Map<String, Object>> inspections = jdbc.queryForList(
                "SELECT id, request_id, overhaul_version, inspector_one, inspector_two,"
                        + " all_passed, version, created_at"
                        + " FROM supervision_inspection WHERE ride_id = ? ORDER BY id", rideId);
        for (Map<String, Object> inspection : inspections) {
            long inspectionId = ((Number) inspection.get("id")).longValue();
            inspection.put("items", jdbc.queryForList(
                    "SELECT item_name, passed FROM supervision_inspection_item"
                            + " WHERE inspection_id = ? ORDER BY id", inspectionId));
        }
        return inspections;
    }

    /** 设备全部大修登记记录，按登记顺序排列，可复算。 */
    public List<Map<String, Object>> listOverhauls(long rideId) {
        return jdbc.queryForList(
                "SELECT id, request_id, overhaul_version, created_at"
                        + " FROM overhaul_record WHERE ride_id = ? ORDER BY id", rideId);
    }
}
