package com.example.starter.ride.repo;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.example.starter.ride.dto.DailyCheckRequest;

/**
 * 日检记录仓储。
 */
@Repository
public class DailyCheckRepository {

    private final JdbcTemplate jdbcTemplate;

    public DailyCheckRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 写入日检记录与逐项结果，使用 GeneratedKeyHolder 取回记录 ID。
     */
    public long insert(String requestId, String rideId, LocalDate bizDate, boolean qualified,
                       List<String> failedItems, List<DailyCheckRequest.ItemResult> itemResults,
                       Instant createdAt) {
        String failedText = failedItems == null ? "" : String.join(",", failedItems);
        org.springframework.jdbc.core.PreparedStatementCreator psc = con -> {
            java.sql.PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO daily_check_record (request_id, ride_id, biz_date, qualified, "
                            + "failed_items, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                    new String[] {"id"});
            ps.setString(1, requestId);
            ps.setString(2, rideId);
            ps.setObject(3, bizDate);
            ps.setBoolean(4, qualified);
            ps.setString(5, failedText);
            ps.setTimestamp(6, Timestamp.from(createdAt));
            return ps;
        };
        org.springframework.jdbc.support.GeneratedKeyHolder keyHolder =
                new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbcTemplate.update(psc, keyHolder);
        Number key = keyHolder.getKey();
        long recordId = key == null ? 0L : key.longValue();

        if (itemResults != null) {
            jdbcTemplate.batchUpdate(
                    "INSERT INTO daily_check_item_result (record_id, item_code, qualified, remark) "
                            + "VALUES (?, ?, ?, ?)",
                    itemResults, itemResults.size(), (ps, item) -> {
                        ps.setLong(1, recordId);
                        ps.setString(2, item.itemCode());
                        ps.setBoolean(3, Boolean.TRUE.equals(item.qualified()));
                        ps.setString(4, item.remark() == null ? "" : item.remark());
                    });
        }
        return recordId;
    }

    public long countPassed(String rideId, LocalDate bizDate) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM daily_check_record WHERE ride_id = ? AND biz_date = ? AND qualified = TRUE",
                Long.class, rideId, bizDate);
        return count == null ? 0L : count;
    }

    /** 读取最近一次日检的逐项结果（供复算/复检参考）。 */
    public List<ItemResultRow> findLatestItemResults(String rideId, LocalDate bizDate) {
        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT id FROM daily_check_record WHERE ride_id = ? AND biz_date = ? ORDER BY id DESC LIMIT 1",
                Long.class, rideId, bizDate);
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbcTemplate.query(
                "SELECT item_code, qualified, remark FROM daily_check_item_result WHERE record_id = ? ORDER BY id",
                (rs, n) -> new ItemResultRow(rs.getString("item_code"), rs.getBoolean("qualified"),
                        rs.getString("remark")),
                ids.get(0));
    }

    public record ItemResultRow(String itemCode, boolean qualified, String remark) {
    }
}
