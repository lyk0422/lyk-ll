package com.example.starter.arbitration.service;

import com.example.starter.arbitration.api.CommandRequest;
import com.example.starter.arbitration.api.CommandResponse;
import com.example.starter.arbitration.config.ArbitrationProperties;
import com.example.starter.arbitration.domain.ActiveClaimPo;
import com.example.starter.arbitration.domain.CommandPo;
import com.example.starter.arbitration.domain.CommandStatus;
import com.example.starter.arbitration.domain.ResourcePo;
import com.example.starter.arbitration.repo.ActiveClaimRepository;
import com.example.starter.arbitration.repo.CommandRepository;
import com.example.starter.arbitration.repo.EstopStateRepository;
import com.example.starter.arbitration.repo.ResourceRepository;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 指令受理与校验。
 *
 * <p>校验项（失败原因在 400 响应中可区分）：
 * <ul>
 *   <li>{@code NOT_LEASE_HOLDER}：控制器未持有目标资源的有效租约（409）；</li>
 *   <li>{@code TIMESTAMP_EXPIRED}：时戳早于 now-maxAgeMs，指令过期，不入队；</li>
 *   <li>{@code FUTURE_TIMESTAMP}：时戳超出未来容忍窗口；</li>
 *   <li>{@code CLOCK_ROLLBACK}：时戳小于同控制器同资源上一条指令，时钟回拨，可与过期区分；</li>
 *   <li>{@code SEQUENCE_NOT_MONOTONIC}：序列号未严格递增；</li>
 *   <li>{@code POSITION_OUT_OF_BOUNDS}：目标位置超出资源边界；</li>
 *   <li>{@code VELOCITY_LIMIT_EXCEEDED}：目标速度超过限速；</li>
 *   <li>{@code ACCELERATION_LIMIT_EXCEEDED}：目标加速度超过限加速度；</li>
 *   <li>{@code IMPLIED_VELOCITY_EXCEEDED}/{@code IMPLIED_ACCELERATION_EXCEEDED}：
 *       由相邻指令位移与速度差推算出的速度/加速度超限。</li>
 * </ul>
 * 同参 requestId 重放幂等；异参重放返回 409。
 */
@Service
public class CommandService {

    private final CommandRepository commandRepository;
    private final ActiveClaimRepository activeClaimRepository;
    private final ResourceRepository resourceRepository;
    private final EstopStateRepository estopStateRepository;
    private final ArbitrationProperties properties;
    private final ControllableClock clock;
    private final CommandService self;

    /** 同 controllerId+requestId 的提交在 JVM 内分段串行，保证并发同参重放返回幂等成功。 */
    private final Map<String, Object> submitLocks = new ConcurrentHashMap<>();

    public CommandService(CommandRepository commandRepository,
                          ActiveClaimRepository activeClaimRepository,
                          ResourceRepository resourceRepository,
                          EstopStateRepository estopStateRepository,
                          ArbitrationProperties properties,
                          ControllableClock clock,
                          @Lazy CommandService self) {
        this.commandRepository = commandRepository;
        this.activeClaimRepository = activeClaimRepository;
        this.resourceRepository = resourceRepository;
        this.estopStateRepository = estopStateRepository;
        this.properties = properties;
        this.clock = clock;
        this.self = self;
    }

    /** 提交指令（分段串行 + 事务内校验入队）。 */
    public CommandResponse submit(CommandRequest request) {
        String key = request.controllerId() + "|" + request.requestId();
        Object lock = submitLocks.computeIfAbsent(key, k -> new Object());
        try {
            synchronized (lock) {
                return self.submitInTransaction(request);
            }
        } finally {
            submitLocks.remove(key, lock);
        }
    }

    /** 校验并入队指令；非法/过期/回拨指令一律 4xx，不写入、不进入执行队列。 */
    @Transactional
    public CommandResponse submitInTransaction(CommandRequest request) {
        return doSubmit(request);
    }

    private CommandResponse doSubmit(CommandRequest request) {
        long now = clock.now();
        if (estopStateRepository.get().isActive()) {
            throw ApiException.locked("ESTOP_ACTIVE", "紧急停止生效中，显式恢复前拒绝指令");
        }

        // 幂等：同 controllerId+requestId 已受理过
        Optional<CommandPo> replay =
                commandRepository.findByControllerAndRequest(request.controllerId(), request.requestId());
        if (replay.isPresent()) {
            ensureSameParameters(replay.get(), request);
            CommandPo old = replay.get();
            return new CommandResponse(true, old.getId(), old.getControllerId(), old.getRequestId(),
                    old.getResourceName(), old.getStatus(), old.getEnqueuedAtMs(), true);
        }

        ResourcePo resource = resourceRepository.findByNameForUpdate(request.resourceName())
                .orElseThrow(() -> ApiException.notFound("RESOURCE_NOT_FOUND",
                        "资源不存在: " + request.resourceName()));

        // 持有有效（未到期）写权限租约
        ActiveClaimPo claim = activeClaimRepository.findByResourceForUpdate(request.resourceName())
                .orElseThrow(() -> ApiException.conflict("NOT_LEASE_HOLDER",
                        "控制器 " + request.controllerId() + " 未持有资源 " + request.resourceName()
                                + " 的写权限租约"));
        if (!claim.getControllerId().equals(request.controllerId())) {
            throw ApiException.conflict("NOT_LEASE_HOLDER",
                    "资源 " + request.resourceName() + " 当前由控制器 " + claim.getControllerId() + " 持有");
        }
        if (claim.getExpiresAtMs() <= now) {
            throw ApiException.conflict("LEASE_EXPIRED",
                    "控制器 " + request.controllerId() + " 对资源 " + request.resourceName()
                            + " 的租约已到期，等待安全回收或请重新申请/续期");
        }

        List<String> violations = validate(request, resource, now);
        if (!violations.isEmpty()) {
            // 非法指令不写入、不入队
            throw ApiException.badRequest("COMMAND_REJECTED", violations);
        }

        CommandPo command = new CommandPo();
        command.setControllerId(request.controllerId());
        command.setResourceName(request.resourceName());
        command.setRequestId(request.requestId());
        command.setTimestampMs(request.timestampMs());
        command.setSequence(request.sequence());
        command.setTargetPosition(request.targetPosition());
        command.setTargetVelocity(request.targetVelocity());
        command.setTargetAcceleration(request.targetAcceleration());
        command.setStatus(CommandStatus.PENDING.name());
        command.setEnqueuedAtMs(now);
        long id = commandRepository.insert(command);

        return new CommandResponse(true, id, request.controllerId(), request.requestId(),
                request.resourceName(), CommandStatus.PENDING.name(), now, false);
    }

