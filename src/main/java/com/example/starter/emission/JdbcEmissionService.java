package com.example.starter.emission;

import com.example.starter.api.dto.CommandResponse;
import com.example.starter.api.dto.HourEvaluationResponse;
import com.example.starter.api.dto.HourEvaluationResponse.PollutantAverage;
import com.example.starter.api.dto.ReadingBatchRequest;
import com.example.starter.api.dto.ReadingIngestResult;
import com.example.starter.command.CommandRowMapper;
import com.example.starter.command.ReductionCommandRepository;
import com.example.starter.config.CementProperties;
import com.example.starter.core.ApiException;
import com.example.starter.domain.PlanStatus;
import com.example.starter.domain.Pollutant;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 排放读数接入与小时评估 JDBC 实现。
 */
@Service
public class JdbcEmissionService implements EmissionService {

    private final JdbcTemplate jdbcTemplate;
    private final ReductionCommandRepository commandRepository;
    private final CementProperties properties;
    private final Clock clock;

    public JdbcEmissionService(JdbcTemplate jdbcTemplate,
                               ReductionCommandRepository commandRepository,
                               CementProperties properties,
                               Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.commandRepository = commandRepository;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    @Transactional
    public ReadingIngestResult ingest(Long planId, ReadingBatchRequest request) {
        PlanBrief plan = requirePlan(planId);
        LocalDateTime now = LocalDateTime.now(clock);
        int accepted = 0;
        int duplicated = 0;
        int late = 0;
        for (var reading : request.readings()) {
            Pollutant pollutant = parsePollutant(reading.pollutant());
            if (PlanStatus.SEALED.name().equals(plan.status())
                    || PlanStatus.BANNED.name().equals(plan.status())) {
                // 封存（或禁烧终态）后迟到读数只登记迟到事件，不进入评估与日结。
                jdbcTemplate.update("""
                        INSERT INTO late_reading_event (plan_id, point_code, pollutant, sampled_at,
                            value_mg_m3, received_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """, planId, reading.pointCode(), pollutant.name(),
                        Timestamp.valueOf(reading.sampledAt()), reading.valueMgM3(), Timestamp.valueOf(now));
                late++;
                continue;
            }
            try {
                jdbcTemplate.update("""
                        INSERT INTO emission_reading (plan_id, point_code, pollutant, sampled_at,
                            value_mg_m3, ingested_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """, planId, reading.pointCode(), pollutant.name(),
                        Timestamp.valueOf(reading.sampledAt()), reading.valueMgM3(), Timestamp.valueOf(now));
                accepted++;
            } catch (DuplicateKeyException e) {
                // 测点＋采样时刻去重：乱序/重复上报直接忽略，保留首条读数。
                duplicated++;
            }
        }
        return new ReadingIngestResult(accepted, duplicated, late);
    }

    @Override
    @Transactional
    public HourEvaluationResponse evaluate(Long planId, LocalDateTime hourStart) {
        if (hourStart.getMinute() != 0 || hourStart.getSecond() != 0 || hourStart.getNano() != 0) {
            throw new IllegalArgumentException("hourStart 必须为自然小时起点（分秒为 00:00）");
        }
        PlanBrief plan = requirePlan(planId);
        // 同一方案同一小时评估幂等：重复评估直接回放首次结果（含已禁烧/封存的历史小时），不重复触发指令。
        if (findSummary(planId, hourStart) != null) {
            return assembleResponse(planId, hourStart);
        }
        if (PlanStatus.SEALED.name().equals(plan.status())) {
            throw ApiException.conflict("PLAN_SEALED", "方案已封存，不再进行小时评估：planId=" + planId);
        }
        if (PlanStatus.BANNED.name().equals(plan.status())) {
            throw ApiException.conflict("PLAN_BANNED", "方案已当日禁烧，不再进行小时评估：planId=" + planId);
        }
        if (!PlanStatus.EXECUTING.name().equals(plan.status())) {
            throw ApiException.conflict("PLAN_NOT_EXECUTING",
                    "仅 EXECUTING 状态方案可进行小时评估，当前状态=" + plan.status());
        }

        LocalDateTime hourEnd = hourStart.plusHours(1);
        List<PollutantAverage> averages = new ArrayList<>();
        boolean anyExceeded = false;
        for (Pollutant pollutant : Pollutant.values()) {
            BigDecimal limit = limitOf(pollutant);
            Map<String, Object> agg = jdbcTemplate.queryForMap("""
                    SELECT AVG(value_mg_m3) AS avg_value, COUNT(1) AS cnt
                      FROM emission_reading
                     WHERE plan_id = ? AND pollutant = ?
                       AND sampled_at >= ? AND sampled_at < ?
                    """, planId, pollutant.name(),
                    Timestamp.valueOf(hourStart), Timestamp.valueOf(hourEnd));
            int sampleCount = ((Number) agg.get("cnt")).intValue();
            if (sampleCount == 0) {
                continue;
            }
            BigDecimal average = ((BigDecimal) agg.get("avg_value")).setScale(3, RoundingMode.HALF_UP);
            // 恰等于标准限值判达标：严格大于限值才算超标。
            boolean exceeded = average.compareTo(limit) > 0;
            anyExceeded |= exceeded;
            averages.add(new PollutantAverage(pollutant.name(), average, limit, exceeded, sampleCount));
            jdbcTemplate.update("""
                    INSERT INTO hour_evaluation (plan_id, hour_start, pollutant, average_mg_m3,
                        limit_mg_m3, sample_count, exceeded)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, planId, Timestamp.valueOf(hourStart), pollutant.name(),
                    average, limit, sampleCount, exceeded);
        }

        BigDecimal effectiveRatio = plan.currentHeatRatio();
        CommandResponse command = null;
        Long commandId = null;
        boolean banned = false;
        int consecutive;
        LocalDateTime now = LocalDateTime.now(clock);
        if (anyExceeded) {
            consecutive = resolveConsecutiveCount(planId, hourStart);
            // 小时均值超标即触发降掺：按步长下调，不低于保底比例。
            BigDecimal target = plan.currentHeatRatio().subtract(properties.reductionStep())
                    .max(plan.floorHeatRatio());
            command = commandRepository.create(planId, hourStart, target, now);
            commandId = command.id();
            jdbcTemplate.update("UPDATE co_firing_plan SET current_heat_ratio = ? WHERE id = ?",
                    target, planId);
            if (consecutive >= properties.consecutiveExceedanceBan()) {
                // 恰连续 3 次：当日禁烧，方案置 BANNED 终态并释放窑口执行槽。
                ban(plan, hourStart.toLocalDate(), now);
                banned = true;
            }
        } else {
            consecutive = 0;
        }
        jdbcTemplate.update("""
                INSERT INTO hour_evaluation_summary (plan_id, hour_start, exceeded, consecutive_count,
                    effective_ratio, command_id, triggered_ban)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, planId, Timestamp.valueOf(hourStart), anyExceeded, consecutive,
                effectiveRatio, commandId == null ? null : commandId.longValue(), banned);
        return new HourEvaluationResponse(planId, hourStart, averages, anyExceeded,
                consecutive, banned, command);
    }

    /**
     * 连续超标计数：仅认定紧邻的自然小时（上一小时存在汇总且超标）才连续，否则从 1 起计。
     */
    private int resolveConsecutiveCount(Long planId, LocalDateTime hourStart) {
        Map<String, Object> previous = findSummary(planId, hourStart.minusHours(1));
        if (previous != null && Boolean.TRUE.equals(previous.get("exceeded"))) {
            return ((Number) previous.get("consecutive_count")).intValue() + 1;
        }
        return 1;
    }

    private void ban(PlanBrief plan, LocalDate banDate, LocalDateTime now) {
        jdbcTemplate.update("DELETE FROM kiln_execution_slot WHERE kiln_id = ?", plan.kilnId());
        jdbcTemplate.update("""
                UPDATE co_firing_plan SET status = ?, banned_at = ?, ban_date = ? WHERE id = ?
                """, PlanStatus.BANNED.name(), Timestamp.valueOf(now), java.sql.Date.valueOf(banDate), plan.id());
        try {
            jdbcTemplate.update("""
                    INSERT INTO daily_ban (kiln_id, ban_date, triggered_plan_id, created_at)
                    VALUES (?, ?, ?, ?)
                    """, plan.kilnId(), java.sql.Date.valueOf(banDate), plan.id(), Timestamp.valueOf(now));
        } catch (DuplicateKeyException e) {
            // 当日已有禁烧标记时保持既有标记，仍完成本方案终态。
        }
    }

    private HourEvaluationResponse assembleResponse(Long planId, LocalDateTime hourStart) {
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
        Map<String, Object> summary = findSummary(planId, hourStart);
        boolean exceeded = Boolean.TRUE.equals(summary.get("exceeded"));
        int consecutive = ((Number) summary.get("consecutive_count")).intValue();
        boolean banned = Boolean.TRUE.equals(summary.get("triggered_ban"));
        Long commandId = summary.get("command_id") == null ? null : ((Number) summary.get("command_id")).longValue();
        CommandResponse command = null;
        if (commandId != null) {
            List<CommandResponse> commands = jdbcTemplate.query(
                    "SELECT * FROM reduction_command WHERE id = ?", CommandRowMapper.INSTANCE, commandId);
            if (!commands.isEmpty()) {
                command = commands.get(0);
            }
        }
        return new HourEvaluationResponse(planId, hourStart, averages, exceeded, consecutive, banned, command);
    }

    private Map<String, Object> findSummary(Long planId, LocalDateTime hourStart) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT exceeded, consecutive_count, effective_ratio, command_id, triggered_ban
                  FROM hour_evaluation_summary
                 WHERE plan_id = ? AND hour_start = ?
                """, planId, Timestamp.valueOf(hourStart));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private BigDecimal limitOf(Pollutant pollutant) {
        BigDecimal configured = properties.emissionLimits().get(pollutant.name());
        return configured != null ? configured : pollutant.defaultLimitMgM3();
    }

    private Pollutant parsePollutant(String raw) {
        try {
            return Pollutant.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("不支持的污染物类型：" + raw);
        }
    }

    private PlanBrief requirePlan(Long planId) {
        List<PlanBrief> plans = jdbcTemplate.query(
                "SELECT id, kiln_id, status, current_heat_ratio, floor_heat_ratio FROM co_firing_plan WHERE id = ?",
                (rs, n) -> new PlanBrief(rs.getLong("id"), rs.getString("kiln_id"),
                        rs.getString("status"), rs.getBigDecimal("current_heat_ratio"),
                        rs.getBigDecimal("floor_heat_ratio")),
                planId);
        if (plans.isEmpty()) {
            throw ApiException.notFound("PLAN_NOT_FOUND", "方案不存在：id=" + planId);
        }
        return plans.get(0);
    }

    private record PlanBrief(Long id, String kilnId, String status,
                             BigDecimal currentHeatRatio, BigDecimal floorHeatRatio) {
    }
}
