package com.example.starter.plan;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.plan.dto.ApproveRequest;
import com.example.starter.plan.dto.CloseRequest;
import com.example.starter.plan.dto.CreatePlanRequest;
import com.example.starter.plan.dto.FractionRequest;
import com.example.starter.plan.dto.OarDose;
import com.example.starter.plan.dto.ReviewRequest;
import com.example.starter.plan.dto.ReviseRequest;
import com.example.starter.plan.dto.VerifyRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 放疗计划核心服务：设计→物理审核→医师批准→首次验证→分次执行→疗程结案。
 * 审核/批准/分次记录均按 requestId 幂等，指纹覆盖 requestId 与全部业务字段。
 */
@Service
public class PlanService {

    /** 模体验证允许的最大剂量偏差百分比，恰等合法 */
    private static final BigDecimal MAX_DEVIATION_PERCENT = new BigDecimal("3");

    private static final RowMapper<PlanRow> PLAN_MAPPER = (rs, n) -> new PlanRow(
            rs.getLong("id"),
            rs.getLong("patient_id"),
            rs.getInt("version"),
            PlanStatus.valueOf(rs.getString("status")),
            rs.getBigDecimal("prescription_total_gy"),
            rs.getInt("fraction_count"),
            rs.getBigDecimal("fraction_dose_gy"));

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final AlertService alertService;

