package com.example.starter.fermentation.service;

import com.example.starter.fermentation.BatchStatus;
import com.example.starter.fermentation.DomainException;
import com.example.starter.fermentation.DomainRules;
import com.example.starter.fermentation.dto.BatchResponse;
import com.example.starter.fermentation.dto.CoolingRequest;
import com.example.starter.fermentation.dto.CoolingResponse;
import com.example.starter.fermentation.dto.CreateBatchRequest;
import com.example.starter.fermentation.dto.DiacetylRequest;
import com.example.starter.fermentation.dto.DiacetylResponse;
import com.example.starter.fermentation.dto.JudgeRequest;
import com.example.starter.fermentation.dto.JudgeResponse;
import com.example.starter.fermentation.dto.SegmentSpec;
import com.example.starter.fermentation.dto.TemperatureRequest;
import com.example.starter.fermentation.dto.TemperatureResponse;
import com.example.starter.fermentation.dto.TransferRequest;
import com.example.starter.fermentation.dto.TransferResponse;
import com.example.starter.fermentation.repo.BatchRepository;
import com.example.starter.fermentation.repo.BatchRow;
import com.example.starter.fermentation.repo.CoolingRepository;
import com.example.starter.fermentation.repo.CurveRepository;
import com.example.starter.fermentation.repo.DiacetylRepository;
import com.example.starter.fermentation.repo.DiacetylRepository.DiacetylRow;
import com.example.starter.fermentation.repo.JudgmentRepository;
import com.example.starter.fermentation.repo.LateEventRepository;
import com.example.starter.fermentation.repo.TemperatureRepository;
import com.example.starter.fermentation.repo.TransferRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 发酵批次领域服务：满罐→主酵→降温→后熟→判定→清酒转出全流程与幂等、并发、终态规则。
 */
@Service
public class FermentationService {

    private final BatchRepository batchRepository;
    private final CurveRepository curveRepository;
    private final TemperatureRepository temperatureRepository;
    private final DiacetylRepository diacetylRepository;
    private final CoolingRepository coolingRepository;
    private final JudgmentRepository judgmentRepository;
    private final TransferRepository transferRepository;
    private final LateEventRepository lateEventRepository;
    private final IdempotencyExecutor idempotencyExecutor;
    private final ObjectMapper objectMapper;

    public FermentationService(BatchRepository batchRepository,
                               CurveRepository curveRepository,
                               TemperatureRepository temperatureRepository,
                               DiacetylRepository diacetylRepository,
                               CoolingRepository coolingRepository,
                               JudgmentRepository judgmentRepository,
                               TransferRepository transferRepository,
                               LateEventRepository lateEventRepository,
                               IdempotencyExecutor idempotencyExecutor,
                               ObjectMapper objectMapper) {
        this.batchRepository = batchRepository;
        this.curveRepository = curveRepository;
        this.temperatureRepository = temperatureRepository;
        this.diacetylRepository = diacetylRepository;
        this.coolingRepository = coolingRepository;
        this.judgmentRepository = judgmentRepository;
        this.transferRepository = transferRepository;
        this.lateEventRepository = lateEventRepository;
        this.idempotencyExecutor = idempotencyExecutor;
        this.objectMapper = objectMapper;
    }

    /**
     * 入罐（满罐）：建罐（若不存在）、建批次、写入初始曲线版本 1，同一事务完成。
     * 同一罐存在未转出批次时数据库唯一键冲突 409，并发入罐唯一受理。
     */
    @Transactional
    public BatchResponse createBatch(CreateBatchRequest request) {
        validateCurve(request.curve());
        long tankId = batchRepository.findOrCreateTank(request.tankCode());
        long batchId = batchRepository.createBatch(request.batchNo(), tankId, request.filledAt());
        long versionId = curveRepository.insertVersion(batchId, 1, "INITIAL", null);
        curveRepository.insertSegments(versionId, request.curve());
        return new BatchResponse(batchId, request.batchNo(), request.tankCode(),
                BatchStatus.FILLED, request.filledAt(), 1);
    }

