package com.example.starter.repo;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import com.example.starter.domain.QualificationStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 运行资格与停运留痕数据访问。
 */
@Repository
public class QualificationRepository {

    private final JdbcClient jdbc;

    public QualificationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 资格记录；有效期含 validFrom 与 validTo 当日，validTo 当日 24:00（UTC 日界）失效。
     */
    public record QualificationRow(long id, long routeId, LocalDate validFrom, LocalDate validTo,
                                   String status, String source) {
    }

    /**
     * 写入资格，返回自增ID。
     */
    public long insert(long routeId, LocalDate validFrom, LocalDate validTo, String source,
                       String requestId) {
        jdbc.sql("INSERT INTO qualification (route_id, valid_from, valid_to, status, source, request_id)"
                        + " VALUES (?, ?, ?, ?, ?, ?)")
                .params(routeId, validFrom, validTo, QualificationStatus.ACTIVE.name(), source,
                        requestId)
                .update();
        return jdbc.sql("SELECT MAX(id) FROM qualification WHERE route_id = ?")
                .param(routeId)
                .query(Long.class)
                .single();
    }

    public Optional<QualificationRow> findById(long id) {
        return jdbc.sql("SELECT id, route_id, valid_from, valid_to, status, source"
                        + " FROM qualification WHERE id = ?")
                .param(id)
                .query(this::mapRow)
                .optional();
    }

    /**
     * 线路当前有效资格（ACTIVE 且 valid_to >= 今日）。
     */
    public Optional<QualificationRow> findActiveByRoute(long routeId, LocalDate today) {
        return jdbc.sql("SELECT id, route_id, valid_from, valid_to, status, source"
                        + " FROM qualification WHERE route_id = ? AND status = ? AND valid_to >= ?"
                        + " ORDER BY valid_to DESC LIMIT 1")
                .params(routeId, QualificationStatus.ACTIVE.name(), today)
                .query(this::mapRow)
                .optional();
    }

    /**
     * 已到期但仍为 ACTIVE 的资格：valid_to 当日 24:00 失效，即 valid_to < 今日。
     */
    public List<QualificationRow> findExpiredActive(LocalDate today) {
        return jdbc.sql("SELECT id, route_id, valid_from, valid_to, status, source"
                        + " FROM qualification WHERE status = ? AND valid_to < ? ORDER BY id")
                .params(QualificationStatus.ACTIVE.name(), today)
                .query(this::mapRow)
                .list();
    }

    public void updateStatus(long id, QualificationStatus status) {
        jdbc.sql("UPDATE qualification SET status = ? WHERE id = ?")
                .params(status.name(), id)
                .update();
    }

    /**
     * 写入停运留痕。
     */
    public long insertSuspensionTrace(long routeId, long qualificationId, String reason) {
        jdbc.sql("INSERT INTO suspension_trace (route_id, qualification_id, reason)"
                        + " VALUES (?, ?, ?)")
                .params(routeId, qualificationId, reason)
                .update();
        return jdbc.sql("SELECT MAX(id) FROM suspension_trace WHERE route_id = ?")
                .param(routeId)
                .query(Long.class)
                .single();
    }

    private QualificationRow mapRow(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new QualificationRow(rs.getLong(1), rs.getLong(2), rs.getDate(3).toLocalDate(),
                rs.getDate(4).toLocalDate(), rs.getString(5), rs.getString(6));
    }
}
