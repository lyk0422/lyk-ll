package com.example.starter.service;

import com.example.starter.domain.EvaluationPhase;
import com.example.starter.domain.TaskStatus;
import com.example.starter.domain.ValveAction;
import com.example.starter.dto.AdjustRequest;
import com.example.starter.dto.AdjustStep;
import com.example.starter.dto.AdjustResponse;
import com.example.starter.dto.ArchiveRequest;
import com.example.starter.dto.ArchiveResponse;
import com.example.starter.dto.EvaluateRequest;
import com.example.starter.dto.EvaluationResponse;
import com.example.starter.dto.StepResult;
import com.example.starter.dto.TemperatureReadingItem;
import com.example.starter.dto.TemperatureReportRequest;
import com.example.starter.dto.TemperatureReportResponse;
import com.example.starter.dto.UnitEvaluation;
import com.example.starter.po.AdjustmentTask;
import com.example.starter.po.HeatStation;
import com.example.starter.po.HeatingUnit;
import com.example.starter.po.Valve;
import com.example.starter.repo.AdjustmentRecordRepository;
import com.example.starter.repo.CatalogRepository;
import com.example.starter.repo.EvaluationRepository;
import com.example.starter.repo.MiscEventRepository;
import com.example.starter.repo.TaskRepository;
import com.example.starter.repo.TemperatureRepository;
import com.example.starter.repo.ValveVersionRepository;
import com.example.starter.support.AdjustmentGate;
import com.example.starter.web.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 热网水力平衡核心业务：温度上报、步进调节、失衡判定、效果评估、归档。
 *
 * <p>关键规则（边界均含等号）：</p>
 * <ul>
 *   <li>同一换热站各单元回水温度最高最低差 ≤ 2℃ 判平衡，恰 2℃ 合格；</li>
 *   <li>单次阀门步进 ≤ 10%，恰 10% 合法；开度始终在 [0,100]；</li>
 *   <li>效果评估距最近一次调节须稳定 ≥ 4 小时，恰满 4 小时允许；</li>
 *   <li>同一单元 24 小时(UTC)内最多调节 3 次，恰 3 次合法，超出转人工复核；</li>
 *   <li>阀门开度更新与版本写入同一事务，原子提交。</li>
 * </ul>
 */
@Service
public class BalanceService {

    /** 平衡温差阈值，单位:℃（含等号，恰 2℃ 合格） */
    public static final BigDecimal BALANCE_DIFF_LIMIT = new BigDecimal("2.000");
    /** 单次步进上限，单位:%（含等号，恰 10% 合法） */
    public static final BigDecimal MAX_STEP_DELTA = new BigDecimal("10.000");
    /** 效果评估前稳定时长 */
    public static final Duration STABLE_DURATION = Duration.ofHours(4);
    /** 单元调节次数统计窗口 */
    public static final Duration ADJUST_WINDOW = Duration.ofHours(24);
    /** 窗口内最大调节次数（含等号，恰 3 次合法） */
    public static final int MAX_ADJUSTMENTS_PER_WINDOW = 3;
    private static final BigDecimal OPENING_MIN = BigDecimal.ZERO;
    private static final BigDecimal OPENING_MAX = new BigDecimal("100.000");

    private final CatalogRepository catalog;
    private final TaskRepository tasks;
    private final ValveVersionRepository versions;
    private final AdjustmentRecordRepository records;
    private final TemperatureRepository temperatures;
    private final EvaluationRepository evaluations;
    private final MiscEventRepository events;
    private final IdempotencyExecutor idempotency;
    private final FingerprintFactory fingerprints;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final AdjustmentGate adjustmentGate;
    private final TransactionTemplate tx;

    public BalanceService(CatalogRepository catalog, TaskRepository tasks, ValveVersionRepository versions,
                          AdjustmentRecordRepository records, TemperatureRepository temperatures,
                          EvaluationRepository evaluations, MiscEventRepository events,
                          IdempotencyExecutor idempotency, FingerprintFactory fingerprints,
                          ObjectMapper objectMapper, Clock clock, AdjustmentGate adjustmentGate,
                          PlatformTransactionManager txManager) {
        this.catalog = catalog;
        this.tasks = tasks;
        this.versions = versions;
        this.records = records;
        this.temperatures = temperatures;
        this.evaluations = evaluations;
        this.events = events;
        this.idempotency = idempotency;
        this.fingerprints = fingerprints;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.adjustmentGate = adjustmentGate;
        this.tx = new TransactionTemplate(txManager);
    }

