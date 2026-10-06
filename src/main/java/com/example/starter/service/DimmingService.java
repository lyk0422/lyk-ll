package com.example.starter.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Function;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.domain.Circuit;
import com.example.starter.domain.CircuitStatus;
import com.example.starter.domain.DimSource;
import com.example.starter.dto.AccidentLockRequest;
import com.example.starter.dto.AccidentUnlockRequest;
import com.example.starter.dto.BrightnessRequest;
import com.example.starter.dto.DimRequest;
import com.example.starter.dto.DimResult;
import com.example.starter.dto.TrafficRequest;
import com.example.starter.exception.BizException;
import com.example.starter.repo.AccidentRepository;
import com.example.starter.repo.CircuitRepository;
import com.example.starter.repo.CommandRepository;
import com.example.starter.repo.RecordRepository;
import com.example.starter.repo.TrafficRepository;
import com.example.starter.repo.WriteRequestRepository;
import com.example.starter.util.Fingerprints;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 调光核心服务。规则要点：
 * <ul>
 *   <li>普通调光（人工/车流量）单次调整后须稳定 10 分钟方可再调，恰满 10 分钟允许；</li>
 *   <li>洞外亮度低于合成阈值立即提升一档（不受稳定期限制）并按过渡曲线执行，恰等阈值不触发，过渡期间禁止下调；</li>
 *   <li>车流量恰等为 0 时降至保底亮度；</li>
 *   <li>事故锁定期间普通调光请求不得覆盖锁定值，解除须现场确认，锁定与解除在同一事务内留痕；</li>
 *   <li>写操作按 requestId 幂等，同键同参返回首次结果，同键异参 409。</li>
 * </ul>
 */
@Service
public class DimmingService {

    /** 稳定时限：单次调整后 10 分钟内禁止普通调光，恰满 10 分钟允许。 */
    public static final Duration STABILITY = Duration.ofMinutes(10);

    /** 亮度触发升档后的过渡曲线时长（大于稳定时限，过渡期间禁止下调）。 */
    public static final Duration TRANSITION = Duration.ofMinutes(15);

