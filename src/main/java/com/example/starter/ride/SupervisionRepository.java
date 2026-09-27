package com.example.starter.ride;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.ride.dto.RideDtos.CheckItem;

/**
 * 大修登记与大修后监督检验仓储：监督检验绑定确认时的当前大修版本，
 * 登记大修递增大修版本后，旧版本监督检验自然失效（不修改历史行）。
 */
@Repository
public class SupervisionRepository {

    private final JdbcTemplate jdbc;

    public SupervisionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 登记一次大修，返回大修记录ID。 */
    public long insertOverhaul(long rideId, String requestId, long overhaulVersion,
            Instant registeredAt, String note) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO overhaul_record"
                            + " (ride_id, request_id, overhaul_version, registered_at, note)"
                            + " VALUES (?, ?, ?, ?, ?)",
                    new String[]{"id"});
            ps.setLong(1, rideId);
            ps.setString(2, requestId);
            ps.setLong(3, overhaulVersion);
            ps.setTimestamp(4, Timestamp.from(registeredAt));
            ps.setString(5, note);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /** 确认一次监督检验（两名不同检验人共同确认全清单），返回监督检验记录ID。 */
    public long insertInspection(long rideId, String requestId, long overhaulVersion,
            String inspectorOne, String inspectorTwo, boolean allPassed,
            Instant confirmedAt, List<CheckItem> items) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO supervision_inspection"
                            + " (ride_id, request_id, overhaul_version, inspector_one,"
                            + " inspector_two, all_passed, confirmed_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                    new String[]{"id"});
            ps.setLong(1, rideId);
            ps.setString(2, requestId);
            ps.setLong(3, overhaulVersion);
            ps.setString(4, inspectorOne);
            ps.setString(5, inspectorTwo);
            ps.setBoolean(6, allPassed);
            ps.setTimestamp(7, Timestamp.from(confirmedAt));
            return ps;
        }, keyHolder);
        long id = keyHolder.getKey().longValue();
        for (CheckItem item : items) {
            jdbc.update("INSERT INTO supervision_inspection_item"
                            + " (inspection_id, item_name, passed) VALUES (?, ?, ?)",
                    id, item.itemName(), item.passed());
        }
        return id;
    }

    /** 是否存在绑定指定大修版本且全清单合格的监督检验。 */
    public boolean existsConfirmedInspection(long rideId, long overhaulVersion) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM supervision_inspection"
                        + " WHERE ride_id = ? AND overhaul_version = ? AND all_passed = TRUE",
                Integer.class, rideId, overhaulVersion);
        return count != null && count > 0;
    }

    public List<Map<String, Object>> listOverhauls(long rideId) {
        return jdbc.queryForList(
                "SELECT id, request_id, overhaul_version, registered_at, note, created_at"
                        + " FROM overhaul_record WHERE ride_id = ? ORDER BY id", rideId);
    }

    public List<Map<String, Object>> listInspections(long rideId) {
        return jdbc.queryForList(
                "SELECT id, request_id, overhaul_version, inspector_one, inspector_two,"
                        + " all_passed, confirmed_at, created_at"
                        + " FROM supervision_inspection WHERE ride_id = ? ORDER BY id", rideId);
    }

    public List<Map<String, Object>> listInspectionItems(long inspectionId) {
        return jdbc.queryForList(
                "SELECT item_name, passed FROM supervision_inspection_item"
                        + " WHERE inspection_id = ? ORDER BY id",
                inspectionId);
    }
}
