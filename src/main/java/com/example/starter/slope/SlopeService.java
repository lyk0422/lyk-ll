package com.example.starter.slope;

import com.example.starter.slope.SlopeRepository.AssessmentRow;
import com.example.starter.slope.SlopeRepository.ChainRow;
import com.example.starter.slope.SlopeRepository.EvacuationRow;
import com.example.starter.slope.SlopeRepository.LateEventRow;
import com.example.starter.slope.SlopeRepository.RainfallRow;
import com.example.starter.slope.SlopeRepository.ReadingRow;
import com.example.starter.slope.SlopeRepository.StrategyRow;
import com.example.starter.slope.SlopeRepository.ZoneRow;
import com.example.starter.slope.dto.ApiDtos.ArchiveRequest;
import com.example.starter.slope.dto.ApiDtos.ArchiveResponse;
import com.example.starter.slope.dto.ApiDtos.AssessmentInfo;
import com.example.starter.slope.dto.ApiDtos.ChainDetailResponse;
import com.example.starter.slope.dto.ApiDtos.CreateZoneRequest;
import com.example.starter.slope.dto.ApiDtos.EffectiveThresholdResponse;
import com.example.starter.slope.dto.ApiDtos.EvacuationConfirmRequest;
import com.example.starter.slope.dto.ApiDtos.EvacuationConfirmResponse;
import com.example.starter.slope.dto.ApiDtos.EvacuationInfo;
import com.example.starter.slope.dto.ApiDtos.IngestReadingRequest;
import com.example.starter.slope.dto.ApiDtos.LateEventItem;
import com.example.starter.slope.dto.ApiDtos.PublishStrategyRequest;
import com.example.starter.slope.dto.ApiDtos.RainfallResponse;
import com.example.starter.slope.dto.ApiDtos.RateCurveResponse;
import com.example.starter.slope.dto.ApiDtos.RatePoint;
import com.example.starter.slope.dto.ApiDtos.ReadingResponse;
import com.example.starter.slope.dto.ApiDtos.ReportRainfallRequest;
import com.example.starter.slope.dto.ApiDtos.ResumptionApplyRequest;
import com.example.starter.slope.dto.ApiDtos.ResumptionApplyResponse;
import com.example.starter.slope.dto.ApiDtos.ResumptionConfirmRequest;
import com.example.starter.slope.dto.ApiDtos.ResumptionConfirmResponse;
import com.example.starter.slope.dto.ApiDtos.ResumptionEvidenceItem;
import com.example.starter.slope.dto.ApiDtos.StrategyResponse;
import com.example.starter.slope.dto.ApiDtos.ZoneResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 边坡监测业务服务：监测→预警分级→处置→撤人→复工评估→归档。
 * 所有写操作在事务内完成，并按“测区行锁→处置链行锁”的顺序加锁，保证同一测区
 * 同时刻仅一条执行中处置链、并发预警唯一受理、停产与复工唯一终态。
 * 业务时间全部来自请求报文（监测/确认/申请时刻），便于复算与可控时钟测试。
 */
@Service
public class SlopeService {

    /** 撤人确认时限：红色预警触发后30分钟（恰满判定为按时）。 */
    static final long EVACUATION_LIMIT_MINUTES = 30;

    /** 复工评估等待窗口：红色预警触发后24小时（恰满允许申请）。 */
    static final long RECOVERY_WINDOW_HOURS = 24;

    /** 雨量评价窗口：评估时刻之前24个完整小时。 */
    static final long RAINFALL_WINDOW_HOURS = 24;

    private static final String STATUS_NORMAL = "NORMAL";
    private static final String STATUS_STOPPED = "STOPPED";
    private static final String STATUS_LOCKED = "LOCKED";

    private static final String LEVEL_YELLOW = "YELLOW";
    private static final String LEVEL_RED = "RED";

    private static final String CHAIN_EXECUTING = "EXECUTING";
    private static final String CHAIN_RESUMED = "RESUMED";
    private static final String CHAIN_ARCHIVED = "ARCHIVED";

    private static final String ASSESSMENT_APPLIED = "APPLIED";
    private static final String ASSESSMENT_APPROVED = "APPROVED";

    private static final String CONFIRM_SITE_CHECK = "SITE_CHECK";
    private static final String CONFIRM_MONITORING_REVIEW = "MONITORING_REVIEW";

    private final SlopeRepository repository;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    public SlopeService(SlopeRepository repository, IdempotencyService idempotencyService,
                        ObjectMapper objectMapper) {
        this.repository = repository;
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
    }

    /** 创建测区（幂等）。 */
    @Transactional
    public IdempotencyService.Stored createZone(CreateZoneRequest request) {
        return idempotencyService.execute("POST /api/zones", request.requestId(), request, () -> {
            repository.findZoneByCode(request.code()).ifPresent(existing -> {
                throw ApiException.conflict("ZONE_CODE_EXISTS", "测区编码已存在: " + request.code());
            });
            long zoneId = repository.insertZone(request.code(), request.name(),
                    request.warnHourlyRate(), request.alarmDailyRate(), request.safetyHourlyRate(),
                    request.personnelTotal());
            return json(201, new ZoneResponse(zoneId, request.code(), STATUS_NORMAL));
        });
    }

