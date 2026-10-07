package com.example.starter.fermentation.service;

import com.example.starter.fermentation.api.BatchDtos.BatchResponse;
import com.example.starter.fermentation.api.BatchDtos.CoolingRequest;
import com.example.starter.fermentation.api.BatchDtos.CreateBatchRequest;
import com.example.starter.fermentation.api.BatchDtos.DiacetylResponse;
import com.example.starter.fermentation.api.BatchDtos.DiacetylTestRequest;
import com.example.starter.fermentation.api.BatchDtos.ExtendMaturationRequest;
import com.example.starter.fermentation.api.BatchDtos.JudgeRequest;
import com.example.starter.fermentation.api.BatchDtos.JudgeResponse;
import com.example.starter.fermentation.api.BatchDtos.LateResponse;
import com.example.starter.fermentation.api.BatchDtos.ReadingResponse;
import com.example.starter.fermentation.api.BatchDtos.StartPhaseRequest;
import com.example.starter.fermentation.api.BatchDtos.TemperatureReadingRequest;
import com.example.starter.fermentation.api.BatchDtos.TransferRequest;
import com.example.starter.fermentation.domain.BatchStatus;
import com.example.starter.fermentation.domain.CurvePhase;
import com.example.starter.fermentation.domain.DiacetylTest;
import com.example.starter.fermentation.domain.FermentationBatch;
import com.example.starter.fermentation.domain.TemperatureReading;
import com.example.starter.fermentation.error.ApiException;
import com.example.starter.fermentation.repository.BatchRepository;
import com.example.starter.fermentation.repository.CurveRepository;
import com.example.starter.fermentation.repository.ReadingRepository;
import com.example.starter.fermentation.repository.ReceivingRepository;
import com.example.starter.fermentation.service.IdempotencyService.ConcurrentReplayException;
import com.example.starter.fermentation.service.IdempotencyService.StoredResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;

/**
 * 发酵批次核心服务：状态机流转、主酵分段控温告警、双乙酰判定、
 * 降温与曲线版本切换同事务、后熟时长判定、清酒转出终态与迟到数据。
 *
 * <p>判定口径（均可由落库数据复算）：温度偏差恰 0.5℃ 合格，连续 3 个超差采样点（恰 3 点）告警；
 * 双乙酰连续两次不超过 0.10mg/L（恰 0.10 合格）且间隔不少于 12 小时（恰 12 小时合法）方可降温；
 * 后熟不少于 7 天（恰 7 天合法），仅允许延长一次（延长后要求 14 天），延长后仍不合格整批复审。
 */
@Service
public class BatchService {

    /** 温度允许偏差（℃），恰 0.5 合格。 */
    static final BigDecimal TEMP_TOLERANCE = new BigDecimal("0.5");
    /** 连续超差告警点数，恰 3 点触发。 */
    static final int ALARM_CONSECUTIVE = 3;
    /** 双乙酰合格上限（mg/L），恰 0.10 合格。 */
    static final BigDecimal DIACETYL_LIMIT = new BigDecimal("0.10");
    /** 双乙酰两次采样最小间隔（小时），恰 12 小时合法。 */
    static final long DIACETYL_MIN_INTERVAL_HOURS = 12;
    /** 后熟最短天数，恰 7 天合法。 */
    static final int MATURATION_MIN_DAYS = 7;
    /** 延长后熟后要求的总天数。 */
    static final int MATURATION_EXTENDED_DAYS = 14;

    private final BatchRepository batchRepository;
    private final CurveRepository curveRepository;
    private final ReadingRepository readingRepository;
    private final ReceivingRepository receivingRepository;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    public BatchService(BatchRepository batchRepository, CurveRepository curveRepository,
                        ReadingRepository readingRepository, ReceivingRepository receivingRepository,
                        IdempotencyService idempotencyService,
                        ObjectMapper objectMapper) {
        this.batchRepository = batchRepository;
        this.curveRepository = curveRepository;
        this.readingRepository = readingRepository;
        this.receivingRepository = receivingRepository;
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
    }

