package com.example.starter.service;

import com.example.starter.domain.UnsealOrder;
import com.example.starter.domain.UnsealQuotaEvent;
import com.example.starter.domain.Valve;
import com.example.starter.domain.ValveSeal;
import com.example.starter.repository.UnsealOrderRepository;
import com.example.starter.repository.UnsealQuotaEventRepository;
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
 * 铅封管理服务：铅封登记、临时解封批准/撤销、复封与额度轨迹查询。
 * 所有写操作以 requestId 幂等，结构化指纹覆盖全部业务字段（含版本、授权人集合与精确时刻），
 * 业务变更、行版本与幂等记录在同一事务内提交。
 */
@Service
public class SealService {

    /** 临时解封单调节额度上限：最多两次。 */
    public static final int MAX_QUOTA_TOTAL = 2;

    private final ValveRepository valveRepository;
    private final ValveVersionRepository versionRepository;
    private final ValveSealRepository sealRepository;
    private final UnsealOrderRepository orderRepository;
    private final UnsealQuotaEventRepository eventRepository;
    private final Clock clock;

    public SealService(ValveRepository valveRepository,
                       ValveVersionRepository versionRepository,
                       ValveSealRepository sealRepository,
                       UnsealOrderRepository orderRepository,
                       UnsealQuotaEventRepository eventRepository,
                       Clock clock) {
        this.valveRepository = valveRepository;
        this.versionRepository = versionRepository;
        this.sealRepository = sealRepository;
        this.orderRepository = orderRepository;
        this.eventRepository = eventRepository;
        this.clock = clock;
    }

    /**
     * 登记铅封：登记唯一铅封号、当前开度版本与封存时刻（UTC）。
     *
     * @param valveId         阀门 ID
     * @param requestId       客户端幂等键
     * @param sealNo          铅封号，全局唯一
     * @param expectedVersion 请求核对的开度版本，须等于当前版本（无版本时为 0）
     * @param operator        登记人
     * @return 已登记（或幂等重放的首次）台账行
     */
    @Transactional
    public ValveSeal registerSeal(long valveId, String requestId, String sealNo,
                                  int expectedVersion, String operator) {
        String fingerprint = Fingerprints.of("SEAL-REG", requestId, String.valueOf(valveId),
                sealNo, String.valueOf(expectedVersion), operator);
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
        if (expectedVersion != currentVersion) {
            throw new ConflictException("开度版本不一致，期望 " + expectedVersion
                    + "，当前 " + currentVersion + ": valveId=" + valveId);
        }
        if (sealRepository.findActiveByValveId(valveId).isPresent()) {
            throw new ConflictException("阀门已存在有效铅封，请使用复封: valveId=" + valveId);
        }
        Instant now = clock.instant();
        ValveSeal seal = new ValveSeal(0, valveId, sealNo, currentVersion, now,
                operator, null, ValveSeal.SealStatus.ACTIVE, requestId, fingerprint, 1, now, now);
        try {
            sealRepository.insert(seal);
        } catch (DuplicateKeyException e) {
            // 并发下同键插入：唯一约束保证只登记一次，重放首个结果；铅封号冲突单独甄别。
            var replayed = sealRepository.findByRequestId(requestId);
            if (replayed.isPresent()) {
                return replayOrConflict(replayed.get(), fingerprint);
            }
            throw new ConflictException("铅封号已被使用: sealNo=" + sealNo);
        }
        return sealRepository.findByRequestId(requestId).orElseThrow();
    }