    /**
     * 温度采样受理（requestId 幂等）：按生效曲线版本取目标温度，逐点累计连续偏离，恰连续 3 点告警。
     * 转出终态后到达只记迟到事件。
     */
    public TemperatureResponse reportTemperature(TemperatureRequest request) {
        String fingerprint = Fingerprints.temperature(request);
        return idempotencyExecutor.execute(request.requestId(),
                Fingerprints.TYPE_TEMPERATURE, fingerprint, TemperatureResponse.class,
                new IdempotencyExecutor.BusinessCore<>() {

                    private long batchId;

                    @Override
                    public TemperatureResponse run() {
                        BatchRow batch = batchRepository.requireByBatchNo(request.batchNo());
                        batchId = batch.id();
                        if (batch.status() == BatchStatus.TRANSFERRED) {
                            lateEventRepository.insert(batch.id(),
                                    Fingerprints.TYPE_TEMPERATURE, toJson(request));
                            return new TemperatureResponse(request.requestId(), request.batchNo(),
                                    request.readingAt(), null, request.temperature(), null,
                                    false, 0, false, true);
                        }
                        if (batch.status() == BatchStatus.FILLED) {
                            batchRepository.startPrimaryIfFilled(batch.id(), request.readingAt());
                            batch = batchRepository.requireByBatchNo(request.batchNo());
                        }
                        if (batch.status() != BatchStatus.PRIMARY
                                && batch.status() != BatchStatus.MATURING) {
                            throw new DomainException(409,
                                    "temperature reading only allowed in PRIMARY/MATURING, current "
                                            + batch.status());
                        }
                        if (request.readingAt().isBefore(batch.filledAt())) {
                            throw new DomainException(422, "reading time is before fill time");
                        }
                        String stage = batch.status().name();
                        int elapsedHours = (int) Duration.between(batch.filledAt(), request.readingAt()).toHours();
                        BigDecimal target = curveRepository.findTargetTemp(
                                batch.id(), batch.activeCurveVersion(), elapsedHours);
                        if (target == null) {
                            throw new DomainException(422,
                                    "no active curve segment at elapsed hour " + elapsedHours);
                        }
                        BigDecimal deviation = request.temperature().subtract(target);
                        boolean outOfRange = DomainRules.isTemperatureOutOfRange(target, request.temperature());
                        int previousRun = temperatureRepository.latestRunLengthBefore(
                                batch.id(), stage, request.readingAt());
                        int runLength = outOfRange ? previousRun + 1 : 0;
                        boolean alarmed = outOfRange
                                && runLength == DomainRules.ALARM_CONSECUTIVE_POINTS;
                        temperatureRepository.insert(batch.id(), request.readingAt(), stage,
                                target, request.temperature(), deviation,
                                outOfRange, runLength, alarmed);
                        return new TemperatureResponse(request.requestId(), request.batchNo(),
                                request.readingAt(), target, request.temperature(), deviation,
                                outOfRange, runLength, alarmed, false);
                    }

                    @Override
                    public Long assignedBatchId() {
                        return batchId;
                    }
                });
    }

