package com.example.starter.cems;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.cems.CemsTypes.OverflowAnomalyView;
import com.example.starter.cems.CemsTypes.OverflowCaliberSummary;
import com.example.starter.cems.CemsTypes.OverflowDailySummaryView;
import com.example.starter.cems.CemsTypes.OverflowHourView;
import com.example.starter.cems.CemsTypes.OverflowHourlyRequest;
import com.example.starter.cems.CemsTypes.OverflowPermitCreateRequest;
import com.example.starter.cems.CemsTypes.OverflowPermitUpdateRequest;
import com.example.starter.cems.CemsTypes.OverflowPermitView;
import com.example.starter.cems.CemsTypes.OverflowReviewRequest;
import com.example.starter.cems.CemsTypes.OverflowReviewResult;
import com.example.starter.cems.CemsTypes.RainfallAdjustRequest;
import com.example.starter.cems.CemsTypes.RainfallAdjustResult;
import com.example.starter.cems.CemsTypes.RainfallReportRequest;
import com.example.starter.cems.CemsTypes.RainfallReportResult;

/**
 * 雨季溢流服务（合成许可，UTC 口径）。
 *
 * <p>证据链：溢流许可版本 → 小时雨量版本 → UTC 整点溢流小时 → 溢流证据标记（PENDING/READY/INELIGIBLE）
 * → 标记复核冻结（FROZEN_COMPLIANT，并在同一事务产出 NORMAL/ALL 双口径日结与专项异常）→ 冻结后仅允许独立调差。</p>
 *
 * <p>与既有 Asia/Shanghai 口径的浓度超标/限排链路完全独立：溢流不抹掉原浓度超标标记、
 * 不取消已生效限排令、不减轻原超标判定。</p>
 */
@Service
public class OverflowService {

    /** 一个 UTC 日的固定整点槽位数，用于明示缺点数。 */
    private static final int SLOTS_PER_DAY = 24;
    private static final int AVG_SCALE = 4;

    private final OverflowRepository repo;
    private final CemsRepository cemsRepo;
    private final CemsClock clock;
    private final IdempotencySupport idem;

    public OverflowService(OverflowRepository repo, CemsRepository cemsRepo, CemsClock clock,
                           IdempotencySupport idem) {
        this.repo = repo;
        this.cemsRepo = cemsRepo;
        this.clock = clock;
        this.idem = idem;
    }

    // ---------------- 溢流许可版本 ----------------

