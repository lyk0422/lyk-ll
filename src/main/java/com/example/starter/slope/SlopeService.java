package com.example.starter.slope;

import com.example.starter.slope.SlopeRepository.AssessmentRow;
import com.example.starter.slope.SlopeRepository.ChainRow;
import com.example.starter.slope.SlopeRepository.EvacuationRow;
import com.example.starter.slope.SlopeRepository.LateEventRow;
import com.example.starter.slope.SlopeRepository.ReadingRow;
import com.example.starter.slope.SlopeRepository.ZoneRow;
import com.example.starter.slope.dto.ApiDtos.ArchiveRequest;
import com.example.starter.slope.dto.ApiDtos.ArchiveResponse;
import com.example.starter.slope.dto.ApiDtos.AssessmentInfo;
import com.example.starter.slope.dto.ApiDtos.ChainDetailResponse;
import com.example.starter.slope.dto.ApiDtos.CreateZoneRequest;
import com.example.starter.slope.dto.ApiDtos.EvacuationConfirmRequest;
import com.example.starter.slope.dto.ApiDtos.EvacuationConfirmResponse;
import com.example.starter.slope.dto.ApiDtos.EvacuationInfo;
import com.example.starter.slope.dto.ApiDtos.IngestReadingRequest;
import com.example.starter.slope.dto.ApiDtos.LateEventItem;
import com.example.starter.slope.dto.ApiDtos.RateCurveResponse;
import com.example.starter.slope.dto.ApiDtos.RatePoint;
import com.example.starter.slope.dto.ApiDtos.ReadingResponse;
import com.example.starter.slope.dto.ApiDtos.ResumptionApplyRequest;
import com.example.starter.slope.dto.ApiDtos.ResumptionApplyResponse;
import com.example.starter.slope.dto.ApiDtos.ResumptionConfirmRequest;
import com.example.starter.slope.dto.ApiDtos.ResumptionConfirmResponse;
import com.example.starter.slope.dto.ApiDtos.ZoneResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
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
        // 连续24小时速率回落至安全线以下：窗口内全部读数小时速率严格低于安全线。
        List<ReadingRow> window = repository.findReadingsByZone(chain.zoneId()).stream()
                .filter(row -> row.measuredAt().isAfter(chain.triggeredAt())
                        && !row.measuredAt().isAfter(request.appliedAt()))
                .toList();
        if (window.isEmpty()) {
            throw ApiException.unprocessable("NO_MONITORING_DATA",
                    "预警后窗口内无监测数据，无法证明速率回落: " + chainId);
        }
        for (ReadingRow row : window) {
            if (row.hourlyRate() == null || row.hourlyRate() >= zone.safetyHourlyRate()) {
                throw ApiException.unprocessable("RATE_ABOVE_SAFETY_LINE",
                        "窗口内存在速率未回落至安全线以下的读数: measuredAt=" + row.measuredAt());
            }
        }
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

    /** 幂等指纹携带路径参数，避免不同测区/链的同requestId互相重放。 */
    record ZoneScopedRequest<T>(long zoneId, T body) {
    }

    /** 幂等指纹携带路径参数，避免不同测区/链的同requestId互相重放。 */
    record ChainScopedRequest<T>(long chainId, T body) {
    }
}