    /**
     * 创建批次（满罐）。同一发酵罐同时刻仅允许一个进行中批次，并发由唯一约束兜底。
     */
    @Transactional
    public StoredResponse createBatch(CreateBatchRequest req) {
        String endpoint = "POST /api/batches";
        String fingerprint = idempotencyService.fingerprint(endpoint, req.requestId(),
                req.batchNo(), req.tankCode(), req.curveCode(), req.filledAt().toString());
        return idempotent(req.requestId(), endpoint, fingerprint, () -> {
            long tankId = batchRepository.findTankIdByCode(req.tankCode())
                    .orElseThrow(() -> ApiException.notFound("发酵罐不存在: " + req.tankCode()));
            int curveVersion = curveRepository.findActiveVersion(req.curveCode())
                    .orElseThrow(() -> ApiException.unprocessable("工艺曲线无生效版本: " + req.curveCode()));
            long id;
            try {
                id = batchRepository.insert(req.batchNo(), tankId, req.curveCode(), curveVersion,
                        req.filledAt(), req.tankCode());
            } catch (DuplicateKeyException e) {
                throw translateInsertConflict(e, req);
            }
            FermentationBatch batch = mustFind(id);
            return new StoredResponse(201, toJson(toResponse(batch)));
        });
    }

    /**
     * 开始主酵：FILLED → MAIN_FERMENT。
     */
    @Transactional
    public StoredResponse startMainFerment(long batchId, StartPhaseRequest req) {
        String endpoint = "POST /api/batches/" + batchId + "/main-ferment/start";
        String fingerprint = idempotencyService.fingerprint(endpoint, req.requestId(),
                req.startedAt().toString());
        return idempotent(req.requestId(), endpoint, fingerprint, () -> {
            FermentationBatch batch = mustFind(batchId);
            requireStatus(batch, BatchStatus.FILLED, "仅满罐批次可开始主酵");
            batchRepository.startMainFerment(batchId, req.startedAt());
            return new StoredResponse(200, toJson(toResponse(mustFind(batchId))));
        });
    }

    /**
     * 上报温度采样。按采样时刻所在分段复算设定值；偏差恰 0.5℃ 合格；
     * 连续 3 个超差点（恰 3 点）触发告警。批次已转出时仅记录迟到事件。
     */
    @Transactional
    public StoredResponse addTemperatureReading(long batchId, TemperatureReadingRequest req) {
        String endpoint = "POST /api/batches/" + batchId + "/temperature-readings";
        String fingerprint = idempotencyService.fingerprint(endpoint, req.requestId(),
                req.sampledAt().toString(), req.temperature().toPlainString());
        return idempotent(req.requestId(), endpoint, fingerprint, () -> {
            FermentationBatch batch = mustFind(batchId);
            if (batch.status() == BatchStatus.TRANSFERRED) {
                readingRepository.insertLateEvent(batchId, "TEMPERATURE_READING",
                        "sampledAt=" + req.sampledAt() + ",temperature=" + req.temperature().toPlainString(),
                        req.sampledAt());
                return new StoredResponse(202,
                        toJson(new LateResponse(true, "TEMPERATURE_READING", batchId)));
            }
            CurvePhase phase = switch (batch.status()) {
                case MAIN_FERMENT -> CurvePhase.MAIN_FERMENT;
                case COOLING -> CurvePhase.COOLING;
                case MATURATION -> CurvePhase.MATURATION;
                default -> throw ApiException.unprocessable(
                        "批次状态 " + batch.status() + " 不接受温度采样");
            };
            LocalDateTime phaseStart = phaseStart(batch, phase);
            if (req.sampledAt().isBefore(phaseStart)) {
                throw ApiException.unprocessable("采样时间早于当前阶段开始时间");
            }
            long elapsedHours = Duration.between(phaseStart, req.sampledAt()).toHours();
            BigDecimal setpoint = curveRepository.setpointAt(
                    batch.curveCode(), batch.curveVersion(), phase, elapsedHours);
            BigDecimal deviation = req.temperature().subtract(setpoint).abs();
            boolean outOfTolerance = deviation.compareTo(TEMP_TOLERANCE) > 0;

            List<TemperatureReading> history = new ArrayList<>(
                    readingRepository.findReadings(batchId, phase));
            TemperatureReading pending = new TemperatureReading(0L, batchId, batch.curveVersion(),
                    phase, req.sampledAt(), req.temperature(), setpoint, deviation,
                    outOfTolerance, 0, false, false);
            history.add(pending);
            history.sort(Comparator.comparing(TemperatureReading::sampledAt)
                    .thenComparing(TemperatureReading::id));
            int position = history.indexOf(pending);
            int consecutiveOut = 0;
            for (int i = position; i >= 0 && history.get(i).outOfTolerance(); i--) {
                consecutiveOut++;
            }
            boolean alarm = consecutiveOut >= ALARM_CONSECUTIVE;

            long id = readingRepository.insertReading(batchId, batch.curveVersion(), phase,
                    req.sampledAt(), req.temperature(), setpoint, deviation,
                    outOfTolerance, consecutiveOut, alarm, false);
            ReadingResponse response = new ReadingResponse(id, batchId, phase, batch.curveVersion(),
                    req.sampledAt(), req.temperature(), setpoint, deviation,
                    outOfTolerance, consecutiveOut, alarm, false);
            return new StoredResponse(201, toJson(response));
        });
    }

