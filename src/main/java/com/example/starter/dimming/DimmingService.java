package com.example.starter.dimming;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.dimming.CircuitRepository.CircuitRow;
import com.example.starter.dimming.Dto.AccidentResponse;
import com.example.starter.dimming.Dto.CircuitStateResponse;
import com.example.starter.dimming.Dto.DimCommandRequest;
import com.example.starter.dimming.Dto.DimCommandResponse;
import com.example.starter.dimming.Dto.TelemetryRequest;
import com.example.starter.dimming.Dto.TelemetryResponse;

/**
 * 调光核心服务。规则：
 * 1. 分档调光（MANUAL/TRAFFIC）受稳定时限约束：距上次调整满 stabilityMinutes 方可再调，恰满允许；
 * 2. 洞外亮度低于阈值立即提升一档并进入过渡曲线，恰等阈值不触发；过渡期间禁止下调；
 * 3. 车流量按滚动窗口统计，恰为0时降至保底档位（属分档调光，受稳定时限约束）；
 * 4. 事故锁定期间普通调光请求一律拒绝；解除须现场确认；锁定与解除同事务留痕。
 */
@Service
public class DimmingService {

    private final CircuitRepository circuitRepository;
    private final DimmingCommandRepository commandRepository;
    private final TrafficSampleRepository sampleRepository;
    private final AccidentLockRepository accidentLockRepository;
    private final DimmingProperties properties;
    private final Clock clock;

