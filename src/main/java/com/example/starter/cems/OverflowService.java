package com.example.starter.cems;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.cems.CemsTypes.DischargeStatsView;
import com.example.starter.cems.CemsTypes.OverflowAdjustmentRequest;
import com.example.starter.cems.CemsTypes.OverflowAdjustmentView;
import com.example.starter.cems.CemsTypes.OverflowAnomalyView;
import com.example.starter.cems.CemsTypes.OverflowHourEntry;
import com.example.starter.cems.CemsTypes.OverflowHourView;
import com.example.starter.cems.CemsTypes.OverflowHoursRequest;
import com.example.starter.cems.CemsTypes.OverflowHoursResult;
import com.example.starter.cems.CemsTypes.OverflowPermitRequest;
import com.example.starter.cems.CemsTypes.OverflowPermitView;
import com.example.starter.cems.CemsTypes.OverflowReviewRequest;
import com.example.starter.cems.CemsTypes.OverflowReviewResult;
import com.example.starter.cems.CemsTypes.OverflowSettlementRequest;
import com.example.starter.cems.CemsTypes.OverflowSnapshotView;
import com.example.starter.cems.CemsTypes.StatsCaliber;

/**
 * 雨季溢流证据标记与双口径统计。
 * 业务时刻（许可窗口、小时整点、日）均为 UTC；系统审计列沿用应用时钟。
 * 仅在许可有效、雨量≥门槛（恰等合法）且该小时完整落窗时可标合规溢流；
 * 缺雨量只能待核，不当零也不直接豁免。原排放浓度与超标链永远保留，
 * 溢流不取消已生效限排令，也不减轻原超标判定。
 * 错误语义：参数非法 400、对象不存在 404、业务拒绝 422、幂等异参与版本冲突 409。
 */
@Service
public class OverflowService {

    private final CemsRepository repo;
    private final OverflowRepository overflowRepo;
    private final CemsClock clock;
    private final IdempotencySupport idem;

    public OverflowService(CemsRepository repo, OverflowRepository overflowRepo,
                           CemsClock clock, IdempotencySupport idem) {
        this.repo = repo;
        this.overflowRepo = overflowRepo;
        this.clock = clock;
        this.idem = idem;
    }

    // ---------------- 溢流许可版本（新增/修改统一入口，expectedVersion 乐观校验） ----------------