    /** 上报雷达读数：去重、乱序归位、速率复算、预警分级（幂等）。 */
    @Transactional
    public IdempotencyService.Stored ingestReading(long zoneId, IngestReadingRequest request) {
        return idempotencyService.execute("POST /api/zones/{zoneId}/readings", request.requestId(),
                new ZoneScopedRequest<>(zoneId, request), () -> doIngestReading(zoneId, request));
    }

    /** 撤人确认：与作业封锁在同一事务内完成；恰满30分钟判定为按时（幂等）。 */
    @Transactional
    public IdempotencyService.Stored confirmEvacuation(long chainId, EvacuationConfirmRequest request) {
        return idempotencyService.execute("POST /api/chains/{chainId}/evacuation-confirmations",
                request.requestId(), new ChainScopedRequest<>(chainId, request),
                () -> doConfirmEvacuation(chainId, request));
    }

    /** 复工评估申请：红色预警后连续24小时速率低于安全线，恰满24小时允许（幂等）。 */
    @Transactional
    public IdempotencyService.Stored applyResumption(long chainId, ResumptionApplyRequest request) {
        return idempotencyService.execute("POST /api/chains/{chainId}/resumption-applications",
                request.requestId(), new ChainScopedRequest<>(chainId, request),
                () -> doApplyResumption(chainId, request));
    }

    /** 复工双确认：现场核查与监测复核均确认后批准复工（幂等）。 */
    @Transactional
    public IdempotencyService.Stored confirmResumption(long chainId, ResumptionConfirmRequest request) {
        return idempotencyService.execute("POST /api/chains/{chainId}/resumption-confirmations",
                request.requestId(), new ChainScopedRequest<>(chainId, request),
                () -> doConfirmResumption(chainId, request));
    }

    /** 归档：终态；重复归档返回幂等成功（幂等）。 */
    @Transactional
    public IdempotencyService.Stored archiveChain(long chainId, ArchiveRequest request) {
        return idempotencyService.execute("POST /api/chains/{chainId}/archive",
                request.requestId(), new ChainScopedRequest<>(chainId, request),
                () -> doArchiveChain(chainId, request));
    }

    /** 速率曲线：由位移读数复算。 */
    @Transactional(readOnly = true)
    public RateCurveResponse rateCurve(long zoneId) {
        repository.findZoneById(zoneId).orElseThrow(
                () -> ApiException.notFound("ZONE_NOT_FOUND", "测区不存在: " + zoneId));
        List<RateCalculator.Sample> samples = repository.findReadingsByZone(zoneId).stream()
                .map(row -> new RateCalculator.Sample(row.measuredAt(), row.displacementMm()))
                .toList();
        List<RatePoint> points = RateCalculator.compute(samples).stream()
                .map(rated -> new RatePoint(rated.measuredAt(), rated.displacementMm(),
                        rated.hourlyRate(), rated.dailyRate()))
                .toList();
        return new RateCurveResponse(zoneId, points);
    }

    /** 处置链详情。 */
    @Transactional(readOnly = true)
    public ChainDetailResponse chainDetail(long chainId) {
        ChainRow chain = repository.findChainById(chainId).orElseThrow(
                () -> ApiException.notFound("CHAIN_NOT_FOUND", "处置链不存在: " + chainId));
        EvacuationInfo evacuation = repository.findEvacuationByChain(chainId)
                .map(row -> new EvacuationInfo(row.confirmedAt(), row.arrivedCount(), row.onTime()))
                .orElse(null);
        AssessmentInfo assessment = repository.findAssessmentByChain(chainId)
                .map(row -> new AssessmentInfo(row.id(), row.appliedAt(), row.siteCheckConfirmed(),
                        row.monitoringReviewConfirmed(), row.status(), row.approvedAt()))
                .orElse(null);
        return new ChainDetailResponse(chain.id(), chain.zoneId(), chain.level(), chain.status(),
                chain.triggeredAt(), chain.evacuateDeadline(), chain.evacuatedAt(), chain.archivedAt(),
                evacuation, assessment);
    }

    /** 迟到事件列表。 */
    @Transactional(readOnly = true)
    public List<LateEventItem> lateEvents(long zoneId) {
        repository.findZoneById(zoneId).orElseThrow(
                () -> ApiException.notFound("ZONE_NOT_FOUND", "测区不存在: " + zoneId));
        return repository.findLateEventsByZone(zoneId).stream()
                .map(row -> new LateEventItem(row.id(), row.chainId(), row.measuredAt(),
                        row.displacementMm(), row.reason()))
                .toList();
    }

