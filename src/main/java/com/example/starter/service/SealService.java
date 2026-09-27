package com.example.starter.service;

import com.example.starter.domain.UnsealOrder;
import com.example.starter.domain.UnsealQuotaUsage;
import com.example.starter.domain.Valve;
import com.example.starter.domain.ValveSeal;
import com.example.starter.repository.UnsealOrderRepository;
import com.example.starter.repository.UnsealQuotaUsageRepository;
import com.example.starter.repository.ValveRepository;
import com.example.starter.repository.ValveSealRepository;
import com.example.starter.repository.ValveVersionRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * 铅封管理：登记、临时解封批准、撤销、复封与额度轨迹查询。
 * 锁顺序约定：station → valve → unseal，所有铅封/解封变更均在阀门行锁内串行。
 */
@Service
public class SealService {

    /** 临时解封单的调节额度上限：最多两次。 */
    public static final int UNSEAL_QUOTA_TOTAL = 2;

    private final ValveRepository valveRepository;
    private final ValveSealRepository sealRepository;
    private final UnsealOrderRepository unsealRepository;
    private final UnsealQuotaUsageRepository usageRepository;
    private final ValveVersionRepository versionRepository;
    private final Clock clock;

    public SealService(ValveRepository valveRepository,
                       ValveSealRepository sealRepository,
                       UnsealOrderRepository unsealRepository,
                       UnsealQuotaUsageRepository usageRepository,
                       ValveVersionRepository versionRepository,
                       Clock clock) {
        this.valveRepository = valveRepository;
        this.sealRepository = sealRepository;
        this.unsealRepository = unsealRepository;
        this.usageRepository = usageRepository;
        this.versionRepository = versionRepository;
        this.clock = clock;
    }

