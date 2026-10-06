package com.example.starter.service;

import java.time.LocalDate;
import java.util.List;

import com.example.starter.web.dto.SettlementRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 日结服务：按日期+线路汇总改派与通知数量并落库，可重复执行（复算结果一致）。
 */
@Service
public class SettlementService {

    private final JdbcTemplate jdbc;

    public SettlementService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 复算指定日期（UTC 日界）的日结数据并幂等落库。
     */
    @Transactional
    public List<SettlementRow> settle(LocalDate date) {
        List<SettlementRow> rows = jdbc.query(
                "SELECT route_id, SUM(reassigned), SUM(notified) FROM ("
                        + " SELECT route_id, COUNT(*) AS reassigned, 0 AS notified"
                        + " FROM reassignment WHERE stop_date = ? GROUP BY route_id"
                        + " UNION ALL"
                        + " SELECT route_id, 0 AS reassigned, COUNT(*) AS notified"
                        + " FROM notification WHERE notify_date = ? GROUP BY route_id"
                        + " ) t GROUP BY route_id ORDER BY route_id",
                (rs, i) -> new SettlementRow(date, rs.getLong(1), rs.getInt(2), rs.getInt(3)),
                date, date);
        for (SettlementRow row : rows) {
            jdbc.update("MERGE INTO daily_settlement"
                            + " (settle_date, route_id, reassigned_count, notified_count, computed_at)"
                            + " KEY(settle_date, route_id) VALUES (?,?,?,?, CURRENT_TIMESTAMP)",
                    row.settleDate(), row.routeId(), row.reassignedCount(), row.notifiedCount());
        }
        return rows;
    }
}
