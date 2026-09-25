package com.example.starter.settlement;

import com.example.starter.api.dto.CommandResponse;
import com.example.starter.api.dto.DailySettlementResponse;
import com.example.starter.api.dto.DailySettlementResponse.ExceedancePeriod;
import com.example.starter.api.dto.HourEvaluationResponse.PollutantAverage;
import com.example.starter.command.CommandRowMapper;
import com.example.starter.core.ApiException;
import com.example.starter.domain.PlanStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 日结封存 JDBC 实现。
 *
 * <p>封存为终态：重复封存幂等返回同一份日结；封存后迟到读数仅登记迟到事件。
 * 日结三项可勾稽复算：实际替代率=当日各评估小时生效替代率快照的算术均值；
 * 超标时段=当日超标小时及其污染物均值；降掺记录=超标小时触发的指令，数量一一对应。</p>
 */
@Service
public class JdbcSettlementService implements SettlementService {

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public JdbcSettlementService(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    @Override
    @Transactional
    public DailySettlementResponse seal(Long planId, LocalDate requestedDate) {
        Map<String, Object> plan = requirePlan(planId);
        String status = (String) plan.get("status");
        LocalDate settleDate = requestedDate != null ? requestedDate : LocalDate.now(clock);

        // 封存终态幂等：无论指定日期是否与首次一致，已封存即回放首次日结。
        if (PlanStatus.SEALED.name().equals(status)) {
            LocalDate firstDate = plan.get("settle_date") == null
                    ? settleDate : ((java.sql.Date) plan.get("settle_date")).toLocalDate();
            return buildSettlement(planId, firstDate, true);
        }
        if (PlanStatus.DECLARED.name().equals(status) || PlanStatus.APPROVED.name().equals(status)
                || PlanStatus.REJECTED.name().equals(status)) {
            throw ApiException.conflict("PLAN_NOT_SETTLEABLE",
                    "仅执行过（EXECUTING/STOPPED/BANNED）的方案可日结封存，当前状态=" + status);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        // 封存同时释放窑口执行槽，允许同窑口次日新方案启动。
        jdbcTemplate.update("DELETE FROM kiln_execution_slot WHERE plan_id = ?", planId);
        jdbcTemplate.update("""
                UPDATE co_firing_plan SET status = ?, settle_date = ?, sealed_at = ? WHERE id = ?
                """, PlanStatus.SEALED.name(), java.sql.Date.valueOf(settleDate),
                Timestamp.valueOf(now), planId);
        return buildSettlement(planId, settleDate, true);
    }

    /**
     * 按当日评估汇总复算三项勾稽数据。
     */
    private DailySettlementResponse buildSettlement(Long planId, LocalDate settleDate, boolean sealed) {
        LocalDateTime dayStart = settleDate.atStartOfDay();
        LocalDateTime dayEnd = settleDate.plusDays(1).atStartOfDay();

        List<Map<String, Object>> summaries = jdbcTemplate.queryForList("""
                SELECT hour_start, exceeded, consecutive_count, effective_ratio, command_id
                  FROM hour_evaluation_summary
                 WHERE plan_id = ? AND hour_start >= ? AND hour_start < ?
                 ORDER BY hour_start
                """, planId, Timestamp.valueOf(dayStart), Timestamp.valueOf(dayEnd));

        BigDecimal ratioSum = BigDecimal.ZERO;
        List<ExceedancePeriod> exceedancePeriods = new ArrayList<>();
        List<CommandResponse> commands = new ArrayList<>();
        for (Map<String, Object> summary : summaries) {
            BigDecimal effectiveRatio = (BigDecimal) summary.get("effective_ratio");
            ratioSum = ratioSum.add(effectiveRatio);
            if (Boolean.TRUE.equals(summary.get("exceeded"))) {
                LocalDateTime hourStart = ((Timestamp) summary.get("hour_start")).toLocalDateTime();
                List<PollutantAverage> averages = jdbcTemplate.query("""
                        SELECT pollutant, average_mg_m3, limit_mg_m3, sample_count, exceeded
                          FROM hour_evaluation
                         WHERE plan_id = ? AND hour_start = ?
                         ORDER BY pollutant
                        """, (rs, n) -> new PollutantAverage(
                                rs.getString("pollutant"),
                                rs.getBigDecimal("average_mg_m3"),
                                rs.getBigDecimal("limit_mg_m3"),
                                rs.getBoolean("exceeded"),
                                rs.getInt("sample_count")),
                        planId, Timestamp.valueOf(hourStart));
                exceedancePeriods.add(new ExceedancePeriod(hourStart.toString(), averages));
                Long commandId = summary.get("command_id") == null
                        ? null : ((Number) summary.get("command_id")).longValue();
                if (commandId != null) {
                    List<CommandResponse> rows = jdbcTemplate.query(
                            "SELECT * FROM reduction_command WHERE id = ?", CommandRowMapper.INSTANCE, commandId);
                    rows.stream().findFirst().ifPresent(commands::add);
                }
            }
        }
        int evaluatedHours = summaries.size();
        BigDecimal actualHeatRatio = evaluatedHours == 0 ? null
                : ratioSum.divide(BigDecimal.valueOf(evaluatedHours), 4, RoundingMode.HALF_UP);
        return new DailySettlementResponse(planId, settleDate, actualHeatRatio, evaluatedHours,
                exceedancePeriods, commands, sealed);
    }

    private Map<String, Object> requirePlan(Long planId) {
        List<Map<String, Object>> plans = jdbcTemplate.queryForList(
                "SELECT id, status, settle_date FROM co_firing_plan WHERE id = ?", planId);
        if (plans.isEmpty()) {
            throw ApiException.notFound("PLAN_NOT_FOUND", "方案不存在：id=" + planId);
        }
        return plans.get(0);
    }
}
