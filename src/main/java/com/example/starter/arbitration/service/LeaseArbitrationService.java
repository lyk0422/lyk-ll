package com.example.starter.arbitration.service;

import com.example.starter.arbitration.api.AcquireLeaseRequest;
import com.example.starter.arbitration.api.LeaseResponse;
import com.example.starter.arbitration.api.ReleaseLeaseRequest;
import com.example.starter.arbitration.api.RenewLeaseRequest;
import com.example.starter.arbitration.config.ArbitrationProperties;
import com.example.starter.arbitration.domain.ActiveClaimPo;
import com.example.starter.arbitration.domain.LeasePo;
import com.example.starter.arbitration.repo.ActiveClaimRepository;
import com.example.starter.arbitration.repo.CommandRepository;
import com.example.starter.arbitration.repo.ControllerRepository;
import com.example.starter.arbitration.repo.EstopStateRepository;
import com.example.starter.arbitration.repo.LeaseRepository;
import com.example.starter.arbitration.repo.ResourceRepository;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 租约仲裁：申请（含抢占规则）、续期、释放。
 *
 * <p>抢占规则（在单事务内对每个资源行加锁判定）：
 * <ul>
 *   <li>资源空闲：直接授权；</li>
 *   <li>被本控制器以另一个 requestId 持有：409；</li>
 *   <li>被其他控制器持有：申请优先级严格高于持有方时抢占，否则 409；同级不可抢占。</li>
 * </ul>
 * 同参（controllerId+requestId+优先级+TTL）重复申请幂等回放；requestId 相同但参数不同返回 409。
 */
@Service
public class LeaseArbitrationService {

    private final LeaseRepository leaseRepository;
    private final ActiveClaimRepository activeClaimRepository;
    private final ResourceRepository resourceRepository;
    private final ControllerRepository controllerRepository;
    private final CommandRepository commandRepository;
    private final EstopStateRepository estopStateRepository;
    private final ArbitrationProperties properties;
    private final ControllableClock clock;
    private final LeaseArbitrationService self;

    /** 同 controllerId+requestId 的申请在 JVM 内分段串行，保证并发同参重放落入幂等分支。 */
    private final Map<String, Object> acquireLocks = new ConcurrentHashMap<>();

    public LeaseArbitrationService(LeaseRepository leaseRepository,
                                   ActiveClaimRepository activeClaimRepository,
                                   ResourceRepository resourceRepository,
                                   ControllerRepository controllerRepository,
                                   CommandRepository commandRepository,
                                   EstopStateRepository estopStateRepository,
                                   ArbitrationProperties properties,
                                   ControllableClock clock,
                                   @Lazy LeaseArbitrationService self) {
        this.leaseRepository = leaseRepository;
        this.activeClaimRepository = activeClaimRepository;
        this.resourceRepository = resourceRepository;
        this.controllerRepository = controllerRepository;
        this.commandRepository = commandRepository;
        this.estopStateRepository = estopStateRepository;
        this.properties = properties;
        this.clock = clock;
        this.self = self;
    }

    /** 申请资源写权限（分段串行 + 事务内仲裁）；多资源申请整体原子。 */
    public LeaseResponse acquire(AcquireLeaseRequest request) {
        Object lock = acquireLocks.computeIfAbsent(
                request.controllerId() + "|" + request.requestId(), k -> new Object());
        try {
            synchronized (lock) {
                return self.acquireInTransaction(request);
            }
        } finally {
            acquireLocks.remove(request.controllerId() + "|" + request.requestId(), lock);
        }
    }

    /** 事务内执行单轮申请仲裁；并发争用导致唯一键冲突时由外层重试后落入幂等/抢占判定。 */
    @Transactional
    public LeaseResponse acquireInTransaction(AcquireLeaseRequest request) {
        return doAcquire(request);
    }