    public DimmingService(CircuitRepository circuitRepository,
            DimmingCommandRepository commandRepository,
            TrafficSampleRepository sampleRepository,
            AccidentLockRepository accidentLockRepository,
            DimmingProperties properties,
            Clock clock) {
        this.circuitRepository = circuitRepository;
        this.commandRepository = commandRepository;
        this.sampleRepository = sampleRepository;
        this.accidentLockRepository = accidentLockRepository;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 分档调光指令。亮度应急提升（BRIGHTNESS）与事故（ACCIDENT）来源不受稳定时限约束。
     */
    @Transactional
    public DimCommandResponse dim(String circuitCode, DimCommandRequest request) {
        String source = request.source().toUpperCase();
        if (!List.of("MANUAL", "BRIGHTNESS", "TRAFFIC").contains(source)) {
            throw ApiException.badRequest("INVALID_SOURCE", "不支持的触发来源: " + request.source());
        }
        CircuitRow circuit = lockCircuit(circuitCode);
        if (circuit.locked()) {
            throw ApiException.conflict("CIRCUIT_LOCKED",
                    "回路处于事故锁定，普通调光请求不得覆盖: " + circuitCode);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        int from = circuit.currentLevel();
        int target = request.targetLevel();
        checkLevelRange(target);

        if ("MANUAL".equals(source) || "TRAFFIC".equals(source)) {
            checkStability(circuit, now);
        }
        if (target < from) {
            checkTransition(circuit, now);
        }

        return applyDim(circuit, request.requestId(), source, from, target, now);
    }

    /**
     * 合成检测数据评估：亮度低于阈值立即提升一档（恰等不触发）；
     * 否则按5分钟滚动窗口统计车流量，恰为0时降至保底档位。
     */
    @Transactional
    public TelemetryResponse evaluateTelemetry(String circuitCode, TelemetryRequest request) {
        CircuitRow circuit = lockCircuit(circuitCode);
        LocalDateTime now = LocalDateTime.now(clock);
        sampleRepository.insert(circuitCode, request.brightness(), request.trafficFlow(), now);

        if (circuit.locked()) {
            return new TelemetryResponse(request.requestId(), circuitCode, "LOCKED_SKIP",
                    "事故锁定中，自动调光被抑制", circuit.currentLevel(), true);
        }

        // 规则2：恰等阈值不触发，严格小于才提升
        if (request.brightness() < properties.getBrightnessThresholdLux()) {
            int from = circuit.currentLevel();
            int target = Math.min(from + 1, properties.getMaxLevel());
            if (target == from) {
                return new TelemetryResponse(request.requestId(), circuitCode, "NONE",
                        "已是最高档位", from, false);
            }
            DimCommandResponse applied = applyDim(circuit, request.requestId(), "BRIGHTNESS",
                    from, target, now);
            return new TelemetryResponse(request.requestId(), circuitCode, "BRIGHTNESS_RAISE",
                    "洞外亮度低于阈值，提升一档", applied.toLevel(), false);
        }

        // 规则3：车流量滚动窗口内恰为0时降至保底档位
        LocalDateTime windowStart = now.minusMinutes(properties.getTrafficWindowMinutes());
        List<Integer> samples = sampleRepository.findTrafficBetween(circuitCode, windowStart, now);
        int total = samples.stream().mapToInt(Integer::intValue).sum();
        if (!samples.isEmpty() && total == 0 && circuit.currentLevel() > properties.getFloorLevel()) {
            try {
                checkStability(circuit, now);
            } catch (ApiException stability) {
                return new TelemetryResponse(request.requestId(), circuitCode, "NONE",
                        "稳定时限未满，保底调光暂缓: " + stability.getMessage(),
                        circuit.currentLevel(), false);
            }
            checkTransition(circuit, now);
            DimCommandResponse applied = applyDim(circuit, request.requestId(), "TRAFFIC",
                    circuit.currentLevel(), properties.getFloorLevel(), now);
            return new TelemetryResponse(request.requestId(), circuitCode, "TRAFFIC_FLOOR",
                    "滚动窗口车流量为0，降至保底亮度", applied.toLevel(), false);
        }

        return new TelemetryResponse(request.requestId(), circuitCode, "NONE",
                "无需调整", circuit.currentLevel(), false);
    }

    /**
     * 事故告警：锁定在事故模式（事故档位），锁定与留痕在同一事务内完成。
     */
    @Transactional
    public AccidentResponse lockAccident(String circuitCode, String requestId, String alarmId,
            String detail) {
        CircuitRow circuit = lockCircuit(circuitCode);
        if (circuit.locked()) {
            throw ApiException.conflict("ALREADY_LOCKED", "回路已处于事故锁定: " + circuitCode);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        int from = circuit.currentLevel();
        int target = properties.getAccidentLevel();

        circuitRepository.updateLocked(circuitCode, true);
        if (target != from) {
            applyDim(circuit, requestId, "ACCIDENT", from, target, now);
        }
        accidentLockRepository.insert(circuitCode, "LOCK", alarmId, requestId, detail, now);
        return new AccidentResponse(requestId, circuitCode, "LOCK", true, target, now);
    }

    /**
     * 解除事故锁定：须现场确认（onSiteConfirmed=true），解除与留痕在同一事务内完成。
     */
    @Transactional
    public AccidentResponse unlockAccident(String circuitCode, String requestId,
            boolean onSiteConfirmed, String detail) {
        if (!onSiteConfirmed) {
            throw ApiException.badRequest("ONSITE_CONFIRM_REQUIRED",
                    "解除事故锁定须现场确认（onSiteConfirmed=true）");
        }
        CircuitRow circuit = lockCircuit(circuitCode);
        if (!circuit.locked()) {
            throw ApiException.conflict("NOT_LOCKED", "回路未处于事故锁定: " + circuitCode);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        circuitRepository.updateLocked(circuitCode, false);
        accidentLockRepository.insert(circuitCode, "UNLOCK", null, requestId, detail, now);
        return new AccidentResponse(requestId, circuitCode, "UNLOCK", false,
                circuit.currentLevel(), now);
    }

    @Transactional(readOnly = true)
    public CircuitStateResponse getState(String circuitCode) {
        CircuitRow circuit = circuitRepository.findByCode(circuitCode)
                .orElseThrow(() -> ApiException.notFound("回路不存在: " + circuitCode));
        return new CircuitStateResponse(circuit.code(), circuit.name(), circuit.currentLevel(),
                circuit.locked(), circuit.lastAdjustedAt(), circuit.transitionUntil());
    }

    private CircuitRow lockCircuit(String circuitCode) {
        return circuitRepository.findByCodeForUpdate(circuitCode)
                .orElseThrow(() -> ApiException.notFound("回路不存在: " + circuitCode));
    }

    private void checkLevelRange(int level) {
        if (level < properties.getMinLevel() || level > properties.getMaxLevel()) {
            throw ApiException.badRequest("INVALID_LEVEL",
                    "档位须在 " + properties.getMinLevel() + "-" + properties.getMaxLevel()
                            + " 之间: " + level);
        }
    }

    /** 稳定时限：距上次调整满 stabilityMinutes 方可再调，恰满允许 */
    private void checkStability(CircuitRow circuit, LocalDateTime now) {
        if (circuit.lastAdjustedAt() == null) {
            return;
        }
        LocalDateTime eligible = circuit.lastAdjustedAt()
                .plus(Duration.ofMinutes(properties.getStabilityMinutes()));
        if (now.isBefore(eligible)) {
            throw ApiException.conflict("STABILITY_NOT_ELAPSED",
                    "距上次调整未满 " + properties.getStabilityMinutes() + " 分钟，"
                            + eligible + " 后方可再调");
        }
    }

    /** 过渡期间禁止下调 */
    private void checkTransition(CircuitRow circuit, LocalDateTime now) {
        if (circuit.transitionUntil() != null && now.isBefore(circuit.transitionUntil())) {
            throw ApiException.conflict("TRANSITION_IN_PROGRESS",
                    "过渡曲线执行中，禁止下调至 " + circuit.transitionUntil());
        }
    }

    /** 应用调光：写指令（执行中→完成）、更新回路、写留痕；目标档与当前相同则为无操作 */
    private DimCommandResponse applyDim(CircuitRow circuit, String requestId, String source,
            int from, int target, LocalDateTime now) {
        if (target == from) {
            return new DimCommandResponse(requestId, circuit.code(), source, from, target,
                    false, now);
        }
        long commandId = commandRepository.insertExecuting(requestId, circuit.code(), source,
                from, target, now);
        LocalDateTime transitionUntil = "BRIGHTNESS".equals(source)
                ? now.plusMinutes(properties.getTransitionMinutes())
                : null;
        circuitRepository.updateLevel(circuit.code(), target, now, transitionUntil);
        commandRepository.insertRecord(circuit.code(), commandId, from, target, source, now);
        commandRepository.complete(commandId, now);
        return new DimCommandResponse(requestId, circuit.code(), source, from, target, true, now);
    }
}