    // ============================ 温度上报 ============================

    public TemperatureReportResponse reportTemperature(TemperatureReportRequest request) {
        HeatStation station = requireStation(request.stationCode());
        String fingerprint = fingerprints.temperature(request);
        return idempotency.execute(request.requestId(), com.example.starter.domain.RequestType.TEMPERATURE,
                fingerprint, station.getId(), TemperatureReportResponse.class,
                () -> doReportTemperature(request, station));
    }

    private TemperatureReportResponse doReportTemperature(TemperatureReportRequest request, HeatStation station) {
        Instant now = Instant.now(clock);
        AdjustmentTask latest = tasks.findByStationAny(station.getId());
        boolean archived = latest != null && latest.getStatus() == TaskStatus.ARCHIVED;

        List<TemperatureReadingItem> items = request.readings();
        if (archived) {
            // 归档终态后到达的温度数据只记迟到事件，不参与评估
            return tx.execute(status -> {
                for (TemperatureReadingItem item : items) {
                    HeatingUnit unit = catalog.findUnit(station.getId(), item.unitCode());
                    events.insertLateEvent(station.getId(), unit == null ? null : unit.getId(),
                            request.requestId(), writeJson(item), item.observedAt(), now, now);
                }
                return new TemperatureReportResponse(request.requestId(), 0, true, now, false);
            });
        }

        return tx.execute(status -> {
            for (TemperatureReadingItem item : items) {
                HeatingUnit unit = catalog.findUnit(station.getId(), item.unitCode());
                if (unit == null) {
                    throw ApiException.notFound("换热站 " + request.stationCode()
                            + " 下不存在单元 " + item.unitCode());
                }
                temperatures.insert(unit.getId(), station.getId(),
                        item.supplyTemp(), item.returnTemp(), item.observedAt(), now, now);
            }
            return new TemperatureReportResponse(request.requestId(), items.size(), false, now, false);
        });
    }

    // ============================ 步进调节 ============================

    public AdjustResponse adjust(AdjustRequest request) {
        HeatStation station = requireStation(request.stationCode());
        String fingerprint = fingerprints.adjust(request);
        return idempotency.execute(request.requestId(), com.example.starter.domain.RequestType.ADJUST,
                fingerprint, station.getId(), AdjustResponse.class,
                () -> doAdjust(request, station));
    }