    /**
     * 双乙酰检测受理（requestId 幂等）：恰 0.10mg/L 合格；复算检测链末尾两次合格且间隔恰满 12 小时才配对。
     * 转出终态后到达只记迟到事件。
     */
    public DiacetylResponse reportDiacetyl(DiacetylRequest request) {
        String fingerprint = Fingerprints.diacetyl(request);
        return idempotencyExecutor.execute(request.requestId(),
                Fingerprints.TYPE_DIACETYL, fingerprint, DiacetylResponse.class,
                new IdempotencyExecutor.BusinessCore<>() {

                    private long batchId;

                    @Override
                    public DiacetylResponse run() {
                        BatchRow batch = batchRepository.requireByBatchNo(request.batchNo());
                        batchId = batch.id();
                        if (batch.status() == BatchStatus.TRANSFERRED) {
                            lateEventRepository.insert(batch.id(),
                                    Fingerprints.TYPE_DIACETYL, toJson(request));
                            return new DiacetylResponse(request.requestId(), request.batchNo(),
                                    request.testedAt(), request.valueMgL(),
                                    DomainRules.isDiacetylPassing(request.valueMgL()),
                                    null, false, true);
                        }
                        if (batch.status() != BatchStatus.PRIMARY) {
                            throw new DomainException(409,
                                    "diacetyl test only allowed in PRIMARY stage, current "
                                            + batch.status());
                        }
                        if (request.testedAt().isBefore(batch.filledAt())) {
                            throw new DomainException(422, "test time is before fill time");
                        }
                        diacetylRepository.insert(batch.id(), request.testedAt(),
                                request.valueMgL(), request.requestId());
                        List<DiacetylRow> chain = diacetylRepository.findAll(batch.id());
                        boolean passing = DomainRules.isDiacetylPassing(request.valueMgL());
                        Long hoursSince = null;
                        boolean paired = false;
                        if (chain.size() >= 2) {
                            DiacetylRow previous = chain.get(chain.size() - 2);
                            DiacetylRow latest = chain.get(chain.size() - 1);
                            hoursSince = Duration.between(previous.testedAt(), latest.testedAt()).toHours();
                            paired = DomainRules.isDiacetylPassing(previous.valueMgL())
                                    && DomainRules.isDiacetylPassing(latest.valueMgL())
                                    && DomainRules.meetsSamplingInterval(
                                            Duration.between(previous.testedAt(), latest.testedAt()));
                        }
                        return new DiacetylResponse(request.requestId(), request.batchNo(),
                                request.testedAt(), request.valueMgL(), passing,
                                hoursSince, paired, false);
                    }

                    @Override
                    public Long assignedBatchId() {
                        return batchId;
                    }
                });
    }

    /**
     * 降温指令（requestId 幂等）：双乙酰链末尾两次合格且间隔不少于 12 小时方可降温。
     * 降温指令记录与曲线版本切换、批次状态推进在同一事务内完成，不会按旧曲线降温。
     */
    public CoolingResponse commandCooling(CoolingRequest request) {
        validateCurve(request.newCurve());
        String fingerprint = Fingerprints.cooling(request);
        return idempotencyExecutor.execute(request.requestId(),
                Fingerprints.TYPE_COOLING, fingerprint, CoolingResponse.class,
                new IdempotencyExecutor.BusinessCore<>() {

                    private long batchId;

                    @Override
                    public CoolingResponse run() {
                        BatchRow batch = batchRepository.requireByBatchNo(request.batchNo());
                        batchId = batch.id();
                        if (batch.status() != BatchStatus.PRIMARY) {
                            throw new DomainException(409,
                                    "cooling requires PRIMARY stage, current " + batch.status());
                        }
                        List<DiacetylRow> chain = diacetylRepository.findAll(batch.id());
                        if (!hasQualifiedPair(chain)) {
                            throw new DomainException(409,
                                    "cooling requires two consecutive passing diacetyl tests "
                                            + "(<=0.10mg/L) at least 12 hours apart");
                        }
                        int fromVersion = batch.activeCurveVersion() == null
                                ? 0 : batch.activeCurveVersion();
                        int toVersion = fromVersion + 1;
                        long commandId = coolingRepository.insert(batch.id(), request.commandAt(),
                                batch.activeCurveVersion(), toVersion, request.requestId());
                        long versionId = curveRepository.insertVersion(
                                batch.id(), toVersion, "COOLING", commandId);
                        curveRepository.insertSegments(versionId, request.newCurve());
                        batchRepository.markCooling(batch.id(), request.commandAt(), toVersion);
                        return new CoolingResponse(request.requestId(), request.batchNo(),
                                request.commandAt(), batch.activeCurveVersion(),
                                toVersion, BatchStatus.COOLING.name());
                    }

                    @Override
                    public Long assignedBatchId() {
                        return batchId;
                    }
                });
    }