    @Transactional
    public OverflowPermitView upsertPermit(OverflowPermitRequest req) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("outletCode", req.outletCode());
        fields.put("expectedVersion", req.expectedVersion());
        fields.put("validFrom", req.validFrom() == null ? null : req.validFrom().toString());
        fields.put("validTo", req.validTo() == null ? null : req.validTo().toString());
        fields.put("minRainfall", req.minRainfall());
        fields.put("maxConsecutiveHours", req.maxConsecutiveHours());
        return idem.execute(req.requestId(), "OVERFLOW_PERMIT", fields, OverflowPermitView.class,
                () -> doUpsertPermit(req), true);
    }

    private OverflowPermitView doUpsertPermit(OverflowPermitRequest req) {
        String code = requireText(req.outletCode(), "outletCode");
        Map<String, Object> outlet = loadOutlet(code);
        rejectIfArchived(outlet, code);
        if (req.expectedVersion() == null || req.expectedVersion() < 0) {
            throw new IllegalArgumentException("expectedVersion 不能为空且不能为负（无许可时传 0 表示新增）");
        }
        if (req.validFrom() == null || req.validTo() == null) {
            throw new IllegalArgumentException("validFrom/validTo 不能为空（UTC 半开窗口）");
        }
        if (!req.validFrom().isBefore(req.validTo())) {
            throw new IllegalArgumentException("validFrom 必须早于 validTo（UTC 半开窗口）");
        }
        requireNonNullNonNegative(req.minRainfall(), "minRainfall");
        if (req.maxConsecutiveHours() == null || req.maxConsecutiveHours() < 1) {
            throw new IllegalArgumentException("maxConsecutiveHours 不能为空且至少为 1");
        }
        int current = overflowRepo.currentPermitVersion(code);
        if (req.expectedVersion() != current) {
            throw new ConflictException("溢流许可版本冲突: expectedVersion=" + req.expectedVersion()
                    + ", 当前版本=" + current);
        }
        int newVersion = current + 1;
        try {
            overflowRepo.insertPermit(code, newVersion, req.validFrom(), req.validTo(),
                    req.minRainfall(), req.maxConsecutiveHours(), clock.now());
        } catch (DuplicateKeyException e) {
            // 并发同版本变更：唯一约束仅受理一个，业务、版本与幂等结果随事务整体回滚。
            throw new ConflictException("溢流许可版本并发冲突: outlet=" + code + " version=" + newVersion);
        }
        return toPermitView(overflowRepo.findPermitByVersion(code, newVersion));
    }

    @Transactional(readOnly = true)
    public OverflowPermitView getPermit(String code) {
        loadOutlet(code);
        Map<String, Object> permit = overflowRepo.findCurrentPermit(code);
        if (permit == null) {
            throw new NotFoundException("排口尚无溢流许可: " + code);
        }
        return toPermitView(permit);
    }

    // ---------------- 小时溢流数据上报/更正（集合，UTC 整点） ----------------

    @Transactional
    public OverflowHoursResult reportHours(OverflowHoursRequest req) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("outletCode", req.outletCode());
        fields.put("entries", entryFingerprints(req.entries()));
        return idem.execute(req.requestId(), "OVERFLOW_HOURS", fields, OverflowHoursResult.class,
                () -> doReportHours(req), true);
    }

    /** 结构化集合指纹：按 UTC 整点排序，逐条含精确时刻、雨量与溢流标记。 */
    private List<Map<String, Object>> entryFingerprints(List<OverflowHourEntry> entries) {
        if (entries == null) {
            return null;
        }
        return entries.stream()
                .sorted(Comparator.comparing(e -> e == null ? "" : String.valueOf(e.hour())))
                .map(e -> {
                    if (e == null) {
                        return null;
                    }
                    Map<String, Object> m = new TreeMap<String, Object>();
                    m.put("hour", e.hour() == null ? null : e.hour().toString());
                    m.put("rainfall", e.rainfall());
                    m.put("overflowFlag", e.overflowFlag());
                    return m;
                })
                .toList();
    }

    private OverflowHoursResult doReportHours(OverflowHoursRequest req) {
        String code = requireText(req.outletCode(), "outletCode");
        Map<String, Object> outlet = loadOutlet(code);
        rejectIfArchived(outlet, code);
        if (req.entries() == null || req.entries().isEmpty()) {
            throw new IllegalArgumentException("entries 不能为空集合");
        }
        Set<LocalDateTime> seen = new HashSet<>();
        for (OverflowHourEntry entry : req.entries()) {
            if (entry == null) {
                throw new IllegalArgumentException("entries 不能含空条目");
            }
            validateHour(entry.hour());
            if (entry.rainfall() != null && entry.rainfall().signum() < 0) {
                throw new IllegalArgumentException("rainfall 不能为负数");
            }
            if (entry.overflowFlag() == null) {
                throw new IllegalArgumentException("overflowFlag 不能为空");
            }
            if (!seen.add(entry.hour())) {
                throw new IllegalArgumentException("同一请求内 UTC 整点重复: " + entry.hour());
            }
        }
        Map<String, Object> permit = overflowRepo.findCurrentPermit(code);
        LocalDateTime now = clock.now();
        List<OverflowHourView> views = new ArrayList<>();
        for (OverflowHourEntry entry : req.entries()) {
            LocalDateTime hour = entry.hour();
            // 日结快照已冻结（复核或日结先冻）：不得再更正雨量，只能独立调差。
            if (overflowRepo.findSnapshot(code, dayStartOf(hour)) != null) {
                throw new UnprocessableException("该小时所在 UTC 日已复核/日结冻结，只能独立调差不改日结快照: " + hour);
            }
            Map<String, Object> existing = overflowRepo.findHourly(code, hour);
            if (existing != null && Boolean.TRUE.equals(existing.get("reviewed"))) {
                throw new UnprocessableException("该小时已复核冻结，只能独立调差: " + hour);
            }
            Eval eval = evaluate(entry.overflowFlag(), entry.rainfall(), permit, hour);
            if (existing == null) {
                overflowRepo.insertHourly(code, hour, entry.rainfall(), 1, entry.overflowFlag(),
                        eval.status(), eval.permitVersion(), now);
            } else {
                // 复核前更正雨量须重算：雨量变化则雨量版本加 1 并按当前许可重新评估。
                BigDecimal oldRainfall = bd(existing.get("rainfall"));
                boolean rainfallChanged = oldRainfall == null
                        ? entry.rainfall() != null
                        : entry.rainfall() == null || oldRainfall.compareTo(entry.rainfall()) != 0;
                int rainfallVersion = ((Number) existing.get("rainfall_version")).intValue()
                        + (rainfallChanged ? 1 : 0);
                overflowRepo.updateHourly(code, hour, entry.rainfall(), rainfallVersion,
                        entry.overflowFlag(), eval.status(), eval.permitVersion(), now);
            }
            views.add(toHourView(overflowRepo.findHourly(code, hour)));
        }
        return new OverflowHoursResult(code, views);
    }

    /**
     * 单小时评估：仅在许可有效、雨量≥门槛（恰等合法）且该小时完整落窗时可标合规溢流；
     * 缺雨量只能待核，不当零也不直接豁免。
     */
    private Eval evaluate(boolean overflowFlag, BigDecimal rainfall, Map<String, Object> permit,
                          LocalDateTime hour) {
        Integer permitVersion = permit == null ? null : ((Number) permit.get("version")).intValue();
        if (!overflowFlag) {
            return new Eval("NORMAL", permitVersion);
        }
        if (rainfall == null) {
            return new Eval("PENDING_REVIEW", permitVersion);
        }
        if (permit == null) {
            return new Eval("NON_COMPLIANT", null);
        }
        LocalDateTime validFrom = lt(permit.get("valid_from"));
        LocalDateTime validTo = lt(permit.get("valid_to"));
        boolean fullyInWindow = !hour.isBefore(validFrom) && !hour.plusHours(1).isAfter(validTo);
        if (!fullyInWindow) {
            return new Eval("NON_COMPLIANT", permitVersion);
        }
        if (rainfall.compareTo(bd(permit.get("min_rainfall"))) < 0) {
            return new Eval("NON_COMPLIANT", permitVersion);
        }
        return new Eval("COMPLIANT", permitVersion);
    }

    // ---------------- 标记复核（冻结许可及雨量版本）与日结（双口径冻结），竞争冻结一致 ----------------

    @Transactional
    public OverflowReviewResult review(OverflowReviewRequest req) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("outletCode", req.outletCode());
        fields.put("day", req.day() == null ? null : req.day().toString());
        return idem.execute(req.requestId(), "OVERFLOW_REVIEW", fields, OverflowReviewResult.class,
                () -> doReview(req), true);
    }

    private OverflowReviewResult doReview(OverflowReviewRequest req) {
        String code = requireText(req.outletCode(), "outletCode");
        Map<String, Object> outlet = loadOutlet(code);
        rejectIfArchived(outlet, code);
        if (req.day() == null) {
            throw new IllegalArgumentException("day 不能为空（UTC 日）");
        }
        LocalDateTime dayStart = req.day().atStartOfDay();
        // 冻结该日全部小时标记（所用许可及雨量版本随行冻结，之后只能独立调差）。
        int reviewed = overflowRepo.markReviewed(code, dayStart, dayStart.plusDays(1));
        // 连续段重建：跨许可或出现缺点必须断段，超最长段即专项异常。
        List<OverflowAnomalyView> anomalies = rebuildSegments(code);
        // 复核同时冻结日结快照；与日结竞争时以先冻者为准，双口径结果一致。
        OverflowSnapshotView snapshot = freezeSnapshot(code, req.day(), "REVIEW", req.requestId());
        return new OverflowReviewResult(code, req.day(), reviewed, anomalies, snapshot);
    }

    @Transactional
    public OverflowSnapshotView settle(OverflowSettlementRequest req) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("outletCode", req.outletCode());
        fields.put("day", req.day() == null ? null : req.day().toString());
        return idem.execute(req.requestId(), "OVERFLOW_SETTLE", fields, OverflowSnapshotView.class,
                () -> doSettle(req), true);
    }

    private OverflowSnapshotView doSettle(OverflowSettlementRequest req) {
        String code = requireText(req.outletCode(), "outletCode");
        Map<String, Object> outlet = loadOutlet(code);
        rejectIfArchived(outlet, code);
        if (req.day() == null) {
            throw new IllegalArgumentException("day 不能为空（UTC 日）");
        }
        return freezeSnapshot(code, req.day(), "SETTLEMENT", req.requestId());
    }

    /**
     * 冻结日结快照：排口＋UTC日唯一约束保证复核与日结竞争时仅一个冻结成功；
     * 竞争失败或重复冻结读取已冻结快照返回，保证双口径结果一致。
     */
    private OverflowSnapshotView freezeSnapshot(String code, LocalDate day, String frozenBy, String requestId) {
        LocalDateTime dayStart = day.atStartOfDay();
        Map<String, Object> existing = overflowRepo.findSnapshot(code, dayStart);
        if (existing != null) {
            return toSnapshotView(existing);
        }
        LocalDateTime dayEnd = dayStart.plusDays(1);
        DualStats dual = computeDual(code, dayStart, dayEnd);
        Map<String, Object> permit = overflowRepo.findCurrentPermit(code);
        Integer permitVersion = permit == null ? null : ((Number) permit.get("version")).intValue();
        Map<String, Object> rainfallVersions = new LinkedHashMap<>();
        for (Map<String, Object> h : overflowRepo.findHourlyInRange(code, dayStart, dayEnd)) {
            rainfallVersions.put(lt(h.get("hour_start")).toString(),
                    ((Number) h.get("rainfall_version")).intValue());
        }
        String rainfallVersionsJson = idem.toJson(rainfallVersions);
        String missingJson = idem.toJson(dual.missing().stream().map(LocalDateTime::toString).toList());
        boolean inserted = overflowRepo.insertSnapshot(code, dayStart, permitVersion, rainfallVersionsJson,
                dual.normalAvg(), dual.normalCount(), dual.allAvg(), dual.allCount(),
                missingJson, frozenBy, requestId, clock.now());
        if (!inserted) {
            // 竞争冻结失败：以先冻者为准。
            return toSnapshotView(overflowRepo.findSnapshot(code, dayStart));
        }
        return toSnapshotView(overflowRepo.findSnapshot(code, dayStart));
    }

    /**
     * 连续段重建：仅证据齐全的合规溢流小时成段；相邻整点不连续（缺点）、
     * 许可版本变化（跨许可）或非合规小时均断段；段长超过该段许可的最大连续小时数即专项异常。
     */
    private List<OverflowAnomalyView> rebuildSegments(String code) {
        List<Map<String, Object>> rows = overflowRepo.findAllHourly(code);
        overflowRepo.deleteAnomalies(code);
        List<OverflowAnomalyView> anomalies = new ArrayList<>();
        List<Map<String, Object>> segment = new ArrayList<>();
        Map<String, Object> prev = null;
        for (Map<String, Object> row : rows) {
            boolean compliant = "COMPLIANT".equals(row.get("eval_status"));
            boolean continues = compliant && prev != null
                    && "COMPLIANT".equals(prev.get("eval_status"))
                    && lt(row.get("hour_start")).equals(lt(prev.get("hour_start")).plusHours(1))
                    && Objects.equals(permitVersionOf(row), permitVersionOf(prev));
            if (continues) {
                segment.add(row);
            } else {
                anomalies.addAll(finishSegment(code, segment));
                segment.clear();
                if (compliant) {
                    segment.add(row);
                }
            }
            prev = row;
        }
        anomalies.addAll(finishSegment(code, segment));
        return anomalies;
    }

    private List<OverflowAnomalyView> finishSegment(String code, List<Map<String, Object>> segment) {
        if (segment.isEmpty()) {
            return List.of();
        }
        Integer permitVersion = permitVersionOf(segment.get(0));
        Map<String, Object> permit = permitVersion == null
                ? null : overflowRepo.findPermitByVersion(code, permitVersion);
        int maxAllowed = permit == null ? 0 : ((Number) permit.get("max_consecutive_hours")).intValue();
        if (segment.size() <= maxAllowed) {
            return List.of();
        }
        LocalDateTime start = lt(segment.get(0).get("hour_start"));
        LocalDateTime end = lt(segment.get(segment.size() - 1).get("hour_start")).plusHours(1);
        overflowRepo.insertAnomaly(code, start, end, segment.size(), maxAllowed, clock.now());
        return List.of(new OverflowAnomalyView(start, end, segment.size(), maxAllowed));
    }

    // ---------------- 独立调差（冻结后唯一更正方式，不改日结快照） ----------------

    @Transactional
    public OverflowAdjustmentView adjust(OverflowAdjustmentRequest req) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("outletCode", req.outletCode());
        fields.put("hour", req.hour() == null ? null : req.hour().toString());
        fields.put("adjustValue", req.adjustValue());
        fields.put("reason", req.reason());
        return idem.execute(req.requestId(), "OVERFLOW_ADJUST", fields, OverflowAdjustmentView.class,
                () -> doAdjust(req), true);
    }

    private OverflowAdjustmentView doAdjust(OverflowAdjustmentRequest req) {
        String code = requireText(req.outletCode(), "outletCode");
        Map<String, Object> outlet = loadOutlet(code);
        rejectIfArchived(outlet, code);
        validateHour(req.hour());
        if (req.adjustValue() == null) {
            throw new IllegalArgumentException("adjustValue 不能为空");
        }
        requireText(req.reason(), "reason");
        if (overflowRepo.findSnapshot(code, dayStartOf(req.hour())) == null) {
            throw new UnprocessableException(
                    "该小时所在 UTC 日尚未复核/日结冻结，不能独立调差（冻结前请更正雨量）: " + req.hour());
        }
        LocalDateTime now = clock.now();
        long id = overflowRepo.insertAdjustment(code, req.hour(), req.adjustValue(), req.reason(),
                req.requestId(), now);
        return new OverflowAdjustmentView(id, code, req.hour(), req.adjustValue(), req.reason(), now);
    }

    // ---------------- 双口径统计查询 ----------------

    /**
     * 双口径统计：NORMAL 仅排除证据齐全的合规溢流小时，ALL 不排除；
     * 分母与缺点（无浓度读数的 UTC 整点）明示；空集合均值为 null（无结果而非零）。
     */
    @Transactional(readOnly = true)
    public DischargeStatsView stats(String code, LocalDateTime from, LocalDateTime to, StatsCaliber caliber) {
        requireText(code, "outletCode");
        loadOutlet(code);
        validateHour(from);
        validateHour(to);
        if (!from.isBefore(to)) {
            throw new IllegalArgumentException("from 必须早于 to（UTC 半开区间）");
        }
        if (caliber == null) {
            throw new IllegalArgumentException("caliber 不能为空（NORMAL/ALL）");
        }
        DualStats dual = computeDual(code, from, to);
        if (caliber == StatsCaliber.ALL) {
            return new DischargeStatsView(code, from, to, "ALL", dual.allAvg(), dual.allCount(),
                    dual.missing(), List.of());
        }
        return new DischargeStatsView(code, from, to, "NORMAL", dual.normalAvg(), dual.normalCount(),
                dual.missing(), dual.excludedCompliant());
    }

    @Transactional(readOnly = true)
    public OverflowSnapshotView getSnapshot(String code, LocalDate day) {
        requireText(code, "outletCode");
        loadOutlet(code);
        if (day == null) {
            throw new IllegalArgumentException("day 不能为空（UTC 日）");
        }
        Map<String, Object> snapshot = overflowRepo.findSnapshot(code, day.atStartOfDay());
        if (snapshot == null) {
            throw new NotFoundException("日结快照不存在: " + code + " " + day);
        }
        return toSnapshotView(snapshot);
    }

    @Transactional(readOnly = true)
    public List<OverflowAnomalyView> listAnomalies(String code) {
        requireText(code, "outletCode");
        loadOutlet(code);
        return overflowRepo.findAnomalies(code).stream()
                .map(a -> new OverflowAnomalyView(lt(a.get("segment_start")), lt(a.get("segment_end")),
                        ((Number) a.get("segment_hours")).intValue(),
                        ((Number) a.get("max_allowed")).intValue()))
                .toList();
    }

    // ---------------- 双口径计算 ----------------

    /**
     * 计算区间 [from, to) 双口径结果：浓度读数永远保留，溢流标记只决定正常口径是否排除；
     * 待核（缺雨量）小时证据不齐全，不排除。
     */
    private DualStats computeDual(String code, LocalDateTime from, LocalDateTime to) {
        Map<LocalDateTime, BigDecimal> readings = new LinkedHashMap<>();
        for (Map<String, Object> r : overflowRepo.findConcentrationReadings(code, from, to)) {
            readings.put(lt(r.get("period_start")), bd(r.get("avg_value")));
        }
        Set<LocalDateTime> compliant = new HashSet<>();
        for (Map<String, Object> h : overflowRepo.findCompliantHours(code, from, to)) {
            compliant.add(lt(h.get("hour_start")));
        }
        List<LocalDateTime> missing = new ArrayList<>();
        List<LocalDateTime> excludedCompliant = new ArrayList<>();
        List<BigDecimal> all = new ArrayList<>();
        List<BigDecimal> normal = new ArrayList<>();
        for (LocalDateTime h = from; h.isBefore(to); h = h.plusHours(1)) {
            BigDecimal value = readings.get(h);
            if (value == null) {
                missing.add(h);
                continue;
            }
            all.add(value);
            if (compliant.contains(h)) {
                excludedCompliant.add(h);
            } else {
                normal.add(value);
            }
        }
        return new DualStats(avg(all), all.size(), avg(normal), normal.size(), missing, excludedCompliant);
    }

    /** 空集合返回 null（无结果而非零）；否则算术均值，保留 4 位小数。 */
    private static BigDecimal avg(List<BigDecimal> values) {
        if (values.isEmpty()) {
            return null;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal v : values) {
            sum = sum.add(v);
        }
        return sum.divide(BigDecimal.valueOf(values.size()), 4, RoundingMode.HALF_UP);
    }

    // ---------------- 公共辅助 ----------------

    private Map<String, Object> loadOutlet(String code) {
        Map<String, Object> outlet = repo.findOutlet(code);
        if (outlet == null) {
            throw new NotFoundException("排口不存在: " + code);
        }
        return outlet;
    }

    private void rejectIfArchived(Map<String, Object> outlet, String code) {
        if ("ARCHIVED".equals(outlet.get("status"))) {
            throw new UnprocessableException("排口已归档（终态），不再受理雨季溢流业务: " + code);
        }
    }

    /** UTC 整点校验：小时数据固定在 UTC 整点，非整点拒绝（400）。 */
    private static void validateHour(LocalDateTime hour) {
        if (hour == null) {
            throw new IllegalArgumentException("小时时刻不能为空（UTC 整点）");
        }
        if (!hour.equals(hour.truncatedTo(ChronoUnit.HOURS))) {
            throw new IllegalArgumentException("小时数据必须固定在 UTC 整点: " + hour);
        }
    }

    private static LocalDateTime dayStartOf(LocalDateTime hour) {
        return hour.toLocalDate().atStartOfDay();
    }

    private static Integer permitVersionOf(Map<String, Object> row) {
        Object v = row.get("permit_version");
        return v == null ? null : ((Number) v).intValue();
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

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value;
    }

    private static void requireNonNullNonNegative(BigDecimal value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        if (value.signum() < 0) {
            throw new IllegalArgumentException(field + " 不能为负数");
        }
    }

    private static OverflowPermitView toPermitView(Map<String, Object> p) {
        return new OverflowPermitView(
                (String) p.get("outlet_code"),
                ((Number) p.get("version")).intValue(),
                lt(p.get("valid_from")),
                lt(p.get("valid_to")),
                bd(p.get("min_rainfall")),
                ((Number) p.get("max_consecutive_hours")).intValue(),
                lt(p.get("updated_at")));
    }

    private static OverflowHourView toHourView(Map<String, Object> h) {
        return new OverflowHourView(
                lt(h.get("hour_start")),
                bd(h.get("rainfall")),
                ((Number) h.get("rainfall_version")).intValue(),
                Boolean.TRUE.equals(h.get("overflow_flag")),
                (String) h.get("eval_status"),
                h.get("permit_version") == null ? null : ((Number) h.get("permit_version")).intValue(),
                Boolean.TRUE.equals(h.get("reviewed")));
    }

    private OverflowSnapshotView toSnapshotView(Map<String, Object> s) {
        List<LocalDateTime> missing = idem.fromJson((String) s.get("missing_hours"),
                idem.listType(LocalDateTime.class));
        return new OverflowSnapshotView(
                (String) s.get("outlet_code"),
                lt(s.get("day_start")).toLocalDate(),
                s.get("permit_version") == null ? null : ((Number) s.get("permit_version")).intValue(),
                (String) s.get("rainfall_versions"),
                bd(s.get("normal_avg")),
                ((Number) s.get("normal_count")).intValue(),
                bd(s.get("all_avg")),
                ((Number) s.get("all_count")).intValue(),
                missing,
                (String) s.get("frozen_by"),
                lt(s.get("created_at")));
    }

    /** 单小时评估结果：评估状态与所用许可版本（无许可为 null）。 */
    private record Eval(String status, Integer permitVersion) {
    }

    /** 双口径计算结果。 */
    private record DualStats(BigDecimal allAvg, int allCount, BigDecimal normalAvg, int normalCount,
                             List<LocalDateTime> missing, List<LocalDateTime> excludedCompliant) {
    }
}