    private AdjustResponse doAdjust(AdjustRequest request, HeatStation station) {
        Instant now = Instant.now(clock);
        return tx.execute(status -> {
            adjustmentGate.rendezvous();
            AdjustmentTask task = tasks.findByStationActive(station.getId());
            adjustmentGate.rendezvous();
            boolean created = false;
            if (task == null) {
                AdjustmentTask latest = tasks.findByStationAny(station.getId());
                if (latest != null && latest.getStatus() == TaskStatus.ARCHIVED) {
                    throw ApiException.unprocessable("STATION_ARCHIVED",
                            "换热站任务已归档为终态，不能再下达调节令");
                }
                task = tasks.createExecuting(station.getId(), request.requestId(), now);
                if (task == null) {
                    // 并发下达：唯一约束保证同一换热站只受理一个执行中任务
                    throw ApiException.conflict("STATION_BUSY",
                            "该换热站已有执行中的调节任务，并发调节令唯一受理，本次未执行");
                }
                created = true;
            }

            // 第一步：锁定全部涉及阀门并做规则校验，任何一条不满足则整批不执行
            Map<Long, Integer> plannedCountByUnit = new LinkedHashMap<>();
            List<ValidatedStep> validated = new ArrayList<>();
            for (AdjustStep step : request.steps()) {
                HeatingUnit unit = catalog.findUnit(station.getId(), step.unitCode());
                if (unit == null) {
                    throw ApiException.notFound("换热站 " + request.stationCode()
                            + " 下不存在单元 " + step.unitCode());
                }
                Valve valve = catalog.findValveByUnitForUpdate(unit.getId());
                if (valve == null) {
                    throw ApiException.notFound("单元 " + step.unitCode() + " 未配置阀门");
                }
                if (step.delta().compareTo(MAX_STEP_DELTA) > 0) {
                    throw ApiException.unprocessable("STEP_TOO_LARGE",
                            "单次调整幅度不得超过10%，实际 " + step.delta().toPlainString() + "%");
                }
                BigDecimal toOpening = step.action() == ValveAction.OPEN
                        ? valve.getOpening().add(step.delta())
                        : valve.getOpening().subtract(step.delta());
                if (toOpening.compareTo(OPENING_MIN) < 0 || toOpening.compareTo(OPENING_MAX) > 0) {
                    throw ApiException.unprocessable("VALVE_LIMIT",
                            "单元 " + step.unitCode() + " 调节后开度 " + toOpening.toPlainString()
                                    + "% 超出 [0,100] 区间");
                }
                int planned = plannedCountByUnit.getOrDefault(unit.getId(), 0) + 1;
                plannedCountByUnit.put(unit.getId(), planned);
                validated.add(new ValidatedStep(unit, valve, step, toOpening));
            }

            // 24 小时(UTC)窗口次数校验：已发生次数 + 本批次数 ≤ 3，超出转人工复核
            Instant windowStart = now.minus(ADJUST_WINDOW);
            List<Long> reviewUnitIds = new ArrayList<>();
            for (Map.Entry<Long, Integer> entry : plannedCountByUnit.entrySet()) {
                int existed = records.countByUnitSince(entry.getKey(), windowStart);
                if (existed + entry.getValue() > MAX_ADJUSTMENTS_PER_WINDOW) {
                    reviewUnitIds.add(entry.getKey());
                }
            }
            if (!reviewUnitIds.isEmpty()) {
                registerManualReviews(station.getId(), reviewUnitIds, request.requestId(), now, windowStart);
                throw ApiException.unprocessable("MANUAL_REVIEW",
                        "单元 24 小时内调节次数将超过 3 次，已转人工复核，本次调节未执行");
            }

            // 第二步：逐步进执行；开度更新与版本写入在本事务内原子完成
            List<StepResult> results = new ArrayList<>();
            Map<Long, Integer> stepIndexByUnit = new LinkedHashMap<>();
            for (ValidatedStep vs : validated) {
                BigDecimal from = vs.valve.getOpening();
                int versionNo = versions.maxVersionNo(vs.valve.getId()) + 1;
                versions.append(vs.valve.getId(), versionNo, from, vs.toOpening, vs.step.delta(),
                        vs.step.action(), task.getId(), request.requestId(), now);
                catalog.updateValveOpening(vs.valve.getId(), vs.toOpening, now);
                int stepIndex = stepIndexByUnit.getOrDefault(vs.unit.getId(), 0) + 1;
                stepIndexByUnit.put(vs.unit.getId(), stepIndex);
                records.insert(task.getId(), vs.unit.getId(), vs.valve.getId(), request.requestId(),
                        stepIndex, vs.step.action(), from, vs.toOpening, vs.step.delta(), now);
                results.add(new StepResult(vs.unit.getUnitCode(), vs.valve.getValveCode(),
                        vs.step.action().name(), scale(from), scale(vs.toOpening),
                        scale(vs.step.delta()), versionNo));
            }
            return new AdjustResponse(request.requestId(), task.getId(), created, results, now, false);
        });
    }

    /**
     * 人工复核记录以独立事务持久化，不受随后业务回滚影响。
     */
    private void registerManualReviews(long stationId, List<Long> unitIds, String requestId,
                                       Instant now, Instant windowStart) {
        TransactionTemplate requiresNew = new TransactionTemplate(tx.getTransactionManager());
        requiresNew.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        requiresNew.executeWithoutResult(status -> {
            for (Long unitId : unitIds) {
                int existed = records.countByUnitSince(unitId, windowStart);
                events.insertManualReview(stationId, unitId, requestId, existed,
                        windowStart, now, "24小时内调节次数超出3次上限", now);
            }
        });
    }

    // ============================ 失衡判定 / 效果评估 ============================

    public EvaluationResponse evaluate(EvaluateRequest request) {
        HeatStation station = requireStation(request.stationCode());
        EvaluationPhase phase;
        try {
            phase = EvaluationPhase.valueOf(request.phase());
        } catch (IllegalArgumentException e) {
            throw ApiException.unprocessable("INVALID_PHASE",
                    "phase 仅支持 JUDGMENT（失衡判定）或 RE_EVAL（效果评估）");
        }
        String fingerprint = fingerprints.evaluate(request);
        return idempotency.execute(request.requestId(), com.example.starter.domain.RequestType.EVALUATE,
                fingerprint, station.getId(), EvaluationResponse.class,
                () -> doEvaluate(request, station, phase));
    }