    /** 发布阈值策略版本：版本从1开始，expectedVersion 须等于当前最新版本（幂等）。 */
    @Transactional
    public IdempotencyService.Stored publishStrategy(long zoneId, PublishStrategyRequest request) {
        return idempotencyService.execute("POST /api/zones/{zoneId}/strategies", request.requestId(),
                new ZoneScopedRequest<>(zoneId, request), () -> doPublishStrategy(zoneId, request));
    }

    /** 上报/更正合成雨量小时数据：同值重放、异值按 expectedVersion 更正（幂等）。 */
    @Transactional
    public IdempotencyService.Stored reportRainfall(long zoneId, ReportRainfallRequest request) {
        return idempotencyService.execute("POST /api/zones/{zoneId}/rainfall-hours", request.requestId(),
                new ZoneScopedRequest<>(zoneId, request), () -> doReportRainfall(zoneId, request));
    }

    /**
     * 有效阈值查询：返回指定时刻生效的策略版本、此前24个完整小时雨量合计、
     * 是否启用折减及折减后的有效阈值；无生效策略时回退测区静态安全线。
     */
    @Transactional(readOnly = true)
    public EffectiveThresholdResponse effectiveThreshold(long zoneId, LocalDateTime at) {
        ZoneRow zone = repository.findZoneById(zoneId).orElseThrow(
                () -> ApiException.notFound("ZONE_NOT_FOUND", "测区不存在: " + zoneId));
        var strategyOpt = repository.findStrategyEffectiveAt(zoneId, at);
        if (strategyOpt.isEmpty()) {
            return new EffectiveThresholdResponse(zoneId, at, null, null, null,
                    true, List.of(), null, false, null, null, null, zone.safetyHourlyRate());
        }
        StrategyRow strategy = strategyOpt.get();
        RainfallWindow window = rainfallWindow(zoneId, at);
        // 雨量恰达门槛即启用折减；数据不足时不启用且合计置空（缺失不得当零）。
        boolean reduction = window.complete() && window.sum() >= strategy.rainfall24hThreshold();
        double factor = reduction ? strategy.reductionFactor() : 1.0;
        return new EffectiveThresholdResponse(zoneId, at, strategy.version(),
                strategy.yellowRate(), strategy.redRate(),
                window.complete(), window.missingHours(),
                window.complete() ? window.sum() : null, reduction,
                strategy.yellowRate() * factor, strategy.redRate() * factor,
                strategy.yellowRate() * factor, zone.safetyHourlyRate());
    }

    /** 复工证据查询：返回该链每次安全评估冻结的证据（申请与批准各一条）。 */
    @Transactional(readOnly = true)
    public List<ResumptionEvidenceItem> resumptionEvidence(long chainId) {
        repository.findChainById(chainId).orElseThrow(
                () -> ApiException.notFound("CHAIN_NOT_FOUND", "处置链不存在: " + chainId));
        return repository.findEvidenceByChain(chainId).stream()
                .map(row -> new ResumptionEvidenceItem(row.id(), row.chainId(), row.assessmentId(),
                        row.evaluatedAt(), row.windowStart(), row.windowEnd(),
                        row.rainfallWindowStart(), row.rainfall24hSum(), row.reductionApplied(),
                        row.strategyVersion(), row.safetyThreshold(), row.readingCount(),
                        row.detail()))
                .toList();
    }

    // ------------------------------------------------------------------
    // 以下为事务内业务实现
    // ------------------------------------------------------------------

    private IdempotencyService.Stored doIngestReading(long zoneId, IngestReadingRequest request) {
        ZoneRow zone = repository.findZoneByIdForUpdate(zoneId).orElseThrow(
                () -> ApiException.notFound("ZONE_NOT_FOUND", "测区不存在: " + zoneId));

        // 归档为终态：归档时刻之前（含）的迟到位移只记迟到事件，不进入速率曲线。
        var archived = repository.findLatestArchivedChainByZone(zoneId);
        if (archived.isPresent() && !request.measuredAt().isAfter(archived.get().archivedAt())) {
            repository.insertLateEvent(zoneId, archived.get().id(), request.measuredAt(),
                    request.displacementMm(), "ARCHIVED_LATE_DATA");
            return json(201, new ReadingResponse(null, false, true, null, null,
                    "NONE", null, zone.status()));
        }

        // 监测数据按“测区＋时刻”去重。
        var duplicated = repository.findReading(zoneId, request.measuredAt());
        if (duplicated.isPresent()) {
            ReadingRow existing = duplicated.get();
            return json(200, new ReadingResponse(existing.id(), true, false,
                    existing.hourlyRate(), existing.dailyRate(), "NONE", null, zone.status()));
        }

        repository.insertReading(zoneId, request.measuredAt(), request.displacementMm());
        recomputeZoneRates(zoneId);
        ReadingRow reading = repository.findReading(zoneId, request.measuredAt()).orElseThrow();

        String evaluation = evaluateAlarm(zone, reading);
        Long chainId = repository.findExecutingChainByZone(zoneId).map(ChainRow::id).orElse(null);
        ZoneRow current = repository.findZoneById(zoneId).orElseThrow();
        return json(201, new ReadingResponse(reading.id(), false, false,
                reading.hourlyRate(), reading.dailyRate(), evaluation, chainId, current.status()));
    }

