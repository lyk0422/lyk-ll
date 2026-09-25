package com.example.starter.irrigation.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 实供计量与迟到事件数据访问。
 */
@Repository
public class MeteringRepository {

    private final JdbcTemplate jdbc;

    public MeteringRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Long> findAmount(long outletId, int roundNo) {
        List<Long> rows = jdbc.query("SELECT amount_m3 FROM metering WHERE outlet_id = ? AND round_no = ?",
                (rs, n) -> rs.getLong(1), outletId, roundNo);
        return rows.stream().findFirst();
    }

    public int updateAmount(long outletId, int roundNo, long amountM3) {
        return jdbc.update("UPDATE metering SET amount_m3 = ?, updated_at = CURRENT_TIMESTAMP"
                + " WHERE outlet_id = ? AND round_no = ?", amountM3, outletId, roundNo);
    }

    public void insert(long groupId, long outletId, int roundNo, long amountM3) {
        jdbc.update("INSERT INTO metering (group_id, outlet_id, round_no, amount_m3) VALUES (?, ?, ?, ?)",
                groupId, outletId, roundNo, amountM3);
    }

    /**
     * 返回该轮全部已确认计量：斗口 id → 实供水量快照。
     */
    public Map<Long, Long> findByGroupAndRound(long groupId, int roundNo) {
        Map<Long, Long> result = new HashMap<>();
        jdbc.query("SELECT outlet_id, amount_m3 FROM metering WHERE group_id = ? AND round_no = ?",
                (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                        result.put(rs.getLong("outlet_id"), rs.getLong("amount_m3")),
                groupId, roundNo);
        return result;
    }

    public void insertLateEvent(long groupId, long outletId, int roundNo, long amountM3) {
        jdbc.update("INSERT INTO late_metering_event (group_id, outlet_id, round_no, amount_m3)"
                + " VALUES (?, ?, ?, ?)", groupId, outletId, roundNo, amountM3);
    }

    public int countLateEvents(long groupId, int roundNo) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM late_metering_event"
                + " WHERE group_id = ? AND round_no = ?", Integer.class, groupId, roundNo);
        return count == null ? 0 : count;
    }
}