    private LeaseResponse doAcquire(AcquireLeaseRequest request) {
        long now = clock.now();
        if (estopStateRepository.getForUpdate().isActive()) {
            throw ApiException.locked("ESTOP_ACTIVE", "紧急停止生效中，显式恢复前拒绝普通申请");
        }

        List<String> resources = new ArrayList<>(new LinkedHashSet<>(request.resources()));
        for (String name : resources) {
            if (resourceRepository.findByName(name).isEmpty()) {
                throw ApiException.notFound("RESOURCE_NOT_FOUND", "资源不存在: " + name);
            }
        }
        // 申请即视为一次控制器活动，刷新心跳
        controllerRepository.heartbeat(request.controllerId(), now);

        long ttl = request.ttlMs() != null ? request.ttlMs() : properties.getLease().getTtlMs();
        long expiresAt = now + ttl;

        List<LeasePo> replayLeases = new ArrayList<>();
        List<String> preemptedResources = new ArrayList<>();
        List<LeasePo> newLeases = new ArrayList<>();

        for (String resourceName : resources) {
            Optional<LeasePo> existing =
                    leaseRepository.find(request.controllerId(), request.requestId(), resourceName);
            if (existing.isPresent()) {
                LeasePo old = existing.get();
                ensureSameParameters(old, request, ttl);
                replayLeases.add(old);
                continue;
            }

            // 锁定当前占用后判定抢占
            Optional<ActiveClaimPo> holder = activeClaimRepository.findByResourceForUpdate(resourceName);
            if (holder.isPresent()) {
                ActiveClaimPo current = holder.get();
                if (current.getControllerId().equals(request.controllerId())) {
                    throw ApiException.conflict("RESOURCE_HELD_BY_SELF_OTHER_REQUEST",
                            "资源 " + resourceName + " 已被本控制器另一申请 " + current.getRequestId() + " 持有");
                }
                if (request.priority() <= current.getPriority()) {
                    throw ApiException.conflict("PREEMPTION_DENIED",
                            "资源 " + resourceName + " 被控制器 " + current.getControllerId()
                                    + " 以优先级 " + current.getPriority() + " 持有，申请优先级 "
                                    + request.priority() + " 不能抢占（需严格更高优先级，同级不可抢占）");
                }
                // 高优先级抢占：CAS 撤销旧租约并删除占用
                int revoked = leaseRepository.deactivateIfActive(current.getLeaseId(), "PREEMPTED");
                if (revoked == 0) {
                    // 并发下旧租约恰好刚失效：占用理应已被清理，冲突交由主键保护
                    throw ApiException.conflict("RESOURCE_CONTENTION",
                            "资源 " + resourceName + " 正被并发争用，请重试");
                }
                activeClaimRepository.deleteByLeaseId(current.getLeaseId());
                // 旧持有者失去写权限：取消其对该资源的待执行指令，防止抢占后旧指令继续执行
                commandRepository.cancelPendingByHolder(current.getControllerId(), resourceName,
                        "PREEMPTED", now);
                preemptedResources.add(resourceName);
            }

            LeasePo lease = new LeasePo();
            lease.setControllerId(request.controllerId());
            lease.setResourceName(resourceName);
            lease.setRequestId(request.requestId());
            lease.setPriority(request.priority());
            lease.setAcquiredAtMs(now);
            lease.setExpiresAtMs(expiresAt);
            lease.setActive(true);
            long leaseId = leaseRepository.insert(lease);
            lease.setId(leaseId);
            newLeases.add(lease);

            ActiveClaimPo claim = new ActiveClaimPo();
            claim.setResourceName(resourceName);
            claim.setLeaseId(leaseId);
            claim.setControllerId(request.controllerId());
            claim.setRequestId(request.requestId());
            claim.setPriority(request.priority());
            claim.setAcquiredAtMs(now);
            claim.setExpiresAtMs(expiresAt);
            try {
                activeClaimRepository.insert(claim);
            } catch (org.springframework.dao.DuplicateKeyException concurrentContention) {
                // 并发争用且行锁释放窗口内对手先写入：统一按冲突处理，事务回滚
                throw ApiException.conflict("RESOURCE_CONTENTION",
                        "资源 " + resourceName + " 正被并发争用，请重试");
            }
        }

        // 同参重放：以现存租约状态构造幂等响应
        if (!replayLeases.isEmpty() && newLeases.isEmpty()) {
            return buildReplayResponse(request, replayLeases);
        }
        // 混合情形理论上不会出现（申请整体原子），兜底按新授权处理
        return new LeaseResponse(true, request.controllerId(), request.requestId(),
                resources, request.priority(), now, expiresAt,
                !preemptedResources.isEmpty(), preemptedResources, false);
    }