    private final CircuitRepository circuitRepo;
    private final CommandRepository commandRepo;
    private final RecordRepository recordRepo;
    private final AccidentRepository accidentRepo;
    private final TrafficRepository trafficRepo;
    private final WriteRequestRepository writeRequestRepo;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public DimmingService(CircuitRepository circuitRepo, CommandRepository commandRepo,
                          RecordRepository recordRepo, AccidentRepository accidentRepo,
                          TrafficRepository trafficRepo, WriteRequestRepository writeRequestRepo,
                          ObjectMapper objectMapper, Clock clock) {
        this.circuitRepo = circuitRepo;
        this.commandRepo = commandRepo;
        this.recordRepo = recordRepo;
        this.accidentRepo = accidentRepo;
        this.trafficRepo = trafficRepo;
        this.writeRequestRepo = writeRequestRepo;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** 人工调光。 */
    @Transactional
    public DimResult dimManually(long circuitId, DimRequest req) {
        String fingerprint = Fingerprints.of(req.requestId(), "MANUAL", circuitId, req.targetLevel());
        return executeIdempotent(circuitId, req.requestId(), fingerprint,
                circuit -> executeDim(circuit, DimSource.MANUAL, req.targetLevel(), req.requestId(), false, null));
    }

    /** 洞外亮度检测：低于合成阈值立即升一档并按过渡曲线执行，恰等阈值不触发。 */
    @Transactional
    public DimResult checkBrightness(long circuitId, BrightnessRequest req) {
        String fingerprint = Fingerprints.of(req.requestId(), "BRIGHTNESS", circuitId, req.brightness());
        return executeIdempotent(circuitId, req.requestId(), fingerprint, circuit -> {
            if (req.brightness() >= circuit.brightnessThreshold()) {
                return notTriggered(req.requestId(), circuitId, DimSource.BRIGHTNESS,
                        "洞外亮度未低于合成阈值（恰等阈值不触发）");
            }
            if (circuit.status() == CircuitStatus.ACCIDENT) {
                throw BizException.conflict("ACCIDENT_LOCKED", "回路处于事故锁定状态，普通调光请求不得覆盖锁定值");
            }
            int target = Math.min(circuit.currentLevel() + 1, circuit.maxLevel());
            if (target == circuit.currentLevel()) {
                return notTriggered(req.requestId(), circuitId, DimSource.BRIGHTNESS, "已处于最高档位");
            }
            Instant transitionUntil = clock.instant().plus(TRANSITION);
            return executeDim(circuit, DimSource.BRIGHTNESS, target, req.requestId(), true, transitionUntil);
        });
    }

    /** 车流量合成检测数据上报（5 分钟滚动更新）：恰等为 0 时降至保底亮度。 */
    @Transactional
    public DimResult trafficUpdate(long circuitId, TrafficRequest req) {
        String fingerprint = Fingerprints.of(req.requestId(), "TRAFFIC", circuitId, req.flow(), req.sampledAt());
        return executeIdempotent(circuitId, req.requestId(), fingerprint, circuit -> {
            Instant now = clock.instant();
            trafficRepo.insert(circuitId, req.flow(), req.sampledAt() != null ? req.sampledAt() : now, now);
            if (req.flow() != 0) {
                return notTriggered(req.requestId(), circuitId, DimSource.TRAFFIC, "车流量非零，维持当前档位");
            }
            try {
                return executeDim(circuit, DimSource.TRAFFIC, circuit.baseLevel(), req.requestId(), false, null);
            } catch (BizException e) {
                // 稳定期未满或过渡期内：本次滚动更新仅记录采样，不调光
                if ("STABILITY_NOT_MET".equals(e.code()) || "TRANSITION_NO_DOWNGRADE".equals(e.code())) {
                    return notTriggered(req.requestId(), circuitId, DimSource.TRAFFIC,
                            "车流量为 0，但" + e.getMessage());
                }
                throw e;
            }
        });
    }

    /** 事故告警：调光锁定在事故模式（最高档），锁定与留痕在同一事务内完成。 */
    @Transactional
    public DimResult accidentLock(long circuitId, AccidentLockRequest req) {
        String fingerprint = Fingerprints.of(req.requestId(), "ACCIDENT_LOCK", circuitId, req.operator());
        return executeIdempotent(circuitId, req.requestId(), fingerprint, circuit -> {
            if (circuit.status() == CircuitStatus.ACCIDENT) {
                throw BizException.conflict("ALREADY_LOCKED", "回路已处于事故锁定状态");
            }
            Instant now = clock.instant();
            int target = circuit.maxLevel();
            DimResult result;
            if (target == circuit.currentLevel()) {
                result = new DimResult(req.requestId(), circuitId, DimSource.ACCIDENT.name(),
                        circuit.currentLevel(), target, DimResult.STATUS_EXECUTED,
                        "事故锁定，档位已为最高档", now, false);
            } else {
                result = executeDim(circuit, DimSource.ACCIDENT, target, req.requestId(), true, null);
            }
            circuitRepo.applyAccidentLock(circuitId, target);
            accidentRepo.insert(circuitId, "LOCK", target, req.operator(), req.requestId(), now);
            return result;
        });
    }

    /** 事故解除：须现场确认，解除与留痕在同一事务内完成。 */
    @Transactional
    public DimResult accidentUnlock(long circuitId, AccidentUnlockRequest req) {
        String fingerprint = Fingerprints.of(req.requestId(), "ACCIDENT_UNLOCK", circuitId, req.confirmBy());
        return executeIdempotent(circuitId, req.requestId(), fingerprint, circuit -> {
            if (req.confirmBy() == null || req.confirmBy().isBlank()) {
                throw BizException.badRequest("CONFIRM_REQUIRED", "解除事故锁定须现场确认人");
            }
            if (circuit.status() != CircuitStatus.ACCIDENT) {
                throw BizException.conflict("NOT_LOCKED", "回路未处于事故锁定状态");
            }
            Instant now = clock.instant();
            circuitRepo.applyAccidentUnlock(circuitId);
            accidentRepo.insert(circuitId, "UNLOCK", null, req.confirmBy(), req.requestId(), now);
            return new DimResult(req.requestId(), circuitId, DimSource.ACCIDENT.name(),
                    circuit.currentLevel(), circuit.currentLevel(), DimResult.STATUS_EXECUTED,
                    "事故解除，恢复普通调光", now, false);
        });
    }

    /** 查询回路当前状态。 */
    @Transactional(readOnly = true)
    public Circuit getCircuit(long circuitId) {
        return circuitRepo.findById(circuitId)
                .orElseThrow(() -> BizException.notFound("回路不存在: " + circuitId));
    }

    /**
     * 幂等执行骨架：先查登记表；命中则同参重放、异参 409；
     * 未命中则对回路加行锁并复查（防并发同键双执行），执行后登记首次结果。
     */
    private DimResult executeIdempotent(long circuitId, String requestId, String fingerprint,
                                        Function<Circuit, DimResult> business) {
        var existing = writeRequestRepo.findByRequestId(requestId);
        if (existing.isPresent()) {
            return replay(existing.get(), fingerprint);
        }
        Circuit circuit = circuitRepo.findByIdForUpdate(circuitId)
                .orElseThrow(() -> BizException.notFound("回路不存在: " + circuitId));
        var recheck = writeRequestRepo.findByRequestId(requestId);
        if (recheck.isPresent()) {
            return replay(recheck.get(), fingerprint);
        }
        DimResult result = business.apply(circuit);
        try {
            writeRequestRepo.insert(requestId, fingerprint, toJson(result), clock.instant());
        } catch (DuplicateKeyException e) {
            // 唯一键兜底：并发同键以先登记者为准
            var winner = writeRequestRepo.findByRequestId(requestId)
                    .orElseThrow(() -> new IllegalStateException("幂等登记丢失: " + requestId, e));
            return replay(winner, fingerprint);
        }
        return result;
    }

    /**
     * 执行一次调光：校验事故锁定、稳定时限与过渡曲线，写指令与留痕并更新回路档位。
     */
    private DimResult executeDim(Circuit circuit, DimSource source, int targetLevel, String requestId,
                                 boolean bypassStability, Instant transitionUntil) {
        Instant now = clock.instant();
        if (circuit.status() == CircuitStatus.ACCIDENT && source != DimSource.ACCIDENT) {
            throw BizException.conflict("ACCIDENT_LOCKED", "回路处于事故锁定状态，普通调光请求不得覆盖锁定值");
        }
        if (targetLevel == circuit.currentLevel()) {
            return notTriggered(requestId, circuit.id(), source, "目标档位与当前档位一致");
        }
        if (!bypassStability && circuit.lastAdjustedAt() != null
                && now.isBefore(circuit.lastAdjustedAt().plus(STABILITY))) {
            throw BizException.conflict("STABILITY_NOT_MET", "距上次调光未满 10 分钟稳定时限");
        }
        if (targetLevel < circuit.currentLevel() && circuit.transitionUntil() != null
                && now.isBefore(circuit.transitionUntil())) {
            throw BizException.conflict("TRANSITION_NO_DOWNGRADE", "过渡曲线执行期间禁止下调");
        }
        long commandId = commandRepo.insertExecuting(requestId, circuit.id(), source.name(), targetLevel, now);
        circuitRepo.applyDim(circuit.id(), targetLevel, now, transitionUntil);
        recordRepo.insert(circuit.id(), commandId, circuit.currentLevel(), targetLevel, source.name(), now);
        commandRepo.markExecuted(commandId, circuit.currentLevel(), "调光成功");
        return new DimResult(requestId, circuit.id(), source.name(), circuit.currentLevel(), targetLevel,
                DimResult.STATUS_EXECUTED, "调光成功", now, false);
    }

    private DimResult notTriggered(String requestId, long circuitId, DimSource source, String message) {
        return new DimResult(requestId, circuitId, source.name(), null, null,
                DimResult.STATUS_NOT_TRIGGERED, message, clock.instant(), false);
    }

    private DimResult replay(WriteRequestRepository.WriteRequest registered, String fingerprint) {
        if (!registered.fingerprint().equals(fingerprint)) {
            throw BizException.conflict("IDEMPOTENCY_CONFLICT", "同一 requestId 的业务参数不一致");
        }
        return fromJson(registered.resultJson()).withReplayed(true);
    }

    private String toJson(DimResult result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("调光结果序列化失败", e);
        }
    }

    private DimResult fromJson(String json) {
        try {
            return objectMapper.readValue(json, DimResult.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("幂等结果反序列化失败", e);
        }
    }
}
