package com.example.starter.pump.repo;

import com.example.starter.pump.web.dto.ReplayResult;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 失联派单台账仓储：一个站点在一个预降事件内仅允许一张失联工单（唯一索引保证幂等）。
 */
@Repository
public class MissContactOrderRepository {

    private final JdbcTemplate jdbc;

    public MissContactOrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 派单：恰满30分钟无数据时调用。
     *
     * @return 工单ID；该站点该预降事件已有工单时返回既有ID（幂等）
     */
    public Long createIfAbsent(String stationCode, Long pumpdownId,
                               LocalDateTime lastReadingTime, LocalDateTime triggeredAt) {
        try {
            GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
            jdbc.update(con -> {
                java.sql.PreparedStatement ps = con.prepareStatement(
                        """
                        INSERT INTO miss_contact_order (station_code, pumpdown_id,
                            last_reading_time, triggered_at, status)
                        VALUES (?, ?, ?, ?, 'OPEN')
                        """,
                        new String[]{"id"});
                ps.setString(1, stationCode);
                ps.setLong(2, pumpdownId);
                ps.setObject(3, lastReadingTime);
                ps.setObject(4, triggeredAt);
                return ps;
            }, keyHolder);
            Number key = keyHolder.getKey();
            if (key == null) {
                throw new IllegalStateException("失联派单自增ID回填失败");
            }
            return key.longValue();
        } catch (DuplicateKeyException e) {
            return findId(stationCode, pumpdownId);
        }
    }

    private Long findId(String stationCode, Long pumpdownId) {
        List<Long> ids = jdbc.query(
                "SELECT id FROM miss_contact_order WHERE station_code = ? AND pumpdown_id = ?",
                (rs, n) -> rs.getLong("id"), stationCode, pumpdownId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /** 复算：预降事件失联派单台账。 */
    public List<ReplayResult.MissContactItem> loadOrders(Long pumpdownId) {
        return jdbc.query(
                """
                SELECT id, station_code, last_reading_time, triggered_at, status
                FROM miss_contact_order WHERE pumpdown_id = ? ORDER BY triggered_at, id
                """,
                (rs, n) -> new ReplayResult.MissContactItem(
                        rs.getLong("id"),
                        rs.getString("station_code"),
                        rs.getObject("last_reading_time", LocalDateTime.class),
                        rs.getObject("triggered_at", LocalDateTime.class),
                        rs.getString("status")),
                pumpdownId);
    }
}