    /** 续期：对该 requestId 名下全部仍有效租约延长有效期，重复调用幂等成功。 */
    @Transactional
    public LeaseResponse renew(RenewLeaseRequest request) {
        long now = clock.now();
        long ttl = request.ttlMs() != null ? request.ttlMs() : properties.getLease().getTtlMs();
        long newExpiresAt = now + ttl;

        List<LeasePo> leases = leaseRepository.findActiveByController(request.controllerId()).stream()
                .filter(l -> l.getRequestId().equals(request.requestId()))
                .toList();
        if (leases.isEmpty()) {
            throw ApiException.notFound("LEASE_NOT_FOUND",
                    "控制器 " + request.controllerId() + " 的申请 " + request.requestId()
                            + " 没有有效租约（不存在、已释放、被抢占或已到期）");
        }
        controllerRepository.heartbeat(request.controllerId(), now);
        List<String> resources = new ArrayList<>();
        int priority = leases.get(0).getPriority();
        for (LeasePo lease : leases) {
            leaseRepository.renewIfActive(lease.getId(), newExpiresAt);
            activeClaimRepository.renew(lease.getId(), newExpiresAt);
            resources.add(lease.getResourceName());
        }
        return new LeaseResponse(true, request.controllerId(), request.requestId(), resources,
                priority, now, newExpiresAt, false, List.of(), false);
    }

    /** 释放：撤销该 requestId 名下全部有效租约；重复释放同一 requestId 必须幂等。 */
    @Transactional
    public LeaseResponse release(ReleaseLeaseRequest request) {
        List<LeasePo> active = leaseRepository.findAllActive().stream()
                .filter(l -> l.getControllerId().equals(request.controllerId())
                        && l.getRequestId().equals(request.requestId()))
                .toList();
        List<String> resources = new ArrayList<>();
        long expiresAt = 0L;
        for (LeasePo lease : active) {
            if (leaseRepository.deactivateIfActive(lease.getId(), "RELEASED") > 0) {
                activeClaimRepository.deleteByLeaseId(lease.getId());
            }
            resources.add(lease.getResourceName());
            expiresAt = lease.getExpiresAtMs();
        }
        if (active.isEmpty()) {
            List<LeasePo> historical =
                    leaseRepository.findByControllerAndRequest(request.controllerId(), request.requestId());
            if (historical.isEmpty()) {
                throw ApiException.notFound("LEASE_NOT_FOUND",
                        "控制器 " + request.controllerId() + " 的申请 " + request.requestId() + " 不存在");
            }
            // 已释放/失效后的重复释放：幂等成功
            resources.addAll(historical.stream().map(LeasePo::getResourceName).distinct().toList());
            return new LeaseResponse(false, request.controllerId(), request.requestId(), resources,
                    0, 0L, 0L, false, List.of(), true);
        }
        return new LeaseResponse(false, request.controllerId(), request.requestId(), resources,
                active.get(0).getPriority(), active.get(0).getAcquiredAtMs(), expiresAt,
                false, List.of(), false);
    }

    /** 校验同参重放：优先级或 TTL 不一致即为异参重放，返回 409。 */
    private void ensureSameParameters(LeasePo old, AcquireLeaseRequest request, long requestedTtl) {
        if (old.getPriority() != request.priority()) {
            throw ApiException.conflict("REQUEST_ID_PARAM_MISMATCH",
                    "requestId " + request.requestId() + " 已用于优先级 " + old.getPriority()
                            + " 的申请，本次优先级 " + request.priority() + " 为异参重放");
        }
        long oldTtl = old.getExpiresAtMs() - old.getAcquiredAtMs();
        if (oldTtl != requestedTtl) {
            throw ApiException.conflict("REQUEST_ID_PARAM_MISMATCH",
                    "requestId " + request.requestId() + " 已用于 TTL=" + oldTtl
                            + "ms 的申请，本次 TTL=" + requestedTtl + "ms 为异参重放");
        }
    }

    private LeaseResponse buildReplayResponse(AcquireLeaseRequest request, List<LeasePo> replayLeases) {
        List<String> resources = new ArrayList<>();
        long expiresAt = Long.MAX_VALUE;
        long acquiredAt = 0L;
        boolean allGranted = true;
        for (LeasePo lease : replayLeases) {
            resources.add(lease.getResourceName());
            Optional<ActiveClaimPo> claim = activeClaimRepository.findByResource(lease.getResourceName());
            boolean held = claim.isPresent() && claim.get().getLeaseId() == lease.getId();
            allGranted &= held;
            if (held) {
                expiresAt = Math.min(expiresAt, claim.get().getExpiresAtMs());
            }
            acquiredAt = lease.getAcquiredAtMs();
        }
        if (expiresAt == Long.MAX_VALUE) {
            expiresAt = replayLeases.get(0).getExpiresAtMs();
        }
        return new LeaseResponse(allGranted, request.controllerId(), request.requestId(), resources,
                request.priority(), acquiredAt, expiresAt, false, List.of(), true);
    }
}