    /**
     * 预警分级：日速率严格超过报警值触发红色预警并立即停产撤人；
     * 小时速率严格超过预警值触发黄色预警；恰等阈值判正常。
     * 红色链执行中（已停产）的后续读数属于回落监测，不再重复受理预警；
     * 黄色链执行中再触发黄色视为并发重复预警，拒绝受理（409）。
     */
    private String evaluateAlarm(ZoneRow zone, ReadingRow reading) {
        boolean red = reading.dailyRate() != null && reading.dailyRate() > zone.alarmDailyRate();
        boolean yellow = reading.hourlyRate() != null && reading.hourlyRate() > zone.warnHourlyRate();
        var executing = repository.findExecutingChainByZone(zone.id());
        if (executing.isEmpty()) {
            if (red) {
                repository.insertChain(zone.id(), LEVEL_RED, CHAIN_EXECUTING, reading.measuredAt(),
                        reading.measuredAt().plusMinutes(EVACUATION_LIMIT_MINUTES));
                repository.updateZoneStatus(zone.id(), STATUS_STOPPED);
                return "RED_CHAIN_CREATED";
            }
            if (yellow) {
                repository.insertChain(zone.id(), LEVEL_YELLOW, CHAIN_EXECUTING, reading.measuredAt(), null);
                return "YELLOW_CHAIN_CREATED";
            }
            return "NORMAL";
        }
        ChainRow chain = executing.get();
        if (LEVEL_RED.equals(chain.level())) {
            return "RED_CHAIN_ACTIVE";
        }
        if (red) {
            repository.upgradeChainToRed(chain.id(), reading.measuredAt(),
                    reading.measuredAt().plusMinutes(EVACUATION_LIMIT_MINUTES));
            repository.updateZoneStatus(zone.id(), STATUS_STOPPED);
            return "CHAIN_UPGRADED_RED";
        }
        if (yellow) {
            throw ApiException.conflict("CHAIN_ALREADY_EXECUTING",
                    "测区已存在执行中处置链，本次预警不予受理: chainId=" + chain.id());
        }
        return "NORMAL";
    }

    private IdempotencyService.Stored doConfirmEvacuation(long chainId, EvacuationConfirmRequest request) {
        ChainRow chain = lockZoneAndChain(chainId);
        if (!CHAIN_EXECUTING.equals(chain.status())) {
            throw ApiException.conflict("CHAIN_NOT_EXECUTING", "处置链不在执行中，无法确认撤人: " + chainId);
        }
        if (!LEVEL_RED.equals(chain.level())) {
            throw ApiException.unprocessable("NOT_RED_CHAIN", "黄色预警无需停产撤人: " + chainId);
        }
        if (chain.evacuatedAt() != null) {
            throw ApiException.conflict("EVACUATION_ALREADY_CONFIRMED", "撤人已确认: " + chainId);
        }
        ZoneRow zone = repository.findZoneById(chain.zoneId()).orElseThrow();
        // 恰满30分钟判定为按时：仅严格晚于截止时刻才判超时。
        if (request.confirmedAt().isAfter(chain.evacuateDeadline())) {
            throw ApiException.unprocessable("EVACUATION_TIMEOUT",
                    "撤人确认超过30分钟时限: deadline=" + chain.evacuateDeadline());
        }
        if (request.arrivedCount() != zone.personnelTotal()) {
            throw ApiException.unprocessable("PERSONNEL_INCOMPLETE",
                    "撤人未全员到位: arrived=" + request.arrivedCount()
                            + ", required=" + zone.personnelTotal());
        }
        // 撤人确认与作业封锁在同一事务内完成。
        long confirmationId = repository.insertEvacuation(chainId, chain.zoneId(),
                request.confirmedAt(), request.arrivedCount(), true);
        repository.markChainEvacuated(chainId, request.confirmedAt());
        repository.updateZoneStatus(chain.zoneId(), STATUS_LOCKED);
        return json(201, new EvacuationConfirmResponse(confirmationId, chainId, true, STATUS_LOCKED));
    }

