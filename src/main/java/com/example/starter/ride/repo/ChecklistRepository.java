package com.example.starter.ride.repo;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 合成日检清单仓储。
 */
@Repository
public class ChecklistRepository {

    private final JdbcTemplate jdbcTemplate;

    public ChecklistRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 返回 itemCode -> itemName 的合成清单。 */
    public Map<String, String> findItemsByRide(String rideId) {
        Map<String, String> items = new LinkedHashMap<>();
        org.springframework.jdbc.core.RowCallbackHandler handler =
                rs -> items.put(rs.getString("item_code"), rs.getString("item_name"));
        jdbcTemplate.query(
                "SELECT item_code, item_name FROM checklist_item WHERE ride_id = ? ORDER BY id",
                handler,
                rideId);
        return items;
    }

    public void insertItem(String rideId, String itemCode, String itemName) {
        jdbcTemplate.update(
                "INSERT INTO checklist_item (ride_id, item_code, item_name) VALUES (?, ?, ?)",
                rideId, itemCode, itemName);
    }

    public List<String> findRideIds() {
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT ride_id FROM checklist_item ORDER BY ride_id", String.class);
    }
}