    public PlanService(JdbcTemplate jdbc, Clock clock, ObjectMapper objectMapper, AlertService alertService) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.alertService = alertService;
    }

    private record PlanRow(long id, long patientId, int version, PlanStatus status,
                           BigDecimal totalGy, int fractionCount, BigDecimal fractionDoseGy) {
    }

    private record TemplateRow(String organ, BigDecimal maxDoseGy) {
    }

    // ---------------------------------------------------------------- 计划创建与换版

    /**
     * 创建计划（DESIGN）。患者按 patientNo 查找或创建（合成数据）。
     */
    @Transactional
    public Map<String, Object> createPlan(CreatePlanRequest req) {
        long patientId = findOrCreatePatient(req.patientNo(), req.patientName());
        int version = nextVersion(patientId);
        long planId = insertPlan(patientId, version, req.prescriptionTotalGy(),
                req.fractionCount(), req.fractionDoseGy());
        insertOarDoses(planId, req.oarDoses());
        return planView(loadPlan(planId, false));
    }

    /**
     * 计划换版：新版本置 DESIGN，旧版本同一事务内即刻失效，不得新旧同时可执行。
     */
    @Transactional
    public Map<String, Object> revisePlan(long planId, ReviseRequest req) {
        PlanRow old = loadPlan(planId, true);
        if (old.status() == PlanStatus.CLOSED || old.status() == PlanStatus.INVALIDATED) {
            throw ApiException.invalidState("计划状态 " + old.status() + " 不允许换版");
        }
        int newVersion = nextVersion(old.patientId());
        long newPlanId = insertPlan(old.patientId(), newVersion, req.prescriptionTotalGy(),
                req.fractionCount(), req.fractionDoseGy());
        insertOarDoses(newPlanId, req.oarDoses());
        updateStatus(old.id(), PlanStatus.INVALIDATED);
        Map<String, Object> view = planView(loadPlan(newPlanId, false));
        view.put("supersededPlanId", old.id());
        view.put("supersededStatus", PlanStatus.INVALIDATED.name());
        return view;
    }

    // ---------------------------------------------------------------- 物理审核

    /**
     * 物理审核：危及器官剂量逐项对照模板（恰等限值合法，超限拒绝），
     * 并校验分次剂量×次数==处方总剂量。requestId 幂等。
     */
    @Transactional
    public Map<String, Object> physicsReview(long planId, ReviewRequest req) {
        String fingerprint = fingerprint(req.requestId(), planId, req.reviewer());
        Map<String, Object> existing = findByRequestId("review_record", req.requestId());
        if (existing != null) {
            return replay(existing, fingerprint, this::reviewView);
        }
        PlanRow plan = loadPlan(planId, true);
        if (plan.status() != PlanStatus.DESIGN) {
            throw ApiException.invalidState("计划状态 " + plan.status() + " 不允许物理审核");
        }
        List<Map<String, Object>> violations = computeViolations(plan);
        String result = violations.isEmpty() ? "PASSED" : "REJECTED";
        try {
            jdbc.update(
                    "INSERT INTO review_record (request_id, plan_id, fingerprint, reviewer, result, violations, created_at)"
                            + " VALUES (?,?,?,?,?,?,?)",
                    req.requestId(), planId, fingerprint, req.reviewer(), result,
                    toJson(violations), now());
        } catch (DuplicateKeyException e) {
            return replay(findByRequestId("review_record", req.requestId()), fingerprint, this::reviewView);
        }
        if (violations.isEmpty()) {
            updateStatus(planId, PlanStatus.PHYSICS_REVIEWED);
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("planId", planId);
        view.put("requestId", req.requestId());
        view.put("result", result);
        view.put("violations", violations);
        view.put("idempotent", false);
        return view;
    }

    /** 约束与总剂量勾稽明细，可复算 */
    private List<Map<String, Object>> computeViolations(PlanRow plan) {
        List<Map<String, Object>> violations = new ArrayList<>();
        BigDecimal expectedTotal = plan.fractionDoseGy()
                .multiply(BigDecimal.valueOf(plan.fractionCount()));
        if (expectedTotal.compareTo(plan.totalGy()) != 0) {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("type", "TOTAL_DOSE_MISMATCH");
            v.put("message", "分次剂量×次数不等于处方总剂量");
            v.put("expectedTotalGy", plan.totalGy());
            v.put("actualTotalGy", expectedTotal);
            violations.add(v);
        }
        List<TemplateRow> templates = jdbc.query(
                "SELECT organ, max_dose_gy FROM oar_constraint_template ORDER BY organ",
                (rs, n) -> new TemplateRow(rs.getString("organ"), rs.getBigDecimal("max_dose_gy")));
        Map<String, BigDecimal> doses = new LinkedHashMap<>();
        jdbc.query("SELECT organ, dose_gy FROM plan_oar_dose WHERE plan_id=?",
                (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                        doses.put(rs.getString("organ"), rs.getBigDecimal("dose_gy")),
                plan.id());
        for (TemplateRow tpl : templates) {
            String organ = tpl.organ();
            BigDecimal limit = tpl.maxDoseGy();
            BigDecimal actual = doses.get(organ);
            if (actual == null) {
                Map<String, Object> v = new LinkedHashMap<>();
                v.put("type", "MISSING_OAR_DOSE");
                v.put("organ", organ);
                v.put("message", "缺少危及器官剂量项");
                violations.add(v);
            } else if (actual.compareTo(limit) > 0) {
                Map<String, Object> v = new LinkedHashMap<>();
                v.put("type", "OAR_DOSE_EXCEEDED");
                v.put("organ", organ);
                v.put("limitGy", limit);
                v.put("actualGy", actual);
                v.put("message", "危及器官剂量超限");
                violations.add(v);
            }
        }
        return violations;
    }

    // ---------------------------------------------------------------- 医师批准

    /**
     * 医师批准：同一患者同时刻只允许一个执行中计划，并发批准唯一成功。
     */
    @Transactional
    public Map<String, Object> approve(long planId, ApproveRequest req) {
        String fingerprint = fingerprint(req.requestId(), planId, req.physician());
        Map<String, Object> existing = findByRequestId("approval_record", req.requestId());
        if (existing != null) {
            return replay(existing, fingerprint, this::approvalView);
        }
        PlanRow plan = loadPlan(planId, true);
        lockPatient(plan.patientId());
        if (plan.status() != PlanStatus.PHYSICS_REVIEWED) {
            throw ApiException.invalidState("计划状态 " + plan.status() + " 不允许批准");
        }
        Integer active = jdbc.queryForObject(
                "SELECT COUNT(*) FROM plan WHERE patient_id=? AND id<>?"
                        + " AND status IN ('APPROVED','VERIFIED','EXECUTING')",
                Integer.class, plan.patientId(), planId);
        if (active != null && active > 0) {
            throw ApiException.conflict("同一患者同时刻只允许一个执行中计划");
        }
        try {
            jdbc.update(
                    "INSERT INTO approval_record (request_id, plan_id, fingerprint, physician, created_at)"
                            + " VALUES (?,?,?,?,?)",
                    req.requestId(), planId, fingerprint, req.physician(), now());
        } catch (DuplicateKeyException e) {
            Map<String, Object> raced = findByRequestId("approval_record", req.requestId());
            if (raced != null) {
                return replay(raced, fingerprint, this::approvalView);
            }
            throw ApiException.conflict("计划已被批准，并发批准唯一成功");
        }
        updateStatus(planId, PlanStatus.APPROVED);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("planId", planId);
        view.put("requestId", req.requestId());
        view.put("status", PlanStatus.APPROVED.name());
        view.put("idempotent", false);
        return view;
    }

    // ---------------------------------------------------------------- 首次模体验证

    /**
     * 首次模体验证：剂量偏差>3%不得执行，恰3%合法。requestId 幂等。
     */
    @Transactional
    public Map<String, Object> verify(long planId, VerifyRequest req) {
        String fingerprint = fingerprint(req.requestId(), planId, req.measuredDoseGy());
        Map<String, Object> existing = findByRequestId("verification_record", req.requestId());
        if (existing != null) {
            return replay(existing, fingerprint, this::verificationView);
        }
        PlanRow plan = loadPlan(planId, true);
        if (plan.status() != PlanStatus.APPROVED) {
            throw ApiException.invalidState("计划状态 " + plan.status() + " 不允许模体验证");
        }
        BigDecimal deviation = req.measuredDoseGy().subtract(plan.fractionDoseGy()).abs()
                .divide(plan.fractionDoseGy(), 8, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(4, RoundingMode.HALF_UP);
        boolean passed = deviation.compareTo(MAX_DEVIATION_PERCENT) <= 0;
        try {
            jdbc.update(
                    "INSERT INTO verification_record (request_id, plan_id, fingerprint, measured_dose_gy,"
                            + " deviation_percent, passed, created_at) VALUES (?,?,?,?,?,?,?)",
                    req.requestId(), planId, fingerprint, req.measuredDoseGy(), deviation, passed, now());
        } catch (DuplicateKeyException e) {
            return replay(findByRequestId("verification_record", req.requestId()), fingerprint,
                    this::verificationView);
        }
        if (passed) {
            updateStatus(planId, PlanStatus.VERIFIED);
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("planId", planId);
        view.put("requestId", req.requestId());
        view.put("deviationPercent", deviation);
        view.put("passed", passed);
        view.put("idempotent", false);
        return view;
    }

    // ---------------------------------------------------------------- 分次执行

    /**
     * 分次执行：按患者+计划+分次号去重，须顺序执行，跳次拒绝并告警；
     * 结案后迟到分次只记迟到事件。requestId 幂等。
     */
    @Transactional
    public Map<String, Object> executeFraction(long planId, FractionRequest req) {
        String fingerprint = fingerprint(req.requestId(), planId, req.fractionNo(), req.doseGy());
        Map<String, Object> existing = findByRequestId("fraction_record", req.requestId());
        if (existing != null) {
            return replay(existing, fingerprint, this::fractionView);
        }
        Map<String, Object> existingLate = findByRequestId("late_fraction_event", req.requestId());
        if (existingLate != null) {
            return replay(existingLate, fingerprint, this::lateEventView);
        }
        PlanRow plan = loadPlan(planId, true);
        if (plan.status() == PlanStatus.CLOSED) {
            jdbc.update(
                    "INSERT INTO late_fraction_event (request_id, plan_id, patient_id, fraction_no, dose_gy,"
                            + " fingerprint, created_at) VALUES (?,?,?,?,?,?,?)",
                    req.requestId(), planId, plan.patientId(), req.fractionNo(), req.doseGy(),
                    fingerprint, now());
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("planId", planId);
            view.put("requestId", req.requestId());
            view.put("fractionNo", req.fractionNo());
            view.put("status", "LATE");
            view.put("message", "疗程已结案，分次记录为迟到事件，不计入执行台账");
            view.put("idempotent", false);
            return view;
        }
        if (plan.status() != PlanStatus.VERIFIED && plan.status() != PlanStatus.EXECUTING) {
            throw ApiException.invalidState("计划状态 " + plan.status() + " 不允许分次执行");
        }
        Integer maxNo = jdbc.queryForObject(
                "SELECT COALESCE(MAX(fraction_no),0) FROM fraction_record WHERE plan_id=?",
                Integer.class, planId);
        int expected = (maxNo == null ? 0 : maxNo) + 1;
        if (req.fractionNo() != expected) {
            alertService.fractionSkip(planId, plan.patientId(), expected, req.fractionNo());
            throw ApiException.unprocessable(
                    "跳次执行拒绝：期望分次号 " + expected + "，实际请求分次号 " + req.fractionNo());
        }
        if (req.fractionNo() > plan.fractionCount()) {
            throw ApiException.unprocessable("分次号超过计划分次次数 " + plan.fractionCount());
        }
        if (req.doseGy().compareTo(plan.fractionDoseGy()) != 0) {
            throw ApiException.unprocessable(
                    "分次剂量与计划不符：计划 " + plan.fractionDoseGy() + " Gy，实际 " + req.doseGy() + " Gy");
        }
        try {
            jdbc.update(
                    "INSERT INTO fraction_record (request_id, plan_id, patient_id, fraction_no, dose_gy,"
                            + " fingerprint, created_at) VALUES (?,?,?,?,?,?,?)",
                    req.requestId(), planId, plan.patientId(), req.fractionNo(), req.doseGy(),
                    fingerprint, now());
        } catch (DuplicateKeyException e) {
            Map<String, Object> raced = findByRequestId("fraction_record", req.requestId());
            if (raced != null) {
                return replay(raced, fingerprint, this::fractionView);
            }
            throw ApiException.conflict("分次已执行：患者+计划+分次号去重");
        }
        if (plan.status() == PlanStatus.VERIFIED) {
            updateStatus(planId, PlanStatus.EXECUTING);
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("planId", planId);
        view.put("requestId", req.requestId());
        view.put("fractionNo", req.fractionNo());
        view.put("status", "EXECUTED");
        view.put("idempotent", false);
        return view;
    }

    // ---------------------------------------------------------------- 疗程结案

    /**
     * 疗程结案：终态。重复结案返回幂等成功。
     */
    @Transactional
    public Map<String, Object> close(long planId, CloseRequest req) {
        String fingerprint = fingerprint(req.requestId(), planId, req.operator());
        Map<String, Object> existing = findByRequestId("closure_record", req.requestId());
        if (existing != null) {
            return replay(existing, fingerprint, this::closureView);
        }
        Map<String, Object> closedForPlan = findClosureByPlan(planId);
        if (closedForPlan != null) {
            Map<String, Object> view = closureView(closedForPlan);
            view.put("idempotent", true);
            return view;
        }
        PlanRow plan = loadPlan(planId, true);
        if (plan.status() != PlanStatus.EXECUTING) {
            throw ApiException.invalidState("计划状态 " + plan.status() + " 不允许结案");
        }
        try {
            jdbc.update(
                    "INSERT INTO closure_record (request_id, plan_id, fingerprint, operator, created_at)"
                            + " VALUES (?,?,?,?,?)",
                    req.requestId(), planId, fingerprint, req.operator(), now());
        } catch (DuplicateKeyException e) {
            Map<String, Object> raced = findByRequestId("closure_record", req.requestId());
            if (raced != null) {
                return replay(raced, fingerprint, this::closureView);
            }
            Map<String, Object> view = closureView(findClosureByPlan(planId));
            view.put("idempotent", true);
            return view;
        }
        updateStatus(planId, PlanStatus.CLOSED);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("planId", planId);
        view.put("requestId", req.requestId());
        view.put("status", PlanStatus.CLOSED.name());
        view.put("idempotent", false);
        return view;
    }

    // ---------------------------------------------------------------- 查询（台账复算）

    /** 计划详情（含危及器官剂量明细） */
    @Transactional(readOnly = true)
    public Map<String, Object> getPlan(long planId) {
        return planView(loadPlan(planId, false));
    }

    /** 审核记录（含约束超限明细） */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listReviews(long planId) {
        return jdbc.query("SELECT * FROM review_record WHERE plan_id=? ORDER BY id",
                (rs, n) -> reviewView(rowMap(rs)), planId);
    }

    /** 模体验证链 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listVerifications(long planId) {
        return jdbc.query("SELECT * FROM verification_record WHERE plan_id=? ORDER BY id",
                (rs, n) -> verificationView(rowMap(rs)), planId);
    }

    /** 分次执行台账 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listFractions(long planId) {
        return jdbc.query("SELECT * FROM fraction_record WHERE plan_id=? ORDER BY fraction_no",
                (rs, n) -> fractionView(rowMap(rs)), planId);
    }

    /** 迟到分次事件 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listLateEvents(long planId) {
        return jdbc.query("SELECT * FROM late_fraction_event WHERE plan_id=? ORDER BY id",
                (rs, n) -> lateEventView(rowMap(rs)), planId);
    }

    /** 告警记录 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listAlerts(long planId) {
        return jdbc.query("SELECT * FROM alert_record WHERE plan_id=? ORDER BY id",
                (rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("planId", rs.getLong("plan_id"));
                    m.put("alertType", rs.getString("alert_type"));
                    m.put("message", rs.getString("message"));
                    return m;
                }, planId);
    }

    // ---------------------------------------------------------------- 内部工具

    private long findOrCreatePatient(String patientNo, String patientName) {
        try {
            return jdbc.queryForObject("SELECT id FROM patient WHERE patient_no=?",
                    Long.class, patientNo);
        } catch (EmptyResultDataAccessException e) {
            jdbc.update("INSERT INTO patient (patient_no, patient_name, created_at) VALUES (?,?,?)",
                    patientNo, patientName, now());
            return jdbc.queryForObject("SELECT id FROM patient WHERE patient_no=?",
                    Long.class, patientNo);
        }
    }

    private int nextVersion(long patientId) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version),0) FROM plan WHERE patient_id=?",
                Integer.class, patientId);
        return (max == null ? 0 : max) + 1;
    }

    private long insertPlan(long patientId, int version, BigDecimal totalGy, int fractionCount,
                            BigDecimal fractionDoseGy) {
        jdbc.update(
                "INSERT INTO plan (patient_id, version, status, prescription_total_gy, fraction_count,"
                        + " fraction_dose_gy, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?)",
                patientId, version, PlanStatus.DESIGN.name(), totalGy, fractionCount, fractionDoseGy,
                now(), now());
        return jdbc.queryForObject(
                "SELECT id FROM plan WHERE patient_id=? AND version=?",
                Long.class, patientId, version);
    }

    private void insertOarDoses(long planId, List<OarDose> oarDoses) {
        for (OarDose dose : oarDoses) {
            jdbc.update("INSERT INTO plan_oar_dose (plan_id, organ, dose_gy) VALUES (?,?,?)",
                    planId, dose.organ(), dose.doseGy());
        }
    }

    private PlanRow loadPlan(long planId, boolean forUpdate) {
        try {
            return jdbc.queryForObject(
                    "SELECT * FROM plan WHERE id=?" + (forUpdate ? " FOR UPDATE" : ""),
                    PLAN_MAPPER, planId);
        } catch (EmptyResultDataAccessException e) {
            throw ApiException.notFound("计划不存在: " + planId);
        }
    }

    private void lockPatient(long patientId) {
        jdbc.queryForObject("SELECT id FROM patient WHERE id=? FOR UPDATE", Long.class, patientId);
    }

    private void updateStatus(long planId, PlanStatus status) {
        jdbc.update("UPDATE plan SET status=?, updated_at=? WHERE id=?",
                status.name(), now(), planId);
    }

    private Map<String, Object> findByRequestId(String table, String requestId) {
        List<Map<String, Object>> rows = jdbc.query(
                "SELECT * FROM " + table + " WHERE request_id=?",
                (rs, n) -> rowMap(rs), requestId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> findClosureByPlan(long planId) {
        List<Map<String, Object>> rows = jdbc.query(
                "SELECT * FROM closure_record WHERE plan_id=?",
                (rs, n) -> rowMap(rs), planId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> rowMap(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        var meta = rs.getMetaData();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            row.put(meta.getColumnLabel(i).toLowerCase(), rs.getObject(i));
        }
        return row;
    }

    /** 幂等重放：指纹一致返回首次结果，不一致返回 409 */
    private Map<String, Object> replay(Map<String, Object> stored, String fingerprint,
                                       java.util.function.Function<Map<String, Object>, Map<String, Object>> viewFn) {
        if (!fingerprint.equals(stored.get("fingerprint"))) {
            throw ApiException.conflict("幂等冲突：requestId 相同但业务参数不一致");
        }
        Map<String, Object> view = viewFn.apply(stored);
        view.put("idempotent", true);
        return view;
    }

    private Map<String, Object> planView(PlanRow plan) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("planId", plan.id());
        view.put("patientId", plan.patientId());
        view.put("version", plan.version());
        view.put("status", plan.status().name());
        view.put("prescriptionTotalGy", plan.totalGy());
        view.put("fractionCount", plan.fractionCount());
        view.put("fractionDoseGy", plan.fractionDoseGy());
        view.put("oarDoses", jdbc.query(
                "SELECT organ, dose_gy FROM plan_oar_dose WHERE plan_id=? ORDER BY organ",
                (rs, n) -> Map.of("organ", rs.getString("organ"), "doseGy", rs.getBigDecimal("dose_gy")),
                plan.id()));
        return view;
    }

    private Map<String, Object> reviewView(Map<String, Object> row) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("planId", row.get("plan_id"));
        view.put("requestId", row.get("request_id"));
        view.put("result", row.get("result"));
        view.put("violations", fromJson((String) row.get("violations")));
        return view;
    }

    private Map<String, Object> approvalView(Map<String, Object> row) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("planId", row.get("plan_id"));
        view.put("requestId", row.get("request_id"));
        view.put("status", PlanStatus.APPROVED.name());
        return view;
    }

    private Map<String, Object> verificationView(Map<String, Object> row) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("planId", row.get("plan_id"));
        view.put("requestId", row.get("request_id"));
        view.put("deviationPercent", row.get("deviation_percent"));
        view.put("passed", row.get("passed"));
        return view;
    }

    private Map<String, Object> fractionView(Map<String, Object> row) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("planId", row.get("plan_id"));
        view.put("requestId", row.get("request_id"));
        view.put("fractionNo", row.get("fraction_no"));
        view.put("status", "EXECUTED");
        return view;
    }

    private Map<String, Object> lateEventView(Map<String, Object> row) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("planId", row.get("plan_id"));
        view.put("requestId", row.get("request_id"));
        view.put("fractionNo", row.get("fraction_no"));
        view.put("status", "LATE");
        return view;
    }

    private Map<String, Object> closureView(Map<String, Object> row) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("planId", row.get("plan_id"));
        view.put("requestId", row.get("request_id"));
        view.put("status", PlanStatus.CLOSED.name());
        return view;
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(Instant.now(clock), ZoneOffset.UTC);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    private List<Map<String, Object>> fromJson(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("JSON 反序列化失败", e);
        }
    }

    /** 指纹：SHA-256，覆盖 requestId 与全部业务字段 */
    private static String fingerprint(Object... fields) {
        String raw = Stream.of(fields).map(String::valueOf).collect(Collectors.joining("|"));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