    /**
     * 降温完成进入后熟：后熟保温计时从该时刻起，按 -1℃ 保持。
     */
    @Transactional
    public BatchResponse startMaturation(String batchNo, Instant maturingAt) {
        BatchRow batch = batchRepository.requireByBatchNo(batchNo);
        batchRepository.markMaturing(batch.id(), maturingAt);
        BatchRow updated = batchRepository.requireByBatchNo(batchNo);
        return toResponse(updated);
    }

    /**
     * 成熟度综合判定（requestId 幂等）：后熟按 -1℃ 保持不少于 7 天（恰 7 天合法）。
     * 不合格只允许延长后熟一次（保温计时顺延），延长后仍不合格整批进入复审。
     */
    public JudgeResponse judge(JudgeRequest request) {
        String fingerprint = Fingerprints.judge(request);
        return idempotencyExecutor.execute(request.requestId(),
                Fingerprints.TYPE_JUDGE, fingerprint, JudgeResponse.class,
                new IdempotencyExecutor.BusinessCore<>() {

                    private long batchId;

                    @Override
                    public JudgeResponse run() {
                        BatchRow batch = batchRepository.requireByBatchNo(request.batchNo());
                        batchId = batch.id();
                        if (batch.status() != BatchStatus.MATURING) {
                            throw new DomainException(409,
                                    "judgment requires MATURING stage, current " + batch.status());
                        }
                        if (request.judgedAt().isBefore(batch.maturityHoldFrom())) {
                            throw new DomainException(422, "judgment time is before maturation hold start");
                        }
                        Duration hold = Duration.between(batch.maturityHoldFrom(), request.judgedAt());
                        boolean longEnough = DomainRules.meetsMaturityHold(hold);
                        boolean holdingMinusOne = isHoldingMinusOne(batch, request.judgedAt());
                        boolean passed = longEnough && holdingMinusOne;

                        boolean extended = false;
                        BatchStatus statusAfter;
                        String detail;
                        if (passed) {
                            statusAfter = BatchStatus.MATURING;
                            detail = String.format(
                                    "PASS: hold %d hours (>=168) at -1C tolerance, readings in window all in range",
                                    hold.toHours());
                        } else if (!batch.extensionUsed()) {
                            extended = true;
                            statusAfter = BatchStatus.MATURING;
                            detail = String.format(
                                    "FAIL then extend once: hold %d hours, holdingMinusOne=%s; hold clock reset to judgment time",
                                    hold.toHours(), holdingMinusOne);
                            batchRepository.grantMaturityExtension(batch.id(), request.judgedAt());
                        } else {
                            statusAfter = BatchStatus.REVIEW;
                            detail = String.format(
                                    "FAIL after extension: hold %d hours, holdingMinusOne=%s; whole batch sent to review",
                                    hold.toHours(), holdingMinusOne);
                        }
                        batchRepository.applyJudgment(batch.id(), request.judgedAt(),
                                passed, statusAfter);
                        judgmentRepository.insert(batch.id(), request.judgedAt(),
                                passed, extended, detail, request.requestId());
                        return new JudgeResponse(request.requestId(), request.batchNo(),
                                request.judgedAt(), passed, extended, statusAfter.name(), detail);
                    }

                    @Override
                    public Long assignedBatchId() {
                        return batchId;
                    }
                });
    }