    private List<String> validate(CommandRequest request, ResourcePo resource, long now) {
        List<String> violations = new ArrayList<>();
        long maxAge = properties.getCommand().getMaxAgeMs();
        long futureTolerance = properties.getCommand().getFutureToleranceMs();

        if (request.timestampMs() < now - maxAge) {
            violations.add("TIMESTAMP_EXPIRED: 指令时戳 " + request.timestampMs()
                    + " 早于过期下界 " + (now - maxAge));
        }
        if (request.timestampMs() > now + futureTolerance) {
            violations.add("FUTURE_TIMESTAMP: 指令时戳 " + request.timestampMs()
                    + " 超出未来容忍上界 " + (now + futureTolerance));
        }

        if (request.targetPosition() < resource.getMinPosition()
                || request.targetPosition() > resource.getMaxPosition()) {
            violations.add("POSITION_OUT_OF_BOUNDS: 目标位置 " + request.targetPosition()
                    + " 超出边界 [" + resource.getMinPosition() + ", " + resource.getMaxPosition() + "]");
        }
        if (Math.abs(request.targetVelocity()) > resource.getMaxVelocity()) {
            violations.add("VELOCITY_LIMIT_EXCEEDED: 目标速度绝对值 " + Math.abs(request.targetVelocity())
                    + " 超过限速 " + resource.getMaxVelocity());
        }
        if (Math.abs(request.targetAcceleration()) > resource.getMaxAcceleration()) {
            violations.add("ACCELERATION_LIMIT_EXCEEDED: 目标加速度绝对值 "
                    + Math.abs(request.targetAcceleration())
                    + " 超过限加速度 " + resource.getMaxAcceleration());
        }

        // 单调性：以同控制器同资源上一条已入队指令为基准
        commandRepository
                .findLatestByControllerResourceForUpdate(
                        request.controllerId(), request.resourceName())
                .ifPresent(prev -> {
                    if (request.timestampMs() < prev.getTimestampMs()) {
                        violations.add("CLOCK_ROLLBACK: 指令时戳 " + request.timestampMs()
                                + " 小于上一条时戳 " + prev.getTimestampMs());
                    }
                    if (request.sequence() <= prev.getSequence()) {
                        violations.add("SEQUENCE_NOT_MONOTONIC: 序列号 " + request.sequence()
                                + " 未严格大于上一条序列号 " + prev.getSequence());
                    }
                    double dtSec = (request.timestampMs() - prev.getTimestampMs()) / 1000.0;
                    if (dtSec > 0) {
                        double impliedVelocity =
                                Math.abs(request.targetPosition() - prev.getTargetPosition()) / dtSec;
                        if (impliedVelocity > resource.getMaxVelocity()) {
                            violations.add("IMPLIED_VELOCITY_EXCEEDED: 相邻指令推算速度 "
                                    + String.format("%.4f", impliedVelocity)
                                    + " rad/s 超过限速 " + resource.getMaxVelocity());
                        }
                        double impliedAcceleration =
                                Math.abs(request.targetVelocity() - prev.getTargetVelocity()) / dtSec;
                        if (impliedAcceleration > resource.getMaxAcceleration()) {
                            violations.add("IMPLIED_ACCELERATION_EXCEEDED: 相邻指令推算加速度 "
                                    + String.format("%.4f", impliedAcceleration)
                                    + " rad/s^2 超过限加速度 " + resource.getMaxAcceleration());
                        }
                    }
                });
        return violations;
    }

    /** 同 requestId 重放但任何指令参数不同：异参重放 409。 */
    private void ensureSameParameters(CommandPo old, CommandRequest request) {
        if (!old.getResourceName().equals(request.resourceName())
                || old.getTimestampMs() != request.timestampMs()
                || old.getSequence() != request.sequence()
                || Double.compare(old.getTargetPosition(), request.targetPosition()) != 0
                || Double.compare(old.getTargetVelocity(), request.targetVelocity()) != 0
                || Double.compare(old.getTargetAcceleration(), request.targetAcceleration()) != 0) {
            throw ApiException.conflict("REQUEST_ID_PARAM_MISMATCH",
                    "requestId " + request.requestId()
                            + " 已用于另一条不同参数的指令，异参重放被拒绝");
        }
    }
}
