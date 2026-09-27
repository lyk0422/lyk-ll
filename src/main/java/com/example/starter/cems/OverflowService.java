package com.example.starter.cems;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Supplier;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.cems.OverflowTypes.AnomalyView;
import com.example.starter.cems.OverflowTypes.AveragesView;
import com.example.starter.cems.OverflowTypes.HourMarkView;
import com.example.starter.cems.OverflowTypes.HourReportRequest;
import com.example.starter.cems.OverflowTypes.HourReportResult;
import com.example.starter.cems.OverflowTypes.OverflowHourItem;
import com.example.starter.cems.OverflowTypes.PermitUpsertRequest;
import com.example.starter.cems.OverflowTypes.PermitView;
import com.example.starter.cems.OverflowTypes.RainfallCorrectionRequest;
import com.example.starter.cems.OverflowTypes.RainfallCorrectionResult;
import com.example.starter.cems.OverflowTypes.ReviewFreezeRequest;
import com.example.starter.cems.OverflowTypes.ReviewView;

/**
 * 雨季溢流证据与双口径统计服务（cems-02 新增，合成规则，不用于生产）。
 * 溢流证据独立留痕：原排放浓度读数、超标链与限排令记录不因溢流标记而改变；
 * 溢流不取消已生效限排令，也不减轻原超标判定。
 * 所有写路径（许可版本、小时上报、雨量更正、日结冻结）先取排口行锁，
 * 业务、版本号与幂等结果在同一事务内提交；失败整体回滚、不占幂等键。
 */
@Service
public class OverflowService {

    /** 日结小时数：UTC 日固定 24 个整点槽位。 */
    static final int DAY_SLOTS = 24;

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

    // ---------------- 溢流许可版本（expectedVersion 乐观并发） ----------------

