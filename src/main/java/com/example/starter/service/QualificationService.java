package com.example.starter.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import com.example.starter.support.BusinessException;
import com.example.starter.support.Fingerprints;
import com.example.starter.support.Limits;
import com.example.starter.support.NotFoundException;
import com.example.starter.web.dto.ExpiryRunRequest;
import com.example.starter.web.dto.RenewalRequest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 运行资格服务：到期判定（恰满到期日当日 24:00 UTC 日界失效）、
 * 失效线路自动停运留痕、学生改派与通知在同一事务内生成、资格续期窗口校验。
 */
@Service
public class QualificationService {

    private final JdbcTemplate jdbc;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public QualificationService(JdbcTemplate jdbc, IdempotencyService idempotency, Clock clock) {
        this.jdbc = jdbc;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    /**
     * 资格续期（幂等）。须在到期前 30 日内申请，恰第 30 日受理，更早或逾期拒绝。
     */
    @Transactional
    public RenewalRequest.Result renew(Long routeId, RenewalRequest req) {
        QualificationRow qualification = findQualification(routeId);
        if (qualification == null) {
            throw new NotFoundException("线路无运行资格: " + routeId);
        }
        LocalDate applyDate = req.applyDate() != null ? req.applyDate() : LocalDate.now(clock);
        String fingerprint = Fingerprints.of(List.of(
                req.requestId(), String.valueOf(routeId), applyDate.toString()));
        return idempotency.execute(req.requestId(), fingerprint, RenewalRequest.Result.class,
                () -> doRenew(routeId, qualification, applyDate, req.requestId()));
    }

    private RenewalRequest.Result doRenew(Long routeId, QualificationRow qualification, LocalDate applyDate,
                                          String requestId) {
        if (!"ACTIVE".equals(qualification.status())) {
            throw new BusinessException("资格已失效，不能续期");
        }
        long daysBeforeExpiry = ChronoUnit.DAYS.between(applyDate, qualification.validTo());
        if (daysBeforeExpiry < 0) {
            throw new BusinessException("资格已于 " + qualification.validTo() + " 24:00(UTC) 到期，逾期申请拒绝");
        }
        if (daysBeforeExpiry > Limits.RENEWAL_WINDOW_DAYS) {
            throw new BusinessException("续期须在到期前 " + Limits.RENEWAL_WINDOW_DAYS
                    + " 日内申请（当前距到期 " + daysBeforeExpiry + " 日），过早申请拒绝");
        }
        LocalDate newValidTo = qualification.validTo().plusYears(Limits.QUALIFICATION_YEARS);
        jdbc.update("UPDATE qualification SET valid_to = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                newValidTo, qualification.id());
        jdbc.update("INSERT INTO qualification_renewal"
                        + " (qualification_id, apply_date, old_valid_to, new_valid_to, request_id)"
                        + " VALUES (?,?,?,?,?)",
                qualification.id(), applyDate, qualification.validTo(), newValidTo, requestId);
        return new RenewalRequest.Result(routeId, qualification.validTo(), newValidTo);
    }

    /**
     * 到期巡检：失效资格对应的执行中线路自动停运并留痕，
     * 学生改派记录与通知在同一事务内生成。任一环节失败整体回滚。
     *
     * @param now 判定时刻（UTC）；为空取当前时刻
     */
    @Transactional
    public ExpiryRunRequest.Result runExpiry(java.time.Instant now) {
        java.time.Instant effective = now != null ? now : java.time.Instant.now(clock);
        LocalDate todayUtc = LocalDate.ofInstant(effective, ZoneOffset.UTC);
        // 恰满到期日当日 24:00（UTC 日界）失效，即 valid_to < 今日 UTC 日期时视为已失效
        List<QualificationRow> due = jdbc.query(
                "SELECT id, route_id, valid_to, status FROM qualification"
                        + " WHERE status = 'ACTIVE' AND valid_to < ? ORDER BY id",
                (rs, i) -> new QualificationRow(rs.getLong(1), rs.getLong(2),
                        rs.getObject(3, LocalDate.class), rs.getString(4)),
                todayUtc);

        List<Long> stoppedRouteIds = new ArrayList<>();
        int reassignmentCount = 0;
        int notificationCount = 0;
        for (QualificationRow q : due) {
            jdbc.update("UPDATE qualification SET status = 'EXPIRED', updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                    q.id());
            String routeStatus = jdbc.query(
                    "SELECT status FROM route WHERE id = ?",
                    rs -> rs.next() ? rs.getString(1) : null, q.routeId());
            if ("RUNNING".equals(routeStatus)) {
                jdbc.update("UPDATE route SET status = 'STOPPED' WHERE id = ?", q.routeId());
                jdbc.update("INSERT INTO route_status_log (route_id, from_status, to_status, reason)"
                                + " VALUES (?,?,?,?)",
                        q.routeId(), "RUNNING", "STOPPED",
                        "运行资格于 " + q.validTo() + " 24:00(UTC) 到期失效，线路自动停运");
            }
            stoppedRouteIds.add(q.routeId());
            List<Long> students = jdbc.query(
                    "SELECT student_id FROM route_student WHERE route_id = ? ORDER BY student_id",
                    (rs, i) -> rs.getLong(1), q.routeId());
            for (Long studentId : students) {
                Long targetRouteId = findReassignTarget(q.routeId(), todayUtc);
                String status = targetRouteId != null ? "ASSIGNED" : "UNASSIGNED";
                jdbc.update("INSERT INTO reassignment"
                                + " (route_id, student_id, stop_date, target_route_id, status)"
                                + " VALUES (?,?,?,?,?)",
                        q.routeId(), studentId, todayUtc, targetRouteId, status);
                reassignmentCount++;
                String message = targetRouteId != null
                        ? "线路 " + q.routeId() + " 资格到期停运，学生 " + studentId + " 改派至线路 " + targetRouteId
                        : "线路 " + q.routeId() + " 资格到期停运，学生 " + studentId + " 暂无可改派线路";
                try {
                    jdbc.update("INSERT INTO notification (route_id, student_id, notify_date, type, message)"
                                    + " VALUES (?,?,?,?,?)",
                            q.routeId(), studentId, todayUtc, "ROUTE_STOPPED_REASSIGN", message);
                    notificationCount++;
                } catch (DuplicateKeyException e) {
                    // 同日同类型通知已存在，跳过
                }
            }
        }
        return new ExpiryRunRequest.Result(stoppedRouteIds, reassignmentCount, notificationCount);
    }

    /**
     * 确定性改派目标选择：在执行中且资格有效的线路中，按线路 ID 升序选取首条有余座的线路；
     * 余座 = 车辆座位数 - 已分配学生数 - 当日已改派进入人数。相同输入必得相同结果（可复算）。
     */
    private Long findReassignTarget(Long stoppedRouteId, LocalDate stopDate) {
        List<Long> candidates = jdbc.query(
                "SELECT r.id FROM route r"
                        + " JOIN qualification q ON q.route_id = r.id AND q.status = 'ACTIVE'"
                        + " JOIN vehicle v ON v.id = r.vehicle_id"
                        + " WHERE r.status = 'RUNNING' AND r.id <> ?"
                        + " AND v.seats > (SELECT COUNT(*) FROM route_student rs WHERE rs.route_id = r.id)"
                        + " + (SELECT COUNT(*) FROM reassignment ra"
                        + "     WHERE ra.target_route_id = r.id AND ra.stop_date = ? AND ra.status = 'ASSIGNED')"
                        + " ORDER BY r.id",
                (rs, i) -> rs.getLong(1), stoppedRouteId, stopDate);
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    private QualificationRow findQualification(Long routeId) {
        return jdbc.query(
                "SELECT id, route_id, valid_to, status FROM qualification WHERE route_id = ?",
                rs -> rs.next()
                        ? new QualificationRow(rs.getLong(1), rs.getLong(2),
                                rs.getObject(3, LocalDate.class), rs.getString(4))
                        : null,
                routeId);
    }

    private record QualificationRow(Long id, Long routeId, LocalDate validTo, String status) {
    }
}