    private IdempotencyService.Stored doApplyResumption(long chainId, ResumptionApplyRequest request) {
        ChainRow chain = lockZoneAndChain(chainId);
        if (!CHAIN_EXECUTING.equals(chain.status())) {
            throw ApiException.conflict("CHAIN_NOT_EXECUTING", "处置链不在执行中，无法申请复工: " + chainId);
        }
        if (!LEVEL_RED.equals(chain.level())) {
            throw ApiException.unprocessable("NOT_RED_CHAIN", "黄色预警无需复工评估: " + chainId);
        }
        if (chain.evacuatedAt() == null) {
            throw ApiException.unprocessable("EVACUATION_NOT_CONFIRMED", "撤人未确认，无法申请复工: " + chainId);
        }
        repository.findAssessmentByChain(chainId).ifPresent(existing -> {
            throw ApiException.conflict("ASSESSMENT_EXISTS", "复工评估已申请: " + chainId);
        });
        ZoneRow zone = repository.findZoneById(chain.zoneId()).orElseThrow();
        // 恰满24小时允许申请：仅严格早于触发后24小时才拒绝。
        LocalDateTime earliest = chain.triggeredAt().plusHours(RECOVERY_WINDOW_HOURS);
        if (request.appliedAt().isBefore(earliest)) {
            throw ApiException.unprocessable("RECOVERY_WINDOW_TOO_SHORT",
                    "红色预警后未满24小时，无法申请复工: earliest=" + earliest);
        }
        // 连续24小时安全判定：按每个时刻实际有效的阈值（策略版本+雨量折减）。
        SafetyEvaluation evaluation = evaluateResumptionSafety(chain, zone, request.appliedAt());
        long assessmentId = repository.insertAssessment(chainId, chain.zoneId(), request.appliedAt());
        insertEvidence(chainId, assessmentId, chain.zoneId(), request.appliedAt(), evaluation);
        return json(201, new ResumptionApplyResponse(assessmentId, ASSESSMENT_APPLIED));
    }

    private IdempotencyService.Stored doConfirmResumption(long chainId, ResumptionConfirmRequest request) {
        ChainRow chain = lockZoneAndChain(chainId);
        AssessmentRow assessment = repository.findAssessmentByChainForUpdate(chainId).orElseThrow(
                () -> ApiException.conflict("ASSESSMENT_NOT_APPLIED", "复工评估未申请: " + chainId));
        if (ASSESSMENT_APPROVED.equals(assessment.status())) {
            throw ApiException.conflict("RESUMPTION_ALREADY_APPROVED", "复工已批准: " + chainId);
        }
        String type = request.type();
        if (!CONFIRM_SITE_CHECK.equals(type) && !CONFIRM_MONITORING_REVIEW.equals(type)) {
            throw ApiException.badRequest("UNKNOWN_CONFIRM_TYPE",
                    "确认类型须为SITE_CHECK或MONITORING_REVIEW: " + type);
        }
        boolean siteCheck = assessment.siteCheckConfirmed();
        boolean monitoringReview = assessment.monitoringReviewConfirmed();
        if (CONFIRM_SITE_CHECK.equals(type)) {
            if (siteCheck) {
                throw ApiException.conflict("CONFIRM_DUPLICATED", "现场核查已确认: " + chainId);
            }
            siteCheck = true;
        } else {
            if (monitoringReview) {
                throw ApiException.conflict("CONFIRM_DUPLICATED", "监测复核已确认: " + chainId);
            }
            monitoringReview = true;
        }
        // 双确认齐全才批准复工：停产与复工形成唯一终态。
        boolean approved = siteCheck && monitoringReview;
        String assessmentStatus = approved ? ASSESSMENT_APPROVED : ASSESSMENT_APPLIED;
        if (approved) {
            // 批准前按当前快照复评安全条件并冻结证据：雨量更正或策略发布与复工确认
            // 并发时，测区行锁保证二者串行，批准所依据的证据与最终数据一致。
            ZoneRow zone = repository.findZoneById(chain.zoneId()).orElseThrow();
            SafetyEvaluation evaluation = evaluateResumptionSafety(chain, zone, request.confirmedAt());
            insertEvidence(chainId, assessment.id(), chain.zoneId(), request.confirmedAt(), evaluation);
        }
        repository.updateAssessmentConfirmation(assessment.id(), siteCheck, monitoringReview,
                assessmentStatus, approved ? request.confirmedAt() : null);
        String chainStatus = chain.status();
        String zoneStatus = repository.findZoneById(chain.zoneId()).orElseThrow().status();
        if (approved) {
            repository.updateChainStatus(chainId, CHAIN_RESUMED, null);
            repository.updateZoneStatus(chain.zoneId(), STATUS_NORMAL);
            chainStatus = CHAIN_RESUMED;
            zoneStatus = STATUS_NORMAL;
        }
        return json(201, new ResumptionConfirmResponse(assessment.id(), siteCheck, monitoringReview,
                assessmentStatus, chainStatus, zoneStatus));
    }

    private IdempotencyService.Stored doArchiveChain(long chainId, ArchiveRequest request) {
        ChainRow chain = lockZoneAndChain(chainId);
        if (CHAIN_ARCHIVED.equals(chain.status())) {
            // 归档为终态：重复归档返回幂等成功。
            return json(200, new ArchiveResponse(chainId, CHAIN_ARCHIVED, chain.archivedAt()));
        }
        if (CHAIN_EXECUTING.equals(chain.status()) && LEVEL_RED.equals(chain.level())) {
            throw ApiException.conflict("CHAIN_NOT_RESUMED", "红色处置链须复工批准后方可归档: " + chainId);
        }
        repository.updateChainStatus(chainId, CHAIN_ARCHIVED, request.archivedAt());
        return json(200, new ArchiveResponse(chainId, CHAIN_ARCHIVED, request.archivedAt()));
    }

