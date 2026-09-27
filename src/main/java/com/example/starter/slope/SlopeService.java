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
import com.example.starter.slope.dto.ApiDtos.PublishStrategyResponse;
import com.example.starter.slope.dto.ApiDtos.RainfallHourRequest;
import com.example.starter.slope.dto.ApiDtos.RainfallHourResponse;
import com.example.starter.slope.dto.ApiDtos.RateCurveResponse;
import com.example.starter.slope.dto.ApiDtos.RatePoint;
import com.example.starter.slope.dto.ApiDtos.ReadingResponse;
import com.example.starter.slope.dto.ApiDtos.ResumptionApplyRequest;
import com.example.starter.slope.dto.ApiDtos.ResumptionApplyResponse;
import com.example.starter.slope.dto.ApiDtos.ResumptionConfirmRequest;
import com.example.starter.slope.dto.ApiDtos.ResumptionConfirmResponse;
import com.example.starter.slope.dto.ApiDtos.ResumptionEvidenceItem;
import com.example.starter.slope.dto.ApiDtos.StrategyInfo;
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

    /** 上报/更正合成雨量小时数据：整点唯一、有限非负、显式更正版本（幂等）。 */
    @Transactional
    public IdempotencyService.Stored submitRainfallHour(long zoneId, RainfallHourRequest request) {
        return idempotencyService.execute("POST /api/zones/{zoneId}/rainfall-hours",
                request.requestId(), new ZoneScopedRequest<>(zoneId, request),
                () -> doSubmitRainfallHour(zoneId, request));
    }

    /** 发布阈值策略版本：0&lt;黄&lt;红、0&lt;系数&lt;=1、生效不早于提交（幂等）。 */
    @Transactional
    public IdempotencyService.Stored publishStrategy(long zoneId, PublishStrategyRequest request) {
        return idempotencyService.execute("POST /api/zones/{zoneId}/strategies",
                request.requestId(), new ZoneScopedRequest<>(zoneId, request),
                () -> doPublishStrategy(zoneId, request));
    }

    /** 有效阈值查询：指定时刻生效的策略版本与雨量折减后的阈值。 */
    @Transactional(readOnly = true)
    public EffectiveThresholdResponse effectiveThresholds(long zoneId, LocalDateTime at) {
        ZoneRow zone = repository.findZoneById(zoneId).orElseThrow(
                () -> ApiException.notFound("ZONE_NOT_FOUND", "测区不存在: " + zoneId));
        StrategyRow strategy = strategyEffectiveAt(repository.findStrategiesByZone(zoneId), at);
        if (strategy == null) {
            return new EffectiveThresholdResponse(zoneId, at, null, zone.warnHourlyRate(),
                    zone.alarmDailyRate(), zone.safetyHourlyRate(), null, true, false);
        }
        // 同一事务快照：雨量与策略版本一致
        Double rainfall = rainfall24hOrNull(zoneId, at);
        if (rainfall == null) {
            return new EffectiveThresholdResponse(zoneId, at, strategy.version(),
                    null, null, null, null, false, false);
        }
        boolean reduced = rainfall >= strategy.rainfall24hThreshold();
        double factor = reduced ? strategy.reductionFactor() : 1.0;
        return new EffectiveThresholdResponse(zoneId, at, strategy.version(),
                strategy.yellowHourlyRate() * factor, strategy.redDailyRate() * factor,
                zone.safetyHourlyRate() * factor, rainfall, true, reduced);
    }

    /** 策略版本列表。 */
    @Transactional(readOnly = true)
    public List<StrategyInfo> listStrategies(long zoneId) {
        repository.findZoneById(zoneId).orElseThrow(
                () -> ApiException.notFound("ZONE_NOT_FOUND", "测区不存在: " + zoneId));
        return repository.findStrategiesByZone(zoneId).stream()
                .map(s -> new StrategyInfo(s.id(), s.zoneId(), s.version(), s.yellowHourlyRate(),
                        s.redDailyRate(), s.rainfall24hThreshold(), s.reductionFactor(),
                        s.submittedAt(), s.effectiveFrom()))
                .toList();
    }

    /** 复工证据查询：申请与批准阶段冻结的安全评价证据。 */
    @Transactional(readOnly = true)
    public List<ResumptionEvidenceItem> resumptionEvidence(long chainId) {
        repository.findChainById(chainId).orElseThrow(
                () -> ApiException.notFound("CHAIN_NOT_FOUND", "处置链不存在: " + chainId));
        return repository.findEvidenceByChain(chainId).stream()
                .map(e -> new ResumptionEvidenceItem(e.id(), e.assessmentId(), e.chainId(), e.phase(),
                        e.evaluatedAt(), e.windowStart(), e.readingCount(), e.strategyVersion(),
                        e.rainfall24hMm(), e.reductionApplied(), e.result(), e.detail()))
                .toList();
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
        // 连续24小时安全计时按每个时刻实际有效的阈值判定；策略变严后自生效时刻重新累计。
        SafetyWindowEvaluation evaluation = evaluateSafetyWindow(zone, chain, request.appliedAt());
        long assessmentId = repository.insertAssessment(chainId, chain.zoneId(), request.appliedAt());
        // 冻结申请时刻的评价证据，供复工证据查询与批准复核。
        repository.insertEvidence(assessmentId, chainId, chain.zoneId(), "APPLY",
                evaluation.windowEnd(), evaluation.windowStart(), evaluation.readingCount(),
                evaluation.strategyVersion(), evaluation.rainfall24hMm(), evaluation.reductionApplied(),
                "SAFE", evaluation.detailJson());
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
        repository.updateAssessmentConfirmation(assessment.id(), siteCheck, monitoringReview,
                assessmentStatus, approved ? request.confirmedAt() : null);
        String chainStatus = chain.status();
        String zoneStatus = repository.findZoneById(chain.zoneId()).orElseThrow().status();
        if (approved) {
            // 批准前按当前数据复核安全窗口并冻结一致证据：雨量更正或策略发布与本确认并发时，
            // 测区行锁保证串行，复核要么包含要么不包含对方变更，不会读到中间态。
            ZoneRow zone = repository.findZoneById(chain.zoneId()).orElseThrow();
            SafetyWindowEvaluation evaluation = evaluateSafetyWindow(zone, chain, assessment.appliedAt());
            repository.insertEvidence(assessment.id(), chainId, chain.zoneId(), "APPROVE",
                    evaluation.windowEnd(), evaluation.windowStart(), evaluation.readingCount(),
                    evaluation.strategyVersion(), evaluation.rainfall24hMm(),
                    evaluation.reductionApplied(), "SAFE", evaluation.detailJson());
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

    private IdempotencyService.Stored doSubmitRainfallHour(long zoneId, RainfallHourRequest request) {
        // 测区行锁：与复工确认、策略发布串行，保证同一快照读取雨量与策略版本。
        repository.findZoneByIdForUpdate(zoneId).orElseThrow(
                () -> ApiException.notFound("ZONE_NOT_FOUND", "测区不存在: " + zoneId));
        LocalDateTime hourStart = request.hourStart();
        if (!hourStart.equals(hourStart.truncatedTo(ChronoUnit.HOURS))) {
            throw ApiException.badRequest("HOUR_NOT_ALIGNED", "小时起点须为UTC整点: " + hourStart);
        }
        Double rainfall = request.rainfallMm();
        if (!Double.isFinite(rainfall) || rainfall < 0) {
            throw ApiException.badRequest("RAINFALL_INVALID", "雨量须为有限非负数: " + rainfall);
        }
        int expected = request.expectedVersion();
        var existing = repository.findRainfallHourForUpdate(zoneId, hourStart);
        if (expected == 0) {
            if (existing.isPresent()) {
                RainfallRow row = existing.get();
                if (Double.compare(row.rainfallMm(), rainfall) == 0) {
                    // 同测区同小时同值重放：返回已有记录，不变更版本。
                    return json(200, new RainfallHourResponse(row.id(), zoneId, row.hourStart(),
                            row.rainfallMm(), row.version(), true));
                }
                throw ApiException.conflict("RAINFALL_VALUE_MISMATCH",
                        "同小时已存在异值雨量，须携带expectedVersion=" + row.version()
                                + "显式更正: " + hourStart);
            }
            long id = repository.insertRainfallHour(zoneId, hourStart, rainfall, 1);
            return json(201, new RainfallHourResponse(id, zoneId, hourStart, rainfall, 1, false));
        }
        // 显式更正：版本须匹配，成功后版本加1。
        RainfallRow row = existing.orElseThrow(() -> ApiException.notFound("RAINFALL_HOUR_NOT_FOUND",
                "雨量小时记录不存在，无法更正: " + hourStart));
        if (row.version() != expected) {
            throw ApiException.conflict("VERSION_MISMATCH",
                    "雨量记录版本不一致: expected=" + expected + ", current=" + row.version());
        }
        repository.updateRainfallHour(row.id(), rainfall, expected + 1);
        return json(200, new RainfallHourResponse(row.id(), zoneId, hourStart, rainfall,
                expected + 1, false));
    }

    private IdempotencyService.Stored doPublishStrategy(long zoneId, PublishStrategyRequest request) {
        // 测区行锁：与复工确认、雨量更正串行，保证同一快照读取雨量与策略版本。
        repository.findZoneByIdForUpdate(zoneId).orElseThrow(
                () -> ApiException.notFound("ZONE_NOT_FOUND", "测区不存在: " + zoneId));
        Double yellow = request.yellowHourlyRate();
        Double red = request.redDailyRate();
        Double threshold = request.rainfall24hThreshold();
        Double factor = request.reductionFactor();
        if (!isFinitePositive(yellow) || !isFinitePositive(red) || !(yellow < red)) {
            throw ApiException.badRequest("THRESHOLD_ORDER_INVALID",
                    "基础速率阈值须满足0<黄<红: yellow=" + yellow + ", red=" + red);
        }
        if (threshold == null || !Double.isFinite(threshold) || threshold < 0) {
            throw ApiException.badRequest("RAINFALL_THRESHOLD_INVALID",
                    "24小时雨量门槛须为有限非负数: " + threshold);
        }
        if (factor == null || !Double.isFinite(factor) || factor <= 0 || factor > 1) {
            throw ApiException.badRequest("REDUCTION_FACTOR_INVALID",
                    "折减系数须满足0<系数<=1: " + factor);
        }
        if (request.effectiveFrom().isBefore(request.submittedAt())) {
            throw ApiException.unprocessable("EFFECTIVE_BEFORE_SUBMISSION",
                    "策略生效时刻不得早于提交时刻: effectiveFrom=" + request.effectiveFrom()
                            + ", submittedAt=" + request.submittedAt());
        }
        int latest = repository.findLatestStrategyVersion(zoneId).orElse(0);
        if (request.expectedVersion() != latest) {
            throw ApiException.conflict("VERSION_MISMATCH",
                    "策略版本不一致: expected=" + request.expectedVersion() + ", current=" + latest);
        }
        int version = latest + 1;
        long id = repository.insertStrategy(zoneId, version, yellow, red, threshold, factor,
                request.submittedAt(), request.effectiveFrom());
        return json(201, new PublishStrategyResponse(id, zoneId, version, request.effectiveFrom()));
    }

    /**
     * 复工安全连续24小时评价：窗口内每个读数按其时刻实际有效的安全阈值判定
     * （基础安全线×雨量折减）；策略变严后不沿用旧安全计时，自变严版本生效时刻重新累计。
     * 雨量与策略版本取自同一事务快照；前24个完整小时雨量任一缺点即数据不足（422），不得当零。
     */
    private SafetyWindowEvaluation evaluateSafetyWindow(ZoneRow zone, ChainRow chain,
                                                        LocalDateTime windowEnd) {
        List<StrategyRow> strategies = repository.findStrategiesByZone(zone.id());
        LocalDateTime windowStart = chain.triggeredAt();
        for (StrategyRow v : strategies) {
            if (v.effectiveFrom().isAfter(windowStart) && !v.effectiveFrom().isAfter(windowEnd)
                    && isStricter(v, strategyEffectiveAt(strategies, v.effectiveFrom().minusNanos(1)),
                            zone)) {
                windowStart = v.effectiveFrom();
            }
        }
        // 恰满24小时允许：仅严格早于窗口起点后24小时才拒绝。
        LocalDateTime earliest = windowStart.plusHours(RECOVERY_WINDOW_HOURS);
        if (windowEnd.isBefore(earliest)) {
            throw ApiException.unprocessable("RECOVERY_WINDOW_TOO_SHORT",
                    "安全连续计时未满24小时，无法申请复工: earliest=" + earliest);
        }
        final LocalDateTime start = windowStart;
        List<ReadingRow> window = repository.findReadingsByZone(zone.id()).stream()
                .filter(row -> row.measuredAt().isAfter(start)
                        && !row.measuredAt().isAfter(windowEnd))
                .toList();
        if (window.isEmpty()) {
            throw ApiException.unprocessable("NO_MONITORING_DATA",
                    "预警后窗口内无监测数据，无法证明速率回落: " + chain.id());
        }
        List<ReadingJudgment> judgments = new ArrayList<>();
        for (ReadingRow row : window) {
            StrategyRow strategy = strategyEffectiveAt(strategies, row.measuredAt());
            double effectiveSafety = zone.safetyHourlyRate();
            Integer strategyVersion = null;
            Double rainfall = null;
            boolean reduced = false;
            if (strategy != null) {
                rainfall = rainfall24hOrThrow(zone.id(), row.measuredAt());
                strategyVersion = strategy.version();
                // 雨量恰达门槛即启用折减。
                if (rainfall >= strategy.rainfall24hThreshold()) {
                    effectiveSafety *= strategy.reductionFactor();
                    reduced = true;
                }
            }
            // 安全线语义不变：小时速率须严格低于有效安全阈值，恰等判不安全。
            if (row.hourlyRate() == null || row.hourlyRate() >= effectiveSafety) {
                throw ApiException.unprocessable("RATE_ABOVE_SAFETY_LINE",
                        "窗口内存在速率未回落至有效安全线以下的读数: measuredAt=" + row.measuredAt());
            }
            judgments.add(new ReadingJudgment(row.measuredAt(), row.hourlyRate(), effectiveSafety,
                    strategyVersion, rainfall, reduced));
        }
        // 评价时刻快照：雨量与策略版本一致。
        StrategyRow atEnd = strategyEffectiveAt(strategies, windowEnd);
        Integer endVersion = null;
        Double endRainfall = null;
        boolean endReduced = false;
        if (atEnd != null) {
            endRainfall = rainfall24hOrThrow(zone.id(), windowEnd);
            endVersion = atEnd.version();
            endReduced = endRainfall >= atEnd.rainfall24hThreshold();
        }
        return new SafetyWindowEvaluation(windowStart, windowEnd, window.size(),
                endVersion, endRainfall, endReduced, toJson(judgments));
    }

    /** 指定时刻生效的策略版本：effectiveFrom不晚于该时刻的最新版本；无则返回null。 */
    private StrategyRow strategyEffectiveAt(List<StrategyRow> strategies, LocalDateTime at) {
        StrategyRow best = null;
        for (StrategyRow s : strategies) {
            if (s.effectiveFrom().isAfter(at)) {
                continue;
            }
            if (best == null || s.effectiveFrom().isAfter(best.effectiveFrom())
                    || (s.effectiveFrom().equals(best.effectiveFrom()) && s.version() > best.version())) {
                best = s;
            }
        }
        return best;
    }

    /**
     * 策略是否变严：相对此前生效版本（无策略时相对测区静态阈值，折减系数视为1、
     * 雨量门槛视为无穷大），任一基础阈值更低、折减系数更小或雨量门槛更低即为变严。
     */
    private boolean isStricter(StrategyRow candidate, StrategyRow previous, ZoneRow zone) {
        double prevYellow = previous == null ? zone.warnHourlyRate() : previous.yellowHourlyRate();
        double prevRed = previous == null ? zone.alarmDailyRate() : previous.redDailyRate();
        double prevFactor = previous == null ? 1.0 : previous.reductionFactor();
        double prevThreshold = previous == null ? Double.POSITIVE_INFINITY
                : previous.rainfall24hThreshold();
        return candidate.yellowHourlyRate() < prevYellow
                || candidate.redDailyRate() < prevRed
                || candidate.reductionFactor() < prevFactor
                || candidate.rainfall24hThreshold() < prevThreshold;
    }

    /** 前24个完整小时累计雨量；任一缺点返回null（缺点不得当零）。 */
    private Double rainfall24hOrNull(long zoneId, LocalDateTime moment) {
        LocalDateTime base = moment.truncatedTo(ChronoUnit.HOURS);
        LocalDateTime from = base.minusHours(24);
        List<RainfallRow> rows = repository.findRainfallBetween(zoneId, from, base);
        Map<LocalDateTime, Double> byHour = new HashMap<>();
        for (RainfallRow row : rows) {
            byHour.put(row.hourStart(), row.rainfallMm());
        }
        double total = 0;
        for (int i = 0; i < 24; i++) {
            Double value = byHour.get(from.plusHours(i));
            if (value == null) {
                return null;
            }
            total += value;
        }
        return total;
    }

    /** 前24个完整小时累计雨量；任一缺点抛422数据不足并阻断复工批准。 */
    private double rainfall24hOrThrow(long zoneId, LocalDateTime moment) {
        Double total = rainfall24hOrNull(zoneId, moment);
        if (total == null) {
            throw ApiException.unprocessable("INSUFFICIENT_RAINFALL_DATA",
                    "前24个完整小时雨量数据不足，不得按零处理: moment=" + moment);
        }
        return total;
    }

    private static boolean isFinitePositive(Double value) {
        return value != null && Double.isFinite(value) && value > 0;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("证据明细序列化失败", e);
        }
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

    /** 幂等指纹携带路径参数，避免不同测区/链的同requestId互相重放。 */
    record ZoneScopedRequest<T>(long zoneId, T body) {
    }

    /** 幂等指纹携带路径参数，避免不同测区/链的同requestId互相重放。 */
    record ChainScopedRequest<T>(long chainId, T body) {
    }

    /** 安全窗口内逐读数判定明细（冻结证据）。 */
    record ReadingJudgment(LocalDateTime measuredAt, Double hourlyRate, double effectiveSafety,
                           Integer strategyVersion, Double rainfall24hMm, boolean reductionApplied) {
    }

    /** 安全窗口评价结果：窗口范围与评价时刻的策略/雨量快照。 */
    record SafetyWindowEvaluation(LocalDateTime windowStart, LocalDateTime windowEnd,
                                  int readingCount, Integer strategyVersion, Double rainfall24hMm,
                                  boolean reductionApplied, String detailJson) {
    }
}