    /**
     * 上报双乙酰检测。批次已转出时仅记录迟到事件。
     */
    @Transactional
    public StoredResponse addDiacetylTest(long batchId, DiacetylTestRequest req) {
        String endpoint = "POST /api/batches/" + batchId + "/diacetyl-tests";
        String fingerprint = idempotencyService.fingerprint(endpoint, req.requestId(),
                req.testedAt().toString(), req.valueMgPerLiter().toPlainString());
        return idempotent(req.requestId(), endpoint, fingerprint, () -> {
            FermentationBatch batch = mustFind(batchId);
            if (batch.status() == BatchStatus.TRANSFERRED) {
                readingRepository.insertLateEvent(batchId, "DIACETYL_TEST",
                        "testedAt=" + req.testedAt() + ",value=" + req.valueMgPerLiter().toPlainString(),
                        req.testedAt());
                return new StoredResponse(202, toJson(new LateResponse(true, "DIACETYL_TEST", batchId)));
            }
            requireStatus(batch, BatchStatus.MAIN_FERMENT, "仅主酵期接受双乙酰检测");
            long id = readingRepository.insertDiacetyl(batchId, req.testedAt(),
                    req.valueMgPerLiter(), false);
            return new StoredResponse(201,
                    toJson(new DiacetylResponse(id, batchId, req.testedAt(),
                            req.valueMgPerLiter(), false)));
        });
    }

    /**
     * 下达降温指令：双乙酰判定通过后，降温指令与曲线版本切换在同一事务内完成，
     * 降温按最新生效曲线版本执行，不得按旧曲线降温。
     */
    @Transactional
    public StoredResponse cooling(long batchId, CoolingRequest req) {
        String endpoint = "POST /api/batches/" + batchId + "/cooling";
        String fingerprint = idempotencyService.fingerprint(endpoint, req.requestId(),
                req.instructedAt().toString());
        return idempotent(req.requestId(), endpoint, fingerprint, () -> {
            FermentationBatch batch = mustFind(batchId);
            requireStatus(batch, BatchStatus.MAIN_FERMENT, "仅主酵期可下达降温指令");
            assertDiacetylPassed(batchId);
            int newVersion = curveRepository.findActiveVersion(batch.curveCode())
                    .orElseThrow(() -> ApiException.unprocessable(
                            "工艺曲线无生效版本: " + batch.curveCode()));
            batchRepository.applyCooling(batchId, newVersion, req.instructedAt());
            return new StoredResponse(200, toJson(toResponse(mustFind(batchId))));
        });
    }

    /**
     * 开始后熟：COOLING → MATURATION。
     */
    @Transactional
    public StoredResponse startMaturation(long batchId, StartPhaseRequest req) {
        String endpoint = "POST /api/batches/" + batchId + "/maturation/start";
        String fingerprint = idempotencyService.fingerprint(endpoint, req.requestId(),
                req.startedAt().toString());
        return idempotent(req.requestId(), endpoint, fingerprint, () -> {
            FermentationBatch batch = mustFind(batchId);
            requireStatus(batch, BatchStatus.COOLING, "仅降温结束批次可开始后熟");
            batchRepository.startMaturation(batchId, req.startedAt());
            return new StoredResponse(200, toJson(toResponse(mustFind(batchId))));
        });
    }

