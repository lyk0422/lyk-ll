package com.example.starter.cems;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.cems.OverflowTypes.AdjustmentResult;
import com.example.starter.cems.OverflowTypes.CorrectRainfallRequest;
import com.example.starter.cems.OverflowTypes.CreatePermitRequest;
import com.example.starter.cems.OverflowTypes.DailySettlementRequest;
import com.example.starter.cems.OverflowTypes.DailySettlementView;
import com.example.starter.cems.OverflowTypes.DualAverageResult;
import com.example.starter.cems.OverflowTypes.FreezeResult;
import com.example.starter.cems.OverflowTypes.HourDataRequest;
import com.example.starter.cems.OverflowTypes.HourView;
import com.example.starter.cems.OverflowTypes.PermitView;
import com.example.starter.cems.OverflowTypes.RainfallAdjustmentRequest;
import com.example.starter.cems.OverflowTypes.RainfallCorrectionResult;
import com.example.starter.cems.OverflowTypes.ReviewFreezeRequest;
import com.example.starter.cems.OverflowTypes.UpdatePermitRequest;

/**
 * 雨季溢流证据标记与双口径统计核心服务（B 题新增，合成数据；UTC 时刻域）。
 *
 * <p>关键不变量：</p>
 * <ul>
 *   <li>小时固定 UTC 整点，半开 [hour, hour+1)；合规溢流须许可 ACTIVE、雨量≥门槛（恰等合法）、
 *       小时完整落入许可半开窗口；缺雨量一律 PENDING 待核，不当零、不豁免。</li>
 *   <li>原排放浓度/超标链/限排令在旧域表中永久保留：本服务不读写旧业务表（仅校验排口存在），
 *       溢流既不取消已生效限排令，也不减轻原超标判定。</li>
 *   <li>复核冻结是单向闩锁：固定当时许可版本与雨量版本；冻结前雨量更正触发重算，
 *       冻结后只能独立调差，不回写小时记录、不改日结快照。</li>
 *   <li>连续 COMPLIANT 段遇缺小时槽或跨许可版本即断段；段长严格大于许可最大连续小时即专项异常。</li>
 *   <li>正常排放均值只排除证据齐全的 COMPLIANT 小时；全部排放均值不排除；
 *       分母、缺点明示；空集合返回无结果（均值 null）而非零。</li>
 * </ul>
 */
@Service
public class OverflowService {

    /** 双口径查询/日结的均值保留小数位。 */
    private static final int AVG_SCALE = 4;

    private final OverflowRepository repo;
    private final CemsRepository cemsRepo;
    private final CemsClock clock;
    private final IdempotencySupport idem;

    public OverflowService(OverflowRepository repo, CemsRepository cemsRepo,
                           CemsClock clock, IdempotencySupport idem) {
        this.repo = repo;
        this.cemsRepo = cemsRepo;
        this.clock = clock;
        this.idem = idem;
    }

    // ---------------- 溢流许可版本 ----------------

