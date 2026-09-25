package com.example.starter.service;

import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.starter.support.AppClock;
import com.example.starter.support.Fingerprints;
import com.example.starter.web.ConflictException;
import com.example.starter.web.dto.FaultReportRequest;
import com.example.starter.web.dto.FaultReportResponse;

/**
 * 故障接报与研判服务：接报幂等、按“故障点+时段”去重归并、自动研判建单。
 */
@Service
public class FaultReportService {

    private final JdbcTemplate jdbc;
    private final AppClock clock;
    private final WorkOrderStore store;
    private final TransactionTemplate tx;

    public FaultReportService(JdbcTemplate jdbc, AppClock clock, WorkOrderStore store,
            PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.store = store;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * 故障接报：同 requestId 同参返回首次结果，同键异参抛 409；同故障点同时段归并到既有工单。
     * 并发同键时由唯一约束兜底，整事务回滚后按重放/冲突重查。
     */
    public FaultReportResponse report(FaultReportRequest req) {
        LocalDateTime reportedAt = req.reportedAt() != null ? req.reportedAt() : clock.now();
        String level = resolveLevel(req.importantUser());
        String fingerprint = Fingerprints.of(req.requestId(), req.feederId(), req.faultPoint(),
                req.affectedUsers(), req.importantUser(), reportedAt);
        try {
            return tx.execute(status -> doReport(req, reportedAt, level, fingerprint));
        } catch (DuplicateKeyException e) {
            return tx.execute(status -> findReplay(req.requestId(), fingerprint)
                    .orElseThrow(() -> e));
        }
    }

    private FaultReportResponse doReport(FaultReportRequest req, LocalDateTime reportedAt,
            String level, String fingerprint) {
        Optional<FaultReportResponse> replay = findReplay(req.requestId(), fingerprint);
        if (replay.isPresent()) {
            return replay.get();
        }

        String dedupKey = req.faultPoint() + "|" + reportedAt.truncatedTo(ChronoUnit.HOURS);
        Optional<long[]> prior = findOpenReport(dedupKey);
        long reportId;
        long workOrderId;
        boolean merged;
        if (prior.isPresent()) {
            // 重复接报：归并到首报工单，累计影响用户数
            workOrderId = prior.get()[1];
            merged = true;
            jdbc.update("UPDATE work_order SET affected_users = affected_users + ? WHERE id = ?",
                    req.affectedUsers(), workOrderId);
            store.addEvent(workOrderId, "REPORTED",
                    "归并接报, 新增影响用户" + req.affectedUsers() + "户");
            reportId = insertReport(req, fingerprint, level, reportedAt, dedupKey, prior.get()[0], workOrderId);
        } else {
            // 首报：研判建单
            workOrderId = store.insertOrder(req.feederId(), req.faultPoint(), req.affectedUsers(),
                    level, "PENDING", reportedAt);
            merged = false;
            store.addEvent(workOrderId, "REPORTED", "首次接报, 故障点=" + req.faultPoint());
            store.addEvent(workOrderId, "ASSESSED", "研判完成, 重要等级=" + level + ", 影响用户" + req.affectedUsers() + "户");
            reportId = insertReport(req, fingerprint, level, reportedAt, dedupKey, null, workOrderId);
        }
        String status = store.requireById(workOrderId).status();
        return new FaultReportResponse(reportId, workOrderId, status, merged, false, reportedAt);
    }

    private Optional<FaultReportResponse> findReplay(String requestId, String fingerprint) {
        List<Object[]> rows = jdbc.query(
                "SELECT id, fingerprint, work_order_id, merged_into_id, reported_at FROM fault_report"
                + " WHERE request_id = ?",
                (rs, rowNum) -> new Object[] {
                        rs.getLong(1), rs.getString(2), rs.getLong(3),
                        rs.getObject(4), rs.getTimestamp(5).toLocalDateTime()},
                requestId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Object[] row = rows.get(0);
        if (!row[1].equals(fingerprint)) {
            throw new ConflictException("同键异参幂等冲突: requestId=" + requestId);
        }
        long workOrderId = (Long) row[2];
        String status = store.requireById(workOrderId).status();
        return Optional.of(new FaultReportResponse((Long) row[0], workOrderId, status,
                row[3] != null, true, (LocalDateTime) row[4]));
    }

    /**
     * 查询同时段同故障点且工单未终结（未复电、未封存、未合并）的首报，返回 [首报ID, 工单ID]。
     */
    private Optional<long[]> findOpenReport(String dedupKey) {
        List<long[]> rows = jdbc.query(
                "SELECT r.id, r.work_order_id FROM fault_report r JOIN work_order w ON r.work_order_id = w.id"
                + " WHERE r.dedup_key = ? AND r.merged_into_id IS NULL"
                + " AND w.status IN ('PENDING','QUEUED','EXECUTING') ORDER BY r.id ASC",
                (rs, rowNum) -> new long[] {rs.getLong(1), rs.getLong(2)},
                dedupKey);
        return rows.stream().findFirst();
    }

    private long insertReport(FaultReportRequest req, String fingerprint, String level,
            LocalDateTime reportedAt, String dedupKey, Long mergedIntoId, long workOrderId) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO fault_report (request_id, fingerprint, feeder_id, fault_point,"
                    + " affected_users, important_level, reported_at, dedup_key, merged_into_id, work_order_id)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, req.requestId());
            ps.setString(2, fingerprint);
            ps.setString(3, req.feederId());
            ps.setString(4, req.faultPoint());
            ps.setInt(5, req.affectedUsers());
            ps.setString(6, level);
            ps.setTimestamp(7, Timestamp.valueOf(reportedAt));
            ps.setString(8, dedupKey);
            if (mergedIntoId == null) {
                ps.setObject(9, null);
            } else {
                ps.setLong(9, mergedIntoId);
            }
            ps.setLong(10, workOrderId);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按重要用户名称解析分级，未登记或为空返回 NONE。
     */
    private String resolveLevel(String importantUser) {
        if (importantUser == null || importantUser.isBlank()) {
            return "NONE";
        }
        List<String> levels = jdbc.query(
                "SELECT level FROM important_user WHERE user_name = ?",
                (rs, rowNum) -> rs.getString(1), importantUser);
        return levels.isEmpty() ? "NONE" : levels.get(0);
    }
}
