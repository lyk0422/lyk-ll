package com.example.starter.plan;

import com.example.starter.api.dto.DeclarePlanRequest;
import com.example.starter.api.dto.FuelComponentRequest;
import com.example.starter.api.dto.PlanResponse;
import com.example.starter.api.dto.ReviewDecision;
import com.example.starter.api.dto.ReviewRequest;
import com.example.starter.config.CementProperties;
import com.example.starter.core.ApiException;
import com.example.starter.domain.PlanStatus;
import com.example.starter.license.LicenseService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 掺烧方案流转 JDBC 实现：申报→审核（替代率/加权氯硫/许可整单校验）→执行（窑口槽原子切换）。
 */
@Service
public class JdbcPlanService implements PlanService {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final JdbcTemplate jdbcTemplate;
    private final LicenseService licenseService;
    private final CementProperties properties;
    private final Clock clock;

    public JdbcPlanService(JdbcTemplate jdbcTemplate,
                           LicenseService licenseService,
                           CementProperties properties,
                           Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.licenseService = licenseService;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    @Transactional
    public PlanResponse declare(DeclarePlanRequest request) {
        LocalDateTime now = LocalDateTime.now(clock);
        if (request.floorHeatRatio().compareTo(request.declaredHeatRatio()) > 0) {
            throw ApiException.rejected("FLOOR_RATIO_INVALID",
                    "保底替代率不得高于申报替代率，整单拒绝");
        }
        WeightedTotals totals = weightFuels(request.fuels());

        Long planId = insertPlan(request, totals, now);
        insertFuels(planId, request.fuels());
        return get(planId);
    }

    @Override
    @Transactional
    public PlanResponse review(Long planId, ReviewRequest request) {
        PlanResponse plan = get(planId);
        if (!PlanStatus.DECLARED.name().equals(plan.status())) {
            throw ApiException.conflict("PLAN_STATUS_CONFLICT",
                    "仅 DECLARED 状态方案可审核，当前状态=" + plan.status());
        }
        LocalDate today = LocalDate.now(clock);
        if (request.decision() == ReviewDecision.REJECT) {
            String reason = request.reason() == null || request.reason().isBlank()
                    ? "人工审核拒绝" : request.reason();
            return reject(planId, reason);
        }
        // APPROVE 仍强制执行替代率/氯硫/许可校验，任一不过整单拒绝。
        String violation = validate(plan, today);
        if (violation != null) {
            return reject(planId, violation);
        }        LocalDateTime now = LocalDateTime.now(clock);
        jdbcTemplate.update("UPDATE co_firing_plan SET status = ?, approved_at = ? WHERE id = ?",
                PlanStatus.APPROVED.name(), Timestamp.valueOf(now), planId);
        return get(planId);
    }

    @Override
    @Transactional
    public PlanResponse execute(Long planId) {
        PlanResponse plan = get(planId);
        if (!PlanStatus.APPROVED.name().equals(plan.status())) {
            throw ApiException.conflict("PLAN_STATUS_CONFLICT",
                    "仅 APPROVED 状态方案可执行，当前状态=" + plan.status());
        }
        LocalDate today = LocalDate.now(clock);
        Integer banCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM daily_ban WHERE kiln_id = ? AND ban_date = ?",
                Integer.class, plan.kilnId(), today);
        if (banCount != null && banCount > 0) {
            throw ApiException.conflict("KILN_BANNED_TODAY",
                    "窑口 " + plan.kilnId() + " 已于 " + today + " 触发当日禁烧，禁止掺烧");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        // 方案切换先停旧后启新，三步在同一事务内完成：
        // 1) MERGE 抢占/更新窑口槽行（同窑行锁串行化并发切换）；
        // 2) 将同窑其他执行中方案置 STOPPED（先停旧）；
        // 3) 当前方案置 EXECUTING（后启新），保证同窑同时刻至多一个执行中方案。
        jdbcTemplate.update("""
                MERGE INTO kiln_execution_slot (kiln_id, plan_id) KEY (kiln_id) VALUES (?, ?)
                """, plan.kilnId(), planId);
        jdbcTemplate.update("""
                UPDATE co_firing_plan SET status = ?, stopped_at = ?
                 WHERE status = ? AND kiln_id = ? AND id <> ?
                """, PlanStatus.STOPPED.name(), Timestamp.valueOf(now),
                PlanStatus.EXECUTING.name(), plan.kilnId(), planId);
        jdbcTemplate.update("UPDATE co_firing_plan SET status = ?, executed_at = ? WHERE id = ?",
                PlanStatus.EXECUTING.name(), Timestamp.valueOf(now), planId);
        return get(planId);
    }

    @Override
    public PlanResponse get(Long planId) {
        List<PlanResponse> plans = jdbcTemplate.query(
                "SELECT * FROM co_firing_plan WHERE id = ?", PlanRowMapper.INSTANCE, planId);
        if (plans.isEmpty()) {
            throw ApiException.notFound("PLAN_NOT_FOUND", "方案不存在：id=" + planId);
        }
        return plans.get(0);
    }

    /**
     * 审核硬性校验，返回拒绝原因；全部通过返回 null。许可失效直接抛整单拒绝。
     */
    private String validate(PlanResponse plan, LocalDate reviewDate) {
        if (plan.declaredHeatRatio().compareTo(properties.maxHeatRatio()) > 0) {
            return "替代燃料热量替代率 " + plan.declaredHeatRatio()
                    + " 超过上限 " + properties.maxHeatRatio() + "，整单拒绝";
        }
        if (plan.chlorineKgH() != null && plan.chlorineKgH().compareTo(properties.chlorineLimitKgH()) > 0) {
            return "加权入窑氯总量 " + plan.chlorineKgH() + " kg/h 超过限值 "
                    + properties.chlorineLimitKgH() + " kg/h，整单拒绝";
        }
        if (plan.sulfurKgH() != null && plan.sulfurKgH().compareTo(properties.sulfurLimitKgH()) > 0) {
            return "加权入窑硫总量 " + plan.sulfurKgH() + " kg/h 超过限值 "
                    + properties.sulfurLimitKgH() + " kg/h，整单拒绝";
        }
        try {
            licenseService.requireValid(plan.licenseNo(), reviewDate);
        } catch (ApiException e) {
            // 许可失效（不存在/未生效/过期/吊销）统一落为整单拒绝终态。
            return e.getMessage();
        }
        return null;
    }

    /**
     * 落库整单拒绝终态并返回视图；HTTP 422 的抛出由控制器在事务提交后完成，避免回滚终态。
     */
    private PlanResponse reject(Long planId, String reason) {
        jdbcTemplate.update("UPDATE co_firing_plan SET status = ?, reject_reason = ? WHERE id = ?",
                PlanStatus.REJECTED.name(), reason, planId);
        return get(planId);
    }

    private Long insertPlan(DeclarePlanRequest request, WeightedTotals totals, LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var ps = connection.prepareStatement("""
                    INSERT INTO co_firing_plan (kiln_id, license_no, status, declared_heat_ratio,
                        current_heat_ratio, floor_heat_ratio, chlorine_kg_h, sulfur_kg_h,
                        declare_request_id, declared_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, new String[]{"id"});
            ps.setString(1, request.kilnId());
            ps.setString(2, request.licenseNo());
            ps.setString(3, PlanStatus.DECLARED.name());
            ps.setBigDecimal(4, request.declaredHeatRatio());
            ps.setBigDecimal(5, request.declaredHeatRatio());
            ps.setBigDecimal(6, request.floorHeatRatio());
            ps.setBigDecimal(7, totals.chlorineKgH());
            ps.setBigDecimal(8, totals.sulfurKgH());
            ps.setString(9, request.requestId());
            ps.setTimestamp(10, Timestamp.valueOf(now));
            return ps;
        }, keyHolder);
        return keyHolder.getKeyAs(Long.class);
    }

    private void insertFuels(Long planId, List<FuelComponentRequest> fuels) {
        for (FuelComponentRequest fuel : fuels) {
            jdbcTemplate.update("""
                    INSERT INTO plan_fuel (plan_id, fuel_name, alternative, feed_rate_kg_h,
                        heat_value_kcal_kg, chlorine_pct, sulfur_pct)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, planId, fuel.fuelName(), Boolean.TRUE.equals(fuel.alternative()),
                    fuel.feedRateKgH(), fuel.heatValueKcalKg(),
                    fuel.chlorineContentPct(), fuel.sulfurContentPct());
        }
    }

    /**
     * 按投料量加权计算入窑氯硫总量：元素流量 = Σ 投料量(kg/h) × 质量分数(%) / 100，单位 kg/h。
     */
    private WeightedTotals weightFuels(List<FuelComponentRequest> fuels) {
        BigDecimal chlorine = BigDecimal.ZERO;
        BigDecimal sulfur = BigDecimal.ZERO;
        for (FuelComponentRequest fuel : fuels) {
            chlorine = chlorine.add(fuel.feedRateKgH()
                    .multiply(fuel.chlorineContentPct()).divide(HUNDRED, 4, RoundingMode.HALF_UP));
            sulfur = sulfur.add(fuel.feedRateKgH()
                    .multiply(fuel.sulfurContentPct()).divide(HUNDRED, 4, RoundingMode.HALF_UP));
        }
        return new WeightedTotals(chlorine, sulfur);
    }

    private record WeightedTotals(BigDecimal chlorineKgH, BigDecimal sulfurKgH) {
    }
}