    @Transactional
    public OverflowPermitView createPermit(OverflowPermitCreateRequest req) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("outletCode", req.outletCode());
        fields.put("validFromUtc", req.validFromUtc() == null ? null : req.validFromUtc().toString());
        fields.put("validToUtc", req.validToUtc() == null ? null : req.validToUtc().toString());
        fields.put("minHourlyRainfallMm", req.minHourlyRainfallMm());
        fields.put("maxConsecutiveOverflowHours", req.maxConsecutiveOverflowHours());
        return replayable(req.requestId(), "OVERFLOW_PERMIT_CREATE", fields, OverflowPermitView.class,
                () -> doCreatePermit(req));
    }

    private OverflowPermitView doCreatePermit(OverflowPermitCreateRequest req) {
        String code = requireOutlet(req.outletCode());
        LocalDateTime from = requireUtcHour(req.validFromUtc(), "validFromUtc");
        LocalDateTime to = requireUtcHour(req.validToUtc(), "validToUtc");
        requireWindowOrder(from, to);
        requireRainfall(req.minHourlyRainfallMm());
        int maxHours = requirePositiveHours(req.maxConsecutiveOverflowHours());
        if (repo.findLatestPermit(code) != null) {
            throw new BusinessRejectException("溢流许可已存在，请携带 expectedVersion 修改: " + code);
        }
        validateNoOverlap(code, from, to);
        LocalDateTime now = clock.now();
        repo.insertPermit(code, 1, from, to, req.minHourlyRainfallMm(), maxHours, now);
        cemsRepo.insertEvent(code, "OVERFLOW_PERMIT", now, false,
                "CREATE version=1 window=[" + from + "," + to + ") minRain=" + req.minHourlyRainfallMm()
                        + " maxConsecutive=" + maxHours);
        return toPermitView(repo.findPermit(code, 1));
    }

    @Transactional
    public OverflowPermitView updatePermit(OverflowPermitUpdateRequest req) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("outletCode", req.outletCode());
        fields.put("expectedVersion", req.expectedVersion());
        fields.put("validFromUtc", req.validFromUtc() == null ? null : req.validFromUtc().toString());
        fields.put("validToUtc", req.validToUtc() == null ? null : req.validToUtc().toString());
        fields.put("minHourlyRainfallMm", req.minHourlyRainfallMm());
        fields.put("maxConsecutiveOverflowHours", req.maxConsecutiveOverflowHours());
        return replayable(req.requestId(), "OVERFLOW_PERMIT_UPDATE", fields, OverflowPermitView.class,
                () -> doUpdatePermit(req));
    }

    private OverflowPermitView doUpdatePermit(OverflowPermitUpdateRequest req) {
        String code = requireOutlet(req.outletCode());
        if (req.expectedVersion() == null || req.expectedVersion() < 1) {
            throw new IllegalArgumentException("expectedVersion 必须为不小于 1 的当前版本号");
        }
        LocalDateTime from = requireUtcHour(req.validFromUtc(), "validFromUtc");
        LocalDateTime to = requireUtcHour(req.validToUtc(), "validToUtc");
        requireWindowOrder(from, to);
        requireRainfall(req.minHourlyRainfallMm());
        int maxHours = requirePositiveHours(req.maxConsecutiveOverflowHours());
        Map<String, Object> latest = repo.findLatestPermit(code);
        if (latest == null) {
            throw new NotFoundException("溢流许可不存在，请先创建: " + code);
        }
        int currentVersion = ((Number) latest.get("version")).intValue();
        if (currentVersion != req.expectedVersion()) {
            throw new ConflictException("溢流许可版本不匹配：expectedVersion=" + req.expectedVersion()
                    + "，当前最新版本=" + currentVersion);
        }
        validateNoOverlap(code, from, to);
        int newVersion = currentVersion + 1;
        LocalDateTime now = clock.now();
        repo.insertPermit(code, newVersion, from, to, req.minHourlyRainfallMm(), maxHours, now);
        cemsRepo.insertEvent(code, "OVERFLOW_PERMIT", now, false,
                "UPDATE version=" + newVersion + " expectedVersion=" + currentVersion
                        + " window=[" + from + "," + to + ") minRain=" + req.minHourlyRainfallMm()
                        + " maxConsecutive=" + maxHours);
        return toPermitView(repo.findPermit(code, newVersion));
    }

    private void validateNoOverlap(String code, LocalDateTime from, LocalDateTime to) {
        List<Map<String, Object>> overlap = repo.findOverlappingPermits(code, from, to);
        if (!overlap.isEmpty()) {
            Map<String, Object> hit = overlap.get(0);
            throw new BusinessRejectException("溢流许可窗口与版本 " + hit.get("version")
                    + " 重叠：[" + lt(hit.get("valid_from_utc")) + "," + lt(hit.get("valid_to_utc"))
                    + ")，半开窗口仅允许首尾相接");
        }
    }

    // ---------------- 小时雨量版本 ----------------

    @Transactional
    public RainfallReportResult reportRainfall(RainfallReportRequest req) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("outletCode", req.outletCode());
        fields.put("hourUtc", req.hourUtc() == null ? null : req.hourUtc().toString());
        fields.put("rainfallMm", req.rainfallMm());
        fields.put("expectedVersion", req.expectedVersion());
        return replayable(req.requestId(), "RAINFALL_REPORT", fields, RainfallReportResult.class,
                () -> doReportRainfall(req));
    }

    private RainfallReportResult doReportRainfall(RainfallReportRequest req) {
        String code = requireOutlet(req.outletCode());
        LocalDateTime hour = requireUtcHour(req.hourUtc(), "hourUtc");
        requireRainfall(req.rainfallMm());
        // 已复核冻结的小时不允许直接覆盖更正，只能走独立调差（不改日结快照）。
        Map<String, Object> frozenMark = repo.findOverflowMark(code, hour);
        if (frozenMark != null && "FROZEN_COMPLIANT".equals(frozenMark.get("status"))) {
            throw new BusinessRejectException("小时 " + hour + " 已复核冻结，雨量更正只能走独立调差接口");
        }
        Map<String, Object> latest = repo.findLatestRainfall(code, hour);
        int newVersion;
        if (req.expectedVersion() == null) {
            if (latest != null) {
                throw new ConflictException("小时 " + hour + " 雨量已存在版本 1，更正须携带 expectedVersion="
                        + latest.get("version"));
            }
            newVersion = 1;
        } else {
            if (req.expectedVersion() < 1) {
                throw new IllegalArgumentException("expectedVersion 必须为不小于 1 的当前版本号");
            }
            if (latest == null) {
                throw new ConflictException("小时 " + hour + " 尚无雨量版本，首次上报请勿携带 expectedVersion");
            }
            int currentVersion = ((Number) latest.get("version")).intValue();
            if (currentVersion != req.expectedVersion()) {
                throw new ConflictException("雨量版本不匹配：expectedVersion=" + req.expectedVersion()
                        + "，当前最新版本=" + currentVersion);
            }
            newVersion = currentVersion + 1;
        }
        LocalDateTime now = clock.now();
        if (!repo.insertRainfall(code, hour, newVersion, req.rainfallMm(), now)) {
            throw new ConflictException("小时 " + hour + " 雨量版本 " + newVersion + " 已被并发占用");
        }
        cemsRepo.insertEvent(code, "RAINFALL_REPORT", now, false,
                "hour=" + hour + " version=" + newVersion + " rainfall=" + req.rainfallMm() + "mm");
        // 复核前更正雨量：重算该溢流小时的候选证据状态（READY/PENDING/INELIGIBLE）。
        recomputeCandidateMark(code, hour);
        return new RainfallReportResult(code, hour, req.rainfallMm(), newVersion);
    }

    // ---------------- UTC 整点溢流小时 ----------------

    @Transactional
    public OverflowHourView reportOverflowHour(OverflowHourlyRequest req) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("outletCode", req.outletCode());
        fields.put("hourUtc", req.hourUtc() == null ? null : req.hourUtc().toString());
        fields.put("avgValue", req.avgValue());
        fields.put("overflowFlag", req.overflowFlag());
        return replayable(req.requestId(), "OVERFLOW_HOURLY", fields, OverflowHourView.class,
                () -> doReportOverflowHour(req));
    }

    private OverflowHourView doReportOverflowHour(OverflowHourlyRequest req) {
        String code = requireOutlet(req.outletCode());
        LocalDateTime hour = requireUtcHour(req.hourUtc(), "hourUtc");
        if (req.avgValue() == null || req.avgValue().signum() < 0) {
            throw new IllegalArgumentException("avgValue 不能为空或负数");
        }
        LocalDateTime now = clock.now();
        boolean fresh = repo.insertOverflowHour(code, hour, req.avgValue(), req.overflowFlag(), now);
        if (!fresh) {
            Map<String, Object> existing = repo.findOverflowHour(code, hour);
            if (req.avgValue().compareTo(bd(existing.get("avg_value"))) != 0
                    || req.overflowFlag() != Boolean.TRUE.equals(existing.get("overflow_flag"))) {
                throw new ConflictException("排口＋UTC 小时记录已存在且浓度/溢流标记不同: " + code + " " + hour);
            }
            // 同值同标记重复上报：去重返回当前证据状态，不重复评价。
            return toHourView(existing, repo.findOverflowMark(code, hour));
        }
        cemsRepo.insertEvent(code, "OVERFLOW_HOUR", now, false,
                "hour=" + hour + " avgValue=" + req.avgValue() + " overflowFlag=" + req.overflowFlag());
        if (req.overflowFlag()) {
            evaluateCandidate(code, hour);
        }
        return toHourView(repo.findOverflowHour(code, hour), repo.findOverflowMark(code, hour));
    }

    /**
     * 重算单个溢流小时的复核前候选标记（不触碰 FROZEN_COMPLIANT）。
     * 判定：许可有效且小时完整落半开窗、雨量（最新版本）≥门槛（恰等合法）才 READY；
     * 缺雨量（无任何版本行，0.0 是合法实测）只能 PENDING 待核；其余 INELIGIBLE。
     */
    private void recomputeCandidateMark(String code, LocalDateTime hour) {
        Map<String, Object> hourRow = repo.findOverflowHour(code, hour);
        if (hourRow == null || !Boolean.TRUE.equals(hourRow.get("overflow_flag"))) {
            return;
        }
        Map<String, Object> mark = repo.findOverflowMark(code, hour);
        if (mark != null && "FROZEN_COMPLIANT".equals(mark.get("status"))) {
            return;
        }
        evaluateCandidate(code, hour);
    }

    private void evaluateCandidate(String code, LocalDateTime hour) {
        Map<String, Object> permit = repo.findPermitCoveringHour(code, hour);
        Map<String, Object> rainfall = repo.findLatestRainfall(code, hour);
        String status;
        Integer permitVersion = null;
        Integer rainfallVersion = null;
        BigDecimal rainfallMm = null;
        if (rainfall == null) {
            // 缺雨量：既不能当零，也不能直接豁免，只能待核。
            status = "PENDING";
            if (permit != null) {
                permitVersion = ((Number) permit.get("version")).intValue();
            }
        } else if (permit == null) {
            status = "INELIGIBLE";
            rainfallVersion = ((Number) rainfall.get("version")).intValue();
            rainfallMm = bd(rainfall.get("rainfall_mm"));
        } else {
            BigDecimal threshold = bd(permit.get("min_hourly_rainfall_mm"));
            rainfallVersion = ((Number) rainfall.get("version")).intValue();
            rainfallMm = bd(rainfall.get("rainfall_mm"));
            permitVersion = ((Number) permit.get("version")).intValue();
            // 恰等雨量合法（含本值达标）。
            status = rainfallMm.compareTo(threshold) >= 0 ? "READY" : "INELIGIBLE";
        }
        repo.deleteCandidateMark(code, hour);
        repo.insertCandidateMark(code, hour, status, permitVersion, rainfallVersion, rainfallMm, clock.now());
    }

    // ---------------- 标记复核冻结（双口径日结，同一事务） ----------------

    @Transactional
    public OverflowReviewResult review(OverflowReviewRequest req) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("outletCode", req.outletCode());
        fields.put("dayUtc", req.dayUtc() == null ? null : req.dayUtc().toString());
        return replayable(req.requestId(), "OVERFLOW_REVIEW", fields, OverflowReviewResult.class,
                () -> doReview(req));
    }

    private OverflowReviewResult doReview(OverflowReviewRequest req) {
        String code = requireOutlet(req.outletCode());
        if (req.dayUtc() == null) {
            throw new IllegalArgumentException("dayUtc 不能为空");
        }
        LocalDate day = req.dayUtc();
        LocalDateTime dayStart = day.atStartOfDay();
        LocalDateTime dayEnd = dayStart.plusDays(1);
        LocalDateTime now = clock.now();

        // 行锁串行化同一排口的“标记复核 vs 日结”并发竞争；唯一冻结行决定唯一胜者。
        repo.lockOutletForFreeze(code);
        boolean wonFreeze = repo.insertDailyFreeze(code, dayStart, req.requestId(), now);
        if (!wonFreeze) {
            // 竞争失败：复用首个冻结事务的一致双口径快照，不另算一份。
            return buildReviewResult(req.requestId(), code, day, true);
        }

        List<Map<String, Object>> dayMarks = repo.findOverflowMarksBetween(code, dayStart, dayEnd);
        List<String> pendingHours = new ArrayList<>();
        for (Map<String, Object> m : dayMarks) {
            if ("PENDING".equals(m.get("status"))) {
                pendingHours.add(lt(m.get("hour_utc")).toString());
            }
        }
        if (!pendingHours.isEmpty()) {
            // 缺雨量待核：拒绝冻结，事务回滚，冻结占位与幂等占位一并释放（失败不占键、不占日结）。
            throw new BusinessRejectException("UTC 日 " + day + " 存在缺雨量待核溢流小时，不能复核冻结: "
                    + pendingHours);
        }

        // 冻结当日所有证据齐全（READY）小时：许可版本＋雨量版本一致落库。
        int frozen = 0;
        for (Map<String, Object> m : dayMarks) {
            if (!"READY".equals(m.get("status"))) {
                continue;
            }
            LocalDateTime hour = lt(m.get("hour_utc"));
            int permitVersion = ((Number) m.get("candidate_permit_version")).intValue();
            int rainfallVersion = ((Number) m.get("candidate_rainfall_version")).intValue();
            BigDecimal rainfallMm = bd(m.get("candidate_rainfall_mm"));
            int n = repo.freezeMark(code, hour, permitVersion, rainfallVersion, rainfallMm,
                    req.requestId(), now);
            if (n == 0) {
                throw new ConflictException("溢流小时 " + hour + " 证据状态已变化，冻结未受理");
            }
            frozen++;
        }

        // 断段规则专项异常（跨许可版本或缺点必须断段），仅重算起点落在被复核 UTC 日的异常，
        // 保证与本次冻结口径一致且不改写其他日的冻结证据。
        recomputeSegmentAnomalies(code, dayStart, now);

        // 双口径日结快照与标记冻结同一事务：NORMAL 仅排除证据齐全的合规溢流小时，ALL 不排除。
        writeDailySummaries(code, dayStart, now);

        cemsRepo.insertEvent(code, "OVERFLOW_FREEZE", now, false,
                "day=" + day + " frozenCompliantHours=" + frozen + " requestId=" + req.requestId());
        return buildReviewResult(req.requestId(), code, day, false);
    }

    /**
     * 按“相邻 UTC 整点＋同一许可版本＋中间无缺点”先把全部已冻结合规小时切成最大连续段，
     * 跨许可版本或出现缺测/不合规小时立即断段；段长超过该许可最大连续小时数即专项异常。
     * 仅落库“与被复核 UTC 日有交集”的异常（跨日段在日界处登记一次），其他日证据不动。
     */
    private void recomputeSegmentAnomalies(String code, LocalDateTime dayStart, LocalDateTime now) {
        LocalDateTime dayEnd = dayStart.plusDays(1);
        repo.deleteAnomaliesForDay(code, dayStart);
        List<Map<String, Object>> compliant = repo.findOverflowMarks(code).stream()
                .filter(m -> "FROZEN_COMPLIANT".equals(m.get("status")))
                .toList();

        int i = 0;
        while (i < compliant.size()) {
            Map<String, Object> segFirst = compliant.get(i);
            Integer permitVersion = intOrNull(segFirst.get("frozen_permit_version"));
            int j = i;
            while (j + 1 < compliant.size()) {
                LocalDateTime curHour = lt(compliant.get(j).get("hour_utc"));
                LocalDateTime nextHour = lt(compliant.get(j + 1).get("hour_utc"));
                Integer nextPermit = intOrNull(compliant.get(j + 1).get("frozen_permit_version"));
                boolean adjacent = nextHour.equals(curHour.plusHours(1));
                boolean samePermit = permitVersion == null ? nextPermit == null
                        : permitVersion.equals(nextPermit);
                if (adjacent && samePermit) {
                    j++;
                } else {
                    break;
                }
            }
            LocalDateTime globalSegFrom = lt(segFirst.get("hour_utc"));
            LocalDateTime globalSegTo = lt(compliant.get(j).get("hour_utc")).plusHours(1);
            int length = j - i + 1;
            // 该最大段与被复核日有交集才处理。
            boolean intersectsDay = globalSegFrom.isBefore(dayEnd) && globalSegTo.isAfter(dayStart);
            if (intersectsDay && permitVersion != null) {
                Map<String, Object> permit = repo.findPermit(code, permitVersion);
                if (permit != null) {
                    int limit = ((Number) permit.get("max_consecutive_overflow_hours")).intValue();
                    if (length > limit) {
                        // 跨日段在上一日已有全局起点行时，本日用日界作起点登记，避免唯一约束冲突且归属当日复核。
                        LocalDateTime anomalyFrom = globalSegFrom.isBefore(dayStart) ? dayStart : globalSegFrom;
                        String detail = "连续合规溢流 " + length + " 小时，超过许可版本 " + permitVersion
                                + " 允许的最大连续 " + limit + " 小时（跨版本/缺点已断段）";
                        repo.insertAnomaly(code, "SEGMENT_TOO_LONG", anomalyFrom, globalSegTo, length, limit,
                                permitVersion, detail, now);
                    }
                }
            }
            i = j + 1;
        }
    }

    /** 计算并写入某 UTC 日 NORMAL/ALL 双口径快照；分母、排除小时、缺点全部明示。 */
    private void writeDailySummaries(String code, LocalDateTime dayStart, LocalDateTime now) {
        LocalDateTime dayEnd = dayStart.plusDays(1);
        List<Map<String, Object>> hours = repo.findOverflowHoursBetween(code, dayStart, dayEnd);
        Map<String, Map<String, Object>> markByHour = new LinkedHashMap<>();
        for (Map<String, Object> m : repo.findOverflowMarksBetween(code, dayStart, dayEnd)) {
            markByHour.put(lt(m.get("hour_utc")).toString(), m);
        }

        List<Map<String, Object>> normalIncluded = new ArrayList<>();
        int excluded = 0;
        for (Map<String, Object> h : hours) {
            Map<String, Object> mark = markByHour.get(lt(h.get("hour_utc")).toString());
            if (mark != null && "FROZEN_COMPLIANT".equals(mark.get("status"))) {
                excluded++;
            } else {
                normalIncluded.add(h);
            }
        }
        int missing = SLOTS_PER_DAY - hours.size();

        repo.insertDailySummary(code, dayStart, "NORMAL", averageOrNull(normalIncluded),
                normalIncluded.size(), excluded, missing, now);
        repo.insertDailySummary(code, dayStart, "ALL", averageOrNull(hours),
                hours.size(), 0, missing, now);
    }

    /** 空集合返回无结果（null averageValue），而不是 0。 */
    private BigDecimal averageOrNull(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return null;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (Map<String, Object> r : rows) {
            sum = sum.add(bd(r.get("avg_value")));
        }
        return sum.divide(BigDecimal.valueOf(rows.size()), AVG_SCALE, RoundingMode.HALF_UP);
    }

    // ---------------- 冻结后独立雨量调差（不改日结快照） ----------------

    @Transactional
    public RainfallAdjustResult adjustRainfall(RainfallAdjustRequest req) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("outletCode", req.outletCode());
        fields.put("hourUtc", req.hourUtc() == null ? null : req.hourUtc().toString());
        fields.put("correctedRainfallMm", req.correctedRainfallMm());
        fields.put("reason", req.reason());
        return replayable(req.requestId(), "RAINFALL_ADJUST", fields, RainfallAdjustResult.class,
                () -> doAdjustRainfall(req));
    }

    private RainfallAdjustResult doAdjustRainfall(RainfallAdjustRequest req) {
        String code = requireOutlet(req.outletCode());
        LocalDateTime hour = requireUtcHour(req.hourUtc(), "hourUtc");
        requireRainfall(req.correctedRainfallMm());
        if (req.reason() == null || req.reason().isBlank()) {
            throw new IllegalArgumentException("reason 不能为空");
        }
        Map<String, Object> mark = repo.findOverflowMark(code, hour);
        if (mark == null || !"FROZEN_COMPLIANT".equals(mark.get("status"))) {
            throw new BusinessRejectException("小时 " + hour + " 尚未复核冻结；冻结前更正请走雨量上报（重算）");
        }
        Integer frozenRainVersion = intOrNull(mark.get("frozen_rainfall_version"));
        BigDecimal frozenRainMm = bd(mark.get("frozen_rainfall_mm"));
        Map<String, Object> latestRain = frozenRainVersion == null ? null
                : repo.findRainfallAtVersion(code, hour, frozenRainVersion);
        if (latestRain == null) {
            throw new IllegalStateException("冻结雨量版本缺失: version=" + frozenRainVersion);
        }
        int newVersion = ((Number) repo.findLatestRainfall(code, hour).get("version")).intValue() + 1;
        LocalDateTime now = clock.now();
        if (!repo.insertRainfall(code, hour, newVersion, req.correctedRainfallMm(), now)) {
            throw new ConflictException("小时 " + hour + " 雨量版本 " + newVersion + " 已被并发占用");
        }
        repo.insertAdjustment(code, hour, frozenRainVersion, newVersion, frozenRainMm,
                req.correctedRainfallMm(), req.reason(), now);
        cemsRepo.insertEvent(code, "RAINFALL_ADJUST", now, false,
                "hour=" + hour + " frozenVersion=" + frozenRainVersion + " newVersion=" + newVersion
                        + " frozenRain=" + frozenRainMm + " correctedRain=" + req.correctedRainfallMm());
        // 显式不改 overflow_mark.frozen_*、不改 overflow_daily_summary（快照永久保留）。
        return new RainfallAdjustResult(req.requestId(), code, hour, frozenRainVersion, frozenRainMm,
                newVersion, req.correctedRainfallMm());
    }

    // ---------------- 查询 ----------------

    @Transactional(readOnly = true)
    public OverflowPermitView latestPermit(String code) {
        requireOutlet(code);
        Map<String, Object> latest = repo.findLatestPermit(code);
        if (latest == null) {
            throw new NotFoundException("溢流许可不存在: " + code);
        }
        return toPermitView(latest);
    }

    @Transactional(readOnly = true)
    public List<OverflowPermitView> listPermits(String code) {
        requireOutlet(code);
        List<OverflowPermitView> views = new ArrayList<>();
        for (Map<String, Object> p : repo.findPermits(code)) {
            views.add(toPermitView(p));
        }
        return views;
    }

    @Transactional(readOnly = true)
    public OverflowHourView getOverflowHour(String code, LocalDateTime hourUtc) {
        requireOutlet(code);
        LocalDateTime hour = requireUtcHour(hourUtc, "hourUtc");
        Map<String, Object> row = repo.findOverflowHour(code, hour);
        if (row == null) {
            throw new NotFoundException("UTC 小时溢流记录不存在: " + code + " " + hour);
        }
        return toHourView(row, repo.findOverflowMark(code, hour));
    }

    @Transactional(readOnly = true)
    public OverflowDailySummaryView getDailySummary(String code, LocalDate dayUtc) {
        requireOutlet(code);
        if (dayUtc == null) {
            throw new IllegalArgumentException("dayUtc 不能为空");
        }
        LocalDateTime dayStart = dayUtc.atStartOfDay();
        if (repo.findDailyFreeze(code, dayStart) == null) {
            // 空集合/未冻结返回无结果，而非零。
            throw new NotFoundException("UTC 日 " + dayUtc + " 尚无冻结日结（无结果）: " + code);
        }
        return buildSummaryView(code, dayUtc, false);
    }

    @Transactional(readOnly = true)
    public List<OverflowAnomalyView> listAnomalies(String code) {
        requireOutlet(code);
        List<OverflowAnomalyView> views = new ArrayList<>();
        for (Map<String, Object> a : repo.findAnomalies(code)) {
            views.add(new OverflowAnomalyView(
                    (String) a.get("outlet_code"),
                    (String) a.get("anomaly_type"),
                    lt(a.get("segment_from_utc")),
                    lt(a.get("segment_to_utc")),
                    ((Number) a.get("consecutive_hours")).intValue(),
                    ((Number) a.get("limit_hours")).intValue(),
                    intOrNull(a.get("permit_version")),
                    (String) a.get("detail")));
        }
        return views;
    }

    // ---------------- 视图组装 ----------------

    private OverflowReviewResult buildReviewResult(String requestId, String code, LocalDate day,
                                                   boolean replayed) {
        OverflowDailySummaryView summary = buildSummaryView(code, day, replayed);
        LocalDateTime dayStart = day.atStartOfDay();
        LocalDateTime dayEnd = dayStart.plusDays(1);
        List<OverflowAnomalyView> anomalies = new ArrayList<>();
        for (Map<String, Object> a : repo.findAnomalies(code)) {
            LocalDateTime segFrom = lt(a.get("segment_from_utc"));
            LocalDateTime segTo = lt(a.get("segment_to_utc"));
            // 跨日连续段：与被复核 UTC 日有交集即呈现，避免段落在日界两侧时被两个日结同时漏掉。
            if (segFrom.isBefore(dayEnd) && segTo.isAfter(dayStart)) {
                anomalies.add(new OverflowAnomalyView(
                        (String) a.get("outlet_code"),
                        (String) a.get("anomaly_type"),
                        segFrom,
                        segTo,
                        ((Number) a.get("consecutive_hours")).intValue(),
                        ((Number) a.get("limit_hours")).intValue(),
                        intOrNull(a.get("permit_version")),
                        (String) a.get("detail")));
            }
        }
        int frozen = 0;
        for (Map<String, Object> m : repo.findOverflowMarksBetween(code, dayStart, dayEnd)) {
            if ("FROZEN_COMPLIANT".equals(m.get("status"))) {
                frozen++;
            }
        }
        return new OverflowReviewResult(requestId, code, day, replayed, frozen, summary, anomalies);
    }

    private OverflowDailySummaryView buildSummaryView(String code, LocalDate day, boolean replayed) {
        Map<String, OverflowCaliberSummary> byCaliber = new LinkedHashMap<>();
        for (Map<String, Object> s : repo.findDailySummaries(code, day.atStartOfDay())) {
            byCaliber.put((String) s.get("caliber"), new OverflowCaliberSummary(
                    (String) s.get("caliber"),
                    bd(s.get("average_value")),
                    ((Number) s.get("denominator")).intValue(),
                    ((Number) s.get("excluded_hours")).intValue(),
                    ((Number) s.get("missing_points")).intValue(),
                    s.get("average_value") == null));
        }
        return new OverflowDailySummaryView(code, day, replayed,
                byCaliber.get("NORMAL"), byCaliber.get("ALL"));
    }

    private static OverflowPermitView toPermitView(Map<String, Object> p) {
        return new OverflowPermitView(
                (String) p.get("outlet_code"),
                ((Number) p.get("version")).intValue(),
                lt(p.get("valid_from_utc")),
                lt(p.get("valid_to_utc")),
                bd(p.get("min_hourly_rainfall_mm")),
                ((Number) p.get("max_consecutive_overflow_hours")).intValue(),
                lt(p.get("created_at")));
    }

    private static OverflowHourView toHourView(Map<String, Object> h, Map<String, Object> mark) {
        String status;
        Integer permitVersion = null;
        Integer rainfallVersion = null;
        BigDecimal rainfallMm = null;
        boolean overflowFlag = Boolean.TRUE.equals(h.get("overflow_flag"));
        if (!overflowFlag) {
            status = "NOT_OVERFLOW";
        } else if (mark == null) {
            status = "UNMARKED";
        } else {
            status = (String) mark.get("status");
            permitVersion = firstNonNullInt(mark.get("frozen_permit_version"),
                    mark.get("candidate_permit_version"));
            rainfallVersion = firstNonNullInt(mark.get("frozen_rainfall_version"),
                    mark.get("candidate_rainfall_version"));
            Object rainMm = mark.get("frozen_rainfall_mm");
            if (rainMm == null) {
                rainMm = mark.get("candidate_rainfall_mm");
            }
            rainfallMm = bd(rainMm);
        }
        return new OverflowHourView(
                (String) h.get("outlet_code"),
                lt(h.get("hour_utc")),
                bd(h.get("avg_value")),
                overflowFlag,
                status,
                permitVersion,
                rainfallVersion,
                rainfallMm);
    }

    // ---------------- 幂等支撑（结构化无歧义指纹） ----------------

    /**
     * 与 {@link CemsService} 同语义：占位行主键串行化同键并发，业务成功后回写结果，
     * 失败随事务回滚释放键；同键同参回放首次冻结结果，异参 409。
     * 指纹为长度前缀结构化编码，合法文本含分隔符亦可区分。
     */
    private <T> T replayable(String requestId, String requestType, Map<String, Object> fields,
                             Class<T> resultType, Supplier<T> action) {
        String rid = requireText(requestId, "requestId");
        String fingerprint = idem.fingerprintStructured(rid, fields);
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

    private <T> T replayOrConflict(String rid, String fingerprint, Map<String, Object> row, Class<T> resultType) {
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

    // ---------------- 校验与小工具 ----------------

    private String requireOutlet(String code) {
        String required = requireText(code, "outletCode");
        if (cemsRepo.findOutlet(required) == null) {
            throw new NotFoundException("排口不存在: " + required);
        }
        return required;
    }

    private static LocalDateTime requireUtcHour(LocalDateTime time, String field) {
        if (time == null) {
            throw new IllegalArgumentException(field + " 不能为空（UTC 整点）");
        }
        if (time.getMinute() != 0 || time.getSecond() != 0 || time.getNano() != 0) {
            throw new IllegalArgumentException(field + " 必须为 UTC 整点: " + time);
        }
        return time;
    }

    private static void requireWindowOrder(LocalDateTime from, LocalDateTime to) {
        if (!to.isAfter(from)) {
            throw new IllegalArgumentException("适用窗口终点必须晚于起点（UTC 半开区间）");
        }
    }

    private static void requireRainfall(BigDecimal rainfall) {
        if (rainfall == null || rainfall.signum() < 0) {
            throw new IllegalArgumentException("雨量门槛/雨量值不能为空或负数（单位 mm，0.0 为合法实测）");
        }
    }

    private static int requirePositiveHours(Integer hours) {
        if (hours == null || hours < 1) {
            throw new IllegalArgumentException("maxConsecutiveOverflowHours 必须为不小于 1 的整数");
        }
        return hours;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value;
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

    private static Integer intOrNull(Object value) {
        return value == null ? null : ((Number) value).intValue();
    }

    private static Integer firstNonNullInt(Object first, Object second) {
        Integer a = intOrNull(first);
        return a != null ? a : intOrNull(second);
    }
}