    /**
     * 铅封登记：登记唯一铅封号、当前开度版本与封存时刻，版本从 1 开始。
     * 完整铅封生效后新的调节令必须拒绝（由调节服务在事务内重查）。
     */
    @Transactional
    public ValveSeal registerSeal(long valveId, String requestId, String sealNo, int valveVersion) {
        String fingerprint = Fingerprints.of("SEAL", requestId, String.valueOf(valveId),
                sealNo, String.valueOf(valveVersion));
        var existing = sealRepository.findByRequestId(requestId);
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), fingerprint);
        }
        valveRepository.lockById(valveId)
                .orElseThrow(() -> new NotFoundException("阀门不存在: valveId=" + valveId));
        // 持锁后重查幂等键：并发下同键请求在前序事务提交后按重放处理。
        var existingAfterLock = sealRepository.findByRequestId(requestId);
        if (existingAfterLock.isPresent()) {
            return replayOrConflict(existingAfterLock.get(), fingerprint);
        }
        int currentVersion = versionRepository.maxVersionNo(valveId);
        if (valveVersion != currentVersion) {
            throw new UnprocessableException("登记的开度版本与当前版本不一致: 当前=" + currentVersion
                    + ", 请求=" + valveVersion);
        }
        if (sealRepository.findActiveByValveId(valveId).isPresent()) {
            throw new UnprocessableException("阀门已存在完整铅封，须先复封: valveId=" + valveId);
        }
        if (sealRepository.findBySealNo(sealNo).isPresent()) {
            throw new ConflictException("铅封号已存在: sealNo=" + sealNo);
        }
        ValveSeal seal = new ValveSeal(0, valveId, sealNo, currentVersion,
                ValveSeal.SealStatus.ACTIVE, 1, clock.instant(), null, requestId, fingerprint);
        try {
            long id = sealRepository.insert(seal);
            return sealRepository.findById(id).orElseThrow();
        } catch (DuplicateKeyException e) {
            // 并发下唯一约束兜底：同键重放，异键同铅封号冲突。
            return sealRepository.findByRequestId(requestId)
                    .map(s -> replayOrConflict(s, fingerprint))
                    .orElseThrow(() -> new ConflictException("铅封号已存在: sealNo=" + sealNo));
        }
    }

    /**
     * 临时解封批准：引用铅封号及阀门版本，两名不同授权人共同确认，
     * 固定 UTC 半开窗口 [windowStart, windowEnd) 与固定两次调节额度；生效不改变开度。
     */
    @Transactional
    public UnsealOrder approveUnseal(long sealId, String requestId, int expectedVersion,
                                     int valveVersion, String approverA, String approverB,
                                     Instant windowStart, Instant windowEnd) {
        String fingerprint = Fingerprints.of("UNSEAL", requestId, String.valueOf(sealId),
                String.valueOf(expectedVersion), String.valueOf(valveVersion),
                approverA, approverB, windowStart.toString(), windowEnd.toString());
        var existing = unsealRepository.findByRequestId(requestId);
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), fingerprint);
        }
        ValveSeal seal = sealRepository.findById(sealId)
                .orElseThrow(() -> new NotFoundException("铅封不存在: sealId=" + sealId));
        long sealValveId = seal.valveId();
        valveRepository.lockById(sealValveId)
                .orElseThrow(() -> new NotFoundException("阀门不存在: valveId=" + sealValveId));
        // 持锁后重查幂等键与铅封状态。
        var existingAfterLock = unsealRepository.findByRequestId(requestId);
        if (existingAfterLock.isPresent()) {
            return replayOrConflict(existingAfterLock.get(), fingerprint);
        }
        seal = sealRepository.findById(sealId).orElseThrow();
        if (seal.status() != ValveSeal.SealStatus.ACTIVE) {
            throw new UnprocessableException("铅封已被复封替换，不得解封: sealId=" + sealId);
        }
        if (seal.version() != expectedVersion) {
            throw new ConflictException("铅封版本不一致: 当前=" + seal.version()
                    + ", expectedVersion=" + expectedVersion);
        }
        if (approverA.equals(approverB)) {
            throw new UnprocessableException("解封须两名不同授权人共同确认: " + approverA);
        }
        if (!windowStart.isBefore(windowEnd)) {
            throw new UnprocessableException("解封窗口非法：起点须早于终点(UTC): "
                    + windowStart + " ~ " + windowEnd);
        }
        int currentVersion = versionRepository.maxVersionNo(seal.valveId());
        if (valveVersion != currentVersion) {
            throw new UnprocessableException("引用的阀门版本与当前版本不一致: 当前=" + currentVersion
                    + ", 请求=" + valveVersion);
        }
        if (unsealRepository.findActiveBySealId(sealId).isPresent()) {
            throw new UnprocessableException("铅封已存在有效解封单: sealId=" + sealId);
        }
        UnsealOrder order = new UnsealOrder(0, sealId, seal.valveId(), seal.sealNo(),
                currentVersion, approverA, approverB, windowStart, windowEnd,
                UNSEAL_QUOTA_TOTAL, 0, UnsealOrder.UnsealStatus.ACTIVE, 1,
                clock.instant(), null, requestId, fingerprint, null, null);
        try {
            long id = unsealRepository.insert(order);
            return unsealRepository.findById(id).orElseThrow();
        } catch (DuplicateKeyException e) {
            return unsealRepository.findByRequestId(requestId)
                    .map(o -> replayOrConflict(o, fingerprint))
                    .orElseThrow(() -> e);
        }
    }

    /**
     * 撤销解封单：仅禁止新调节，不回拨已改开度；重复同键撤销重放首次结果。
     */
    @Transactional
    public UnsealOrder revokeUnseal(long unsealId, String requestId, int expectedVersion) {
        String fingerprint = Fingerprints.of("REVOKE", requestId, String.valueOf(unsealId),
                String.valueOf(expectedVersion));
        UnsealOrder order = unsealRepository.findById(unsealId)
                .orElseThrow(() -> new NotFoundException("解封单不存在: unsealId=" + unsealId));
        if (order.status() == UnsealOrder.UnsealStatus.REVOKED) {
            return replayRevoke(order, requestId, fingerprint);
        }
        // 行锁串行化撤销与受控调节的额度消费。
        order = unsealRepository.lockById(unsealId).orElseThrow();
        if (order.status() == UnsealOrder.UnsealStatus.REVOKED) {
            return replayRevoke(order, requestId, fingerprint);
        }
        if (order.status() != UnsealOrder.UnsealStatus.ACTIVE) {
            throw new ConflictException("解封单已终结（复封），不允许撤销: unsealId=" + unsealId);
        }
        if (order.version() != expectedVersion) {
            throw new ConflictException("解封单版本不一致: 当前=" + order.version()
                    + ", expectedVersion=" + expectedVersion);
        }
        int updated = unsealRepository.revoke(unsealId, expectedVersion, clock.instant(),
                requestId, fingerprint);
        if (updated != 1) {
            throw new ConflictException("解封单并发变更，撤销失败: unsealId=" + unsealId);
        }
        return unsealRepository.findById(unsealId).orElseThrow();
    }

    /**
     * 复封：两名不同人员核对当前开度版本并登记新铅封号，
     * 原子终结旧解封权限，旧铅封转入历史封号链。
     */
    @Transactional
    public ValveSeal reseal(long valveId, String requestId, int expectedVersion,
                            String newSealNo, String checkerA, String checkerB, int valveVersion) {
        String fingerprint = Fingerprints.of("RESEAL", requestId, String.valueOf(valveId),
                String.valueOf(expectedVersion), newSealNo, checkerA, checkerB,
                String.valueOf(valveVersion));
        var existing = sealRepository.findByRequestId(requestId);
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), fingerprint);
        }
        valveRepository.lockById(valveId)
                .orElseThrow(() -> new NotFoundException("阀门不存在: valveId=" + valveId));
        var existingAfterLock = sealRepository.findByRequestId(requestId);
        if (existingAfterLock.isPresent()) {
            return replayOrConflict(existingAfterLock.get(), fingerprint);
        }
        ValveSeal active = sealRepository.findActiveByValveId(valveId)
                .orElseThrow(() -> new UnprocessableException("阀门无完整铅封，无需复封: valveId=" + valveId));
        if (active.version() != expectedVersion) {
            throw new ConflictException("铅封版本不一致: 当前=" + active.version()
                    + ", expectedVersion=" + expectedVersion);
        }
        if (checkerA.equals(checkerB)) {
            throw new UnprocessableException("复封须两名不同人员核对: " + checkerA);
        }
        int currentVersion = versionRepository.maxVersionNo(valveId);
        if (valveVersion != currentVersion) {
            throw new UnprocessableException("核对的开度版本与当前版本不一致: 当前=" + currentVersion
                    + ", 请求=" + valveVersion);
        }
        if (sealRepository.findBySealNo(newSealNo).isPresent()) {
            throw new ConflictException("铅封号已存在: sealNo=" + newSealNo);
        }
        Instant now = clock.instant();
        // 原子终结旧解封权限与旧铅封，再登记新铅封；任一失败整体回滚。
        unsealRepository.closeActiveBySealId(active.id());
        int updated = sealRepository.markResealed(active.id(), expectedVersion, now);
        if (updated != 1) {
            throw new ConflictException("铅封并发变更，复封失败: sealId=" + active.id());
        }
        ValveSeal newSeal = new ValveSeal(0, valveId, newSealNo, currentVersion,
                ValveSeal.SealStatus.ACTIVE, 1, now, null, requestId, fingerprint);
        try {
            long id = sealRepository.insert(newSeal);
            return sealRepository.findById(id).orElseThrow();
        } catch (DuplicateKeyException e) {
            return sealRepository.findByRequestId(requestId)
                    .map(s -> replayOrConflict(s, fingerprint))
                    .orElseThrow(() -> new ConflictException("铅封号已存在: sealNo=" + newSealNo));
        }
    }

    /**
     * 查询阀门完整封号链（含历史），按登记先后升序。
     */
    @Transactional(readOnly = true)
    public List<ValveSeal> getSealChain(long valveId) {
        valveRepository.findById(valveId)
                .orElseThrow(() -> new NotFoundException("阀门不存在: valveId=" + valveId));
        return sealRepository.findByValveId(valveId);
    }

    /**
     * 查询阀门当前完整有效的铅封。
     */
    @Transactional(readOnly = true)
    public ValveSeal getActiveSeal(long valveId) {
        valveRepository.findById(valveId)
                .orElseThrow(() -> new NotFoundException("阀门不存在: valveId=" + valveId));
        return sealRepository.findActiveByValveId(valveId)
                .orElseThrow(() -> new NotFoundException("阀门无完整铅封: valveId=" + valveId));
    }

    /**
     * 查询解封单详情。
     */
    @Transactional(readOnly = true)
    public UnsealOrder getUnseal(long unsealId) {
        return unsealRepository.findById(unsealId)
                .orElseThrow(() -> new NotFoundException("解封单不存在: unsealId=" + unsealId));
    }

    /**
     * 查询解封单的额度消费轨迹。
     */
    @Transactional(readOnly = true)
    public List<UnsealQuotaUsage> getQuotaTrail(long unsealId) {
        unsealRepository.findById(unsealId)
                .orElseThrow(() -> new NotFoundException("解封单不存在: unsealId=" + unsealId));
        return usageRepository.findByUnsealId(unsealId);
    }

    private UnsealOrder replayRevoke(UnsealOrder order, String requestId, String fingerprint) {
        if (!requestId.equals(order.revokeRequestId())) {
            throw new ConflictException("解封单已被其他请求撤销: unsealId=" + order.id());
        }
        if (!order.revokeFingerprint().equals(fingerprint)) {
            throw new ConflictException("同键异参，返回 409: requestId=" + requestId);
        }
        return order;
    }

    private ValveSeal replayOrConflict(ValveSeal existing, String fingerprint) {
        if (!existing.fingerprint().equals(fingerprint)) {
            throw new ConflictException("同键异参，返回 409: requestId=" + existing.requestId());
        }
        return existing;
    }

    private UnsealOrder replayOrConflict(UnsealOrder existing, String fingerprint) {
        if (!existing.fingerprint().equals(fingerprint)) {
            throw new ConflictException("同键异参，返回 409: requestId=" + existing.requestId());
        }
        return existing;
    }
}