    /**
     * 按“测区行锁→处置链行锁”顺序加锁并返回最新链状态。
     */
    private ChainRow lockZoneAndChain(long chainId) {
        ChainRow probe = repository.findChainById(chainId).orElseThrow(
                () -> ApiException.notFound("CHAIN_NOT_FOUND", "处置链不存在: " + chainId));
        repository.findZoneByIdForUpdate(probe.zoneId()).orElseThrow(
                () -> ApiException.notFound("ZONE_NOT_FOUND", "测区不存在: " + probe.zoneId()));
        return repository.findChainByIdForUpdate(chainId).orElseThrow();
    }

    /**
     * 乱序归位后整链复算：按监测时刻重排全部读数并重算速率，保证曲线可复算。
     */
    private void recomputeZoneRates(long zoneId) {
        List<ReadingRow> readings = repository.findReadingsByZone(zoneId);
        List<RateCalculator.Sample> samples = readings.stream()
                .map(row -> new RateCalculator.Sample(row.measuredAt(), row.displacementMm()))
                .toList();
        List<RateCalculator.Rated> rated = RateCalculator.compute(samples);
        for (int i = 0; i < readings.size(); i++) {
            repository.updateReadingRates(readings.get(i).id(),
                    rated.get(i).hourlyRate(), rated.get(i).dailyRate());
        }
    }

    private IdempotencyService.Stored json(int status, Object body) {
        try {
            return new IdempotencyService.Stored(status, objectMapper.writeValueAsString(body));
        } catch (Exception e) {
            throw new IllegalStateException("响应报文序列化失败", e);
        }
    }

    private IdempotencyService.Stored doPublishStrategy(long zoneId, PublishStrategyRequest request) {
        // 与复工确认等同测区写操作共用测区行锁，保证同一快照内判定。
        repository.findZoneByIdForUpdate(zoneId).orElseThrow(
                () -> ApiException.notFound("ZONE_NOT_FOUND", "测区不存在: " + zoneId));
        requireFinite(request.yellowRate(), "yellowRate");
        requireFinite(request.redRate(), "redRate");
        requireFinite(request.rainfall24hThreshold(), "rainfall24hThreshold");
        requireFinite(request.reductionFactor(), "reductionFactor");
        if (request.yellowRate() >= request.redRate()) {
            throw ApiException.unprocessable("THRESHOLD_ORDER_INVALID",
                    "基础速率阈值须满足0<黄<红: yellow=" + request.yellowRate()
                            + ", red=" + request.redRate());
        }
        if (request.effectiveFrom().isBefore(request.submittedAt())) {
            throw ApiException.unprocessable("EFFECTIVE_BEFORE_SUBMISSION",
                    "策略生效时刻不得早于提交时刻: effectiveFrom=" + request.effectiveFrom()
                            + ", submittedAt=" + request.submittedAt());
        }
        int current = repository.findLatestStrategyByZone(zoneId).map(StrategyRow::version).orElse(0);
        if (request.expectedVersion() != current) {
            throw ApiException.conflict("VERSION_CONFLICT",
                    "策略版本冲突: expected=" + request.expectedVersion() + ", current=" + current);
        }
        int newVersion = current + 1;
        long strategyId = repository.insertStrategy(zoneId, newVersion, request.yellowRate(),
                request.redRate(), request.rainfall24hThreshold(), request.reductionFactor(),
                request.effectiveFrom(), request.submittedAt());
        return json(201, new StrategyResponse(strategyId, zoneId, newVersion, request.yellowRate(),
                request.redRate(), request.rainfall24hThreshold(), request.reductionFactor(),
                request.effectiveFrom(), request.submittedAt()));
    }

    private IdempotencyService.Stored doReportRainfall(long zoneId, ReportRainfallRequest request) {
        repository.findZoneByIdForUpdate(zoneId).orElseThrow(
                () -> ApiException.notFound("ZONE_NOT_FOUND", "测区不存在: " + zoneId));
        LocalDateTime hour = request.hourStart();
        if (!hour.equals(hour.truncatedTo(ChronoUnit.HOURS))) {
            throw ApiException.badRequest("RAINFALL_HOUR_NOT_ALIGNED",
                    "雨量小时起点须为UTC整点: " + hour);
        }
        requireFinite(request.rainfallMm(), "rainfallMm");
        var existing = repository.findRainfall(zoneId, hour);
        if (existing.isEmpty()) {
            if (request.expectedVersion() != 0) {
                throw ApiException.conflict("VERSION_CONFLICT",
                        "雨量记录不存在，首次上报expectedVersion须为0: " + request.expectedVersion());
            }
            long rainfallId = repository.insertRainfall(zoneId, hour, request.rainfallMm());
            return json(201, new RainfallResponse(rainfallId, zoneId, hour, request.rainfallMm(),
                    1, false, false));
        }
        RainfallRow row = existing.get();
        if (Double.compare(row.rainfallMm(), request.rainfallMm()) == 0) {
            // 同测区同小时同值重放：返回既有记录，不产生新版本。
            return json(200, new RainfallResponse(row.id(), zoneId, hour, row.rainfallMm(),
                    row.version(), true, false));
        }
        // 异值须显式更正版本：expectedVersion 等于当前版本才受理。
        if (request.expectedVersion() != row.version()) {
            throw ApiException.conflict("VERSION_CONFLICT",
                    "雨量更正版本冲突: expected=" + request.expectedVersion()
                            + ", current=" + row.version());
        }
        repository.updateRainfall(row.id(), request.rainfallMm(), row.version() + 1);
        return json(200, new RainfallResponse(row.id(), zoneId, hour, request.rainfallMm(),
                row.version() + 1, false, true));
    }