    private EvaluationResponse doEvaluate(EvaluateRequest request, HeatStation station, EvaluationPhase phase) {
        Instant now = Instant.now(clock);
        return tx.execute(status -> {
            AdjustmentTask task = tasks.findByStationAny(station.getId());
            if (task == null) {
                throw ApiException.unprocessable("NO_TASK", "该换热站尚无调节任务，无法评估");
            }
            if (task.getStatus() == TaskStatus.ARCHIVED) {
                throw ApiException.unprocessable("STATION_ARCHIVED", "任务已归档为终态，不能再评估");
            }
            if (phase == EvaluationPhase.RE_EVAL) {
                Instant latestChange = versions.findLatestChangeAt(task.getId());
                if (latestChange == null) {
                    throw ApiException.unprocessable("NO_ADJUSTMENT", "任务尚未发生任何调节，无法做效果评估");
                }
                // 恰满 4 小时允许评估
                if (Duration.between(latestChange, now).compareTo(STABLE_DURATION) < 0) {
                    long minutesLeft = STABLE_DURATION.minus(Duration.between(latestChange, now)).toMinutes();
                    throw ApiException.unprocessable("STABILIZING",
                            "调节后须稳定满 4 小时再评估，距可评估还剩约 " + Math.max(1, minutesLeft) + " 分钟");
                }
            }

            List<HeatingUnit> units = catalog.findUnitsByStation(station.getId());
            Map<Long, BigDecimal> latestTempByUnit = loadLatestTemps(station.getId(), now);
            if (latestTempByUnit.size() < units.size()) {
                throw ApiException.unprocessable("INSUFFICIENT_DATA",
                        "存在单元缺少温度数据，需全部单元都有回水温度才能评估");
            }

            BigDecimal max = null;
            BigDecimal min = null;
            BigDecimal sum = BigDecimal.ZERO;
            for (HeatingUnit u : units) {
                BigDecimal t = latestTempByUnit.get(u.getId());
                if (max == null || t.compareTo(max) > 0) {
                    max = t;
                }
                if (min == null || t.compareTo(min) < 0) {
                    min = t;
                }
                sum = sum.add(t);
            }
            BigDecimal avg = sum.divide(BigDecimal.valueOf(units.size()), 3, RoundingMode.HALF_UP);
            BigDecimal diff = scale(max.subtract(min));
            boolean balanced = diff.compareTo(BALANCE_DIFF_LIMIT) <= 0;

            List<String> underFlow = new ArrayList<>();
            List<String> overFlow = new ArrayList<>();
            List<UnitEvaluation> details = new ArrayList<>();
            for (HeatingUnit u : units) {
                BigDecimal t = latestTempByUnit.get(u.getId());
                BigDecimal deviation = scale(t.subtract(avg));
                String type;
                if (balanced) {
                    type = "BALANCED";
                } else if (deviation.compareTo(BigDecimal.ZERO) > 0) {
                    // 回水温度高于均值：流量过大为过流，需关小
                    type = "OVER_FLOW";
                    overFlow.add(u.getUnitCode());
                } else if (deviation.compareTo(BigDecimal.ZERO) < 0) {
                    // 回水温度低于均值：流量不足为欠流，需开大
                    type = "UNDER_FLOW";
                    underFlow.add(u.getUnitCode());
                } else {
                    type = "BALANCED";
                }
                details.add(new UnitEvaluation(u.getUnitCode(), scale(t), deviation, type));
            }

            evaluations.insert(task.getId(), station.getId(), request.requestId(), phase,
                    scale(max), scale(min), diff, balanced, writeJson(details), now);

            return new EvaluationResponse(request.requestId(), task.getId(), phase.name(), balanced,
                    scale(max), scale(min), diff, underFlow, overFlow, details, now, false);
        });
    }

    private Map<Long, BigDecimal> loadLatestTemps(long stationId, Instant asOf) {
        Map<Long, BigDecimal> result = new LinkedHashMap<>();
        for (Map<String, Object> row : temperatures.latestReturnTemps(stationId, asOf)) {
            Object id = row.get("unit_id");
            BigDecimal temp = (BigDecimal) row.get("return_temp");
            long unitId = ((Number) id).longValue();
            result.put(unitId, temp);
        }
        return result;
    }

    // ============================ 归档 ============================

    public ArchiveResponse archive(ArchiveRequest request) {
        HeatStation station = requireStation(request.stationCode());
        String fingerprint = fingerprints.archive(request);
        return idempotency.execute(request.requestId(), com.example.starter.domain.RequestType.ARCHIVE,
                fingerprint, station.getId(), ArchiveResponse.class,
                () -> doArchive(request, station));
    }

