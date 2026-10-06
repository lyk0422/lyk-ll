package com.example.starter.waste;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 日结汇总数据访问：按品类汇总进场净重与回执签收净重，直接由联单/回执明细实时计算。
 */
@Repository
public class SettlementRepository {

    private final JdbcClient jdbc;

    public SettlementRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 汇总指定自然日 [start, end) 的进出量。
     */
    public List<DailySettlementResponse.Item> summarize(LocalDate date, LocalDateTime start, LocalDateTime end) {
        return jdbc.sql("""
                SELECT m.category AS category,
                       COALESCE(SUM(CASE WHEN m.created_at >= :start AND m.created_at < :end THEN m.net_kg END), 0) AS inbound_kg,
                       COALESCE(SUM(CASE WHEN r.received_at >= :start AND r.received_at < :end THEN r.received_net_kg END), 0) AS outbound_kg,
                       SUM(CASE WHEN m.created_at >= :start AND m.created_at < :end THEN 1 ELSE 0 END) AS manifest_count,
                       SUM(CASE WHEN r.received_at >= :start AND r.received_at < :end THEN 1 ELSE 0 END) AS receipt_count
                FROM manifest m
                LEFT JOIN receipt r ON r.manifest_id = m.id
                WHERE (m.created_at >= :start AND m.created_at < :end)
                   OR (r.received_at >= :start AND r.received_at < :end)
                GROUP BY m.category
                ORDER BY m.category
                """)
                .param("start", start)
                .param("end", end)
                .query((rs, rowNum) -> new DailySettlementResponse.Item(
                        rs.getString("category"),
                        rs.getBigDecimal("inbound_kg"),
                        rs.getBigDecimal("outbound_kg"),
                        rs.getLong("manifest_count"),
                        rs.getLong("receipt_count")))
                .list();
    }

    /**
     * 复算校验：按明细逐行求和，供测试与对账核对汇总可复算。
     */
    public BigDecimal sumManifestNet(String category, LocalDateTime start, LocalDateTime end) {
        return jdbc.sql("""
                        SELECT COALESCE(SUM(net_kg), 0) FROM manifest
                        WHERE category = :category AND created_at >= :start AND created_at < :end
                        """)
                .param("category", category)
                .param("start", start)
                .param("end", end)
                .query(BigDecimal.class)
                .single();
    }
}
