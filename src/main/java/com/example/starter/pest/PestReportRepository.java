package com.example.starter.pest;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 虫情上报与预防性消杀评估数据访问。
 */
@Repository
public class PestReportRepository {

    /**
     * 虫情上报记录。
     *
     * @param evaluationConsumed 是否已被预防性评估消耗（消耗后不再参与触发）
     */
    public record PestReport(long id, String requestId, String zoneCode, String description,
                             String reporter, String status, Instant reportedAt, Instant confirmedAt,
                             boolean evaluationConsumed) {
    }

    private final JdbcClient jdbc;

    public PestReportRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 新增上报，返回自增 ID。
     */
    public long insert(String requestId, String zoneCode, String description, String reporter,
                       String status, Instant reportedAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO pest_report (request_id, zone_code, description, reporter, status, reported_at)"
                        + " VALUES (:requestId, :zoneCode, :description, :reporter, :status, :reportedAt)")
                .param("requestId", requestId)
                .param("zoneCode", zoneCode)
                .param("description", description)
                .param("reporter", reporter)
                .param("status", status)
                .param("reportedAt", Timestamp.from(reportedAt))
                .update(keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 按 ID 查询上报。
     */
    public Optional<PestReport> findById(long id) {
        return jdbc.sql("SELECT * FROM pest_report WHERE id = :id")
                .param("id", id)
                .query((rs, i) -> map(rs))
                .optional();
    }

    /**
     * 标记上报为已确认。
     */
    public void markConfirmed(long id, Instant confirmedAt) {
        jdbc.sql("UPDATE pest_report SET status = 'CONFIRMED', confirmed_at = :confirmedAt WHERE id = :id")
                .param("confirmedAt", Timestamp.from(confirmedAt))
                .param("id", id)
                .update();
    }

    /**
     * 查询相邻库区在 [windowStart, windowEnd] 内最近一次未被评估消耗的已确认上报。
     */
    public Optional<PestReport> findLatestUnconsumedAdjacentReport(String zoneCode, Instant windowStart,
                                                                   Instant windowEnd, long excludeId) {
        return jdbc.sql("SELECT pr.* FROM pest_report pr"
                        + " JOIN zone_adjacency za ON pr.zone_code = za.adjacent_code"
                        + " WHERE za.zone_code = :zoneCode AND pr.status = 'CONFIRMED'"
                        + " AND pr.evaluation_consumed = FALSE AND pr.id <> :excludeId"
                        + " AND pr.confirmed_at >= :windowStart AND pr.confirmed_at <= :windowEnd"
                        + " ORDER BY pr.confirmed_at DESC LIMIT 1")
                .param("zoneCode", zoneCode)
                .param("excludeId", excludeId)
                .param("windowStart", Timestamp.from(windowStart))
                .param("windowEnd", Timestamp.from(windowEnd))
                .query((rs, i) -> map(rs))
                .optional();
    }

    /**
     * 标记上报已被预防性评估消耗。
     */
    public void markEvaluationConsumed(long id) {
        jdbc.sql("UPDATE pest_report SET evaluation_consumed = TRUE WHERE id = :id")
                .param("id", id)
                .update();
    }

    /**
     * 新增整体预防性消杀评估，返回自增 ID。
     */
    public long insertEvaluation(String zoneCode, String adjacentZoneCode, long triggerReportId,
                                 Instant createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO preventive_evaluation (zone_code, adjacent_zone_code, trigger_report_id, created_at)"
                        + " VALUES (:zoneCode, :adjacentZoneCode, :triggerReportId, :createdAt)")
                .param("zoneCode", zoneCode)
                .param("adjacentZoneCode", adjacentZoneCode)
                .param("triggerReportId", triggerReportId)
                .param("createdAt", Timestamp.from(createdAt))
                .update(keyHolder);
        return keyHolder.getKey().longValue();
    }

    private PestReport map(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp confirmedAt = rs.getTimestamp("confirmed_at");
        return new PestReport(
                rs.getLong("id"),
                rs.getString("request_id"),
                rs.getString("zone_code"),
                rs.getString("description"),
                rs.getString("reporter"),
                rs.getString("status"),
                rs.getTimestamp("reported_at").toInstant(),
                confirmedAt == null ? null : confirmedAt.toInstant(),
                rs.getBoolean("evaluation_consumed"));
    }
}