    @Transactional
    public PermitView upsertPermit(PermitUpsertRequest req) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("outletCode", req.outletCode());
        fields.put("expectedVersion", req.expectedVersion());
        fields.put("windowStartUtc", req.windowStartUtc() == null ? null : req.windowStartUtc().toString());
        fields.put("windowEndUtc", req.windowEndUtc() == null ? null : req.windowEndUtc().toString());
        fields.put("minRainfallMm", req.minRainfallMm());
        fields.put("maxConsecutiveHours", req.maxConsecutiveHours());
        return replayable(req.requestId(), "OVERFLOW_PERMIT", fields, PermitView.class,
                () -> doUpsertPermit(req));
    }

    private PermitView doUpsertPermit(PermitUpsertRequest req) {
        String code = requireText(req.outletCode(), "outletCode");
        loadOutlet(code);
        if (req.expectedVersion() == null) {
            throw new IllegalArgumentException("expectedVersion 不能为空（无许可时传 0）");
        }
        if (req.expectedVersion() < 0) {
            throw new IllegalArgumentException("expectedVersion 不能为负数");
        }
        if (req.windowStartUtc() == null || req.windowEndUtc() == null) {
            throw new IllegalArgumentException("windowStartUtc/windowEndUtc 不能为空");
        }
        if (!req.windowEndUtc().isAfter(req.windowStartUtc())) {
            throw new IllegalArgumentException("windowEndUtc 必须晚于 windowStartUtc（UTC 半开窗口）");
        }
        requireNonNullNonNegative(req.minRainfallMm(), "minRainfallMm");
        if (req.maxConsecutiveHours() == null || req.maxConsecutiveHours() < 1) {
            throw new IllegalArgumentException("maxConsecutiveHours 必须为正整数");
        }
        cemsRepo.lockOutlet(code);
        Map<String, Object> latest = repo.findLatestPermit(code);
        int current = latest == null ? 0 : ((Number) latest.get("version")).intValue();
        if (req.expectedVersion() != current) {
            throw new ConflictException("溢流许可版本不匹配: expectedVersion=" + req.expectedVersion()
                    + " 当前版本=" + current);
        }
        int newVersion = current + 1;
        LocalDateTime now = clock.now();
        repo.supersedeActivePermits(code);
        try {
            repo.insertPermit(code, newVersion, toUtcLdt(req.windowStartUtc()), toUtcLdt(req.windowEndUtc()),
                    req.minRainfallMm(), req.maxConsecutiveHours(), now);
        } catch (DuplicateKeyException e) {
            throw new ConflictException("溢流许可版本 " + newVersion + " 已存在（并发新增冲突）: " + code);
        }
        cemsRepo.insertEvent(code, "OVERFLOW_PERMIT", now, false,
                "version=" + newVersion + " window=[" + req.windowStartUtc() + "," + req.windowEndUtc() + ")"
                        + " minRainfallMm=" + req.minRainfallMm()
                        + " maxConsecutiveHours=" + req.maxConsecutiveHours());
        // 许可边界变化后按既有小时数据重建分段（跨许可必须断段）。
        rebuildSegments(code);
        return toPermitView(repo.findLatestPermit(code));
    }

    // ---------------- 小时数据上报与合规溢流标记 ----------------

    @Transactional
    public HourReportResult reportHours(HourReportRequest req) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("outletCode", req.outletCode());
        fields.put("hours", canonicalHours(req.hours()));
        return replayable(req.requestId(), "OVERFLOW_HOURS", fields, HourReportResult.class,
                () -> doReportHours(req));
    }

    private HourReportResult doReportHours(HourReportRequest req) {
        String code = requireText(req.outletCode(), "outletCode");
        loadOutlet(code);
        if (req.hours() == null || req.hours().isEmpty()) {
            throw new IllegalArgumentException("hours 不能为空集合");
        }
        Map<String, Boolean> seen = new LinkedHashMap<>();
        for (OverflowHourItem item : req.hours()) {
            validateHourItem(item);
            String key = item.hourUtc().toString();
            if (seen.put(key, Boolean.TRUE) != null) {
                throw new IllegalArgumentException("hours 内存在重复 UTC 整点: " + key);
            }
        }
        List<OverflowHourItem> items = new ArrayList<>(req.hours());
        items.sort(Comparator.comparing(OverflowHourItem::hourUtc));
        cemsRepo.lockOutlet(code);
        LocalDateTime now = clock.now();
        repo.ensureCounter(code, now);
        int reported = 0;
        int deduplicated = 0;
        List<HourMarkView> views = new ArrayList<>();
        for (OverflowHourItem item : items) {
            LocalDateTime hourUtc = toUtcLdt(item.hourUtc());
            Map<String, Object> existing = repo.findHour(code, hourUtc);
            if (existing != null) {
                // 排口＋整点去重：同参去重，异参 409；雨量更正须走更正入口。
                if (sameHourData(existing, item)) {
                    views.add(toHourMarkView(existing, true));
                    deduplicated++;
                    continue;
                }
                throw new ConflictException("排口＋UTC 整点小时数据已存在且内容不同: " + code + " " + item.hourUtc());
            }
            MarkDecision decision = evaluateNewHour(code, item, hourUtc);
            repo.insertHour(code, hourUtc, item.rainfallMm(), item.overflowFlag(), item.avgValue(),
                    decision.markStatus(), decision.permitVersion(), now);
            reported++;
            views.add(new HourMarkView(item.hourUtc(), decision.markStatus(), decision.permitVersion(),
                    item.rainfallMm(), item.overflowFlag(), item.avgValue(), false));
        }
        rebuildSegments(code);
        cemsRepo.insertEvent(code, "OVERFLOW_HOURS", now, false,
                "reported=" + reported + " deduplicated=" + deduplicated);
        return new HourReportResult(req.requestId(), code, reported, deduplicated, views);
    }

    /**
     * 新小时标记判定：仅在许可有效、雨量≥门槛（恰等合法）且该小时完整落窗时可标合规溢流；
     * 缺雨量只能待核（不当零也不豁免）；其余溢流标记请求属业务拒绝（422）。
     */
    private MarkDecision evaluateNewHour(String code, OverflowHourItem item, LocalDateTime hourUtc) {
        if (!item.overflowFlag()) {
            return new MarkDecision("NORMAL", null);
        }
        if (item.rainfallMm() == null) {
            return new MarkDecision("PENDING", null);
        }
        Map<String, Object> permit = repo.findActivePermit(code);
        if (permit == null) {
            throw new BusinessException("无现行溢流许可，不能标记合规溢流: " + code);
        }
        LocalDateTime windowStart = lt(permit.get("window_start_utc"));
        LocalDateTime windowEnd = lt(permit.get("window_end_utc"));
        if (hourUtc.isBefore(windowStart) || hourUtc.plusHours(1).isAfter(windowEnd)) {
            throw new BusinessException("该小时未完整落入许可 UTC 半开窗口 [" + windowStart + "," + windowEnd
                    + "): " + hourUtc);
        }
        BigDecimal minRainfall = bd(permit.get("min_rainfall_mm"));
        if (item.rainfallMm().compareTo(minRainfall) < 0) {
            throw new BusinessException("小时雨量 " + item.rainfallMm() + "mm 低于许可门槛 "
                    + minRainfall + "mm，不能标记合规溢流");
        }
        return new MarkDecision("COMPLIANT", ((Number) permit.get("version")).intValue());
    }

    // ---------------- 雨量更正（复核前重算 / 复核后独立调差） ----------------

    @Transactional
    public RainfallCorrectionResult correctRainfall(RainfallCorrectionRequest req) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("outletCode", req.outletCode());
        fields.put("hourUtc", req.hourUtc() == null ? null : req.hourUtc().toString());
        fields.put("rainfallMm", req.rainfallMm());
        fields.put("expectedVersion", req.expectedVersion());
        return replayable(req.requestId(), "OVERFLOW_CORRECTION", fields,
                RainfallCorrectionResult.class, () -> doCorrectRainfall(req));
    }

    private RainfallCorrectionResult doCorrectRainfall(RainfallCorrectionRequest req) {
        String code = requireText(req.outletCode(), "outletCode");
        loadOutlet(code);
        if (req.hourUtc() == null) {
            throw new IllegalArgumentException("hourUtc 不能为空");
        }
        requireWholeHour(req.hourUtc(), "hourUtc");
        requireNonNullNonNegative(req.rainfallMm(), "rainfallMm");
        if (req.expectedVersion() == null) {
            throw new IllegalArgumentException("expectedVersion 不能为空");
        }
        cemsRepo.lockOutlet(code);
        LocalDateTime hourUtc = toUtcLdt(req.hourUtc());
        Map<String, Object> hour = repo.findHour(code, hourUtc);
        if (hour == null) {
            throw new NotFoundException("小时数据不存在: " + code + " " + req.hourUtc());
        }
        Integer currentVersion = repo.findRainfallVersion(code);
        if (currentVersion == null || req.expectedVersion() != currentVersion) {
            throw new ConflictException("雨量版本不匹配: expectedVersion=" + req.expectedVersion()
                    + " 当前版本=" + currentVersion);
        }
        LocalDateTime now = clock.now();
        if (repo.bumpRainfallVersion(code, currentVersion, now) == 0) {
            throw new ConflictException("雨量版本已变化，更正未受理: " + code);
        }
        int newVersion = currentVersion + 1;
        LocalDate dayUtc = hourUtc.toLocalDate();
        BigDecimal oldRainfall = bd(hour.get("rainfall_mm"));
        if (repo.findReview(code, dayUtc) != null) {
            // 复核冻结后：只登记独立调差，不改日结快照，也不改已冻结小时的雨量与标记。
            repo.insertAdjustment(code, dayUtc, hourUtc, oldRainfall, req.rainfallMm(),
                    newVersion, req.requestId(), now);
            cemsRepo.insertEvent(code, "OVERFLOW_ADJUSTMENT", now, false,
                    "day=" + dayUtc + " hour=" + req.hourUtc() + " rainfall " + oldRainfall
                            + "->" + req.rainfallMm() + " rainfallVersion=" + newVersion);
            return new RainfallCorrectionResult(req.requestId(), code, req.hourUtc(), newVersion,
                    true, (String) hour.get("mark_status"), true);
        }
        // 复核冻结前：更正雨量并重算该小时标记与连续分段（同事务）。
        MarkDecision decision = reevaluateMark(code, bool(hour.get("overflow_flag")), req.rainfallMm(), hourUtc);
        repo.updateHourRainfallAndMark(code, hourUtc, req.rainfallMm(),
                decision.markStatus(), decision.permitVersion(), now);
        rebuildSegments(code);
        cemsRepo.insertEvent(code, "OVERFLOW_CORRECTION", now, false,
                "hour=" + req.hourUtc() + " rainfall " + oldRainfall + "->" + req.rainfallMm()
                        + " mark=" + decision.markStatus() + " rainfallVersion=" + newVersion);
        return new RainfallCorrectionResult(req.requestId(), code, req.hourUtc(), newVersion,
                false, decision.markStatus(), false);
    }

    /** 复核前重算：按当前现行许可与更正后雨量重新判定；证据不足降级为 NORMAL（更正属数据维护，不拒绝）。 */
    private MarkDecision reevaluateMark(String code, boolean overflowFlag, BigDecimal rainfallMm,
                                        LocalDateTime hourUtc) {
        if (!overflowFlag) {
            return new MarkDecision("NORMAL", null);
        }
        Map<String, Object> permit = repo.findActivePermit(code);
        if (permit == null) {
            return new MarkDecision("NORMAL", null);
        }
        LocalDateTime windowStart = lt(permit.get("window_start_utc"));
        LocalDateTime windowEnd = lt(permit.get("window_end_utc"));
        if (hourUtc.isBefore(windowStart) || hourUtc.plusHours(1).isAfter(windowEnd)) {
            return new MarkDecision("NORMAL", null);
        }
        if (rainfallMm.compareTo(bd(permit.get("min_rainfall_mm"))) < 0) {
            return new MarkDecision("NORMAL", null);
        }
        return new MarkDecision("COMPLIANT", ((Number) permit.get("version")).intValue());
    }

    // ---------------- 日结复核冻结（双口径快照） ----------------

    @Transactional
    public ReviewView freezeReview(ReviewFreezeRequest req) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("outletCode", req.outletCode());
        fields.put("dayUtc", req.dayUtc() == null ? null : req.dayUtc().toString());
        return replayable(req.requestId(), "OVERFLOW_REVIEW", fields, ReviewView.class,
                () -> doFreezeReview(req));
    }

    private ReviewView doFreezeReview(ReviewFreezeRequest req) {
        String code = requireText(req.outletCode(), "outletCode");
        loadOutlet(code);
        if (req.dayUtc() == null) {
            throw new IllegalArgumentException("dayUtc 不能为空");
        }
        // 与小时标记写路径竞争同一排口行锁：冻结到的双口径结果必然来自同一一致快照。
        cemsRepo.lockOutlet(code);
        if (repo.findReview(code, req.dayUtc()) != null) {
            throw new ConflictException("该 UTC 日已复核冻结，不能重复冻结: " + code + " " + req.dayUtc());
        }
        LocalDateTime dayStart = toUtcLdt(req.dayUtc().atStartOfDay(ZoneOffset.UTC).toInstant());
        List<Map<String, Object>> hours = repo.findHoursBetween(code, dayStart, dayStart.plusDays(1));
        for (Map<String, Object> h : hours) {
            if ("PENDING".equals(h.get("mark_status"))) {
                throw new BusinessException("存在待核小时（缺雨量）" + lt(h.get("hour_utc"))
                        + "，不能复核冻结；缺雨量不能当零或直接豁免");
            }
        }
        LocalDateTime now = clock.now();
        Map<String, Object> permit = repo.findActivePermit(code);
        Integer permitVersion = permit == null ? null : ((Number) permit.get("version")).intValue();
        Integer rainfallVersion = repo.findRainfallVersion(code);
        AveragePair averages = computeAverages(hours);
        int missingCount = DAY_SLOTS - hours.size();
        try {
            repo.insertReview(code, req.dayUtc(), permitVersion,
                    rainfallVersion == null ? 0 : rainfallVersion,
                    averages.normalAvg(), averages.normalCount(),
                    averages.allAvg(), averages.allCount(), missingCount, req.requestId(), now);
        } catch (DuplicateKeyException e) {
            throw new ConflictException("该 UTC 日已复核冻结（并发冻结唯一受理）: " + code + " " + req.dayUtc());
        }
        cemsRepo.insertEvent(code, "OVERFLOW_REVIEW", now, false,
                "day=" + req.dayUtc() + " permitVersion=" + permitVersion
                        + " rainfallVersion=" + rainfallVersion
                        + " normal=" + averages.normalAvg() + "/" + averages.normalCount()
                        + " all=" + averages.allAvg() + "/" + averages.allCount());
        return toReviewView(repo.findReview(code, req.dayUtc()));
    }

    // ---------------- 双口径均值查询 ----------------

    /**
     * 双口径均值：正常排放口径仅排除证据齐全的合规溢流小时（待核不排除），全部排放口径不排除；
     * 分母与缺点（无记录整点）明示；空集合均值为 null（无结果而非零）。
     */
    @Transactional(readOnly = true)
    public AveragesView averages(String code, Instant fromUtc, Instant toUtc) {
        requireText(code, "outletCode");
        loadOutlet(code);
        if (fromUtc == null || toUtc == null) {
            throw new IllegalArgumentException("from/to 不能为空（UTC 精确时刻，如 2026-09-26T00:00:00Z）");
        }
        requireWholeHour(fromUtc, "from");
        requireWholeHour(toUtc, "to");
        if (!toUtc.isAfter(fromUtc)) {
            throw new IllegalArgumentException("to 必须晚于 from（UTC 半开区间）");
        }
        LocalDateTime from = toUtcLdt(fromUtc);
        LocalDateTime to = toUtcLdt(toUtc);
        Map<String, Map<String, Object>> byHour = new LinkedHashMap<>();
        for (Map<String, Object> h : repo.findHoursBetween(code, from, to)) {
            byHour.put(lt(h.get("hour_utc")).toString(), h);
        }
        long totalSlots = java.time.Duration.between(from, to).toHours();
        List<Instant> missingHours = new ArrayList<>();
        List<Map<String, Object>> reported = new ArrayList<>();
        for (long i = 0; i < totalSlots; i++) {
            LocalDateTime slot = from.plusHours(i);
            Map<String, Object> h = byHour.get(slot.toString());
            if (h == null) {
                missingHours.add(slot.toInstant(ZoneOffset.UTC));
            } else {
                reported.add(h);
            }
        }
        AveragePair averages = computeAverages(reported);
        int pendingCount = (int) reported.stream()
                .filter(h -> "PENDING".equals(h.get("mark_status"))).count();
        return new AveragesView(code, fromUtc, toUtc, totalSlots, reported.size(),
                missingHours.size(), pendingCount,
                averages.normalAvg(), averages.normalCount(),
                averages.allAvg(), averages.allCount(), missingHours);
    }

    /** 双口径统计：normal 仅排除 COMPLIANT；空集合均值为 null。 */
    private AveragePair computeAverages(List<Map<String, Object>> hours) {
        BigDecimal allSum = BigDecimal.ZERO;
        int allCount = 0;
        BigDecimal normalSum = BigDecimal.ZERO;
        int normalCount = 0;
        for (Map<String, Object> h : hours) {
            BigDecimal value = bd(h.get("avg_value"));
            allSum = allSum.add(value);
            allCount++;
            if (!"COMPLIANT".equals(h.get("mark_status"))) {
                normalSum = normalSum.add(value);
                normalCount++;
            }
        }
        return new AveragePair(
                normalCount == 0 ? null : normalSum.divide(BigDecimal.valueOf(normalCount), 4,
                        RoundingMode.HALF_UP),
                normalCount,
                allCount == 0 ? null : allSum.divide(BigDecimal.valueOf(allCount), 4,
                        RoundingMode.HALF_UP),
                allCount);
    }

    // ---------------- 连续分段与专项异常 ----------------

    /**
     * 连续合规溢流分段：相邻 UTC 整点且许可版本相同才连续；跨许可或出现缺点（无记录/待核/非合规）
     * 必须断段；段长严格超过许可最长连续小时数即登记专项异常。
     */
    private void rebuildSegments(String code) {
        Map<Integer, Map<String, Object>> permitsByVersion = new LinkedHashMap<>();
        for (Map<String, Object> p : repo.findPermits(code)) {
            permitsByVersion.put(((Number) p.get("version")).intValue(), p);
        }
        repo.deleteAnomalies(code);
        LocalDateTime now = clock.now();
        List<Map<String, Object>> run = new ArrayList<>();
        Map<String, Object> prevCompliant = null;
        for (Map<String, Object> h : repo.findHours(code)) {
            if (!"COMPLIANT".equals(h.get("mark_status"))) {
                flushSegment(code, run, permitsByVersion, now);
                run = new ArrayList<>();
                prevCompliant = null;
                continue;
            }
            boolean adjacent = prevCompliant != null
                    && lt(h.get("hour_utc")).equals(lt(prevCompliant.get("hour_utc")).plusHours(1))
                    && Objects.equals(permitVersionOf(h), permitVersionOf(prevCompliant));
            if (!adjacent) {
                flushSegment(code, run, permitsByVersion, now);
                run = new ArrayList<>();
            }
            run.add(h);
            prevCompliant = h;
        }
        flushSegment(code, run, permitsByVersion, now);
    }

    private void flushSegment(String code, List<Map<String, Object>> run,
                              Map<Integer, Map<String, Object>> permitsByVersion, LocalDateTime now) {
        if (run.isEmpty()) {
            return;
        }
        Integer permitVersion = permitVersionOf(run.get(0));
        Map<String, Object> permit = permitVersion == null ? null : permitsByVersion.get(permitVersion);
        int maxAllowed = permit == null ? 0
                : ((Number) permit.get("max_consecutive_hours")).intValue();
        if (run.size() > maxAllowed) {
            LocalDateTime start = lt(run.get(0).get("hour_utc"));
            LocalDateTime end = lt(run.get(run.size() - 1).get("hour_utc")).plusHours(1);
            repo.insertAnomaly(code, start, end, run.size(), maxAllowed,
                    permitVersion == null ? 0 : permitVersion,
                    "连续合规溢流 " + run.size() + " 小时超过许可（版本 " + permitVersion
                            + "）最长连续 " + maxAllowed + " 小时", now);
        }
    }

    // ---------------- 查询 ----------------

    @Transactional(readOnly = true)
    public PermitView getCurrentPermit(String code) {
        requireText(code, "outletCode");
        loadOutlet(code);
        Map<String, Object> permit = repo.findActivePermit(code);
        if (permit == null) {
            throw new NotFoundException("排口无现行溢流许可: " + code);
        }
        return toPermitView(permit);
    }

    @Transactional(readOnly = true)
    public ReviewView getReview(String code, LocalDate dayUtc) {
        requireText(code, "outletCode");
        loadOutlet(code);
        Map<String, Object> review = repo.findReview(code, dayUtc);
        if (review == null) {
            throw new NotFoundException("日结复核快照不存在: " + code + " " + dayUtc);
        }
        return toReviewView(review);
    }

    @Transactional(readOnly = true)
    public List<AnomalyView> listAnomalies(String code) {
        requireText(code, "outletCode");
        loadOutlet(code);
        List<AnomalyView> views = new ArrayList<>();
        for (Map<String, Object> a : repo.findAnomalies(code)) {
            views.add(new AnomalyView(code,
                    lt(a.get("segment_start_utc")).toInstant(ZoneOffset.UTC),
                    lt(a.get("segment_end_utc")).toInstant(ZoneOffset.UTC),
                    ((Number) a.get("hour_count")).intValue(),
                    ((Number) a.get("max_allowed")).intValue(),
                    ((Number) a.get("permit_version")).intValue(),
                    (String) a.get("reason"), (String) a.get("status")));
        }
        return views;
    }

    // ---------------- 幂等支撑（与 CemsService 同构：同键同参回放、异参 409、失败不占键） ----------------

    private <T> T replayable(String requestId, String requestType, Map<String, Object> fields,
                             Class<T> resultType, Supplier<T> action) {
        String rid = requireText(requestId, "requestId");
        String fingerprint = idem.fingerprint(rid, fields);
        Map<String, Object> existing = cemsRepo.findIdempotent(rid);
        if (existing != null) {
            return replayOrConflict(rid, fingerprint, existing, resultType);
        }
        while (true) {
            if (cemsRepo.insertIdempotent(rid, requestType, fingerprint, "", clock.now())) {
                T result = action.get();
                cemsRepo.updateIdempotentResult(rid, idem.toJson(result));
                return result;
            }
            Map<String, Object> raced = waitForIdempotentResult(rid);
            if (raced != null) {
                return replayOrConflict(rid, fingerprint, raced, resultType);
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

    // ---------------- 公共辅助 ----------------

    private Map<String, Object> loadOutlet(String code) {
        Map<String, Object> outlet = cemsRepo.findOutlet(code);
        if (outlet == null) {
            throw new NotFoundException("排口不存在: " + code);
        }
        return outlet;
    }

    private void validateHourItem(OverflowHourItem item) {
        if (item == null) {
            throw new IllegalArgumentException("hours 内不能包含空项");
        }
        if (item.hourUtc() == null) {
            throw new IllegalArgumentException("hourUtc 不能为空");
        }
        requireWholeHour(item.hourUtc(), "hourUtc");
        if (item.overflowFlag() == null) {
            throw new IllegalArgumentException("overflowFlag 不能为空");
        }
        requireNonNullNonNegative(item.avgValue(), "avgValue");
        if (item.rainfallMm() != null && item.rainfallMm().signum() < 0) {
            throw new IllegalArgumentException("rainfallMm 不能为负数");
        }
    }

    private static void requireWholeHour(Instant instant, String field) {
        if (instant.getNano() != 0 || instant.getEpochSecond() % 3600 != 0) {
            throw new IllegalArgumentException(field + " 必须为 UTC 整点（分秒为 0）: " + instant);
        }
    }

    private static boolean sameHourData(Map<String, Object> existing, OverflowHourItem item) {
        BigDecimal existingRainfall = bd(existing.get("rainfall_mm"));
        boolean sameRainfall = existingRainfall == null ? item.rainfallMm() == null
                : item.rainfallMm() != null && existingRainfall.compareTo(item.rainfallMm()) == 0;
        return sameRainfall
                && bool(existing.get("overflow_flag")) == item.overflowFlag()
                && bd(existing.get("avg_value")).compareTo(item.avgValue()) == 0;
    }

    /** 幂等指纹用的小时集合规范化：按 UTC 整点排序，时刻用 ISO 文本，数值原样（JSON 结构化无歧义）。 */
    private static List<Map<String, Object>> canonicalHours(List<OverflowHourItem> hours) {
        if (hours == null) {
            return null;
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (OverflowHourItem item : hours) {
            if (item == null) {
                list.add(null);
                continue;
            }
            Map<String, Object> m = new TreeMap<>();
            m.put("hourUtc", item.hourUtc() == null ? null : item.hourUtc().toString());
            m.put("rainfallMm", item.rainfallMm());
            m.put("overflowFlag", item.overflowFlag());
            m.put("avgValue", item.avgValue());
            list.add(m);
        }
        list.sort(Comparator.comparing(m -> m == null ? "" : String.valueOf(m.get("hourUtc"))));
        return list;
    }

    private static Integer permitVersionOf(Map<String, Object> hourRow) {
        Object v = hourRow.get("permit_version");
        return v == null ? null : ((Number) v).intValue();
    }

    private static LocalDateTime toUtcLdt(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static BigDecimal bd(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof BigDecimal b ? b : new BigDecimal(value.toString());
    }

    private static boolean bool(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        return value instanceof Number n && n.intValue() != 0;
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

    private static PermitView toPermitView(Map<String, Object> p) {
        return new PermitView(
                (String) p.get("outlet_code"),
                ((Number) p.get("version")).intValue(),
                lt(p.get("window_start_utc")).toInstant(ZoneOffset.UTC),
                lt(p.get("window_end_utc")).toInstant(ZoneOffset.UTC),
                bd(p.get("min_rainfall_mm")),
                ((Number) p.get("max_consecutive_hours")).intValue(),
                (String) p.get("status"),
                lt(p.get("created_at")));
    }

    private static HourMarkView toHourMarkView(Map<String, Object> h, boolean deduplicated) {
        return new HourMarkView(
                lt(h.get("hour_utc")).toInstant(ZoneOffset.UTC),
                (String) h.get("mark_status"),
                permitVersionOf(h),
                bd(h.get("rainfall_mm")),
                bool(h.get("overflow_flag")),
                bd(h.get("avg_value")),
                deduplicated);
    }

    private static ReviewView toReviewView(Map<String, Object> r) {
        Object pv = r.get("permit_version");
        return new ReviewView(
                (String) r.get("outlet_code"),
                r.get("day_utc") instanceof java.sql.Date d ? d.toLocalDate()
                        : LocalDate.parse(r.get("day_utc").toString()),
                pv == null ? null : ((Number) pv).intValue(),
                ((Number) r.get("rainfall_version")).intValue(),
                bd(r.get("normal_avg")),
                ((Number) r.get("normal_count")).intValue(),
                bd(r.get("all_avg")),
                ((Number) r.get("all_count")).intValue(),
                ((Number) r.get("missing_count")).intValue(),
                (String) r.get("status"),
                lt(r.get("frozen_at")));
    }

    /** 小时标记判定结果。 */
    private record MarkDecision(String markStatus, Integer permitVersion) {
    }

    /** 双口径均值结果。 */
    private record AveragePair(BigDecimal normalAvg, int normalCount, BigDecimal allAvg, int allCount) {
    }
}