    /**
     * 此前24个完整小时的雨量窗口：bucket 为 [floor(at)-24h, floor(at)) 的24个整点小时。
     * 任一缺点 complete=false 且 sum 为 null（缺失不得当零）。
     */
    private RainfallWindow rainfallWindow(long zoneId, LocalDateTime at) {
        LocalDateTime end = at.truncatedTo(ChronoUnit.HOURS);
        LocalDateTime start = end.minusHours(RAINFALL_WINDOW_HOURS);
        Map<LocalDateTime, Double> byHour = new HashMap<>();
        for (RainfallRow row : repository.findRainfallByZoneBetween(zoneId, start, end)) {
            byHour.put(row.hourStart(), row.rainfallMm());
        }
        List<LocalDateTime> missing = new ArrayList<>();
        double sum = 0.0;
        for (int i = 0; i < RAINFALL_WINDOW_HOURS; i++) {
            LocalDateTime bucket = start.plusHours(i);
            Double value = byHour.get(bucket);
            if (value == null) {
                missing.add(bucket);
            } else {
                sum += value;
            }
        }
        return new RainfallWindow(start, missing.isEmpty(), missing.isEmpty() ? sum : null, missing);
    }

    private static void requireFinite(Double value, String field) {
        if (value == null || !Double.isFinite(value)) {
            throw ApiException.badRequest("NON_FINITE_VALUE", "数值须为有限值: " + field);
        }
    }

    /** 雨量窗口：起点、是否完整、合计（不完整时为null）、缺点小时列表。 */
    private record RainfallWindow(LocalDateTime start, boolean complete, Double sum,
                                  List<LocalDateTime> missingHours) {
    }