    @Transactional
    public PermitView createPermit(CreatePermitRequest req) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("windowStartUtc", req.windowStartUtc() == null ? null : req.windowStartUtc().toString());
        fields.put("windowEndUtc", req.windowEndUtc() == null ? null : req.windowEndUtc().toString());
        fields.put("minHourlyRainfall", req.minHourlyRainfall());
        fields.put("maxConsecutiveHours", req.maxConsecutiveHours());
        return replayable(req.requestId(), "OVERFLOW_PERMIT_CREATE", fields, PermitView.class,
                () -> doCreatePermit(req));
    }

    private PermitView doCreatePermit(CreatePermitRequest req) {
        String code = requireOutletCode(req.outletCode());
        requireValidWindow(req.windowStartUtc(), req.windowEndUtc());
        requireNonNullNonNegative(req.minHourlyRainfall(), "minHourlyRainfall");
        requirePositiveInt(req.maxConsecutiveHours(), "maxConsecutiveHours");
        if (repo.maxPermitVersion(code) > 0) {
            throw new ConflictException("排口溢流许可已存在，修改须携带 expectedVersion: " + code);
        }
        LocalDateTime now = clock.nowUtc();
        repo.insertPermit(code, 1, req.windowStartUtc(), req.windowEndUtc(),
                req.minHourlyRainfall(), req.maxConsecutiveHours(), "ACTIVE", req.requestId(), now);
        return toPermitView(repo.findPermit(code, 1));
    }

    @Transactional
    public PermitView updatePermit(UpdatePermitRequest req) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("expectedVersion", req.expectedVersion());
        fields.put("windowStartUtc", req.windowStartUtc() == null ? null : req.windowStartUtc().toString());
        fields.put("windowEndUtc", req.windowEndUtc() == null ? null : req.windowEndUtc().toString());
        fields.put("minHourlyRainfall", req.minHourlyRainfall());
        fields.put("maxConsecutiveHours", req.maxConsecutiveHours());
        return replayable(req.requestId(), "OVERFLOW_PERMIT_UPDATE", fields, PermitView.class,
                () -> doUpdatePermit(req));
    }

    private PermitView doUpdatePermit(UpdatePermitRequest req) {
        String code = requireOutletCode(req.outletCode());
        if (req.expectedVersion() == null) {
            throw new IllegalArgumentException("expectedVersion 不能为空（版本从 1 开始）");
        }
        requirePositiveInt(req.expectedVersion(), "expectedVersion");
        requireValidWindow(req.windowStartUtc(), req.windowEndUtc());
        requireNonNullNonNegative(req.minHourlyRainfall(), "minHourlyRainfall");
        requirePositiveInt(req.maxConsecutiveHours(), "maxConsecutiveHours");

        Map<String, Object> active = repo.findActivePermit(code);
        if (active == null) {
            throw new NotFoundException("排口无有效溢流许可可修改: " + code);
        }
        int activeVersion = ((Number) active.get("version")).intValue();
        if (req.expectedVersion() != activeVersion) {
            // 乐观锁：版本落后/超前一律 409，不产生新版本。
            throw new ConflictException("expectedVersion=" + req.expectedVersion()
                    + " 与当前有效版本 " + activeVersion + " 不一致");
        }
        int superseded = repo.supersedePermit(code, req.expectedVersion());
        if (superseded == 0) {
            // 并发修改抢先：同一 expectedVersion 只允许一个成功变更。
            throw new ConflictException("许可版本 " + req.expectedVersion() + " 已被并发修改，请刷新后重试");
        }
        int newVersion = req.expectedVersion() + 1;
        LocalDateTime now = clock.nowUtc();
        repo.insertPermit(code, newVersion, req.windowStartUtc(), req.windowEndUtc(),
                req.minHourlyRainfall(), req.maxConsecutiveHours(), "ACTIVE", req.requestId(), now);
        // 许可变更不回改历史小时标记：旧 COMPLIANT 点仍引用旧版本，跨版本相邻自动断段。
        recomputeSegments(code);
        return toPermitView(repo.findPermit(code, newVersion));
    }

    // ---------------- 小时数据与溢流标记 ----------------

    @Transactional
    public HourView reportHour(HourDataRequest req) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("hourUtc", req.hourUtc() == null ? null : req.hourUtc().toString());
        fields.put("dischargeValue", req.dischargeValue());
        fields.put("rainfallMm", req.rainfallMm());
        fields.put("observedOverflow", req.observedOverflow());
        return replayable(requestIdOrNull(req.requestId()), "OVERFLOW_HOUR", fields, HourView.class,
                () -> doReportHour(req));
    }

    private HourView doReportHour(HourDataRequest req) {
        String code = requireOutletCode(req.outletCode());
        requireExactHour(req.hourUtc(), "hourUtc");
        requireNonNullNonNegative(req.dischargeValue(), "dischargeValue");
        if (req.rainfallMm() != null && req.rainfallMm().signum() < 0) {
            throw new IllegalArgumentException("rainfallMm 不能为负数（缺雨量请传 null，禁止当零）");
        }
        LocalDateTime hour = req.hourUtc();
        LocalDateTime now = clock.nowUtc();

        Map<String, Object> existing = repo.findHour(code, hour);
        if (existing != null) {
            // 排口＋UTC 小时去重：内容一致回放既有标记，内容不一致 409；不重复评价、不重复计段。
            ensureSameHourContent(existing, req);
            return toHourView(existing, true);
        }

        Evaluation evaluation = evaluate(code, hour, req.rainfallMm(), req.observedOverflow());
        repo.insertHour(code, hour, req.dischargeValue(), req.rainfallMm(), 1,
                req.observedOverflow(), evaluation.status(), evaluation.permitVersion(), now);
        recomputeSegments(code);
        return toHourView(repo.findHour(code, hour), false);
    }

    private void ensureSameHourContent(Map<String, Object> existing, HourDataRequest req) {
        BigDecimal discharge = bd(existing.get("discharge_value"));
        BigDecimal rainfall = bd(existing.get("rainfall_mm"));
        boolean observed = Boolean.TRUE.equals(existing.get("overflow_observed"));
        if (req.dischargeValue().compareTo(discharge) != 0
                || (req.rainfallMm() == null != (rainfall == null))
                || (req.rainfallMm() != null && rainfall != null
                        && req.rainfallMm().compareTo(rainfall) != 0)
                || req.observedOverflow() != observed) {
            throw new ConflictException("排口＋UTC 小时数据已存在且内容不同: "
                    + req.outletCode() + " " + req.hourUtc());
        }
    }

    /**
     * 按当前 ACTIVE 许可评价单个小时：
     * 未观测溢流→NORMAL；观测溢流但缺雨量→PENDING（待核，不当零不豁免）；
     * 观测溢流且有雨量→许可有效、雨量≥门槛（恰等合法）、小时完整落窗才 COMPLIANT，否则 DENIED。
     */
    private Evaluation evaluate(String code, LocalDateTime hour, BigDecimal rainfallMm, boolean observedOverflow) {
        if (!observedOverflow) {
            return new Evaluation("NORMAL", null);
        }
        if (rainfallMm == null) {
            return new Evaluation("PENDING", null);
        }
        Map<String, Object> active = repo.findActivePermit(code);
        if (active == null) {
            return new Evaluation("DENIED", null);
        }
        int permitVersion = ((Number) active.get("version")).intValue();
        BigDecimal threshold = bd(active.get("min_hourly_rainfall"));
        LocalDateTime windowStart = lt(active.get("window_start_utc"));
        LocalDateTime windowEnd = lt(active.get("window_end_utc"));
        boolean rainOk = rainfallMm.compareTo(threshold) >= 0; // 恰等门槛合法
        boolean fullyInWindow = !hour.isBefore(windowStart) && !hour.plusHours(1).isAfter(windowEnd);
        boolean compliant = rainOk && fullyInWindow;
        return new Evaluation(compliant ? "COMPLIANT" : "DENIED", permitVersion);
    }

    // ---------------- 雨量更正（冻结前）与独立调差（冻结后） ----------------

    @Transactional
    public RainfallCorrectionResult correctRainfall(CorrectRainfallRequest req) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("hourUtc", req.hourUtc() == null ? null : req.hourUtc().toString());
        fields.put("correctedRainfallMm", req.correctedRainfallMm());
        return replayable(req.requestId(), "OVERFLOW_RAIN_CORRECT", fields,
                RainfallCorrectionResult.class, () -> doCorrectRainfall(req));
    }

    private RainfallCorrectionResult doCorrectRainfall(CorrectRainfallRequest req) {
        String code = requireOutletCode(req.outletCode());
        requireExactHour(req.hourUtc(), "hourUtc");
        requireNonNullNonNegative(req.correctedRainfallMm(), "correctedRainfallMm");
        Map<String, Object> hourRow = loadHour(code, req.hourUtc());
        if (hourRow.get("frozen_at") != null) {
            // 复核冻结后证据固定：更正被业务拒绝，只能走独立调差。
            throw new BusinessRejectException("该小时已复核冻结，不能再更正雨量，只能独立调差: " + req.hourUtc());
        }
        BigDecimal oldRainfall = bd(hourRow.get("rainfall_mm"));
        int oldVersion = ((Number) hourRow.get("rainfall_version")).intValue();
        int newVersion = oldVersion + 1;
        boolean observed = Boolean.TRUE.equals(hourRow.get("overflow_observed"));
        Evaluation evaluation = evaluate(code, req.hourUtc(), req.correctedRainfallMm(), observed);
        int updated = repo.updateRainfallAndStatus(code, req.hourUtc(), req.correctedRainfallMm(),
                newVersion, evaluation.status(), evaluation.permitVersion(), clock.nowUtc());
        if (updated == 0) {
            throw new ConflictException("小时记录已被并发冻结，雨量更正未受理: " + req.hourUtc());
        }
        // 复核前更正雨量必须重算：标记状态变化与连续段异常随之刷新。
        recomputeSegments(code);
        return new RainfallCorrectionResult(code, req.hourUtc(), oldRainfall, req.correctedRainfallMm(),
                newVersion, evaluation.status());
    }

    @Transactional
    public AdjustmentResult independentAdjustment(RainfallAdjustmentRequest req) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("hourUtc", req.hourUtc() == null ? null : req.hourUtc().toString());
        fields.put("newRainfallMm", req.newRainfallMm());
        fields.put("reason", req.reason());
        return replayable(req.requestId(), "OVERFLOW_RAIN_ADJUST", fields, AdjustmentResult.class,
                () -> doIndependentAdjustment(req));
    }

    private AdjustmentResult doIndependentAdjustment(RainfallAdjustmentRequest req) {
        String code = requireOutletCode(req.outletCode());
        requireExactHour(req.hourUtc(), "hourUtc");
        requireNonNullNonNegative(req.newRainfallMm(), "newRainfallMm");
        String reason = req.reason() == null ? "" : req.reason();
        Map<String, Object> hourRow = loadHour(code, req.hourUtc());
        if (hourRow.get("frozen_at") == null) {
            // 未冻结证据不允许调差：应走“更正雨量”触发重算，避免绕过复核链。
            throw new BusinessRejectException("该小时尚未复核冻结，应使用雨量更正（会触发重算）: " + req.hourUtc());
        }
        BigDecimal oldRainfall = bd(hourRow.get("rainfall_mm"));
        int frozenRainfallVersion = ((Number) hourRow.get("rainfall_version_frozen")).intValue();
        LocalDateTime now = clock.nowUtc();
        // 独立留证：不回写 overflow_hour_record，不改任何日结快照。
        repo.insertAdjustment(code, req.hourUtc(), frozenRainfallVersion, oldRainfall,
                req.newRainfallMm(), reason, req.requestId(), now);
        return new AdjustmentResult(req.requestId(), code, req.hourUtc(), frozenRainfallVersion,
                oldRainfall, req.newRainfallMm(), reason, now);
    }

    // ---------------- 复核冻结 ----------------

    @Transactional
    public FreezeResult reviewFreeze(ReviewFreezeRequest req) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("hourUtc", req.hourUtc() == null ? null : req.hourUtc().toString());
        fields.put("freezeAtUtc", req.freezeAtUtc() == null ? null : req.freezeAtUtc().toString());
        return replayable(req.requestId(), "OVERFLOW_FREEZE", fields, FreezeResult.class,
                () -> doReviewFreeze(req));
    }

    private FreezeResult doReviewFreeze(ReviewFreezeRequest req) {
        String code = requireOutletCode(req.outletCode());
        requireExactHour(req.hourUtc(), "hourUtc");
        if (req.freezeAtUtc() == null) {
            throw new IllegalArgumentException("freezeAtUtc 不能为空（须携带精确 UTC 时刻）");
        }
        Map<String, Object> hourRow = loadHour(code, req.hourUtc());

        // 竞争冻结一致：已被冻结（含并发抢先）则回放首个冻结结果，不再改写。
        Map<String, Object> already = hourRow.get("frozen_at") == null ? null : hourRow;
        if (already == null) {
            String status = (String) hourRow.get("overflow_status");
            if ("NORMAL".equals(status)) {
                throw new BusinessRejectException("正常排放小时无溢流标记，无需复核冻结: " + req.hourUtc());
            }
            if ("PENDING".equals(status)) {
                throw new BusinessRejectException("缺雨量待核，证据未齐，不能复核冻结（不得当零或豁免）: "
                        + req.hourUtc());
            }
            Integer evalVersion = hourRow.get("evaluated_permit_version") == null
                    ? null : ((Number) hourRow.get("evaluated_permit_version")).intValue();
            if (evalVersion == null) {
                throw new BusinessRejectException("标记未引用有效许可版本，不能复核冻结: " + req.hourUtc());
            }
            int rainfallVersion = ((Number) hourRow.get("rainfall_version")).intValue();
            int n = repo.freezeHour(code, req.hourUtc(), evalVersion, rainfallVersion,
                    req.requestId(), req.freezeAtUtc());
            if (n == 0) {
                already = loadHour(code, req.hourUtc()); // 并发抢先：转读首个冻结
            } else {
                recomputeSegments(code);
                return new FreezeResult(req.requestId(), code, req.hourUtc(), status,
                        evalVersion, rainfallVersion, req.freezeAtUtc());
            }
        }
        return new FreezeResult(req.requestId(), code, req.hourUtc(),
                (String) already.get("overflow_status"),
                ((Number) already.get("permit_version_frozen")).intValue(),
                ((Number) already.get("rainfall_version_frozen")).intValue(),
                lt(already.get("frozen_at")));
    }

    // ---------------- 双口径均值查询 ----------------

    @Transactional(readOnly = true)
    public DualAverageResult queryDualAverage(String outletCode, LocalDateTime windowStartUtc,
                                              LocalDateTime windowEndUtc, String basis) {
        String code = requireOutletCode(outletCode);
        requireExactHour(windowStartUtc, "fromUtc");
        requireExactHour(windowEndUtc, "toUtc");
        if (!windowEndUtc.isAfter(windowStartUtc)) {
            throw new IllegalArgumentException("toUtc 必须晚于 fromUtc（半开窗口）");
        }
        String normalizedBasis = basis == null ? "NORMAL" : basis.trim().toUpperCase(java.util.Locale.ROOT);
        if (!"NORMAL".equals(normalizedBasis) && !"ALL".equals(normalizedBasis)) {
            throw new IllegalArgumentException("basis 只能为 NORMAL 或 ALL");
        }

        List<Map<String, Object>> presentRows = repo.findHoursInWindow(code, windowStartUtc, windowEndUtc);
        return buildDualAverage(code, windowStartUtc, windowEndUtc, presentRows);
    }

    private DualAverageResult buildDualAverage(String code, LocalDateTime windowStart, LocalDateTime windowEnd,
                                               List<Map<String, Object>> presentRows) {
        long slots = ChronoUnit.HOURS.between(windowStart, windowEnd);
        List<HourView> present = new ArrayList<>();
        List<LocalDateTime> missing = new ArrayList<>();

        int idx = 0;
        for (long s = 0; s < slots; s++) {
            LocalDateTime slot = windowStart.plusHours(s);
            if (idx < presentRows.size() && slot.equals(lt(presentRows.get(idx).get("hour_utc")))) {
                present.add(toHourView(presentRows.get(idx), false));
                idx++;
            } else {
                missing.add(slot);
            }
        }

        int allDenominator = present.size();
        int excludedCompliant = 0;
        int pendingCount = 0;
        BigDecimal allSum = BigDecimal.ZERO;
        BigDecimal normalSum = BigDecimal.ZERO;
        for (Map<String, Object> row : presentRows) {
            BigDecimal value = bd(row.get("discharge_value"));
            String status = (String) row.get("overflow_status");
            allSum = allSum.add(value);
            if ("COMPLIANT".equals(status)) {
                excludedCompliant++;
            } else {
                normalSum = normalSum.add(value);
            }
            if ("PENDING".equals(status)) {
                pendingCount++;
            }
        }
        int normalDenominator = allDenominator - excludedCompliant;

        // 空集合：无结果（均值 null），不得返回零均值冒充统计结论。
        if (allDenominator == 0) {
            return new DualAverageResult(code, windowStart, windowEnd, true, null, null,
                    0, 0, missing.size(), 0, 0, present, missing);
        }
        BigDecimal allAvg = allSum.divide(BigDecimal.valueOf(allDenominator), AVG_SCALE, RoundingMode.HALF_UP);
        BigDecimal normalAvg = normalDenominator == 0 ? null
                : normalSum.divide(BigDecimal.valueOf(normalDenominator), AVG_SCALE, RoundingMode.HALF_UP);
        return new DualAverageResult(code, windowStart, windowEnd, false, normalAvg, allAvg,
                normalDenominator, allDenominator, missing.size(), excludedCompliant, pendingCount,
                present, missing);
    }

    // ---------------- 日结快照 ----------------

    @Transactional
    public DailySettlementView settleDaily(DailySettlementRequest req) {
        if (req.dayUtc() == null) {
            throw new IllegalArgumentException("dayUtc 不能为空");
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("dayUtc", req.dayUtc().toString());
        ReplayOutcome<DailySettlementView> outcome = replayableEx(
                req.requestId(), "OVERFLOW_SETTLE", fields, DailySettlementView.class,
                () -> doSettleDaily(req));
        // 命中幂等重放或快照此前已存在（竞争冻结），本次均未新建快照：replayed=true。
        DailySettlementView view = outcome.value();
        return outcome.replayed() || view.replayed()
                ? new DailySettlementView(view.outletCode(), view.dayUtc(), view.normalAvg(), view.allAvg(),
                        view.normalDenominator(), view.allDenominator(), view.requestedHours(),
                        view.presentCount(), view.excludedCount(), view.pendingCount(),
                        view.missingPointCount(), true, view.settledAt())
                : view;
    }

    private DailySettlementView doSettleDaily(DailySettlementRequest req) {
        String code = requireOutletCode(req.outletCode());
        LocalDateTime dayStart = req.dayUtc().atStartOfDay();
        LocalDateTime dayEnd = dayStart.plusDays(1);

        Map<String, Object> existing = repo.findSettlement(code, req.dayUtc());
        if (existing != null) {
            // 竞争/重复日结：唯一快照闩锁，回放同一双口径结果。
            return toSettlementView(existing, true);
        }

        List<Map<String, Object>> presentRows = repo.findHoursInWindow(code, dayStart, dayEnd);
        DualAverageResult dual = buildDualAverage(code, dayStart, dayEnd, presentRows);
        if (dual.noResult()) {
            // 全日无到点数据：无结果而非零，不落快照（CHECK 约束亦要求 all_denominator>0）。
            return new DailySettlementView(code, req.dayUtc(), null, null, 0, 0, 24, 0, 0, 0, 24,
                    false, clock.nowUtc());
        }
        LocalDateTime settledAt = clock.nowUtc();
        boolean inserted = repo.insertSettlement(code, req.dayUtc(), dual.normalAverage(), dual.allAverage(),
                dual.normalDenominator(), dual.allDenominator(), 24, dual.allDenominator(),
                dual.excludedCompliantCount(), dual.pendingCount(), dual.missingPointCount(),
                settledAt, req.requestId(), settledAt);
        if (!inserted) {
            return toSettlementView(repo.findSettlement(code, req.dayUtc()), true);
        }
        return new DailySettlementView(code, req.dayUtc(), dual.normalAverage(), dual.allAverage(),
                dual.normalDenominator(), dual.allDenominator(), 24, dual.allDenominator(),
                dual.excludedCompliantCount(), dual.pendingCount(), dual.missingPointCount(),
                false, settledAt);
    }

    // ---------------- 连续合规溢流段与专项异常 ----------------

    /**
     * 按全部到点小时重算连续 COMPLIANT 段：
     * 相邻小时必须逐时连续（出现缺小时槽即断段）、evaluated_permit_version 必须相同（跨许可即断段）；
     * 非 COMPLIANT 点本身不属任何段，亦使其前后段断开。段长严格大于该版本许可最大连续小时即专项异常。
     */
    private void recomputeSegments(String code) {
        repo.deleteSegmentAnomalies(code);
        List<Map<String, Object>> hours = repo.findAllHours(code);

        LocalDateTime runStart = null;
        LocalDateTime runPrev = null;
        Integer runVersion = null;
        int runLength = 0;
        LocalDateTime now = clock.nowUtc();

        for (Map<String, Object> row : hours) {
            LocalDateTime hour = lt(row.get("hour_utc"));
            String status = (String) row.get("overflow_status");
            Integer version = row.get("evaluated_permit_version") == null
                    ? null : ((Number) row.get("evaluated_permit_version")).intValue();
            boolean extendsRun = "COMPLIANT".equals(status) && version != null
                    && runLength > 0 && hour.equals(runPrev.plusHours(1)) && version.equals(runVersion);
            if ("COMPLIANT".equals(status) && version != null && (runLength == 0 || !extendsRun)) {
                if (runLength > 0) {
                    closeSegment(code, runStart, runPrev, runVersion, runLength, now);
                }
                runStart = hour;
                runVersion = version;
                runLength = 1;
            } else if (extendsRun) {
                runLength++;
            } else {
                if (runLength > 0) {
                    closeSegment(code, runStart, runPrev, runVersion, runLength, now);
                }
                runStart = null;
                runVersion = null;
                runLength = 0;
            }
            runPrev = hour;
        }
        if (runLength > 0) {
            closeSegment(code, runStart, runPrev, runVersion, runLength, now);
        }
    }

    private void closeSegment(String code, LocalDateTime runStart, LocalDateTime runLast,
                              Integer permitVersion, int runLength, LocalDateTime now) {
        Map<String, Object> permit = repo.findPermit(code, permitVersion);
        int maxConsecutive = permit == null ? Integer.MAX_VALUE
                : ((Number) permit.get("max_consecutive_hours")).intValue();
        if (runLength > maxConsecutive) {
            // 严格大于许可最大连续小时：专项异常（段去重唯一约束保证幂等重算不重复）。
            repo.insertAnomaly(code, "SEGMENT_TOO_LONG", runStart, runLast.plusHours(1),
                    runLength, permitVersion, now);
        }
    }

    // ---------------- 查询 ----------------

    @Transactional(readOnly = true)
    public List<PermitView> listPermits(String outletCode) {
        String code = requireOutletCode(outletCode);
        return repo.findPermits(code).stream().map(OverflowService::toPermitView).toList();
    }

    @Transactional(readOnly = true)
    public List<HourView> listHours(String outletCode, LocalDate dayUtc) {
        String code = requireOutletCode(outletCode);
        if (dayUtc == null) {
            throw new IllegalArgumentException("dayUtc 不能为空");
        }
        return repo.findHoursInWindow(code, dayUtc.atStartOfDay(), dayUtc.atStartOfDay().plusDays(1))
                .stream().map(r -> toHourView(r, false)).toList();
    }

    @Transactional(readOnly = true)
    public List<OverflowTypes.AnomalyView> listAnomalies(String outletCode) {
        String code = requireOutletCode(outletCode);
        return repo.findAnomalies(code).stream().map(r -> new OverflowTypes.AnomalyView(
                code,
                (String) r.get("anomaly_type"),
                lt(r.get("segment_start_utc")),
                lt(r.get("segment_end_utc")),
                ((Number) r.get("segment_hours")).intValue(),
                ((Number) r.get("permit_version")).intValue(),
                lt(r.get("created_at")))).toList();
    }

    // ---------------- 幂等支撑（与旧域共用 idempotent_request，request_type 区分命名空间） ----------------

    /**
     * 结构化指纹幂等执行：同键同参回放首次结果；同键异参 409；业务失败随事务回滚，不占用 requestId。
     * requestId 可空（小时上报兼容旧“无幂等号去重”入口；为空时跳过幂等层）。
     */
    private <T> T replayable(String requestId, String requestType, Map<String, Object> fields,
                             Class<T> resultType, Supplier<T> action) {
        return replayableEx(requestId, requestType, fields, resultType, action).value();
    }

    /** 幂等执行结果：value 为业务结果；replayed=true 表示命中既有幂等记录重放（未执行业务动作）。 */
    private record ReplayOutcome<T>(T value, boolean replayed) {
    }

    private <T> ReplayOutcome<T> replayableEx(String requestId, String requestType, Map<String, Object> fields,
                                              Class<T> resultType, Supplier<T> action) {
        if (requestId == null) {
            return new ReplayOutcome<>(action.get(), false);
        }
        String fingerprint = idem.fingerprintStructured(requestId, requestType, fields);
        Map<String, Object> existing = cemsRepo.findIdempotent(requestId);
        if (existing != null) {
            return new ReplayOutcome<>(replayOrConflict(requestId, fingerprint, existing, resultType), true);
        }
        while (true) {
            if (cemsRepo.insertIdempotent(requestId, requestType, fingerprint, "", clock.nowUtc())) {
                T result = action.get();
                cemsRepo.updateIdempotentResult(requestId, idem.toJson(result));
                return new ReplayOutcome<>(result, false);
            }
            Map<String, Object> raced = waitForIdempotentResult(requestId);
            if (raced != null) {
                return new ReplayOutcome<>(replayOrConflict(requestId, fingerprint, raced, resultType), true);
            }
        }
    }

    private <T> T replayOrConflict(String rid, String fingerprint, Map<String, Object> row,
                                   Class<T> resultType) {
        if (!fingerprint.equals(row.get("fingerprint"))) {
            throw new ConflictException("requestId=" + rid + " 请求参数与首次调用不一致（409 幂等冲突）");
        }
        return idem.fromJson((String) row.get("result_json"), resultType);
    }

    private Map<String, Object> waitForIdempotentResult(String rid) {
        long deadlineMillis = System.currentTimeMillis() + 15_000L;
        while (true) {
            Map<String, Object> row = cemsRepo.findIdempotent(rid);
            if (row == null) {
                return null;
            }
            if (!"".equals(row.get("result_json"))) {
                return row;
            }
            if (System.currentTimeMillis() >= deadlineMillis) {
                throw new ConflictException("requestId=" + rid + " 并发请求等待首次结果超时");
            }
            try {
                Thread.sleep(20L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ConflictException("并发幂等等待被中断");
            }
        }
    }

    // ---------------- 辅助 ----------------

    /** 校验排口存在且未归档：复用旧域排口登记；404/409 语义与旧入口一致。 */
    private String requireOutletCode(String code) {
        String trimmed = requireText(code, "outletCode");
        Map<String, Object> outlet = cemsRepo.findOutlet(trimmed);
        if (outlet == null) {
            throw new NotFoundException("排口不存在: " + trimmed);
        }
        if ("ARCHIVED".equals(outlet.get("status"))) {
            throw new ConflictException("排口已归档（终态），不能进行雨季溢流操作: " + trimmed);
        }
        return trimmed;
    }

    private Map<String, Object> loadHour(String code, LocalDateTime hour) {
        Map<String, Object> row = repo.findHour(code, hour);
        if (row == null) {
            throw new NotFoundException("UTC 小时数据不存在: " + code + " " + hour);
        }
        return row;
    }

    private static void requireValidWindow(LocalDateTime start, LocalDateTime end) {
        requireExactHour(start, "windowStartUtc");
        requireExactHour(end, "windowEndUtc");
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("适用窗口终点必须晚于起点（UTC 半开区间）");
        }
    }

    private static void requireExactHour(LocalDateTime time, String field) {
        if (time == null) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        if (time.getMinute() != 0 || time.getSecond() != 0 || time.getNano() != 0) {
            throw new IllegalArgumentException(field + " 必须是 UTC 整点（分秒为 0）: " + time);
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value.trim();
    }

    private static void requireNonNullNonNegative(BigDecimal value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        if (value.signum() < 0) {
            throw new IllegalArgumentException(field + " 不能为负数");
        }
    }

    private static void requirePositiveInt(Integer value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        if (value < 1) {
            throw new IllegalArgumentException(field + " 必须为正整数");
        }
    }

    private static String requestIdOrNull(String requestId) {
        return requestId == null || requestId.isBlank() ? null : requestId;
    }

    private static BigDecimal bd(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof BigDecimal b ? b : new BigDecimal(value.toString());
    }

    private static LocalDateTime lt(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof Timestamp t ? t.toLocalDateTime() : LocalDateTime.parse(value.toString());
    }

    private static PermitView toPermitView(Map<String, Object> p) {
        return new PermitView(
                (String) p.get("outlet_code"),
                ((Number) p.get("version")).intValue(),
                lt(p.get("window_start_utc")),
                lt(p.get("window_end_utc")),
                bd(p.get("min_hourly_rainfall")),
                ((Number) p.get("max_consecutive_hours")).intValue(),
                (String) p.get("status"),
                lt(p.get("created_at")));
    }

    private static HourView toHourView(Map<String, Object> h, boolean deduplicated) {
        return new HourView(
                (String) h.get("outlet_code"),
                lt(h.get("hour_utc")),
                bd(h.get("discharge_value")),
                bd(h.get("rainfall_mm")),
                ((Number) h.get("rainfall_version")).intValue(),
                Boolean.TRUE.equals(h.get("overflow_observed")),
                (String) h.get("overflow_status"),
                h.get("evaluated_permit_version") == null ? null
                        : ((Number) h.get("evaluated_permit_version")).intValue(),
                h.get("permit_version_frozen") == null ? null
                        : ((Number) h.get("permit_version_frozen")).intValue(),
                h.get("rainfall_version_frozen") == null ? null
                        : ((Number) h.get("rainfall_version_frozen")).intValue(),
                lt(h.get("frozen_at")),
                deduplicated);
    }

    private static DailySettlementView toSettlementView(Map<String, Object> s, boolean replayed) {
        return new DailySettlementView(
                (String) s.get("outlet_code"),
                ((java.sql.Date) s.get("day_utc")).toLocalDate(),
                bd(s.get("normal_avg")),
                bd(s.get("all_avg")),
                ((Number) s.get("normal_denominator")).intValue(),
                ((Number) s.get("all_denominator")).intValue(),
                ((Number) s.get("requested_hours")).intValue(),
                ((Number) s.get("present_count")).intValue(),
                ((Number) s.get("excluded_count")).intValue(),
                ((Number) s.get("pending_count")).intValue(),
                ((Number) s.get("missing_point_count")).intValue(),
                replayed,
                lt(s.get("settled_at")));
    }

    /** 单小时评价结果：标记状态与所引用的许可版本（无有效许可时为 null）。 */
    private record Evaluation(String status, Integer permitVersion) {
    }
}