    private ArchiveResponse doArchive(ArchiveRequest request, HeatStation station) {
        Instant now = Instant.now(clock);
        AdjustmentTask task = tasks.findByStationAny(station.getId());
        if (task == null) {
            throw ApiException.unprocessable("NO_TASK", "该换热站尚无调节任务，无法归档");
        }
        if (task.getStatus() == TaskStatus.ARCHIVED) {
            // 归档为终态：重复归档（含新 requestId）返回幂等成功
            return new ArchiveResponse(request.requestId(), task.getId(), true, task.getArchivedAt(), false);
        }
        return tx.execute(status -> {
            int rows = tasks.archive(task.getId(), now);
            if (rows == 0) {
                AdjustmentTask concurrent = tasks.findById(task.getId());
                if (concurrent != null && concurrent.getStatus() == TaskStatus.ARCHIVED) {
                    return new ArchiveResponse(request.requestId(), concurrent.getId(), true,
                            concurrent.getArchivedAt(), false);
                }
                throw ApiException.conflict("STATION_BUSY", "任务状态并发变化，归档未完成，请重试");
            }
            return new ArchiveResponse(request.requestId(), task.getId(), true, now, false);
        });
    }

    // ============================ 复算查询 ============================

    /** 各单元最新回水温度分布（可复算）。 */
    public List<Map<String, Object>> temperatureDistribution(String stationCode) {
        HeatStation station = requireStation(stationCode);
        Instant now = Instant.now(clock);
        Map<Long, BigDecimal> temps = loadLatestTemps(station.getId(), now);
        List<Map<String, Object>> result = new ArrayList<>();
        for (HeatingUnit unit : catalog.findUnitsByStation(station.getId())) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("unitCode", unit.getUnitCode());
            row.put("returnTemp", temps.get(unit.getId()) == null ? null : scale(temps.get(unit.getId())));
            result.add(row);
        }
        return result;
    }

    /** 阀门开度版本链（可复算）：按版本顺序展示。 */
    public List<Map<String, Object>> openingVersionChain(String stationCode) {
        HeatStation station = requireStation(stationCode);
        Map<Long, String> unitCodeByUnit = new LinkedHashMap<>();
        for (HeatingUnit unit : catalog.findUnitsByStation(station.getId())) {
            unitCodeByUnit.put(unit.getId(), unit.getUnitCode());
        }
        Map<Long, String> unitCodeByValve = new LinkedHashMap<>();
        for (Valve valve : catalog.findValvesByStation(station.getId())) {
            unitCodeByValve.put(valve.getId(), unitCodeByUnit.get(valve.getUnitId()));
        }
        AdjustmentTask latest = tasks.findByStationAny(station.getId());
        if (latest == null) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (com.example.starter.po.ValveOpeningVersion v : versions.findByTask(latest.getId())) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("unitCode", unitCodeByValve.get(v.getValveId()));
            row.put("versionNo", v.getVersionNo());
            row.put("action", v.getAction().name());
            row.put("fromOpening", scale(v.getFromOpening()));
            row.put("toOpening", scale(v.getToOpening()));
            row.put("delta", scale(v.getDelta()));
            row.put("requestId", v.getRequestId());
            row.put("changedAt", v.getChangedAt());
            result.add(row);
        }
        return result;
    }

    /** 前后评估对比（可复算）：按时间顺序返回全部判定/评估记录。 */
    public List<Map<String, Object>> evaluationHistory(String stationCode) {
        HeatStation station = requireStation(stationCode);
        AdjustmentTask latest = tasks.findByStationAny(station.getId());
        if (latest == null) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (com.example.starter.po.EvaluationRecord e : evaluations.findByTask(latest.getId())) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("requestId", e.getRequestId());
            row.put("phase", e.getPhase().name());
            row.put("balanced", e.getBalanced());
            row.put("maxReturnTemp", scale(e.getMaxReturnTemp()));
            row.put("minReturnTemp", scale(e.getMinReturnTemp()));
            row.put("tempDiff", scale(e.getTempDiff()));
            row.put("detailJson", e.getDetailJson());
            row.put("evaluatedAt", e.getEvaluatedAt());
            result.add(row);
        }
        return result;
    }

    private HeatStation requireStation(String stationCode) {
        HeatStation station = catalog.findStationByCode(stationCode);
        if (station == null) {
            throw ApiException.notFound("换热站不存在: " + stationCode);
        }
        return station;
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(3, RoundingMode.HALF_UP);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    /** 已通过校验、待执行的步进。 */
    private record ValidatedStep(HeatingUnit unit, Valve valve, AdjustStep step, BigDecimal toOpening) {
    }
}
