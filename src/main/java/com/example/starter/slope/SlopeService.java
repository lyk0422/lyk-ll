package com.example.starter.slope;

import com.example.starter.slope.SlopeRepository.AssessmentRow;
import com.example.starter.slope.SlopeRepository.ChainRow;
import com.example.starter.slope.SlopeRepository.EvacuationRow;
import com.example.starter.slope.SlopeRepository.EvidenceRow;
import com.example.starter.slope.SlopeRepository.LateEventRow;
import com.example.starter.slope.SlopeRepository.PolicyVersionRow;
import com.example.starter.slope.SlopeRepository.RainfallHourRow;
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
import com.example.starter.slope.dto.ApiDtos.EvidenceConfirmation;
import com.example.starter.slope.dto.ApiDtos.EvidenceMoment;
import com.example.starter.slope.dto.ApiDtos.EvidenceSnapshot;
import com.example.starter.slope.dto.ApiDtos.IngestReadingRequest;
import com.example.starter.slope.dto.ApiDtos.LateEventItem;
import com.example.starter.slope.dto.ApiDtos.PolicyPublishRequest;
import com.example.starter.slope.dto.ApiDtos.PolicyVersionResponse;
import com.example.starter.slope.dto.ApiDtos.RainfallHourItem;
import com.example.starter.slope.dto.ApiDtos.RainfallHourRequest;
import com.example.starter.slope.dto.ApiDtos.RainfallHourResponse;
import com.example.starter.slope.dto.ApiDtos.RateCurveResponse;
import com.example.starter.slope.dto.ApiDtos.RatePoint;
import com.example.starter.slope.dto.ApiDtos.ReadingResponse;
import com.example.starter.slope.dto.ApiDtos.ResumptionApplyRequest;
import com.example.starter.slope.dto.ApiDtos.ResumptionApplyResponse;
import com.example.starter.slope.dto.ApiDtos.ResumptionConfirmRequest;
import com.example.starter.slope.dto.ApiDtos.ResumptionConfirmResponse;
import com.example.starter.slope.dto.ApiDtos.ResumptionEvidenceResponse;
import com.example.starter.slope.dto.ApiDtos.ZoneResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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
    private final RainThresholdResolver thresholdResolver;
    private final ObjectMapper objectMapper;

    public SlopeService(SlopeRepository repository, IdempotencyService idempotencyService,
                        RainThresholdResolver thresholdResolver, ObjectMapper objectMapper) {
        this.repository = repository;
        this.idempotencyService = idempotencyService;
        this.thresholdResolver = thresholdResolver;
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

    // ------------------------------------------------------------------
    // 雨量联动阈值版本
    // ------------------------------------------------------------------

    /**
     * 发布雨量联动阈值策略版本（幂等）。版本从1开始，expectedVersion 携带当前最新版本号
     * （首版传0）；成功变更加1。新策略只影响生效时刻及之后的读数，不重算已归档告警或撤人证据。
     */
    @Transactional
    public IdempotencyService.Stored publishPolicy(long zoneId, PolicyPublishRequest request) {
        return idempotencyService.execute("POST /api/zones/{zoneId}/threshold-policies",
                request.requestId(), new ZoneScopedRequest<>(zoneId, request),
                () -> doPublishPolicy(zoneId, request));
    }

    private IdempotencyService.Stored doPublishPolicy(long zoneId, PolicyPublishRequest request) {
        ZoneRow zone = repository.findZoneByIdForUpdate(zoneId).orElseThrow(
                () -> ApiException.notFound("ZONE_NOT_FOUND", "测区不存在: " + zoneId));
        if (request.expectedVersion() == null || request.expectedVersion() < 0) {
            throw ApiException.badRequest("INVALID_EXPECTED_VERSION",
                    "expectedVersion不能为空或负数: " + request.expectedVersion());
        }
        if (!(request.yellowHourlyRate() > 0 && request.redDailyRate() > request.yellowHourlyRate())) {
            throw ApiException.badRequest("INVALID_RATE_THRESHOLDS",
                    "速率阈值须满足0<黄色小时速率<红色日速率: yellow=" + request.yellowHourlyRate()
                            + ", red=" + request.redDailyRate());
        }
        if (!(request.reductionFactor() > 0 && request.reductionFactor() <= 1)) {
            throw ApiException.badRequest("INVALID_REDUCTION_FACTOR",
                    "折减系数取值须为(0,1]: " + request.reductionFactor());
        }
        if (!(request.rainGateMm() >= 0 && Double.isFinite(request.rainGateMm()))) {
            throw ApiException.badRequest("INVALID_RAIN_GATE",
                    "24小时雨量门槛须为有限非负值: " + request.rainGateMm());
        }
        if (request.effectiveAt().isBefore(request.submittedAt())) {
            throw ApiException.unprocessable("EFFECTIVE_BEFORE_SUBMITTED",
                    "策略生效时刻不得早于提交时刻: submittedAt=" + request.submittedAt()
                            + ", effectiveAt=" + request.effectiveAt());
        }
        PolicyVersionRow latest = repository.findLatestPolicyVersionByZoneForUpdate(zoneId).orElse(null);
        int currentVersion = latest == null ? 0 : latest.version();
        if (request.expectedVersion() != currentVersion) {
            throw ApiException.conflict("POLICY_VERSION_CONFLICT",
                    "策略期望版本与当前版本不一致: expected=" + request.expectedVersion()
                            + ", current=" + currentVersion);
        }
        boolean tightened = latest != null
                && RainThresholdResolver.isTightened(latest, request.yellowHourlyRate(),
                request.redDailyRate(), request.rainGateMm(), request.reductionFactor());
        int newVersion = currentVersion + 1;
        long policyId = repository.insertPolicyVersion(zoneId, newVersion, request.yellowHourlyRate(),
                request.redDailyRate(), request.rainGateMm(), request.reductionFactor(),
                request.submittedAt(), request.effectiveAt(), tightened);
        return json(201, new PolicyVersionResponse(policyId, newVersion, request.yellowHourlyRate(),
                request.redDailyRate(), request.rainGateMm(), request.reductionFactor(),
                request.submittedAt(), request.effectiveAt(), tightened));
    }

    /** 测区策略版本列表（按版本升序）。 */
    @Transactional(readOnly = true)
    public List<PolicyVersionResponse> listPolicies(long zoneId) {
        requireZone(zoneId);
        return repository.findPolicyVersionsByZone(zoneId).stream()
                .map(this::toPolicyResponse)
                .toList();
    }

    /**
     * 查询指定时刻实际生效的阈值；无生效策略时返回测区静态阈值（雨量字段为空、数据充分）。
     * 有生效策略但近24个完整UTC小时存在缺点时 dataSufficient=false 且有效阈值为 null。
     */
    @Transactional(readOnly = true)
    public EffectiveThresholdResponse effectiveThreshold(long zoneId, LocalDateTime at) {
        ZoneRow zone = requireZone(zoneId);
        RainThresholdResolver.Result result = thresholdResolver.resolve(zone, at, repository);
        List<RainfallHourItem> rainItems = result.rainByHour().values().stream()
                .map(row -> new RainfallHourItem(row.id(), row.hourUtc(), row.rainMm(), row.version()))
                .sorted(Comparator.comparing(RainfallHourItem::hourUtc))
                .toList();
        if (result.policy() == null) {
            return new EffectiveThresholdResponse(zoneId, at, null, null,
                    zone.warnHourlyRate(), zone.alarmDailyRate(), zone.safetyHourlyRate(),
                    null, null, false, null,
                    zone.warnHourlyRate(), zone.alarmDailyRate(), zone.safetyHourlyRate(),
                    true, List.of(), rainItems);
        }
        PolicyVersionRow policy = result.policy();
        if (!result.dataSufficient()) {
            return new EffectiveThresholdResponse(zoneId, at, policy.version(), policy.effectiveAt(),
                    policy.yellowHourlyRate(), policy.redDailyRate(), zone.safetyHourlyRate(),
                    policy.rainGateMm(), policy.reductionFactor(), false, null,
                    null, null, null, false, result.missingHoursUtc(), rainItems);
        }
        double factor = result.reductionApplied() ? policy.reductionFactor() : 1.0;
        return new EffectiveThresholdResponse(zoneId, at, policy.version(), policy.effectiveAt(),
                policy.yellowHourlyRate(), policy.redDailyRate(), zone.safetyHourlyRate(),
                policy.rainGateMm(), policy.reductionFactor(), result.reductionApplied(),
                result.rainSum24h(),
                policy.yellowHourlyRate() * factor, policy.redDailyRate() * factor,
                zone.safetyHourlyRate() * factor, true, List.of(), rainItems);
    }

    // ------------------------------------------------------------------
    // 合成小时雨量（UTC整点）
    // ------------------------------------------------------------------

    /**
     * 写入/更正合成小时雨量（幂等）。UTC整点、有限非负；同测区同小时同值重放返回首次结果，
     * 异值须携带当前版本号显式更正，成功后版本加1。更正只影响之后的评价，不重算已归档证据。
     */
    @Transactional
    public IdempotencyService.Stored upsertRainfallHour(long zoneId, RainfallHourRequest request) {
        return idempotencyService.execute("POST /api/zones/{zoneId}/rainfall-hours",
                request.requestId(), new ZoneScopedRequest<>(zoneId, request),
                () -> doUpsertRainfallHour(zoneId, request));
    }

    private IdempotencyService.Stored doUpsertRainfallHour(long zoneId, RainfallHourRequest request) {
        ZoneRow zone = repository.findZoneByIdForUpdate(zoneId).orElseThrow(
                () -> ApiException.notFound("ZONE_NOT_FOUND", "测区不存在: " + zoneId));
        LocalDateTime hourUtc = request.hourUtc();
        if (hourUtc == null || (hourUtc.getMinute() | hourUtc.getSecond() | hourUtc.getNano()) != 0) {
            throw ApiException.badRequest("INVALID_RAIN_HOUR",
                    "雨量小时须为UTC整点（分秒纳秒均为0）: " + hourUtc);
        }
        if (request.rainMm() == null || !Double.isFinite(request.rainMm()) || request.rainMm() < 0) {
            throw ApiException.badRequest("INVALID_RAIN_VALUE",
                    "小时雨量须为有限非负值: " + request.rainMm());
        }
        if (request.expectedVersion() == null || request.expectedVersion() < 0) {
            throw ApiException.badRequest("INVALID_EXPECTED_VERSION",
                    "expectedVersion不能为空或负数: " + request.expectedVersion());
        }
        RainfallHourRow existing = repository.findRainfallHourForUpdate(zoneId, hourUtc).orElse(null);
        if (existing == null) {
            if (request.expectedVersion() != 0) {
                throw ApiException.conflict("RAIN_HOUR_VERSION_CONFLICT",
                        "小时雨量尚不存在，首写expectedVersion须为0: " + request.expectedVersion());
            }
            long id = repository.insertRainfallHour(zoneId, hourUtc, request.rainMm());
            return json(201, new RainfallHourResponse(id, hourUtc, request.rainMm(), 1, "CREATED"));
        }
        if (Double.compare(existing.rainMm(), request.rainMm()) == 0) {
            // 自然重放：同测区同小时同值，无论是否携带版本号都返回首次冻结结果，不升版。
            return json(200, new RainfallHourResponse(existing.id(), hourUtc, existing.rainMm(),
                    existing.version(), "REPLAYED"));
        }
        if (request.expectedVersion() != existing.version()) {
            throw ApiException.conflict("RAIN_HOUR_VERSION_CONFLICT",
                    "异值更正须显式携带当前版本号: expected=" + request.expectedVersion()
                            + ", current=" + existing.version());
        }
        int updated = repository.correctRainfallHour(existing.id(), request.rainMm(), existing.version());
        if (updated == 0) {
            throw ApiException.conflict("RAIN_HOUR_VERSION_CONFLICT",
                    "小时雨量版本已被并发更正: current=" + existing.version());
        }
        return json(200, new RainfallHourResponse(existing.id(), hourUtc, request.rainMm(),
                existing.version() + 1, "CORRECTED"));
    }

    /** 测区合成小时雨量列表（按UTC小时升序）。 */
    @Transactional(readOnly = true)
    public List<RainfallHourItem> listRainfallHours(long zoneId) {
        requireZone(zoneId);
        return repository.findRainfallHoursByZone(zoneId).stream()
                .map(row -> new RainfallHourItem(row.id(), row.hourUtc(), row.rainMm(), row.version()))
                .toList();
    }

    // ------------------------------------------------------------------
    // 复工证据查询
    // ------------------------------------------------------------------

    /** 查询复工批准时冻结的一致证据；未批准或无证据返回404。 */
    @Transactional(readOnly = true)
    public ResumptionEvidenceResponse resumptionEvidence(long chainId) {
        ChainRow chain = repository.findChainById(chainId).orElseThrow(
                () -> ApiException.notFound("CHAIN_NOT_FOUND", "处置链不存在: " + chainId));
        EvidenceRow row = repository.findEvidenceByChain(chainId).orElseThrow(
                () -> ApiException.notFound("RESUMPTION_EVIDENCE_NOT_FOUND",
                        "复工尚未批准或证据未冻结: " + chainId));
        EvidenceSnapshot snapshot;
        try {
            snapshot = objectMapper.readValue(row.evidenceJson(), EvidenceSnapshot.class);
        } catch (Exception e) {
            throw new IllegalStateException("冻结证据反序列化失败: evidenceId=" + row.id(), e);
        }
        return new ResumptionEvidenceResponse(row.id(), row.assessmentId(), row.chainId(), row.zoneId(),
                row.frozenAt(), row.windowStart(), row.windowEnd(), row.lastTightenedAt(), snapshot);
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
     * 存在生效雨量联动策略时，阈值取该读数时刻实际生效的折减后阈值；该时刻近24个完整
     * UTC小时雨量任一缺点则无法取得一致阈值，直接返回数据不足（422），缺数不按零处理。
     * 红色链执行中（已停产）的后续读数属于回落监测，不再重复受理预警；
     * 黄色链执行中再触发黄色视为并发重复预警，拒绝受理（409）。
     */
    private String evaluateAlarm(ZoneRow zone, ReadingRow reading) {
        RainThresholdResolver.Result threshold = thresholdResolver.resolve(zone, reading.measuredAt(), repository);
        if (threshold.policyActive() && !threshold.dataSufficient()) {
            throw ApiException.unprocessable("RAIN_DATA_INSUFFICIENT",
                    "生效雨量策略存在但近24个完整UTC小时雨量存在缺点，无法判定阈值: measuredAt="
                            + reading.measuredAt() + ", missing=" + threshold.missingHoursUtc());
        }
        double redLine = threshold.effectiveRedDailyRate(zone);
        double yellowLine = threshold.effectiveYellowHourlyRate(zone);
        boolean red = reading.dailyRate() != null && reading.dailyRate() > redLine;
        boolean yellow = reading.hourlyRate() != null && reading.hourlyRate() > yellowLine;
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
        // 安全门禁：申请时刻须已连续24小时安全；存在雨量策略时逐时刻按实际有效阈值判定，
        // 策略变严则安全计时自生效时刻重新累计，雨量缺点阻断申请。
        evaluateSafetyGate(zone, chain, request.appliedAt());
        long assessmentId = repository.insertAssessment(chainId, chain.zoneId(), request.appliedAt());
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
        LocalDateTime siteCheckAt = assessment.siteCheckConfirmedAt();
        LocalDateTime monitoringReviewAt = assessment.monitoringReviewConfirmedAt();
        if (CONFIRM_SITE_CHECK.equals(type)) {
            if (siteCheck) {
                throw ApiException.conflict("CONFIRM_DUPLICATED", "现场核查已确认: " + chainId);
            }
            siteCheck = true;
            siteCheckAt = request.confirmedAt();
        } else {
            if (monitoringReview) {
                throw ApiException.conflict("CONFIRM_DUPLICATED", "监测复核已确认: " + chainId);
            }
            monitoringReview = true;
            monitoringReviewAt = request.confirmedAt();
        }
        // 双确认齐全才批准复工：批准时刻（第二个确认的confirmedAt）须重新满足连续24小时安全门禁，
        // 并在同一事务内冻结一致证据；停产与复工形成唯一终态。
        boolean approved = siteCheck && monitoringReview;
        String chainStatus;
        String zoneStatus;
        if (approved) {
            ZoneRow zone = repository.findZoneById(chain.zoneId()).orElseThrow();
            SafetyGate gate = evaluateSafetyGate(zone, chain, request.confirmedAt());
            String assessmentStatus = ASSESSMENT_APPROVED;
            repository.updateAssessmentConfirmation(assessment.id(), siteCheck, monitoringReview,
                    siteCheckAt, monitoringReviewAt, assessmentStatus, request.confirmedAt());
            EvidenceSnapshot snapshot = buildEvidenceSnapshot(zone, chain, gate,
                    request.confirmedAt(), siteCheckAt, monitoringReviewAt);
            String evidenceJson = writeJson(snapshot);
            repository.insertResumptionEvidence(assessment.id(), chainId, chain.zoneId(),
                    request.confirmedAt(), gate.windowStart(), request.confirmedAt(),
                    gate.lastTightenedAt(), evidenceJson);
            repository.updateChainStatus(chainId, CHAIN_RESUMED, null);
            repository.updateZoneStatus(chain.zoneId(), STATUS_NORMAL);
            chainStatus = CHAIN_RESUMED;
            zoneStatus = STATUS_NORMAL;
        } else {
            repository.updateAssessmentConfirmation(assessment.id(), siteCheck, monitoringReview,
                    siteCheckAt, monitoringReviewAt, ASSESSMENT_APPLIED, null);
            chainStatus = chain.status();
            zoneStatus = repository.findZoneById(chain.zoneId()).orElseThrow().status();
        }
        return json(201, new ResumptionConfirmResponse(assessment.id(), siteCheck, monitoringReview,
                approved ? ASSESSMENT_APPROVED : ASSESSMENT_APPLIED, chainStatus, zoneStatus));
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

    // ------------------------------------------------------------------
    // 复工安全门禁与冻结证据
    // ------------------------------------------------------------------

    /** 安全门禁评价结果：窗口起点、最近变严生效时刻、逐时刻评价与涉及的策略版本。 */
    private record SafetyGate(LocalDateTime windowStart, LocalDateTime lastTightenedAt,
                              List<EvidenceMoment> moments, List<PolicyVersionRow> policies) {
    }

    private ZoneRow requireZone(long zoneId) {
        return repository.findZoneById(zoneId).orElseThrow(
                () -> ApiException.notFound("ZONE_NOT_FOUND", "测区不存在: " + zoneId));
    }

    private PolicyVersionResponse toPolicyResponse(PolicyVersionRow row) {
        return new PolicyVersionResponse(row.id(), row.version(), row.yellowHourlyRate(),
                row.redDailyRate(), row.rainGateMm(), row.reductionFactor(),
                row.submittedAt(), row.effectiveAt(), row.tightenedFromPrevious());
    }

    /**
     * 复工安全门禁：评价时刻 at 须已连续24小时安全。
     * 窗口起点为红色触发时刻；窗口内每一次“变严”策略生效都令计时自该生效时刻重新累计
     * （恰等生效时刻即计入重置）；窗口内每个读数时刻按当时实际生效的阈值（含雨量折减）
     * 判定，小时速率须严格低于当时安全线；任一时刻雨量缺点即数据不足并阻断批准。
     */
    private SafetyGate evaluateSafetyGate(ZoneRow zone, ChainRow chain, LocalDateTime at) {
        LocalDateTime triggeredAt = chain.triggeredAt();
        List<PolicyVersionRow> tightened = repository.findPolicyVersionsByZone(zone.id()).stream()
                .filter(p -> p.tightenedFromPrevious()
                        && !p.effectiveAt().isBefore(triggeredAt)
                        && !p.effectiveAt().isAfter(at))
                .sorted(Comparator.comparing(PolicyVersionRow::effectiveAt)
                        .thenComparing(PolicyVersionRow::version))
                .toList();
        LocalDateTime windowStart = triggeredAt;
        LocalDateTime lastTightenedAt = null;
        if (!tightened.isEmpty()) {
            lastTightenedAt = tightened.get(tightened.size() - 1).effectiveAt();
            windowStart = lastTightenedAt;
        }
        final LocalDateTime finalWindowStart = windowStart;
        final LocalDateTime finalLastTightenedAt = lastTightenedAt;
        // 恰满24小时允许：仅严格早于窗口起点+24小时才拒绝。
        LocalDateTime earliest = windowStart.plusHours(RECOVERY_WINDOW_HOURS);
        if (at.isBefore(earliest)) {
            throw ApiException.unprocessable("RECOVERY_WINDOW_TOO_SHORT",
                    "安全连续计时未满24小时（策略变严后须重新累计）: windowStart=" + windowStart
                            + ", earliest=" + earliest);
        }
        // 红色触发读数本身不属于安全窗口（严格晚于触发时刻）；变严生效时刻的读数已按新策略
        // 评价，纳入重计后的窗口（不早于生效时刻）。
        List<ReadingRow> window = repository.findReadingsByZone(zone.id()).stream()
                .filter(row -> finalLastTightenedAt == null
                        ? row.measuredAt().isAfter(finalWindowStart)
                        : !row.measuredAt().isBefore(finalWindowStart))
                .filter(row -> !row.measuredAt().isAfter(at))
                .toList();
        if (window.isEmpty()) {
            throw ApiException.unprocessable("NO_MONITORING_DATA",
                    "安全窗口内无监测数据，无法证明速率回落: chainId=" + chain.id()
                            + ", windowStart=" + windowStart);
        }
        List<EvidenceMoment> moments = new ArrayList<>(window.size());
        for (ReadingRow row : window) {
            RainThresholdResolver.Result threshold =
                    thresholdResolver.resolve(zone, row.measuredAt(), repository);
            if (threshold.policyActive() && !threshold.dataSufficient()) {
                throw ApiException.unprocessable("RAIN_DATA_INSUFFICIENT",
                        "安全窗口内存在雨量缺点，缺点不得按零处理并阻断复工: measuredAt="
                                + row.measuredAt() + ", missing=" + threshold.missingHoursUtc());
            }
            double safetyLine = threshold.effectiveSafetyHourlyRate(zone);
            boolean safe = row.hourlyRate() != null && row.hourlyRate() < safetyLine;
            if (!safe) {
                throw ApiException.unprocessable("RATE_ABOVE_SAFETY_LINE",
                        "窗口内存在按当时有效阈值判定未回落至安全线以下的读数: measuredAt="
                                + row.measuredAt() + ", hourlyRate=" + row.hourlyRate()
                                + ", effectiveSafetyLine=" + safetyLine);
            }
            moments.add(new EvidenceMoment(row.measuredAt(), row.hourlyRate(),
                    threshold.policy() == null ? null : threshold.policy().version(),
                    threshold.reductionApplied(),
                    threshold.dataSufficient() ? threshold.rainSum24h() : null,
                    safetyLine, true));
        }
        return new SafetyGate(windowStart, lastTightenedAt, List.copyOf(moments),
                repository.findPolicyVersionsByZone(zone.id()));
    }

    /** 在批准事务的同一快照内组装结构化冻结证据。 */
    private EvidenceSnapshot buildEvidenceSnapshot(ZoneRow zone, ChainRow chain, SafetyGate gate,
                                                   LocalDateTime approvedAt,
                                                   LocalDateTime siteCheckAt,
                                                   LocalDateTime monitoringReviewAt) {
        List<PolicyVersionResponse> policies = gate.policies().stream()
                .map(this::toPolicyResponse)
                .toList();
        List<EvidenceConfirmation> confirmations = List.of(
                new EvidenceConfirmation(CONFIRM_SITE_CHECK, siteCheckAt),
                new EvidenceConfirmation(CONFIRM_MONITORING_REVIEW, monitoringReviewAt));
        return new EvidenceSnapshot(zone.id(), zone.code(), chain.id(), chain.triggeredAt(),
                gate.windowStart(), approvedAt,
                gate.lastTightenedAt(), policies, gate.moments(), confirmations);
    }

    private String writeJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("证据报文序列化失败", e);
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
}
