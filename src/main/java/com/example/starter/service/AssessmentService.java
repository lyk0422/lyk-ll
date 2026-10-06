package com.example.starter.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.example.starter.support.BusinessException;
import com.example.starter.support.ConflictException;
import com.example.starter.support.Fingerprints;
import com.example.starter.support.Limits;
import com.example.starter.support.NotFoundException;
import com.example.starter.web.dto.AssessmentRequest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;

/**
 * 线路安全评估服务：站点间距、道路等级、候选车辆座位数逐项校验，
 * 任一项不满足拒绝，恰等限值通过；评估通过生成 1 年有效期运行资格。
 * 同线路同日期重复评估拒绝（409）。
 */
@Service
public class AssessmentService {

    private final JdbcTemplate jdbc;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public AssessmentService(JdbcTemplate jdbc, IdempotencyService idempotency, Clock clock) {
        this.jdbc = jdbc;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    /**
     * 执行安全评估（幂等）。
     */
    @Transactional
    public AssessmentRequest.Result assess(AssessmentRequest req) {
        // 先校验资源存在性，避免无效请求占用幂等键
        requireRoute(req.routeId());
        requireVehicle(req.vehicleId());
        String fingerprint = Fingerprints.of(List.of(
                req.requestId(),
                String.valueOf(req.routeId()),
                String.valueOf(req.vehicleId()),
                String.valueOf(req.roadGrade()),
                req.stopDistancesM().toString()));
        return idempotency.execute(req.requestId(), fingerprint, AssessmentRequest.Result.class,
                () -> doAssess(req));
    }

    private AssessmentRequest.Result doAssess(AssessmentRequest req) {
        LocalDate today = LocalDate.now(clock);
        String routeStatus = jdbc.queryForObject(
                "SELECT status FROM route WHERE id = ?", String.class, req.routeId());
        if ("RUNNING".equals(routeStatus)) {
            throw new BusinessException("线路已在执行中，如需延期请使用资格续期");
        }
        int seats = jdbc.queryForObject(
                "SELECT seats FROM vehicle WHERE id = ?", Integer.class, req.vehicleId());
        int studentCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM route_student WHERE route_id = ?", Integer.class, req.routeId());

        List<String> reasons = new ArrayList<>();
        int minDistance = req.stopDistancesM().stream().mapToInt(Integer::intValue).min().orElse(0);
        int maxDistance = req.stopDistancesM().stream().mapToInt(Integer::intValue).max().orElse(0);
        if (minDistance < Limits.MIN_STOP_DISTANCE_M) {
            reasons.add("站点间距低于下限 " + Limits.MIN_STOP_DISTANCE_M + " 米（实测 " + minDistance + " 米）");
        }
        if (maxDistance > Limits.MAX_STOP_DISTANCE_M) {
            reasons.add("站点间距高于上限 " + Limits.MAX_STOP_DISTANCE_M + " 米（实测 " + maxDistance + " 米）");
        }
        if (req.roadGrade() < Limits.MIN_ROAD_GRADE) {
            reasons.add("道路等级低于下限 " + Limits.MIN_ROAD_GRADE + " 级（实测 " + req.roadGrade() + " 级）");
        }
        if (seats < studentCount) {
            reasons.add("候选车辆座位数 " + seats + " 小于线路学生数 " + studentCount);
        }
        if (seats > Limits.MAX_VEHICLE_SEATS) {
            reasons.add("候选车辆座位数超过上限 " + Limits.MAX_VEHICLE_SEATS + " 座（实测 " + seats + " 座）");
        }
        boolean passed = reasons.isEmpty();

        Long assessmentId;
        try {
            assessmentId = insertAssessment(req, today, minDistance, maxDistance, seats, studentCount,
                    passed, String.join("；", reasons));
        } catch (DuplicateKeyException e) {
            throw new ConflictException("同线路同日期重复评估，拒绝执行");
        }

        LocalDate validFrom = null;
        LocalDate validTo = null;
        if (passed) {
            // 车辆同一时刻只允许一条执行中线路：预检 + 唯一索引兜底（并发时由索引抛出冲突）
            Integer busy = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM route WHERE running_vehicle_id = ? AND id <> ?",
                    Integer.class, req.vehicleId(), req.routeId());
            if (busy != null && busy > 0) {
                throw new ConflictException("候选车辆已被其他执行中线路占用");
            }
            try {
                jdbc.update("UPDATE route SET vehicle_id = ?, status = 'RUNNING' WHERE id = ?",
                        req.vehicleId(), req.routeId());
            } catch (DuplicateKeyException e) {
                throw new ConflictException("候选车辆已被其他执行中线路占用");
            }
            jdbc.update("INSERT INTO route_status_log (route_id, from_status, to_status, reason) VALUES (?,?,?,?)",
                    req.routeId(), routeStatus, "RUNNING", "安全评估通过，线路投入运行");
            validFrom = today;
            validTo = today.plusYears(Limits.QUALIFICATION_YEARS);
            upsertQualification(req.routeId(), validFrom, validTo);
        }
        return new AssessmentRequest.Result(assessmentId, req.routeId(), today, passed, reasons, validFrom, validTo);
    }

    private Long insertAssessment(AssessmentRequest req, LocalDate assessDate, int minDistance, int maxDistance,
                                  int seats, int studentCount, boolean passed, String reasons) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO assessment (route_id, assess_date, request_id, road_grade, stop_count,"
                            + " min_distance_m, max_distance_m, vehicle_id, vehicle_seats, student_count,"
                            + " passed, reject_reasons) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                    new String[]{"id"});
            ps.setLong(1, req.routeId());
            ps.setObject(2, assessDate);
            ps.setString(3, req.requestId());
            ps.setInt(4, req.roadGrade());
            ps.setInt(5, req.stopDistancesM().size() + 1);
            ps.setInt(6, minDistance);
            ps.setInt(7, maxDistance);
            ps.setLong(8, req.vehicleId());
            ps.setInt(9, seats);
            ps.setInt(10, studentCount);
            ps.setBoolean(11, passed);
            ps.setString(12, reasons);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("评估记录主键生成失败");
        }
        return key.longValue();
    }

    private void upsertQualification(Long routeId, LocalDate validFrom, LocalDate validTo) {
        int updated = jdbc.update(
                "UPDATE qualification SET valid_from = ?, valid_to = ?, status = 'ACTIVE',"
                        + " updated_at = CURRENT_TIMESTAMP WHERE route_id = ?",
                validFrom, validTo, routeId);
        if (updated == 0) {
            jdbc.update("INSERT INTO qualification (route_id, valid_from, valid_to, status) VALUES (?,?,?, 'ACTIVE')",
                    routeId, validFrom, validTo);
        }
    }

    private void requireRoute(Long routeId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM route WHERE id = ?", Integer.class, routeId);
        if (count == null || count == 0) {
            throw new NotFoundException("线路不存在: " + routeId);
        }
    }

    private void requireVehicle(Long vehicleId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM vehicle WHERE id = ?", Integer.class, vehicleId);
        if (count == null || count == 0) {
            throw new NotFoundException("车辆不存在: " + vehicleId);
        }
    }
}