    /**
     * 延长后熟：仅允许一次，延长后要求后熟总时长 14 天。
     */
    @Transactional
    public StoredResponse extendMaturation(long batchId, ExtendMaturationRequest req) {
        String endpoint = "POST /api/batches/" + batchId + "/maturation/extend";
        String fingerprint = idempotencyService.fingerprint(endpoint, req.requestId(),
                req.extendedAt().toString());
        return idempotent(req.requestId(), endpoint, fingerprint, () -> {
            FermentationBatch batch = mustFind(batchId);
            requireStatus(batch, BatchStatus.MATURATION, "仅后熟期可延长后熟");
            if (batch.maturationExtended()) {
                throw ApiException.conflict("后熟仅允许延长一次，本批次已延长过");
            }
            batchRepository.markMaturationExtended(batchId);
            return new StoredResponse(200, toJson(toResponse(mustFind(batchId))));
        });
    }

    /**
     * 成熟度判定：后熟保持不少于 7 天（恰 7 天合法，延长后 14 天）。
     * 不合格且未延长时可延长一次；延长后仍不合格整批复审（REVIEW 终态）。
     */
    @Transactional
    public StoredResponse judge(long batchId, JudgeRequest req) {
        String endpoint = "POST /api/batches/" + batchId + "/judge";
        String fingerprint = idempotencyService.fingerprint(endpoint, req.requestId(),
                req.judgedAt().toString());
        return idempotent(req.requestId(), endpoint, fingerprint, () -> {
            FermentationBatch batch = mustFind(batchId);
            requireStatus(batch, BatchStatus.MATURATION, "仅后熟期可判定成熟度");
            int requiredDays = batch.maturationExtended()
                    ? MATURATION_EXTENDED_DAYS : MATURATION_MIN_DAYS;
            boolean qualified = !req.judgedAt()
                    .isBefore(batch.maturationStartedAt().plusDays(requiredDays));
            BatchStatus newStatus;
            boolean canExtend;
            if (qualified) {
                newStatus = BatchStatus.JUDGED;
                canExtend = false;
            } else if (!batch.maturationExtended()) {
                newStatus = BatchStatus.MATURATION;
                canExtend = true;
            } else {
                newStatus = BatchStatus.REVIEW;
                canExtend = false;
            }
            batchRepository.updateJudgement(batchId, newStatus, req.judgedAt());
            return new StoredResponse(200, toJson(new JudgeResponse(
                    qualified, canExtend, newStatus, requiredDays, req.judgedAt())));
        });
    }

    /**
     * 清酒转出：JUDGED → TRANSFERRED（终态）。重复转出返回幂等成功。
     * 受控源旧单笔转出同样占用唯一核销（liquor_writeoff），与合批接收共享，
     * 任一方已消费则另一方不得重复消费；核销与状态流转同事务提交。
     */
    @Transactional
    public StoredResponse transfer(long batchId, TransferRequest req) {
        String endpoint = "POST /api/batches/" + batchId + "/transfer";
        String fingerprint = idempotencyService.fingerprint(endpoint, req.requestId(),
                req.transferredAt().toString());
        return idempotent(req.requestId(), endpoint, fingerprint, () -> {
            FermentationBatch batch = mustFind(batchId);
            if (batch.status() == BatchStatus.TRANSFERRED) {
                // 终态幂等：重复转出返回成功，不产生新的状态变更
                return new StoredResponse(200, toJson(toResponse(batch)));
            }
            requireStatus(batch, BatchStatus.JUDGED, "仅判定合格批次可清酒转出");
            try {
                receivingRepository.insertWriteoff(batchId, "SINGLE", null,
                        LocalDateTime.now(ZoneOffset.UTC));
            } catch (DuplicateKeyException e) {
                // 唯一核销已被合批接收消费：单笔转出不得重复消费，整单回滚
                throw ApiException.conflict(
                        "批次清酒量已被合批接收核销，旧单笔转出不得重复消费: " + batchId);
            }
            batchRepository.completeTransfer(batchId, req.transferredAt());
            return new StoredResponse(200, toJson(toResponse(mustFind(batchId))));
        });
    }

    /**
     * 查询批次详情（含判定口径复算所需的全部状态字段）。
     */
    @Transactional(readOnly = true)
    public BatchResponse getBatch(long batchId) {
        return toResponse(mustFind(batchId));
    }

