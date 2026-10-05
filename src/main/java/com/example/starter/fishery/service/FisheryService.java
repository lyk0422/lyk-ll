package com.example.starter.fishery.service;

import com.example.starter.fishery.api.BusinessRuleException;
import com.example.starter.fishery.api.ConflictException;
import com.example.starter.fishery.api.NotFoundException;
import com.example.starter.fishery.api.dto.DepartureRequest;
import com.example.starter.fishery.api.dto.DepartureResponse;
import com.example.starter.fishery.api.dto.LandingRequest;
import com.example.starter.fishery.api.dto.LandingResponse;
import com.example.starter.fishery.api.dto.LogbookRequest;
import com.example.starter.fishery.api.dto.LogbookResponse;
import com.example.starter.fishery.api.dto.QuotaView;
import com.example.starter.fishery.api.dto.ReconcileLine;
import com.example.starter.fishery.api.dto.ReconcileResponse;
import com.example.starter.fishery.api.dto.ReviewCaseView;
import com.example.starter.fishery.api.dto.ReviewDecisionRequest;
import com.example.starter.fishery.api.dto.ReviewDecisionResponse;
import com.example.starter.fishery.api.dto.SettlementRequest;
import com.example.starter.fishery.api.dto.SettlementResponse;
import com.example.starter.fishery.api.dto.WeightLineView;
import com.example.starter.fishery.db.CarryoverRepository;
import com.example.starter.fishery.db.CarryoverRow;
import com.example.starter.fishery.db.CatalogRepository;
import com.example.starter.fishery.db.IdempotencyRepository;
import com.example.starter.fishery.db.IdempotencyRepository.AcquireResult;
import com.example.starter.fishery.db.IdempotencyRepository.Outcome;
import com.example.starter.fishery.db.LandingRepository;
import com.example.starter.fishery.db.LandingRow;
import com.example.starter.fishery.db.LogbookRepository;
import com.example.starter.fishery.db.LogbookRow;
import com.example.starter.fishery.db.QuotaRepository;
import com.example.starter.fishery.db.QuotaRow;
import com.example.starter.fishery.db.ReviewRepository;
import com.example.starter.fishery.db.ReviewRow;
import com.example.starter.fishery.db.SettlementRow;
import com.example.starter.fishery.db.SpeciesRow;
import com.example.starter.fishery.db.TripRepository;
import com.example.starter.fishery.db.TripRow;
import com.example.starter.fishery.domain.ReviewConclusion;
import com.example.starter.fishery.domain.ReviewReason;
import com.example.starter.fishery.domain.ReviewStatus;
import com.example.starter.fishery.domain.TripStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 渔业捕捞配额与渔捞日志核销应用服务。
 *
 * <p>关键口径：兼捕保护鱼种占比恰 5% 合法，严格大于才标注并审查；卸渔偏差恰 3% 视为一致，
 * 严格大于才需差异说明并转复核；申报量累计恰达配额合法，任鱼种超出则整笔拒绝。
 */
@Service
public class FisheryService {

    /** 兼捕保护鱼种允许比例（含边界，恰 5% 合法）。 */
    static final BigDecimal BYCATCH_LIMIT_PCT = new BigDecimal("5.0000");
    /** 卸渔偏差允许比例（含边界，恰 3% 视为一致）。 */
    static final BigDecimal DEVIATION_LIMIT_PCT = new BigDecimal("3.0000");

    private static final String OP_DEPARTURE = "DEPARTURE";
    private static final String OP_LOGBOOK = "LOGBOOK";
    private static final String OP_LANDING = "LANDING";
    private static final String OP_SETTLEMENT = "SETTLEMENT";
    private static final String OP_REVIEW_DECISION = "REVIEW_DECISION";