    /**
     * 清酒转出（requestId 幂等）：终态；同键同参回放首次结果，同键异参 409。
     * 已转出后换用新 requestId 重复转出返回首次转出时刻的幂等成功。
     */
    public TransferResponse transfer(TransferRequest request) {
        String fingerprint = Fingerprints.transfer(request);
        return idempotencyExecutor.execute(request.requestId(),
                Fingerprints.TYPE_TRANSFER, fingerprint, TransferResponse.class,
                new IdempotencyExecutor.BusinessCore<>() {

                    private long batchId;

                    @Override
                    public TransferResponse run() {
                        BatchRow batch = batchRepository.requireByBatchNo(request.batchNo());
                        batchId = batch.id();
                        if (batch.status() == BatchStatus.TRANSFERRED) {
                            Instant firstAt = transferRepository.findTransferredAt(batch.id())
                                    .orElse(batch.transferredAt());
                            return new TransferResponse(request.requestId(), request.batchNo(),
                                    firstAt, BatchStatus.TRANSFERRED.name(), true);
                        }
                        if (batch.status() != BatchStatus.MATURING
                                || !Boolean.TRUE.equals(batch.maturityPassed())) {
                            throw new DomainException(409,
                                    "transfer requires maturity passed; current status=" + batch.status()
                                            + ", maturityPassed=" + batch.maturityPassed());
                        }
                        transferRepository.insert(batch.id(), request.transferredAt(), request.requestId());
                        batchRepository.markTransferred(batch.id(), request.transferredAt());
                        return new TransferResponse(request.requestId(), request.batchNo(),
                                request.transferredAt(), BatchStatus.TRANSFERRED.name(), false);
                    }

                    @Override
                    public Long assignedBatchId() {
                        return batchId;
                    }
                });
    }

    /**
     * 查询批次当前视图。
     */
    public BatchResponse getBatch(String batchNo) {
        return toResponse(batchRepository.requireByBatchNo(batchNo));
    }

    /**
     * 复算双乙酰链是否满足降温前置条件：末尾相邻两次检测均合格且间隔不少于 12 小时（恰 12 合法）。
     */
    static boolean hasQualifiedPair(List<DiacetylRow> chain) {
        if (chain.size() < 2) {
            return false;
        }
        DiacetylRow previous = chain.get(chain.size() - 2);
        DiacetylRow latest = chain.get(chain.size() - 1);
        return DomainRules.isDiacetylPassing(previous.valueMgL())
                && DomainRules.isDiacetylPassing(latest.valueMgL())
                && DomainRules.meetsSamplingInterval(
                        Duration.between(previous.testedAt(), latest.testedAt()));
    }

    /**
     * 后熟保温窗口内（保温起点至判定时刻，端点含）读数必须全部落在 -1℃±0.5℃ 容差内，且至少一条读数。
     * 恰 0.5℃ 偏差合格，由读数落库时的 out_of_range 口径保证。
     */
    private boolean isHoldingMinusOne(BatchRow batch, Instant judgedAt) {
        var readings = temperatureRepository.findInWindow(
                batch.id(), BatchStatus.MATURING.name(), batch.maturityHoldFrom(), judgedAt);
        if (readings.isEmpty()) {
            return false;
        }
        return readings.stream().allMatch(reading -> !reading.outOfRange());
    }

    private String toJson(Object request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (Exception e) {
            throw new IllegalStateException("failed to snapshot late event", e);
        }
    }

    private static BatchResponse toResponse(BatchRow batch) {
        return new BatchResponse(batch.id(), batch.batchNo(), batch.tankCode(),
                batch.status(), batch.filledAt(), batch.activeCurveVersion());
    }

    /**
     * 校验曲线分段：从 0 小时起、相邻段首尾相接、每段时长为正。
     */
    static void validateCurve(List<SegmentSpec> curve) {
        if (curve == null || curve.isEmpty()) {
            throw new DomainException(422, "curve must contain at least one segment");
        }
        int expectedStart = 0;
        for (SegmentSpec segment : curve) {
            if (segment.fromHour() == null || segment.toHour() == null
                    || segment.targetTemp() == null) {
                throw new DomainException(422, "curve segment fields must not be null");
            }
            if (segment.fromHour() != expectedStart) {
                throw new DomainException(422,
                        "curve segments must be contiguous from hour 0, expected from "
                                + expectedStart + " but got " + segment.fromHour());
            }
            if (segment.toHour() <= segment.fromHour()) {
                throw new DomainException(422, "curve segment must have positive length");
            }
            expectedStart = segment.toHour();
        }
    }
}