    /**
     * 查询批次某阶段温度采样链（按采样时间升序，可复算告警）。
     */
    @Transactional(readOnly = true)
    public List<ReadingResponse> getReadings(long batchId, CurvePhase phase) {
        mustFind(batchId);
        return readingRepository.findReadings(batchId, phase).stream()
                .map(r -> new ReadingResponse(r.id(), r.batchId(), r.phase(), r.curveVersion(),
                        r.sampledAt(), r.temperature(), r.setpoint(), r.deviation(),
                        r.outOfTolerance(), r.consecutiveOut(), r.alarmTriggered(), r.late()))
                .toList();
    }

    /**
     * 查询批次双乙酰检测链（按检测时间升序，可复算降温资格）。
     */
    @Transactional(readOnly = true)
    public List<DiacetylResponse> getDiacetylTests(long batchId) {
        mustFind(batchId);
        return readingRepository.findDiacetylTests(batchId).stream()
                .map(t -> new DiacetylResponse(t.id(), t.batchId(), t.testedAt(),
                        t.valueMgPerLiter(), t.late()))
                .toList();
    }

    private StoredResponse idempotent(String requestId, String endpoint, String fingerprint,
                                      Supplier<StoredResponse> action) {
        try {
            return idempotencyService.execute(requestId, endpoint, fingerprint, action);
        } catch (ConcurrentReplayException e) {
            // 并发同键：回滚本次业务写入，按首次受理结果裁决
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return idempotencyService.resolveConcurrent(e.requestId(), e.fingerprint());
        }
    }

    private FermentationBatch mustFind(long batchId) {
        return batchRepository.findById(batchId)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchId));
    }

    private void requireStatus(FermentationBatch batch, BatchStatus expected, String message) {
        if (batch.status() != expected) {
            throw ApiException.unprocessable(
                    message + "（当前状态 " + batch.status() + "，要求 " + expected + "）");
        }
    }

    private LocalDateTime phaseStart(FermentationBatch batch, CurvePhase phase) {
        return switch (phase) {
            case MAIN_FERMENT -> batch.mainFermentStartedAt();
            case COOLING -> batch.coolingStartedAt();
            case MATURATION -> batch.maturationStartedAt();
        };
    }

    /**
     * 双乙酰判定：检测链最后两次均不超过 0.10mg/L（恰 0.10 合格），
     * 且两次采样间隔不少于 12 小时（恰 12 小时合法）。
     */
    private void assertDiacetylPassed(long batchId) {
        List<DiacetylTest> tests = readingRepository.findDiacetylTests(batchId);
        if (tests.size() < 2) {
            throw ApiException.unprocessable("双乙酰检测不足两次，不允许降温");
        }
        DiacetylTest previous = tests.get(tests.size() - 2);
        DiacetylTest latest = tests.get(tests.size() - 1);
        if (previous.valueMgPerLiter().compareTo(DIACETYL_LIMIT) > 0
                || latest.valueMgPerLiter().compareTo(DIACETYL_LIMIT) > 0) {
            throw ApiException.unprocessable("双乙酰未连续两次降至 0.10mg/L 及以下，不允许降温");
        }
        Duration interval = Duration.between(previous.testedAt(), latest.testedAt());
        if (interval.compareTo(Duration.ofHours(DIACETYL_MIN_INTERVAL_HOURS)) < 0) {
            throw ApiException.unprocessable("双乙酰两次采样间隔不足 12 小时，不允许降温");
        }
    }

    private ApiException translateInsertConflict(DuplicateKeyException e, CreateBatchRequest req) {
        String message = String.valueOf(e.getMessage()).toLowerCase();
        if (message.contains("active_tank")) {
            return ApiException.conflict("发酵罐 " + req.tankCode() + " 已存在进行中批次，本次入罐不予受理");
        }
        if (message.contains("batch_no")) {
            return ApiException.conflict("批次号已存在: " + req.batchNo());
        }
        return ApiException.conflict("批次创建冲突: " + req.batchNo());
    }

    private BatchResponse toResponse(FermentationBatch b) {
        return new BatchResponse(b.id(), b.batchNo(), b.status(), b.curveCode(), b.curveVersion(),
                b.maturationExtended(), b.filledAt(), b.mainFermentStartedAt(), b.coolingStartedAt(),
                b.maturationStartedAt(), b.judgedAt(), b.transferredAt());
    }

    private String toJson(Object dto) {
        try {
            return objectMapper.writeValueAsString(dto);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }
}
