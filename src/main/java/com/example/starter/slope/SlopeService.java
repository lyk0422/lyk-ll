package com.example.starter.slope;

import com.example.starter.slope.SlopeRepository.AssessmentRow;
import com.example.starter.slope.SlopeRepository.ChainRow;
import com.example.starter.slope.SlopeRepository.EvacuationRow;
import com.example.starter.slope.SlopeRepository.EvidenceRow;
import com.example.starter.slope.SlopeRepository.LateEventRow;
import com.example.starter.slope.SlopeRepository.PolicyRow;
import com.example.starter.slope.SlopeRepository.RainfallRow;
import com.example.starter.slope.SlopeRepository.ReadingRow;
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
import com.example.starter.slope.dto.ApiDtos.PolicyPublishRequest;
import com.example.starter.slope.dto.ApiDtos.PolicyPublishResponse;
import com.example.starter.slope.dto.ApiDtos.PolicyVersionInfo;
import com.example.starter.slope.dto.ApiDtos.RainfallCorrectionInfo;
import com.example.starter.slope.dto.ApiDtos.RainfallHourInfo;
import com.example.starter.slope.dto.ApiDtos.RainfallHourResponse;
import com.example.starter.slope.dto.ApiDtos.RainfallReportRequest;
import com.example.starter.slope.dto.ApiDtos.RateCurveResponse;
import com.example.starter.slope.dto.ApiDtos.RatePoint;
import com.example.starter.slope.dto.ApiDtos.ReadingResponse;
import com.example.starter.slope.dto.ApiDtos.ResumptionApplyRequest;
import com.example.starter.slope.dto.ApiDtos.ResumptionApplyResponse;
import com.example.starter.slope.dto.ApiDtos.ResumptionConfirmRequest;
import com.example.starter.slope.dto.ApiDtos.ResumptionConfirmResponse;
import com.example.starter.slope.dto.ApiDtos.ResumptionEvidenceInfo;
import com.example.starter.slope.dto.ApiDtos.ZoneResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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

    /** 上报/更正雨量小时数据：同小时同值重放、异值须显式更正版本（幂等）。 */
    @Transactional
    public IdempotencyService.Stored reportRainfall(long zoneId, RainfallReportRequest request) {
        return idempotencyService.execute("POST /api/zones/{zoneId}/rainfall-hours",
                request.requestId(), new ZoneScopedRequest<>(zoneId, request),
                () -> doReportRainfall(zoneId, request));
    }

    /** 发布阈值策略版本：expectedVersion乐观锁，生效时刻不早于提交时刻（幂等）。 */
    @Transactional
    public IdempotencyService.Stored publishPolicy(long zoneId, PolicyPublishRequest request) {
        return idempotencyService.execute("POST /api/zones/{zoneId}/threshold-policies",
                request.requestId(), new ZoneScopedRequest<>(zoneId, request),
                () -> doPublishPolicy(zoneId, request));
    }

    /** 策略版本列表查询。 */
    @Transactional(readOnly = true)
    public List<PolicyVersionInfo> policyVersions(long zoneId) {
        repository.findZoneById(zoneId).orElseThrow(
                () -> ApiException.notFound("ZONE_NOT_FOUND", "测区不存在: " + zoneId));
        return repository.findPoliciesByZone(zoneId).stream()
                .map(p -> new PolicyVersionInfo(p.version(), p.yellowHourlyRate(), p.redHourlyRate(),
                        p.rain24hThreshold(), p.reductionFactor(), p.effectiveAt(), p.submittedAt()))
                .toList();
    }

    /** 雨量小时当前值查询；from/to 须同时提供或同时缺省（缺省返回全部小时）。 */
    @Transactional(readOnly = true)
    public List<RainfallHourInfo> rainfallHours(long zoneId, LocalDateTime from, LocalDateTime to) {
        repository.findZoneById(zoneId).orElseThrow(
                () -> ApiException.notFound("ZONE_NOT_FOUND", "测区不存在: " + zoneId));
        if ((from == null) != (to == null)) {
            throw ApiException.badRequest("INVALID_RANGE", "from与to须同时提供或同时缺省");
        }
        List<RainfallRow> rows = from != null
                ? repository.findLatestRainfallInRange(zoneId, from, to)
                : repository.findLatestRainfallByZone(zoneId);
        return rows.stream()
                .map(r -> new RainfallHourInfo(r.hourStart(), r.rainfallMm(), r.version(), r.correctedAt()))
                .toList();
    }

    /** 雨量小时更正历史查询（含全部版本）；hour 缺省返回该测区全部历史。 */
    @Transactional(readOnly = true)
    public List<RainfallCorrectionInfo> rainfallCorrections(long zoneId, LocalDateTime hour) {
        repository.findZoneById(zoneId).orElseThrow(
                () -> ApiException.notFound("ZONE_NOT_FOUND", "测区不存在: " + zoneId));
        return repository.findRainfallHistory(zoneId, hour).stream()
                .map(r -> new RainfallCorrectionInfo(r.hourStart(), r.rainfallMm(), r.version(),
                        r.correctedAt()))
                .toList();
    }

    /** 指定时刻的有效阈值查询：同一事务快照内读取策略版本与雨量。 */
    @Transactional(readOnly = true)
    public EffectiveThresholdResponse effectiveThreshold(long zoneId, LocalDateTime at) {
        ZoneRow zone = repository.findZoneById(zoneId).orElseThrow(
                () -> ApiException.notFound("ZONE_NOT_FOUND", "测区不存在: " + zoneId));
        EffectiveThreshold eff = effectiveThresholdAt(zone, repository.findPoliciesByZone(zoneId), at);
        String status = !eff.policyBased() ? "NO_POLICY"
                : eff.insufficient() ? "INSUFFICIENT_DATA" : "OK";
        return new EffectiveThresholdResponse(zoneId, at, status, eff.policyVersion(),
                eff.rain24hMm(), eff.reductionApplied(), eff.yellow(), eff.red(), eff.missingHours());
    }

    /** 复工安全门禁冻结证据查询。 */
    @Transactional(readOnly = true)
    public List<ResumptionEvidenceInfo> resumptionEvidence(long chainId) {
        repository.findChainById(chainId).orElseThrow(
                () -> ApiException.notFound("CHAIN_NOT_FOUND", "处置链不存在: " + chainId));
        return repository.findEvidenceByChain(chainId).stream()
                .map(e -> new ResumptionEvidenceInfo(e.id(), e.assessmentId(), e.chainId(), e.phase(),
                        e.windowStart(), e.windowEnd(), e.result(), e.detail(), e.createdAt()))
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
     * 预警分级：无生效策略时保持旧逻辑（日速率严格超过报警值触发红色、小时速率严格
     * 超过预警值触发黄色）；有生效策略时按策略版本与此前24个完整小时雨量计算有效
     * 阈值，黄/红均按小时速率判定，恰等阈值判正常；雨量缺点不当零，返回数据不足。
     * 红色链执行中（已停产）的后续读数属于回落监测，不再重复受理预警；
     * 黄色链执行中再触发黄色视为并发重复预警，拒绝受理（409）。
     */
    private String evaluateAlarm(ZoneRow zone, ReadingRow reading) {
        EffectiveThreshold eff = effectiveThresholdAt(zone, repository.findPoliciesByZone(zone.id()),
                reading.measuredAt());
        if (eff.insufficient()) {
            // 雨量数据不足：读数仅入库，本次不评价预警；复工批准前复评门禁同样会被阻断。
            return "RAINFALL_DATA_INSUFFICIENT";
        }
        boolean red;
        boolean yellow;
        if (eff.policyBased()) {
            red = reading.hourlyRate() != null && reading.hourlyRate() > eff.red();
            yellow = reading.hourlyRate() != null && reading.hourlyRate() > eff.yellow();
        } else {
            red = reading.dailyRate() != null && reading.dailyRate() > zone.alarmDailyRate();
            yellow = reading.hourlyRate() != null && reading.hourlyRate() > zone.warnHourlyRate();
        }
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
        // 复工安全门禁：连续24小时按每个读数时刻实际有效的阈值判定；策略变严后自其
        // 生效时刻重新累计；雨量缺点返回数据不足并阻断申请，缺点不当零。
        GateResult gate = evaluateSafetyGate(zone, chain, request.appliedAt());
        if (!gate.passed()) {
            throw ApiException.unprocessable(gate.errorCode(), gate.errorMessage());
        }
        long assessmentId = repository.insertAssessment(chainId, chain.zoneId(), request.appliedAt());
        freezeEvidence(assessmentId, chain, gate, "APPLY");
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
        if (approved) {
            // 批准前复评安全门禁：本方法持有测区行锁，雨量更正与策略发布同样先取测区行锁，
            // 三者串行，故批准看到的是同一事务内的一致快照；门禁不再满足则拒绝批准并
            // 回滚本次确认（修正数据后可重试），批准通过则冻结一致证据。
            ZoneRow zone = repository.findZoneById(chain.zoneId()).orElseThrow();
            GateResult gate = evaluateSafetyGate(zone, chain, assessment.appliedAt());
            if (!gate.passed()) {
                throw ApiException.unprocessable(gate.errorCode(), gate.errorMessage());
            }
            freezeEvidence(assessment.id(), chain, gate, "APPROVE");
        }
        String assessmentStatus = approved ? ASSESSMENT_APPROVED : ASSESSMENT_APPLIED;
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

    private IdempotencyService.Stored doReportRainfall(long zoneId, RainfallReportRequest request) {
        repository.findZoneByIdForUpdate(zoneId).orElseThrow(
                () -> ApiException.notFound("ZONE_NOT_FOUND", "测区不存在: " + zoneId));
        LocalDateTime hourStart = request.hourStart();
        // UTC整点约束：Asia/Shanghai为UTC+8，UTC整点同为本地整点。
        if (hourStart.getMinute() != 0 || hourStart.getSecond() != 0 || hourStart.getNano() != 0) {
            throw ApiException.badRequest("INVALID_HOUR_START",
                    "雨量小时起点须为UTC整点: " + hourStart);
        }
        double value = request.rainfallMm();
        if (!Double.isFinite(value) || value < 0) {
            throw ApiException.badRequest("INVALID_RAINFALL_VALUE", "雨量须为有限非负数: " + value);
        }
        var current = repository.findLatestRainfall(zoneId, hourStart);
        if (request.expectedVersion() == null) {
            // 首次上报：同测区同小时同值重放直接返回现状；异值须显式更正版本。
            if (current.isEmpty()) {
                repository.insertRainfall(zoneId, hourStart, value, 1, request.reportedAt());
                return json(201, new RainfallHourResponse(zoneId, hourStart, value, 1, false));
            }
            RainfallRow existing = current.get();
            if (Double.compare(existing.rainfallMm(), value) == 0) {
                return json(200, new RainfallHourResponse(zoneId, hourStart, value,
                        existing.version(), true));
            }
            throw ApiException.conflict("RAINFALL_CORRECTION_REQUIRED",
                    "该小时已存在异值雨量，须携带expectedVersion显式更正: hour=" + hourStart);
        }
        RainfallRow existing = current.orElseThrow(
                () -> ApiException.notFound("RAINFALL_HOUR_NOT_FOUND",
                        "该小时尚无雨量数据，无法更正: " + hourStart));
        if (request.expectedVersion() != existing.version()) {
            throw ApiException.conflict("VERSION_CONFLICT",
                    "雨量版本冲突: expected=" + request.expectedVersion()
                            + ", current=" + existing.version());
        }
        if (Double.compare(existing.rainfallMm(), value) == 0) {
            // 同值更正视为重放，不产生新版本。
            return json(200, new RainfallHourResponse(zoneId, hourStart, value,
                    existing.version(), true));
        }
        int newVersion = existing.version() + 1;
        repository.insertRainfall(zoneId, hourStart, value, newVersion, request.reportedAt());
        return json(201, new RainfallHourResponse(zoneId, hourStart, value, newVersion, false));
    }

    private IdempotencyService.Stored doPublishPolicy(long zoneId, PolicyPublishRequest request) {
        repository.findZoneByIdForUpdate(zoneId).orElseThrow(
                () -> ApiException.notFound("ZONE_NOT_FOUND", "测区不存在: " + zoneId));
        double yellow = request.yellowHourlyRate();
        double red = request.redHourlyRate();
        double threshold = request.rain24hThreshold();
        double factor = request.reductionFactor();
        if (!Double.isFinite(yellow) || yellow <= 0 || !Double.isFinite(red) || red <= yellow) {
            throw ApiException.badRequest("INVALID_POLICY_THRESHOLDS",
                    "基础速率阈值须满足0<黄<红: yellow=" + yellow + ", red=" + red);
        }
        if (!Double.isFinite(threshold) || threshold < 0) {
            throw ApiException.badRequest("INVALID_POLICY_THRESHOLDS",
                    "24小时雨量门槛须为有限非负数: " + threshold);
        }
        if (!Double.isFinite(factor) || factor <= 0 || factor > 1) {
            throw ApiException.badRequest("INVALID_POLICY_THRESHOLDS",
                    "折减系数须满足0<系数<=1: " + factor);
        }
        if (request.effectiveAt().isBefore(request.submittedAt())) {
            throw ApiException.badRequest("EFFECTIVE_BEFORE_SUBMISSION",
                    "策略生效时刻不得早于提交时刻: effectiveAt=" + request.effectiveAt()
                            + ", submittedAt=" + request.submittedAt());
        }
        long currentVersion = repository.findLatestPolicyByZone(zoneId)
                .map(PolicyRow::version).orElse(0);
        if (request.expectedVersion() != currentVersion) {
            throw ApiException.conflict("VERSION_CONFLICT",
                    "策略版本冲突: expected=" + request.expectedVersion()
                            + ", current=" + currentVersion);
        }
        long newVersion = currentVersion + 1;
        // 仅追加新版本行：只影响生效时刻（含）之后的读数评价，不重算已归档告警或撤人证据，
        // 变宽也不撤销既有红色处置链。
        repository.insertPolicy(zoneId, (int) newVersion, yellow, red, threshold, factor,
                request.effectiveAt(), request.submittedAt());
        return json(201, new PolicyPublishResponse(zoneId, newVersion, request.effectiveAt()));
    }

    /**
     * 计算某时刻的有效阈值：取生效时刻不晚于该时刻的最新策略版本（同一事务快照内读取），
     * 并汇总此前24个完整小时雨量；任一缺点返回数据不足（缺点不当零），雨量达到（含恰等）
     * 门槛启用折减。无生效策略时回退测区静态阈值。
     */
    private EffectiveThreshold effectiveThresholdAt(ZoneRow zone, List<PolicyRow> policies,
                                                    LocalDateTime at) {
        PolicyRow policy = null;
        for (PolicyRow candidate : policies) {
            if (!candidate.effectiveAt().isAfter(at) && (policy == null
                    || candidate.effectiveAt().isAfter(policy.effectiveAt())
                    || (candidate.effectiveAt().equals(policy.effectiveAt())
                            && candidate.version() > policy.version()))) {
                policy = candidate;
            }
        }
        if (policy == null) {
            return new EffectiveThreshold(null, null, null, zone.warnHourlyRate(),
                    zone.alarmDailyRate(), false, List.of());
        }
        LocalDateTime windowEndHour = at.withMinute(0).withSecond(0).withNano(0);
        LocalDateTime windowStartHour = windowEndHour.minusHours(24);
        Map<LocalDateTime, Double> byHour = new HashMap<>();
        for (RainfallRow row : repository.findLatestRainfallInRange(zone.id(), windowStartHour,
                windowEndHour)) {
            byHour.put(row.hourStart(), row.rainfallMm());
        }
        List<LocalDateTime> missing = new ArrayList<>();
        double rain24 = 0.0;
        for (int i = 0; i < 24; i++) {
            LocalDateTime hour = windowStartHour.plusHours(i);
            Double value = byHour.get(hour);
            if (value == null) {
                missing.add(hour);
            } else {
                rain24 += value;
            }
        }
        if (!missing.isEmpty()) {
            return new EffectiveThreshold((long) policy.version(), null, null, null, null, true,
                    missing);
        }
        boolean reduced = rain24 >= policy.rain24hThreshold();
        double factor = reduced ? policy.reductionFactor() : 1.0;
        return new EffectiveThreshold((long) policy.version(), rain24, reduced,
                policy.yellowHourlyRate() * factor, policy.redHourlyRate() * factor, false, List.of());
    }

    /**
     * 复工安全门禁：连续24小时按每个读数时刻实际有效的阈值判定（有策略用有效黄阈值，
     * 无策略用测区静态安全线）；策略变严（黄/红基础阈值或折减系数较上一版本更低）后
     * 不沿用旧安全计时，自最迟变严版本生效时刻重新累计；雨量缺点返回数据不足。
     */
    private GateResult evaluateSafetyGate(ZoneRow zone, ChainRow chain, LocalDateTime windowEnd) {
        LocalDateTime windowStart = chain.triggeredAt();
        List<PolicyRow> policies = repository.findPoliciesByZone(zone.id());
        LocalDateTime latestStricter = null;
        PolicyRow prev = null;
        for (PolicyRow p : policies) {
            if (prev != null && isStricter(p, prev) && !p.effectiveAt().isAfter(windowEnd)
                    && (latestStricter == null || p.effectiveAt().isAfter(latestStricter))) {
                latestStricter = p.effectiveAt();
            }
            prev = p;
        }
        if (latestStricter != null && latestStricter.isAfter(windowStart)) {
            windowStart = latestStricter;
        }
        // 恰满24小时允许：仅严格早于窗口起点后24小时才拒绝。
        LocalDateTime earliest = windowStart.plusHours(RECOVERY_WINDOW_HOURS);
        if (windowEnd.isBefore(earliest)) {
            return new GateResult("RECOVERY_WINDOW_TOO_SHORT",
                    "红色预警后连续安全时长不足24小时（策略变严后自生效时刻重新累计）: earliest=" + earliest,
                    windowStart, windowEnd, List.of());
        }
        final LocalDateTime start = windowStart;
        List<ReadingRow> window = repository.findReadingsByZone(zone.id()).stream()
                .filter(row -> row.measuredAt().isAfter(start) && !row.measuredAt().isAfter(windowEnd))
                .toList();
        if (window.isEmpty()) {
            return new GateResult("NO_MONITORING_DATA",
                    "预警后窗口内无监测数据，无法证明速率回落: " + chain.id(),
                    windowStart, windowEnd, List.of());
        }
        List<GateReading> details = new ArrayList<>();
        for (ReadingRow row : window) {
            EffectiveThreshold eff = effectiveThresholdAt(zone, policies, row.measuredAt());
            if (eff.insufficient()) {
                return new GateResult("RAINFALL_DATA_INSUFFICIENT",
                        "评价时刻此前24个完整小时雨量数据不足，缺点不当零: measuredAt=" + row.measuredAt()
                                + ", missingHours=" + eff.missingHours().size(),
                        windowStart, windowEnd, details);
            }
            double threshold = eff.policyBased() ? eff.yellow() : zone.safetyHourlyRate();
            boolean pass = row.hourlyRate() != null && row.hourlyRate() < threshold;
            details.add(new GateReading(row.measuredAt(), row.hourlyRate(), eff.policyVersion(),
                    eff.rain24hMm(), eff.reductionApplied(), threshold, pass));
            if (!pass) {
                return new GateResult("RATE_ABOVE_SAFETY_LINE",
                        "窗口内存在速率未回落至有效安全阈值以下的读数: measuredAt=" + row.measuredAt(),
                        windowStart, windowEnd, details);
            }
        }
        return new GateResult(null, null, windowStart, windowEnd, details);
    }

    /** 策略变严判定：黄/红基础阈值或折减系数任一较上一版本更低。 */
    private static boolean isStricter(PolicyRow next, PolicyRow prev) {
        return next.yellowHourlyRate() < prev.yellowHourlyRate()
                || next.redHourlyRate() < prev.redHourlyRate()
                || next.reductionFactor() < prev.reductionFactor();
    }

    /** 冻结复工门禁证据：窗口与逐读数判定明细（含策略版本、24小时雨量、有效阈值）。 */
    private void freezeEvidence(long assessmentId, ChainRow chain, GateResult gate, String phase) {
        try {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("windowStart", gate.windowStart());
            detail.put("windowEnd", gate.windowEnd());
            detail.put("readings", gate.readings());
            repository.insertEvidence(assessmentId, chain.id(), chain.zoneId(), phase,
                    gate.windowStart(), gate.windowEnd(), "PASS",
                    objectMapper.writeValueAsString(detail));
        } catch (Exception e) {
            throw new IllegalStateException("复工证据序列化失败", e);
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

    /** 某时刻有效阈值判定结果；policyVersion为NULL表示无生效策略（回退测区静态阈值）。 */
    private record EffectiveThreshold(Long policyVersion, Double rain24hMm, Boolean reductionApplied,
                                      Double yellow, Double red, boolean insufficient,
                                      List<LocalDateTime> missingHours) {
        boolean policyBased() {
            return policyVersion != null;
        }
    }

    /** 复工门禁逐读数判定明细（冻结进证据）。 */
    private record GateReading(LocalDateTime measuredAt, Double hourlyRate, Long policyVersion,
                               Double rain24hMm, Boolean reductionApplied, Double threshold,
                               boolean pass) {
    }

    /** 复工门禁评价结果；errorCode为NULL表示通过。 */
    private record GateResult(String errorCode, String errorMessage, LocalDateTime windowStart,
                              LocalDateTime windowEnd, List<GateReading> readings) {
        boolean passed() {
            return errorCode == null;
        }
    }
}