    private final TripRepository tripRepo;
    private final LogbookRepository logbookRepo;
    private final LandingRepository landingRepo;
    private final ReviewRepository reviewRepo;
    private final QuotaRepository quotaRepo;
    private final CarryoverRepository carryoverRepo;
    private final CatalogRepository catalogRepo;
    private final IdempotencyRepository idempotencyRepo;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public FisheryService(TripRepository tripRepo, LogbookRepository logbookRepo,
                          LandingRepository landingRepo, ReviewRepository reviewRepo,
                          QuotaRepository quotaRepo, CarryoverRepository carryoverRepo,
                          CatalogRepository catalogRepo,
                          IdempotencyRepository idempotencyRepo, ObjectMapper objectMapper, Clock clock) {
        this.tripRepo = tripRepo;
        this.logbookRepo = logbookRepo;
        this.landingRepo = landingRepo;
        this.reviewRepo = reviewRepo;
        this.quotaRepo = quotaRepo;
        this.carryoverRepo = carryoverRepo;
        this.catalogRepo = catalogRepo;
        this.idempotencyRepo = idempotencyRepo;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // ------------------------------------------------------------------
    // 出港申报
    // ------------------------------------------------------------------

    @Transactional
    public DepartureResponse declareDeparture(DepartureRequest request) {
        String fingerprint = fingerprint(Map.of(
                "op", OP_DEPARTURE,
                "tripCode", request.tripCode(),
                "vesselCode", request.vesselCode(),
                "departAt", request.departAt().toString(),
                "lines", normalizedLines(request.lines().stream()
                        .map(l -> Map.of("speciesCode", l.speciesCode(), "weightKg", l.weightKg().toPlainString()))
                        .toList())));
        AcquireResult acquired = idempotencyRepo.acquire(
                request.requestId(), OP_DEPARTURE, fingerprint, Instant.now(clock));
        switch (acquired.outcome()) {
            case REPLAY -> {
                return replay(acquired.responseJson(), DepartureResponse.class);
            }
            case MISMATCH -> throw new ConflictException("相同 requestId 已用于不同业务参数");
            case IN_PROGRESS -> throw new ConflictException("相同 requestId 请求正在处理中");
            default -> { }
        }

        requireVessel(request.vesselCode());
        Map<String, BigDecimal> declared = mergePositiveLines(request.lines().stream()
                .map(l -> new WeightLineView(l.speciesCode(), l.weightKg())).toList());
        declared.keySet().forEach(this::requireSpecies);

        int year = utcYear(request.departAt());
        // 配额按鱼种分年度核定：单航次申报后该船该鱼种累计不得超配额，超出整笔拒绝。
        for (Map.Entry<String, BigDecimal> entry : declared.entrySet()) {
            SpeciesRow species = catalogRepo.findSpecies(entry.getKey());
            if (species != null && species.protectedSpecies()) {
                continue; // 兼捕保护鱼种不占用商业配额
            }
            QuotaRow quota = quotaRepo.findForUpdate(request.vesselCode(), entry.getKey(), year)
                    .orElse(null);
            // 可用额度含上年结转（结转额度在次年台账单列），已用含结转核销。
            BigDecimal quotaLimit = (quota == null ? BigDecimal.ZERO : quota.quotaKg())
                    .add(carryoverRepo.sumAmountForYear(request.vesselCode(), entry.getKey(), year));
            BigDecimal used = (quota == null ? BigDecimal.ZERO : quota.usedKg())
                    .add(carryoverRepo.sumUsedForYear(request.vesselCode(), entry.getKey(), year));
            BigDecimal cumulative = used.add(entry.getValue());
            if (cumulative.compareTo(quotaLimit) > 0) {
                throw new BusinessRuleException("申报量使渔船 " + request.vesselCode() + " 鱼种 "
                        + entry.getKey() + " " + year + " 年度累计 " + cumulative.toPlainString()
                        + "kg 超过配额 " + quotaLimit.toPlainString() + "kg，整笔拒绝");
            }
        }

        // 同一渔船同时刻只允许一个进行中航次（数据库护栏保证并发唯一受理）。
        if (tripRepo.existsOpenForVessel(request.vesselCode())
                || !tripRepo.tryOccupyActiveVessel(request.vesselCode(), request.tripCode())) {
            throw new ConflictException("渔船 " + request.vesselCode() + " 已存在进行中航次，出港申报唯一受理");
        }

        if (tripRepo.findByCode(request.tripCode()).isPresent()) {
            throw new ConflictException("航次编号 " + request.tripCode() + " 已存在");
        }

        Instant now = Instant.now(clock);
        tripRepo.insert(new TripRow(null, request.tripCode(), request.vesselCode(),
                request.departAt(), null, TripStatus.DEPARTED, false, now, now));
        declared.forEach((speciesCode, weight) ->
                tripRepo.insertDepartureLine(request.tripCode(), speciesCode, weight));

        DepartureResponse response = new DepartureResponse(request.tripCode(), request.vesselCode(),
                TripStatus.DEPARTED, request.departAt(), year, toViews(declared), false);
        idempotencyRepo.saveResponse(request.requestId(), toJson(response));
        return response;
    }

    // ------------------------------------------------------------------
    // 渔捞日志
    // ------------------------------------------------------------------

    @Transactional
    public LogbookResponse submitLogbook(LogbookRequest request) {
        String fingerprint = fingerprint(Map.of(
                "op", OP_LOGBOOK,
                "tripCode", request.tripCode(),
                "zoneCode", request.zoneCode(),
                "fishingAt", request.fishingAt().toString(),
                "lines", normalizedLines(request.lines().stream()
                        .map(l -> Map.of("speciesCode", l.speciesCode(), "weightKg", l.weightKg().toPlainString()))
                        .toList())));
        AcquireResult acquired = idempotencyRepo.acquire(
                request.requestId(), OP_LOGBOOK, fingerprint, Instant.now(clock));
        switch (acquired.outcome()) {
            case REPLAY -> {
                return replay(acquired.responseJson(), LogbookResponse.class);
            }
            case MISMATCH -> throw new ConflictException("相同 requestId 已用于不同业务参数");
            case IN_PROGRESS -> throw new ConflictException("相同 requestId 请求正在处理中");
            default -> { }
        }

        TripRow trip = requireTrip(request.tripCode());
        request.lines().forEach(l -> requireSpecies(l.speciesCode()));

        LocalDate logDate = request.fishingAt().atZone(ZoneOffset.UTC).toLocalDate();
        // 按“船＋日期＋渔区”去重
        LogbookRow duplicate = logbookRepo
                .findByVesselDateZone(trip.vesselCode(), logDate, request.zoneCode()).orElse(null);
        if (duplicate != null) {
            throw new ConflictException("渔船 " + trip.vesselCode() + " 在 " + logDate + " 渔区 "
                    + request.zoneCode() + " 已存在渔捞日志，按船＋日期＋渔区去重");
        }

        Map<String, BigDecimal> catchLines = mergePositiveLines(request.lines().stream()
                .map(l -> new WeightLineView(l.speciesCode(), l.weightKg())).toList());
        BigDecimal total = catchLines.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal bycatchKg = catchLines.entrySet().stream()
                .filter(e -> {
                    SpeciesRow species = catalogRepo.findSpecies(e.getKey());
                    return species != null && species.protectedSpecies();
                })
                .map(Map.Entry::getValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        // 兼捕比例（百分比，4 位小数）；恰 5% 合法，严格大于才标注审查。
        BigDecimal bycatchPct = bycatchKg.multiply(BigDecimal.valueOf(100))
                .divide(total, 4, RoundingMode.HALF_UP);
        boolean bycatchFlagged = bycatchPct.compareTo(BYCATCH_LIMIT_PCT) > 0;

        Instant now = Instant.now(clock);
        boolean late = trip.status() == TripStatus.CLOSED;

        int seqNo = (int) logbookRepo.findByTrip(request.tripCode()).stream()
                .filter(r -> !r.late())
                .count() + 1;
        long logId = logbookRepo.insert(new LogbookRow(null, request.tripCode(), trip.vesselCode(),
                request.zoneCode(), logDate, request.fishingAt(), total, bycatchFlagged, late, seqNo, now));
        catchLines.forEach((speciesCode, weight) -> logbookRepo.insertLine(logId, speciesCode, weight));
        logbookRepo.renumberSequence(request.tripCode());

        boolean reviewOpened = false;
        if (late) {
            // 结案后迟到日志：只记迟到事件并入审查队列，不参与核销。
            logbookRepo.insertLateEvent(request.tripCode(), trip.vesselCode(), request.zoneCode(),
                    logDate, request.fishingAt(), toJson(request), now);
            reviewRepo.insert(request.tripCode(), trip.vesselCode(), ReviewReason.LATE_LOG,
                    "结案后迟到日志：渔区 " + request.zoneCode() + "，作业时刻 " + request.fishingAt(), now);
            reviewOpened = true;
        } else if (bycatchFlagged) {
            // 兼捕保护鱼种超比例：该航次日志单独标注并触发审查，冻结该船配额余额。
            reviewRepo.insert(request.tripCode(), trip.vesselCode(), ReviewReason.BYCATCH_RATIO,
                    "兼捕保护鱼种占比 " + bycatchPct.toPlainString() + "% 超过允许 5%", now);
            if (!trip.frozen()) {
                tripRepo.updateState(request.tripCode(), TripStatus.IN_REVIEW, true, now);
            }
            reviewOpened = true;
        }

        if (!late) {
            recomputeLoggedCatch(request.tripCode());
        }

        LogbookRow saved = logbookRepo.findById(logId).orElseThrow();
        LogbookResponse response = new LogbookResponse(saved.id(), request.tripCode(), request.zoneCode(),
                logDate, request.fishingAt(), saved.seqNo(), total, bycatchFlagged, late, reviewOpened,
                toViews(catchLines), false);
        idempotencyRepo.saveResponse(request.requestId(), toJson(response));
        return response;
    }

    // ------------------------------------------------------------------
    // 进港报告
    // ------------------------------------------------------------------

    @Transactional
    public LandingResponse reportLanding(LandingRequest request) {
        String fingerprint = fingerprint(Map.of(
                "op", OP_LANDING,
                "tripCode", request.tripCode(),
                "landedAt", request.landedAt().toString(),
                "explanation", request.deviationExplanation() == null ? "" : request.deviationExplanation(),
                "lines", normalizedLines(request.lines().stream()
                        .map(l -> Map.of("speciesCode", l.speciesCode(), "weightKg", l.weightKg().toPlainString()))
                        .toList())));
        AcquireResult acquired = idempotencyRepo.acquire(
                request.requestId(), OP_LANDING, fingerprint, Instant.now(clock));
        switch (acquired.outcome()) {
            case REPLAY -> {
                return replay(acquired.responseJson(), LandingResponse.class);
            }
            case MISMATCH -> throw new ConflictException("相同 requestId 已用于不同业务参数");
            case IN_PROGRESS -> throw new ConflictException("相同 requestId 请求正在处理中");
            default -> { }
        }

        TripRow trip = requireTripForUpdate(request.tripCode());
        if (trip.status() == TripStatus.CLOSED) {
            throw new BusinessRuleException("航次 " + request.tripCode() + " 已结案，不能再提交进港报告");
        }
        if (landingRepo.findByTrip(request.tripCode()).isPresent()) {
            throw new ConflictException("航次 " + request.tripCode() + " 已提交进港报告");
        }
        request.lines().forEach(l -> requireSpecies(l.speciesCode()));

        Map<String, BigDecimal> landed = mergePositiveLines(request.lines().stream()
                .map(l -> new WeightLineView(l.speciesCode(), l.weightKg())).toList());
        Map<String, BigDecimal> logged = effectiveLoggedMap(request.tripCode());
        if (logged.isEmpty()) {
            throw new BusinessRuleException("航次 " + request.tripCode() + " 尚无有效渔捞日志，不能进港报告");
        }

        BigDecimal maxDeviation = BigDecimal.ZERO;
        String deviationSpecies = null;
        for (Map.Entry<String, BigDecimal> entry : logged.entrySet()) {
            BigDecimal landedWeight = landed.getOrDefault(entry.getKey(), BigDecimal.ZERO);
            BigDecimal diff = landedWeight.subtract(entry.getValue()).abs();
            BigDecimal pct = diff.multiply(BigDecimal.valueOf(100))
                    .divide(entry.getValue(), 4, RoundingMode.HALF_UP);
            if (pct.compareTo(maxDeviation) > 0) {
                maxDeviation = pct;
                deviationSpecies = entry.getKey();
            }
        }
        // 恰 3% 视为一致；严格大于须提交差异说明并转人工复核。
        boolean deviationOver = maxDeviation.compareTo(DEVIATION_LIMIT_PCT) > 0;
        if (deviationOver) {
            if (request.deviationExplanation() == null || request.deviationExplanation().isBlank()) {
                throw new BusinessRuleException("鱼种 " + deviationSpecies + " 卸渔偏差 "
                        + maxDeviation.toPlainString() + "% 超过 3%，须提交差异说明");
            }
        }

        Instant now = Instant.now(clock);
        BigDecimal totalLanded = landed.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        long landingId = landingRepo.insert(new LandingRow(null, request.tripCode(), request.landedAt(),
                totalLanded, maxDeviation, deviationOver,
                deviationOver ? request.deviationExplanation() : null, now));
        landed.forEach((speciesCode, weight) -> landingRepo.insertLine(landingId, speciesCode, weight));
        tripRepo.markLanded(request.tripCode(), request.landedAt(), now);

        // 口径行：无复核时以日志累计量为核销最终口径；有偏差复核时待复核结论覆盖。
        landed.forEach((speciesCode, weight) ->
                tripRepo.updateLandedKg(request.tripCode(), speciesCode, weight,
                        deviationOver ? BigDecimal.ZERO : logged.getOrDefault(speciesCode, BigDecimal.ZERO)));
        logged.forEach((speciesCode, weight) -> {
            if (!landed.containsKey(speciesCode)) {
                tripRepo.updateLandedKg(request.tripCode(), speciesCode, BigDecimal.ZERO,
                        deviationOver ? BigDecimal.ZERO : weight);
            }
        });

        if (deviationOver) {
            reviewRepo.insert(request.tripCode(), trip.vesselCode(), ReviewReason.LANDING_DEVIATION,
                    "卸渔偏差 " + maxDeviation.toPlainString() + "%（鱼种 " + deviationSpecies
                            + "）；差异说明：" + request.deviationExplanation(), now);
        }

        long openReviews = reviewRepo.countOpenForTrip(request.tripCode());
        boolean frozen = openReviews > 0;
        TripStatus newStatus = frozen ? TripStatus.IN_REVIEW : TripStatus.DISCHARGED;
        tripRepo.updateState(request.tripCode(), newStatus, frozen, now);

        LandingResponse response = new LandingResponse(request.tripCode(), newStatus, request.landedAt(),
                maxDeviation, deviationOver, toViews(landed), toViews(logged), false);
        idempotencyRepo.saveResponse(request.requestId(), toJson(response));
        return response;
    }

    // ------------------------------------------------------------------
    // 配额核销结案
    // ------------------------------------------------------------------

    @Transactional
    public SettlementResponse settle(SettlementRequest request) {
        String fingerprint = fingerprint(Map.of("op", OP_SETTLEMENT, "tripCode", request.tripCode()));
        AcquireResult acquired = idempotencyRepo.acquire(
                request.requestId(), OP_SETTLEMENT, fingerprint, Instant.now(clock));
        switch (acquired.outcome()) {
            case REPLAY -> {
                return replay(acquired.responseJson(), SettlementResponse.class);
            }
            case MISMATCH -> throw new ConflictException("相同 requestId 已用于不同业务参数");
            case IN_PROGRESS -> throw new ConflictException("相同 requestId 请求正在处理中");
            default -> { }
        }

        SettlementResponse response = doSettle(request.tripCode(), request.requestId());
        return response;
    }

    /**
     * 核销与余额扣减、结案在同一事务内完成：配额行 FOR UPDATE 串行化并发核销，
     * 任鱼种余额不足则整笔回滚（不会出现余额已扣而航次未结案）。
     */
    private SettlementResponse doSettle(String tripCode, String requestId) {
        TripRow trip = requireTripForUpdate(tripCode);
        if (trip.status() == TripStatus.CLOSED) {
            // 结案为终态：重复结案返回幂等成功（返回首次核销明细）。
            SettlementResponse existing = buildSettlementResponse(trip, true);
            if (requestId != null) {
                idempotencyRepo.saveResponse(requestId, toJson(existing));
            }
            return existing;
        }
        if (reviewRepo.countOpenForTrip(tripCode) > 0 || trip.frozen()) {
            throw new ConflictException("航次 " + tripCode + " 复核期间配额余额冻结，暂不能核销");
        }
        if (landingRepo.findByTrip(tripCode).isEmpty()) {
            throw new BusinessRuleException("航次 " + tripCode + " 尚未进港报告，不能核销");
        }

        int year = utcYear(trip.departAt());
        Map<String, BigDecimal> finalCatch = finalCatchMap(tripCode);
        Instant now = Instant.now(clock);

        // 持锁校验并扣减，两航次并发核销同一配额不会合计超配额。
        List<WeightLineView> deducted = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> entry : finalCatch.entrySet()) {
            SpeciesRow species = catalogRepo.findSpecies(entry.getKey());
            BigDecimal amount = entry.getValue();
            if (species != null && species.protectedSpecies()) {
                deducted.add(new WeightLineView(entry.getKey(), BigDecimal.ZERO.setScale(3)));
                continue;
            }
            QuotaRow quota = quotaRepo.findForUpdate(trip.vesselCode(), entry.getKey(), year)
                    .orElse(null);
            BigDecimal quotaLimit = quota == null ? BigDecimal.ZERO : quota.quotaKg();
            BigDecimal used = quota == null ? BigDecimal.ZERO : quota.usedKg();
            // 结转额度次年优先核销：可用额度 = 年度配额剩余 + 结转剩余。
            List<CarryoverRow> carryovers = carryoverRepo.findUsableForYear(
                    trip.vesselCode(), entry.getKey(), year);
            BigDecimal carryoverRemaining = carryovers.stream()
                    .map(c -> c.amountKg().subtract(c.usedKg()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal totalAvailable = quotaLimit.subtract(used).add(carryoverRemaining);
            if (amount.compareTo(totalAvailable) > 0) {
                throw new BusinessRuleException("核销使渔船 " + trip.vesselCode() + " 鱼种 "
                        + entry.getKey() + " 累计 " + used.add(amount).toPlainString()
                        + "kg 超过可用额度 " + quotaLimit.add(carryoverRemaining).toPlainString()
                        + "kg（含结转 " + carryoverRemaining.toPlainString() + "kg），核销整笔回滚");
            }
            // 先扣结转额度（逐行持锁），不足部分再扣年度配额。
            BigDecimal toDeduct = amount;
            for (CarryoverRow carryover : carryovers) {
                if (toDeduct.signum() <= 0) {
                    break;
                }
                CarryoverRow locked = carryoverRepo.findByIdForUpdate(carryover.id()).orElseThrow();
                BigDecimal available = locked.amountKg().subtract(locked.usedKg());
                BigDecimal portion = toDeduct.min(available);
                if (portion.signum() > 0) {
                    carryoverRepo.deductLocked(locked, portion);
                    toDeduct = toDeduct.subtract(portion);
                }
            }
            if (toDeduct.signum() > 0) {
                quotaRepo.deductLocked(quota, toDeduct);
            }
            quotaRepo.insertSettlement(tripCode, trip.vesselCode(), year, entry.getKey(), amount, now);
            deducted.add(new WeightLineView(entry.getKey(), amount.setScale(3, RoundingMode.HALF_UP)));
        }
        deducted.sort(Comparator.comparing(WeightLineView::speciesCode));

        tripRepo.updateState(tripCode, TripStatus.CLOSED, false, now);
        tripRepo.releaseActiveVessel(trip.vesselCode());

        SettlementResponse response = new SettlementResponse(tripCode, trip.vesselCode(),
                TripStatus.CLOSED, year, deducted, false);
        if (requestId != null) {
            idempotencyRepo.saveResponse(requestId, toJson(response));
        }
        return response;
    }

    private SettlementResponse buildSettlementResponse(TripRow trip, boolean replayed) {
        int year = utcYear(trip.departAt());
        List<WeightLineView> deducted = quotaRepo.findSettlements(trip.tripCode()).stream()
                .map(s -> new WeightLineView(s.speciesCode(), s.deductedKg()))
                .toList();
        return new SettlementResponse(trip.tripCode(), trip.vesselCode(), TripStatus.CLOSED,
                year, deducted, replayed);
    }

    // ------------------------------------------------------------------
    // 人工复核结论
    // ------------------------------------------------------------------

    @Transactional
    public ReviewDecisionResponse decideReview(Long reviewId, ReviewDecisionRequest request) {
        String fingerprint = fingerprint(Map.of(
                "op", OP_REVIEW_DECISION,
                "reviewId", reviewId.toString(),
                "conclusion", request.conclusion().name(),
                "note", request.note() == null ? "" : request.note(),
                "lines", normalizedLines((request.adjustedLines() == null ? List.<ReviewDecisionRequest.AdjustedLine>of()
                        : request.adjustedLines()).stream()
                        .map(l -> Map.of("speciesCode", l.speciesCode(), "weightKg", l.weightKg().toPlainString()))
                        .toList())));
        AcquireResult acquired = idempotencyRepo.acquire(
                request.requestId(), OP_REVIEW_DECISION, fingerprint, Instant.now(clock));
        switch (acquired.outcome()) {
            case REPLAY -> {
                return replay(acquired.responseJson(), ReviewDecisionResponse.class);
            }
            case MISMATCH -> throw new ConflictException("相同 requestId 已用于不同业务参数");
            case IN_PROGRESS -> throw new ConflictException("相同 requestId 请求正在处理中");
            default -> { }
        }

        ReviewRow review = reviewRepo.findByIdForUpdate(reviewId)
                .orElseThrow(() -> new NotFoundException("复核案件 " + reviewId + " 不存在"));
        if (review.status() == ReviewStatus.CLOSED) {
            throw new ConflictException("复核案件 " + reviewId + " 已有最终结论");
        }
        TripRow trip = requireTripForUpdate(review.tripCode());

        Map<String, BigDecimal> adjusted = request.adjustedLines() == null
                ? Map.of()
                : mergePositiveLines(request.adjustedLines().stream()
                        .map(l -> new WeightLineView(l.speciesCode(), l.weightKg())).toList());
        if (request.conclusion() == ReviewConclusion.ADJUSTED && adjusted.isEmpty()) {
            throw new BusinessRuleException("复核结论为 ADJUSTED 时必须提供分鱼种最终数量");
        }

        String note = request.note() == null ? review.note() : request.note();
        Instant now = Instant.now(clock);

        // 结案为终态：结案后迟到日志案件的结论只关闭审查队列，不改口径、不重新核销、不改航次状态。
        if (trip.status() == TripStatus.CLOSED) {
            reviewRepo.close(reviewId, request.conclusion(), note, now);
            ReviewRow closedCase = reviewRepo.findById(reviewId).orElseThrow();
            ReviewDecisionResponse response = new ReviewDecisionResponse(
                    reviewId, trip.tripCode(), toView(closedCase), false,
                    reviewRepo.countOpenForVessel(trip.vesselCode()), false, null,
                    TripStatus.CLOSED, false);
            idempotencyRepo.saveResponse(request.requestId(), toJson(response));
            return response;
        }

        // 复核结论为最终口径：按结论重算各鱼种 final_kg。
        Map<String, BigDecimal> logged = effectiveLoggedMap(trip.tripCode());
        Map<String, BigDecimal> landed = tripRepo.findCatchLines(trip.tripCode()).stream()
                .collect(LinkedHashMap::new,
                        (m, c) -> m.put(c.speciesCode(), c.landedKg()),
                        LinkedHashMap::putAll);
        Map<String, BigDecimal> finalCatch = new LinkedHashMap<>();
        logged.forEach((speciesCode, weight) -> {
            BigDecimal finalWeight = switch (request.conclusion()) {
                case CONFIRM_LANDED -> landed.getOrDefault(speciesCode, BigDecimal.ZERO);
                case CONFIRM_LOGGED -> weight;
                case ADJUSTED -> adjusted.getOrDefault(speciesCode, weight);
            };
            finalCatch.put(speciesCode, finalWeight);
        });
        if (request.conclusion() == ReviewConclusion.ADJUSTED) {
            adjusted.forEach(finalCatch::putIfAbsent);
        }
        finalCatch.forEach((speciesCode, weight) ->
                tripRepo.updateFinalKg(trip.tripCode(), speciesCode, weight));

        reviewRepo.close(reviewId, request.conclusion(), note, now);

        long openReviews = reviewRepo.countOpenForVessel(trip.vesselCode());
        boolean frozen = openReviews > 0;
        // 冻结与复核结论在同一事务内解除；无未决案件且已进港则同事务核销结案。
        boolean canSettle = openReviews == 0 && landingRepo.findByTrip(trip.tripCode()).isPresent();
        SettlementResponse settlement = null;
        TripStatus resultingStatus;
        if (canSettle) {
            // 同一事务内先解除冻结（无未决案件），再核销结案；任一失败整体回滚。
            tripRepo.updateState(trip.tripCode(), TripStatus.DISCHARGED, false, now);
            settlement = doSettle(trip.tripCode(), null);
            resultingStatus = TripStatus.CLOSED;
        } else if (openReviews > 0) {
            resultingStatus = TripStatus.IN_REVIEW;
            tripRepo.updateState(trip.tripCode(), resultingStatus, true, now);
        } else {
            // 无未决复核：按是否已进港回到 DISCHARGED 或 DEPARTED，并同事务解除冻结。
            resultingStatus = landingRepo.findByTrip(trip.tripCode()).isPresent()
                    ? TripStatus.DISCHARGED : TripStatus.DEPARTED;
            tripRepo.updateState(trip.tripCode(), resultingStatus, false, now);
        }

        ReviewRow closed = reviewRepo.findById(reviewId).orElseThrow();
        ReviewDecisionResponse response = new ReviewDecisionResponse(
                reviewId, trip.tripCode(), toView(closed), resultingStatus == TripStatus.IN_REVIEW,
                openReviews, settlement != null, settlement, resultingStatus, false);
        idempotencyRepo.saveResponse(request.requestId(), toJson(response));
        return response;
    }

    // ------------------------------------------------------------------
    // 复算与台账查询
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public ReconcileResponse reconcile(String tripCode) {
        TripRow trip = requireTrip(tripCode);
        int year = utcYear(trip.departAt());

        Map<String, BigDecimal> declared = new LinkedHashMap<>(
                tripRepo.findDepartureLines(tripCode).stream()
                        .collect(LinkedHashMap::new,
                                (m, l) -> m.put(l.speciesCode(), l.weightKg()),
                                LinkedHashMap::putAll));
        Map<String, BigDecimal> logged = effectiveLoggedMap(tripCode);
        Map<String, BigDecimal> landed = new LinkedHashMap<>();
        landingRepo.findByTrip(tripCode).ifPresent(landing ->
                landingRepo.findLines(landing.id()).forEach(l -> landed.put(l.speciesCode(), l.weightKg())));
        Map<String, BigDecimal> finalKg = finalCatchMap(tripCode);
        Map<String, BigDecimal> deducted = new LinkedHashMap<>();
        quotaRepo.findSettlements(tripCode).forEach(s -> deducted.put(s.speciesCode(), s.deductedKg()));

        List<String> species = new ArrayList<>();
        for (Map<String, BigDecimal> source : List.of(declared, logged, landed, finalKg, deducted)) {
            for (String code : source.keySet()) {
                if (!species.contains(code)) {
                    species.add(code);
                }
            }
        }
        species.sort(Comparator.naturalOrder());

        List<ReconcileLine> lines = species.stream()
                .map(code -> new ReconcileLine(
                        code,
                        declared.getOrDefault(code, BigDecimal.ZERO).setScale(3, RoundingMode.HALF_UP),
                        logged.getOrDefault(code, BigDecimal.ZERO).setScale(3, RoundingMode.HALF_UP),
                        landed.getOrDefault(code, BigDecimal.ZERO).setScale(3, RoundingMode.HALF_UP),
                        finalKg.getOrDefault(code, BigDecimal.ZERO).setScale(3, RoundingMode.HALF_UP),
                        deducted.getOrDefault(code, BigDecimal.ZERO).setScale(3, RoundingMode.HALF_UP)))
                .toList();

        // 台账复算：used_kg 加结转已核销必须等于该船该年度全部核销明细合计。
        boolean ledgerConsistent = quotaRepo.findByVesselYear(trip.vesselCode(), year).stream()
                .allMatch(q -> q.usedKg()
                        .add(carryoverRepo.sumUsedForYear(trip.vesselCode(), q.speciesCode(), year))
                        .compareTo(quotaRepo.sumSettled(trip.vesselCode(), q.speciesCode(), year)) == 0);
        boolean hasDeduction = !deducted.isEmpty();
        // 不得出现余额已扣而航次未结案。
        boolean settledAndClosed = !hasDeduction || trip.status() == TripStatus.CLOSED;

        List<ReviewCaseView> reviews = reviewRepo.findByTrip(tripCode).stream()
                .map(this::toView).toList();
        return new ReconcileResponse(tripCode, trip.vesselCode(), year, trip.status(), trip.frozen(),
                lines, reviews, ledgerConsistent, settledAndClosed
                        && logbookRepo.chainIsContiguous(tripCode));
    }

    @Transactional(readOnly = true)
    public List<QuotaView> quotas(String vesselCode, Integer year) {
        if (vesselCode == null) {
            return List.of();
        }
        List<QuotaRow> rows = year == null
                ? quotaRepo.findByVessel(vesselCode)
                : quotaRepo.findByVesselYear(vesselCode, year);
        return rows.stream()
                .map(q -> new QuotaView(q.vesselCode(), q.speciesCode(), q.year(), q.quotaKg(),
                        q.usedKg(), q.quotaKg().subtract(q.usedKg()),
                        carryoverRepo.sumAmountForYear(q.vesselCode(), q.speciesCode(), q.year()),
                        carryoverRepo.sumUsedForYear(q.vesselCode(), q.speciesCode(), q.year())))
                .toList();
    }

    // ------------------------------------------------------------------
    // 辅助方法
    // ------------------------------------------------------------------

    private void recomputeLoggedCatch(String tripCode) {
        effectiveLoggedMap(tripCode).forEach((speciesCode, weight) ->
                tripRepo.updateLoggedKg(tripCode, speciesCode, weight));
    }

    private Map<String, BigDecimal> effectiveLoggedMap(String tripCode) {
        Map<String, BigDecimal> map = new LinkedHashMap<>();
        logbookRepo.sumEffectiveLines(tripCode).forEach(l -> map.put(l.speciesCode(), l.weightKg()));
        return map;
    }

    /** 核销最终口径：有口径行以 final_kg 为准，未生成时回退日志累计量。 */
    private Map<String, BigDecimal> finalCatchMap(String tripCode) {
        Map<String, BigDecimal> map = new LinkedHashMap<>();
        tripRepo.findCatchLines(tripCode).forEach(c -> {
            if (c.finalKg() != null && c.finalKg().compareTo(BigDecimal.ZERO) > 0) {
                map.put(c.speciesCode(), c.finalKg());
            } else if (c.loggedKg() != null && c.loggedKg().compareTo(BigDecimal.ZERO) > 0) {
                map.put(c.speciesCode(), c.loggedKg());
            }
        });
        return map;
    }

    private void requireVessel(String vesselCode) {
        if (catalogRepo.findVessel(vesselCode) == null) {
            throw new NotFoundException("渔船 " + vesselCode + " 不存在");
        }
    }

    private void requireSpecies(String speciesCode) {
        if (catalogRepo.findSpecies(speciesCode) == null) {
            throw new NotFoundException("鱼种 " + speciesCode + " 不存在");
        }
    }

    private TripRow requireTrip(String tripCode) {
        return tripRepo.findByCode(tripCode)
                .orElseThrow(() -> new NotFoundException("航次 " + tripCode + " 不存在"));
    }

    private TripRow requireTripForUpdate(String tripCode) {
        return tripRepo.findByCodeForUpdate(tripCode)
                .orElseThrow(() -> new NotFoundException("航次 " + tripCode + " 不存在"));
    }

    private static int utcYear(Instant instant) {
        return instant.atZone(ZoneOffset.UTC).getYear();
    }

    /** 合并同鱼种数量（防御性，请求内同鱼种多行时累加）。 */
    private Map<String, BigDecimal> mergePositiveLines(List<WeightLineView> lines) {
        Map<String, BigDecimal> merged = new LinkedHashMap<>();
        for (WeightLineView line : lines) {
            if (line.weightKg().signum() <= 0) {
                throw new BusinessRuleException("鱼种 " + line.speciesCode() + " 数量必须大于 0");
            }
            merged.merge(line.speciesCode(), line.weightKg(), BigDecimal::add);
        }
        return merged;
    }

    private List<WeightLineView> toViews(Map<String, BigDecimal> weights) {
        return weights.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> new WeightLineView(e.getKey(), e.getValue().setScale(3, RoundingMode.HALF_UP)))
                .toList();
    }

    private ReviewCaseView toView(ReviewRow row) {
        return new ReviewCaseView(row.id(), row.reason(), row.status(), row.conclusion(),
                row.note(), row.createdAt(), row.decidedAt());
    }

    /** 规范化业务字段（行按鱼种排序）后再哈希，避免行序影响指纹。 */
    private List<Map<String, String>> normalizedLines(List<Map<String, String>> lines) {
        return lines.stream()
                .sorted(Comparator.comparing(m -> m.get("speciesCode")))
                .map(TreeMap::new)
                .map(m -> (Map<String, String>) m)
                .toList();
    }

    private String fingerprint(Object canonicalPayload) {
        try {
            String json = objectMapper.writer()
                    .with(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsString(canonicalPayload);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(json.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (JsonProcessingException | NoSuchAlgorithmException ex) {
            throw new IllegalStateException("生成幂等指纹失败", ex);
        }
    }

    private String toJson(Object response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("序列化响应快照失败", ex);
        }
    }

    private <T> T replay(String json, Class<T> type) {
        if (json == null) {
            throw new ConflictException("首次请求尚未处理完成");
        }
        try {
            T value = objectMapper.readValue(json, type);
            if (value instanceof com.example.starter.fishery.api.dto.ReplayAware replayAware) {
                @SuppressWarnings("unchecked")
                T marked = (T) replayAware.withReplayed(true);
                return marked;
            }
            return value;
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("读取首次响应快照失败", ex);
        }
    }
}