    /**
     * 复工安全评估：连续24小时按每个时刻实际有效的阈值判定。
     * 评估时刻存在生效策略版本时：读取此前24个完整小时雨量，任一缺点返回数据不足并
     * 阻断（不得当零）；雨量恰达门槛启用折减；速率恰等有效阈值仍判正常（须严格低于）。
     * 策略变严（黄/红阈值或折减系数变低）后安全计时自生效时刻重新累计，不沿用旧计时；
     * 变宽不影响既有处置链。无生效策略时回退测区静态安全线（旧线）。
     * 雨量与策略版本均取自本事务同一快照（调用方已持有测区行锁）。
     */
    private SafetyEvaluation evaluateResumptionSafety(ChainRow chain, ZoneRow zone,
                                                      LocalDateTime evaluatedAt) {
        List<StrategyRow> strategies = repository.findStrategiesByZone(chain.zoneId());
        StrategyRow atEval = strategies.stream()
                .filter(s -> !s.effectiveFrom().isAfter(evaluatedAt))
                .max((a, b) -> a.effectiveFrom().equals(b.effectiveFrom())
                        ? Integer.compare(a.version(), b.version())
                        : a.effectiveFrom().compareTo(b.effectiveFrom()))
                .orElse(null);

        LocalDateTime windowStart = chain.triggeredAt();
        LocalDateTime rainfallStart = null;
        Double rainfallSum = null;
        boolean reduction = false;
        Integer strategyVersion = null;
        double evalThreshold = zone.safetyHourlyRate();

        if (atEval != null) {
            strategyVersion = atEval.version();
            RainfallWindow rainfall = rainfallWindow(chain.zoneId(), evaluatedAt);
            if (!rainfall.complete()) {
                throw ApiException.unprocessable("RAINFALL_DATA_INSUFFICIENT",
                        "此前24个完整小时雨量数据不足，阻断复工批准: firstMissing="
                                + rainfall.missingHours().get(0));
            }
            rainfallStart = rainfall.start();
            rainfallSum = rainfall.sum();
            // 雨量恰达门槛即启用折减。
            reduction = rainfallSum >= atEval.rainfall24hThreshold();
            evalThreshold = atEval.yellowRate() * (reduction ? atEval.reductionFactor() : 1.0);
            // 策略变严后不沿用旧安全计时：自最新变严版本生效时刻重新累计。
            LocalDateTime stricterAt = latestStricterBoundary(strategies, evaluatedAt);
            if (stricterAt != null && stricterAt.isAfter(windowStart)) {
                windowStart = stricterAt;
            }
            if (evaluatedAt.isBefore(windowStart.plusHours(RECOVERY_WINDOW_HOURS))) {
                throw ApiException.unprocessable("SAFETY_WINDOW_RESTARTED",
                        "策略变严后安全计时重新累计，生效后未满24小时: earliest="
                                + windowStart.plusHours(RECOVERY_WINDOW_HOURS));
            }
        }

        final LocalDateTime start = windowStart;
        List<ReadingRow> window = repository.findReadingsByZone(chain.zoneId()).stream()
                .filter(row -> row.measuredAt().isAfter(start)
                        && !row.measuredAt().isAfter(evaluatedAt))
                .toList();
        if (window.isEmpty()) {
            throw ApiException.unprocessable("NO_MONITORING_DATA",
                    "预警后窗口内无监测数据，无法证明速率回落: " + chain.id());
        }
        var detail = objectMapper.createObjectNode();
        detail.put("evaluatedAt", evaluatedAt.toString());
        detail.put("windowStart", windowStart.toString());
        var readingsNode = detail.putArray("readings");
        for (ReadingRow row : window) {
            // 每个时刻实际有效的阈值：该时刻生效的策略版本（无则旧线），叠加评估时的折减判定。
            double threshold = zone.safetyHourlyRate();
            Integer rowVersion = null;
            if (atEval != null) {
                StrategyRow atReading = strategies.stream()
                        .filter(s -> !s.effectiveFrom().isAfter(row.measuredAt()))
                        .max((a, b) -> a.effectiveFrom().equals(b.effectiveFrom())
                                ? Integer.compare(a.version(), b.version())
                                : a.effectiveFrom().compareTo(b.effectiveFrom()))
                        .orElse(null);
                if (atReading != null) {
                    rowVersion = atReading.version();
                    threshold = atReading.yellowRate()
                            * (reduction ? atReading.reductionFactor() : 1.0);
                }
            }
            // 速率恰等有效阈值判正常：仅严格低于才安全。
            if (row.hourlyRate() == null || row.hourlyRate() >= threshold) {
                throw ApiException.unprocessable("RATE_ABOVE_SAFETY_LINE",
                        "窗口内存在速率未回落至有效安全阈值以下的读数: measuredAt="
                                + row.measuredAt() + ", threshold=" + threshold);
            }
            var item = readingsNode.addObject();
            item.put("measuredAt", row.measuredAt().toString());
            item.put("hourlyRate", row.hourlyRate());
            if (rowVersion != null) {
                item.put("strategyVersion", rowVersion);
            }
            item.put("threshold", threshold);
            item.put("passed", true);
        }
        String detailJson;
        try {
            detailJson = objectMapper.writeValueAsString(detail);
        } catch (Exception e) {
            throw new IllegalStateException("证据明细序列化失败", e);
        }
        return new SafetyEvaluation(windowStart, evaluatedAt, rainfallStart, rainfallSum,
                reduction, strategyVersion, evalThreshold, window.size(), detailJson);
    }

    /** 最新变严版本的生效时刻（不晚于评估时刻）；无变严版本时为 null。 */
    private LocalDateTime latestStricterBoundary(List<StrategyRow> strategies,
                                                 LocalDateTime evaluatedAt) {
        LocalDateTime boundary = null;
        for (int i = 1; i < strategies.size(); i++) {
            StrategyRow prev = strategies.get(i - 1);
            StrategyRow current = strategies.get(i);
            boolean stricter = current.yellowRate() < prev.yellowRate()
                    || current.redRate() < prev.redRate()
                    || current.reductionFactor() < prev.reductionFactor();
            if (stricter && !current.effectiveFrom().isAfter(evaluatedAt)
                    && (boundary == null || current.effectiveFrom().isAfter(boundary))) {
                boundary = current.effectiveFrom();
            }
        }
        return boundary;
    }

    private void insertEvidence(long chainId, long assessmentId, long zoneId,
                                LocalDateTime evaluatedAt, SafetyEvaluation evaluation) {
        repository.insertEvidence(chainId, assessmentId, zoneId, evaluatedAt,
                evaluation.windowStart(), evaluation.windowEnd(), evaluation.rainfallWindowStart(),
                evaluation.rainfall24hSum(), evaluation.reductionApplied(),
                evaluation.strategyVersion(), evaluation.safetyThreshold(),
                evaluation.readingCount(), evaluation.detail());
    }

    /** 一次复工安全评估的冻结结果。 */
    private record SafetyEvaluation(LocalDateTime windowStart, LocalDateTime windowEnd,
                                    LocalDateTime rainfallWindowStart, Double rainfall24hSum,
                                    boolean reductionApplied, Integer strategyVersion,
                                    double safetyThreshold, int readingCount, String detail) {
    }

    /** 幂等指纹携带路径参数，避免不同测区/链的同requestId互相重放。 */
    record ZoneScopedRequest<T>(long zoneId, T body) {
    }

    /** 幂等指纹携带路径参数，避免不同测区/链的同requestId互相重放。 */
    record ChainScopedRequest<T>(long chainId, T body) {
    }
}