    /**
     * 批准临时解封：两名不同授权人共同确认，固定 UTC 半开窗口 [windowStart, windowEnd)，
     * 额度最多两次；解封生效不改变阀门开度。
     *
     * @param valveId         阀门 ID
     * @param requestId       客户端幂等键
     * @param sealNo          引用的铅封号，须为阀门当前有效铅封
     * @param expectedVersion 请求核对的开度版本，须等于当前版本
     * @param authorizerA     授权人甲
     * @param authorizerB     授权人乙，须与甲不同
     * @param windowStart     有效窗口起点（UTC，含）
     * @param windowEnd       有效窗口终点（UTC，不含，恰到终点失效）
     * @param quotaTotal      调节额度，1~2 次
     * @return 已批准（或幂等重放的首次）解封单
     */
    @Transactional
    public UnsealOrder approveUnseal(long valveId, String requestId, String sealNo,
                                     int expectedVersion, String authorizerA, String authorizerB,
                                     Instant windowStart, Instant windowEnd, int quotaTotal) {
        String fingerprint = Fingerprints.of("UNSEAL-APPROVE", requestId, String.valueOf(valveId),
                sealNo, String.valueOf(expectedVersion), authorizerA, authorizerB,
                Fingerprints.at(windowStart), Fingerprints.at(windowEnd), String.valueOf(quotaTotal));
        var existing = orderRepository.findByRequestId(requestId);
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), fingerprint);
        }
        valveRepository.lockById(valveId)
                .orElseThrow(() -> new NotFoundException("阀门不存在: valveId=" + valveId));
        var existingAfterLock = orderRepository.findByRequestId(requestId);
        if (existingAfterLock.isPresent()) {
            return replayOrConflict(existingAfterLock.get(), fingerprint);
        }
        if (authorizerA.equals(authorizerB)) {
            throw new UnprocessableException("解封须两名不同授权人共同确认: " + authorizerA);
        }
        if (quotaTotal < 1 || quotaTotal > MAX_QUOTA_TOTAL) {
            throw new UnprocessableException("解封额度须为 1~" + MAX_QUOTA_TOTAL + " 次，当前: " + quotaTotal);
        }
        if (!windowStart.isBefore(windowEnd)) {
            throw new UnprocessableException("解封窗口起点须早于终点(UTC): "
                    + windowStart + " ~ " + windowEnd);
        }
        ValveSeal seal = sealRepository.findActiveByValveId(valveId)
                .orElseThrow(() -> new NotFoundException("阀门无有效铅封，无法解封: valveId=" + valveId));
        if (!seal.sealNo().equals(sealNo)) {
            throw new NotFoundException("铅封号不属于该阀门当前有效铅封: sealNo=" + sealNo);
        }
        int currentVersion = versionRepository.maxVersionNo(valveId);
        if (expectedVersion != currentVersion) {
            throw new ConflictException("开度版本不一致，期望 " + expectedVersion
                    + "，当前 " + currentVersion + ": valveId=" + valveId);
        }
        if (orderRepository.lockActiveByValveId(valveId).isPresent()) {
            throw new ConflictException("阀门存在生效中的解封单，须先撤销或复封: valveId=" + valveId);
        }
        Instant now = clock.instant();
        UnsealOrder order = new UnsealOrder(0, valveId, sealNo, currentVersion,
                authorizerA, authorizerB, windowStart, windowEnd, quotaTotal, 0,
                UnsealOrder.OrderStatus.ACTIVE, requestId, fingerprint, null, null, 1, now, now);
        long orderId;
        try {
            orderId = orderRepository.insert(order);
        } catch (DuplicateKeyException e) {
            // 并发下同键插入：唯一约束保证只批准一次，重放首个结果。
            return orderRepository.findByRequestId(requestId)
                    .map(o -> replayOrConflict(o, fingerprint))
                    .orElseThrow(() -> e);
        }
        eventRepository.insert(new UnsealQuotaEvent(0, orderId, valveId,
                UnsealQuotaEvent.EventType.GRANT, 0, null,
                "批准解封，授予额度 " + quotaTotal + " 次", now));
        return orderRepository.findById(orderId).orElseThrow();
    }

    /**
     * 撤销解封单：撤销后不再允许新的受控调节，已改开度不回拨。
     * 撤销幂等键与指纹记录在解封单行上，同键同参重放首次冻结结果，异参 409。
     *
     * @param orderId         解封单 ID
     * @param requestId       客户端幂等键
     * @param expectedVersion 请求核对的解封单行版本，须等于当前行版本
     * @return 已撤销（或幂等重放的首次）解封单
     */
    @Transactional
    public UnsealOrder revokeUnseal(long orderId, String requestId, int expectedVersion) {
        String fingerprint = Fingerprints.of("UNSEAL-REVOKE", requestId,
                String.valueOf(orderId), String.valueOf(expectedVersion));
        // 行锁串行化并发撤销：后进入者看到已写入的撤销幂等键，按重放或 409 处理。
        UnsealOrder order = orderRepository.lockById(orderId)
                .orElseThrow(() -> new NotFoundException("解封单不存在: orderId=" + orderId));
        if (requestId.equals(order.revokeRequestId())) {
            if (!order.revokeFingerprint().equals(fingerprint)) {
                throw new ConflictException("同键异参，返回 409: requestId=" + requestId);
            }
            return order;
        }
        if (expectedVersion != order.version()) {
            throw new ConflictException("解封单版本不一致，期望 " + expectedVersion
                    + "，当前 " + order.version() + ": orderId=" + orderId);
        }
        if (order.status() != UnsealOrder.OrderStatus.ACTIVE) {
            throw new ConflictException("解封单已" + order.status() + "，不允许撤销: orderId=" + orderId);
        }
        Instant now = clock.instant();
        if (!orderRepository.revoke(orderId, expectedVersion, requestId, fingerprint, now)) {
            throw new ConflictException("解封单并发变更，撤销失败: orderId=" + orderId);
        }
        eventRepository.insert(new UnsealQuotaEvent(0, orderId, order.valveId(),
                UnsealQuotaEvent.EventType.REVOKE, order.quotaUsed(), null,
                "撤销解封，剩余额度作废", now));
        return orderRepository.findById(orderId).orElseThrow();
    }

    /**
     * 复封：两名不同人员核对当前开度版本并登记新铅封号；
     * 原子终结旧解封权限，旧铅封保留为历史封号链。
     *
     * @param valveId         阀门 ID
     * @param requestId       客户端幂等键
     * @param newSealNo       新铅封号，全局唯一
     * @param expectedVersion 请求核对的开度版本，须等于当前版本
     * @param confirmerA      核对人甲
     * @param confirmerB      核对人乙，须与甲不同
     * @return 已登记（或幂等重放的首次）新台账行
     */
    @Transactional
    public ValveSeal reseal(long valveId, String requestId, String newSealNo,
                            int expectedVersion, String confirmerA, String confirmerB) {
        String fingerprint = Fingerprints.of("SEAL-RESEAL", requestId, String.valueOf(valveId),
                newSealNo, String.valueOf(expectedVersion), confirmerA, confirmerB);
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
        if (confirmerA.equals(confirmerB)) {
            throw new UnprocessableException("复封须两名不同人员核对: " + confirmerA);
        }
        int currentVersion = versionRepository.maxVersionNo(valveId);
        if (expectedVersion != currentVersion) {
            throw new ConflictException("开度版本不一致，期望 " + expectedVersion
                    + "，当前 " + currentVersion + ": valveId=" + valveId);
        }
        ValveSeal active = sealRepository.findActiveByValveId(valveId)
                .orElseThrow(() -> new NotFoundException("阀门无有效铅封，请登记: valveId=" + valveId));
        Instant now = clock.instant();
        // 与受控调节竞争同一解封单行锁：复封提交后调节重查必失败，不可已复封仍追加调节版本。
        var activeOrder = orderRepository.lockActiveByValveId(valveId);
        if (activeOrder.isPresent()) {
            UnsealOrder order = activeOrder.get();
            if (!orderRepository.terminate(order.id(), order.version(), now)) {
                throw new ConflictException("解封单并发变更，复封失败: orderId=" + order.id());
            }
            eventRepository.insert(new UnsealQuotaEvent(0, order.id(), valveId,
                    UnsealQuotaEvent.EventType.TERMINATE, order.quotaUsed(), null,
                    "复封终结解封权限，剩余额度作废", now));
        }
        if (!sealRepository.supersede(active.id(), active.version(), now)) {
            throw new ConflictException("铅封台账并发变更，复封失败: valveId=" + valveId);
        }
        ValveSeal renewed = new ValveSeal(0, valveId, newSealNo, currentVersion, now,
                confirmerA, confirmerB, ValveSeal.SealStatus.ACTIVE, requestId, fingerprint,
                1, now, now);
        try {
            sealRepository.insert(renewed);
        } catch (DuplicateKeyException e) {
            var replayed = sealRepository.findByRequestId(requestId);
            if (replayed.isPresent()) {
                return replayOrConflict(replayed.get(), fingerprint);
            }
            throw new ConflictException("铅封号已被使用: sealNo=" + newSealNo);
        }
        return sealRepository.findByRequestId(requestId).orElseThrow();
    }

    /**
     * 查询阀门完整封号链（含历史），按登记顺序升序。
     */
    @Transactional(readOnly = true)
    public List<ValveSeal> getSealChain(long valveId) {
        valveRepository.findById(valveId)
                .orElseThrow(() -> new NotFoundException("阀门不存在: valveId=" + valveId));
        return sealRepository.findByValveId(valveId);
    }

    /**
     * 查询解封单详情。
     */
    @Transactional(readOnly = true)
    public UnsealOrder getUnsealOrder(long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new NotFoundException("解封单不存在: orderId=" + orderId));
    }

    /**
     * 查询解封单额度轨迹（授予/消费/撤销/终结）。
     */
    @Transactional(readOnly = true)
    public List<UnsealQuotaEvent> getQuotaEvents(long orderId) {
        orderRepository.findById(orderId)
                .orElseThrow(() -> new NotFoundException("解封单不存在: orderId=" + orderId));
        return eventRepository.findByOrderId(orderId);
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

    /**
     * 受控调节事务内重查并预检解封权限：返回行锁定的生效中解封单。
     * 由 AdjustmentService 在同一事务内调用，调用方须已完成开度版本一致性比对。
     *
     * @param valve 目标阀门
     * @param now   当前时刻（UTC）
     * @return 行锁定的生效中解封单
     */
    UnsealOrder lockValidOrderForAdjustment(Valve valve, Instant now) {
        UnsealOrder order = orderRepository.lockActiveByValveId(valve.id())
                .orElseThrow(() -> new UnprocessableException(
                        "阀门铅封完整或无生效解封单，禁止调节: valveId=" + valve.id()));
        if (now.isBefore(order.windowStart()) || !now.isBefore(order.windowEnd())) {
            throw new UnprocessableException("解封窗口未生效或已失效(UTC): 窗口 ["
                    + order.windowStart() + ", " + order.windowEnd() + ")，当前 " + now);
        }
        if (order.quotaUsed() >= order.quotaTotal()) {
            throw new UnprocessableException("解封额度已用尽: orderId=" + order.id());
        }
        return order;
    }

    /**
     * 受控调节成功后消费一次额度并写轨迹；与开度写入、版本记录同事务。
     */
    void consumeQuota(UnsealOrder order, long valveId, long taskId, Instant now) {
        if (!orderRepository.consumeQuota(order.id(), order.version(), now)) {
            throw new ConflictException("解封单并发变更，额度消费失败: orderId=" + order.id());
        }
        eventRepository.insert(new UnsealQuotaEvent(0, order.id(), valveId,
                UnsealQuotaEvent.EventType.CONSUME, order.quotaUsed() + 1, taskId,
                "受控调节消费一次额度", now));
    }
}
