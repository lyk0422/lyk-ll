package com.example.starter.transition.service;

import com.example.starter.transition.ApiException;
import com.example.starter.transition.TransitionStatus;
import com.example.starter.transition.dto.BlendAdjustRequest;
import com.example.starter.transition.dto.BlendControlStateView;
import com.example.starter.transition.dto.BlendReadingRequest;
import com.example.starter.transition.dto.BlendReadingView;
import com.example.starter.transition.dto.BlendRatioVersionView;
import com.example.starter.transition.dto.BlendRecomputeView;
import com.example.starter.transition.dto.BlendResumeRequest;
import com.example.starter.transition.dto.IdempotentResult;
import com.example.starter.transition.model.BlendControlState;
import com.example.starter.transition.model.BlendRatioVersion;
import com.example.starter.transition.model.BlendReading;
import com.example.starter.transition.model.IdempotentRequest;
import com.example.starter.transition.model.TransitionOrder;
import com.example.starter.transition.repo.BlendControlRepository;
import com.example.starter.transition.repo.BlendRatioVersionRepository;
import com.example.starter.transition.repo.BlendReadingRepository;
import com.example.starter.transition.repo.TransitionOrderRepository;
import com.example.starter.transition.support.FingerprintUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 回抄浆掺配比例控制服务：比例版本链、实际读数去重、失控暂停转人工与复核恢复。
 *
 * <p>规则：比例须在合成允许区间 [5.000,30.000]% 内（恰在边界合法）；
 * 实际读数偏离目标超过 ±2%（恰 2% 合格）且连续 3 个采样点判失控，
 * 失控即暂停执行并转人工，恢复须复核确认；全部写操作按 requestId 幂等。</p>
 */
@Service
public class BlendService {

    static final String BIZ_BLEND_ADJUST = "ADJUST_BLEND_RATIO";
    static final String BIZ_BLEND_READING = "SUBMIT_BLEND_READING";
    static final String BIZ_BLEND_RESUME = "RESUME_BLEND_CONTROL";

    static final String ADJUST_INITIAL = "INITIAL";
    static final String ADJUST_DIRECT = "DIRECT_ADJUST";
    static final String ADJUST_SPEED_DOWN = "SPEED_DOWN_ADJUST";

    static final String EVENT_OUT_OF_CONTROL = "OUT_OF_CONTROL";
    static final String EVENT_RESUMED = "RESUMED";

    private final TransitionOrderRepository orderRepository;
    private final BlendRatioVersionRepository versionRepository;
    private final BlendReadingRepository readingRepository;
    private final BlendControlRepository controlRepository;
    private final IdempotencyGuard idempotencyGuard;
    private final Clock clock;

    public BlendService(TransitionOrderRepository orderRepository,
                        BlendRatioVersionRepository versionRepository,
                        BlendReadingRepository readingRepository,
                        BlendControlRepository controlRepository,
                        IdempotencyGuard idempotencyGuard,
                        Clock clock) {
        this.orderRepository = orderRepository;
        this.versionRepository = versionRepository;
        this.readingRepository = readingRepository;
        this.controlRepository = controlRepository;
        this.idempotencyGuard = idempotencyGuard;
        this.clock = clock;
    }

    /**
     * 执行中掺配比例调整（requestId 幂等）：携带比例变更须重新走方式判定，
     * 判定结论与比例版本在同一事务内落库；同一订单同时刻只允许一次执行中调整，
     * 由唯一约束 (order_no, version_no) 仲裁并发。
     */
    @Transactional
    public IdempotentResult<BlendRatioVersionView> adjustRatio(String orderNo, BlendAdjustRequest request) {
        String fingerprint = FingerprintUtil.sha256(request.requestId(), request);
        IdempotentRequest existing = idempotencyGuard.lookup(request.requestId(), BIZ_BLEND_ADJUST, fingerprint);
        if (existing != null) {
            return idempotencyGuard.replay(existing, new TypeReference<BlendRatioVersionView>() {
            });
        }

        TransitionOrder order = requireOrder(orderNo);
        requireExecutable(order);
        BlendControlState state = requireState(orderNo);
        requireRunning(state);
        if (!TransitionRules.withinBlendRatioRange(request.targetBlendRatioPct())) {
            throw ApiException.badRequest("掺配比例 " + request.targetBlendRatioPct()
                    + " 超出合成允许区间 [5.000, 30.000] %");
        }

        BlendRatioVersion current = versionRepository.find(orderNo, state.getCurrentVersionNo())
                .orElseThrow(() -> new IllegalStateException("当前比例版本缺失: " + orderNo));
        BigDecimal delta = TransitionRules.blendDeviationPct(
                request.targetBlendRatioPct(), current.getTargetRatioPct());
        // 方式判定：相邻版本差值恰 5% 在线直调，超出须降速调整。
        String adjustMode = TransitionRules.isDirectBlendAdjust(delta) ? ADJUST_DIRECT : ADJUST_SPEED_DOWN;

        BlendRatioVersion next = new BlendRatioVersion();
        next.setOrderNo(orderNo);
        next.setVersionNo(state.getCurrentVersionNo() + 1);
        next.setTargetRatioPct(request.targetBlendRatioPct());
        next.setRatioDeltaPct(delta);
        next.setAdjustMode(adjustMode);
        next.setRequestId(request.requestId());
        next.setDecidedAt(now());
        try {
            // 判定结论与比例版本在同一事务内落库；并发调整由唯一索引仲裁，仅一个成功。
            versionRepository.insert(next);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("同一订单同时刻只允许一次执行中调整: " + orderNo);
        }
        controlRepository.advanceVersion(orderNo, next.getVersionNo(), now());

        BlendRatioVersionView view = toVersionView(next);
        idempotencyGuard.register(request.requestId(), orderNo, view);
        return new IdempotentResult<>(request.requestId(), false, view);
    }

    /**
     * 实际掺配读数上报（requestId 幂等）：按订单与采样时刻去重；
     * 偏离目标超过 ±2%（恰 2% 合格）且连续 3 个采样点判失控，失控即暂停执行并转人工。
     */
    @Transactional
    public IdempotentResult<BlendReadingView> submitReading(String orderNo, BlendReadingRequest request) {
        String fingerprint = FingerprintUtil.sha256(request.requestId(), request);
        IdempotentRequest existing = idempotencyGuard.lookup(request.requestId(), BIZ_BLEND_READING, fingerprint);
        if (existing != null) {
            return idempotencyGuard.replay(existing, new TypeReference<BlendReadingView>() {
            });
        }

        TransitionOrder order = requireOrder(orderNo);
        requireExecutable(order);
        BlendControlState state = requireState(orderNo);
        requireRunning(state);
        BlendRatioVersion current = versionRepository.find(orderNo, state.getCurrentVersionNo())
                .orElseThrow(() -> new IllegalStateException("当前比例版本缺失: " + orderNo));

        BigDecimal deviation = TransitionRules.blendDeviationPct(
                request.actualRatioPct(), current.getTargetRatioPct());
        boolean withinTolerance = TransitionRules.withinBlendTolerance(deviation);

        BlendReading reading = new BlendReading();
        reading.setOrderNo(orderNo);
        reading.setVersionNo(current.getVersionNo());
        reading.setSampledAt(request.sampledAt());
        reading.setActualRatioPct(request.actualRatioPct());
        reading.setDeviationPct(deviation);
        reading.setWithinTolerance(withinTolerance);
        reading.setRequestId(request.requestId());
        reading.setCreatedAt(now());
        try {
            // 按订单与时刻去重：唯一索引冲突即该时刻已登记。
            readingRepository.insert(reading);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("订单 " + orderNo + " 时刻 " + request.sampledAt() + " 的掺配读数已登记");
        }

        int streak = withinTolerance ? 0 : state.getOutStreak() + 1;
        boolean outOfControl = TransitionRules.isBlendOutOfControl(streak);
        controlRepository.updateStreak(orderNo, streak, now());
        if (outOfControl) {
            // 失控即暂停执行并转人工，事件与状态变更同事务落库。
            controlRepository.pause(orderNo, now());
            controlRepository.insertEvent(orderNo, EVENT_OUT_OF_CONTROL, current.getVersionNo(),
                    null, request.requestId(), now());
        }

        BlendReadingView view = new BlendReadingView(orderNo, current.getVersionNo(),
                request.sampledAt(), request.actualRatioPct(), deviation, withinTolerance,
                outOfControl, outOfControl ? BlendControlState.PAUSED_MANUAL : BlendControlState.RUNNING);
        idempotencyGuard.register(request.requestId(), orderNo, view);
        return new IdempotentResult<>(request.requestId(), false, view);
    }

    /** 掺配失控恢复（requestId 幂等）：恢复须复核确认，恢复后连续超差计数清零。 */
    @Transactional
    public IdempotentResult<BlendControlStateView> resume(String orderNo, BlendResumeRequest request) {
        String fingerprint = FingerprintUtil.sha256(request.requestId(), request);
        IdempotentRequest existing = idempotencyGuard.lookup(request.requestId(), BIZ_BLEND_RESUME, fingerprint);
        if (existing != null) {
            return idempotencyGuard.replay(existing, new TypeReference<BlendControlStateView>() {
            });
        }

        requireOrder(orderNo);
        BlendControlState state = requireState(orderNo);
        if (!BlendControlState.PAUSED_MANUAL.equals(state.getControlStatus())) {
            throw ApiException.conflict("订单 " + orderNo + " 未处于失控暂停状态，无需恢复");
        }
        controlRepository.resume(orderNo, now());
        controlRepository.insertEvent(orderNo, EVENT_RESUMED, state.getCurrentVersionNo(),
                request.reviewer(), request.requestId(), now());

        BlendControlStateView view = new BlendControlStateView(orderNo, BlendControlState.RUNNING,
                state.getCurrentVersionNo(), 0);
        idempotencyGuard.register(request.requestId(), orderNo, view);
        return new IdempotentResult<>(request.requestId(), false, view);
    }

    public BlendControlStateView getControlState(String orderNo) {
        requireOrder(orderNo);
        BlendControlState state = requireState(orderNo);
        return new BlendControlStateView(orderNo, state.getControlStatus(),
                state.getCurrentVersionNo(), state.getOutStreak());
    }

    /** 比例版本链复算：历史订单可按版本复算实际掺配轨迹，偏差与失控点实时重算。 */
    public BlendRecomputeView recompute(String orderNo) {
        requireOrder(orderNo);
        List<BlendRatioVersion> versions = versionRepository.findByOrderNo(orderNo);
        List<BlendRecomputeView.VersionTrajectory> trajectories = new ArrayList<>();
        for (BlendRatioVersion version : versions) {
            List<BlendReading> readings = readingRepository.findByOrderAndVersion(
                    orderNo, version.getVersionNo());
            List<BlendRecomputeView.RecomputedReading> recomputed = new ArrayList<>();
            int streak = 0;
            boolean outOfControl = false;
            for (BlendReading reading : readings) {
                BigDecimal deviation = TransitionRules.blendDeviationPct(
                        reading.getActualRatioPct(), version.getTargetRatioPct());
                boolean within = TransitionRules.withinBlendTolerance(deviation);
                streak = within ? 0 : streak + 1;
                if (TransitionRules.isBlendOutOfControl(streak)) {
                    outOfControl = true;
                }
                recomputed.add(new BlendRecomputeView.RecomputedReading(
                        reading.getSampledAt(), reading.getActualRatioPct(), deviation, within));
            }
            trajectories.add(new BlendRecomputeView.VersionTrajectory(
                    version.getVersionNo(), version.getTargetRatioPct(), version.getAdjustMode(),
                    recomputed, outOfControl));
        }
        List<BlendRecomputeView.ControlEventView> events = controlRepository.findEvents(orderNo)
                .stream()
                .map(e -> new BlendRecomputeView.ControlEventView(
                        e.getEventType(), e.getVersionNo(), e.getReviewer(), e.getEventTime()))
                .toList();
        return new BlendRecomputeView(orderNo, trajectories, events);
    }

    // ------------------------------------------------------------------
    // 私有辅助方法（置于作用域末尾）
    // ------------------------------------------------------------------

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private TransitionOrder requireOrder(String orderNo) {
        return orderRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> ApiException.notFound("改品种单不存在: " + orderNo));
    }

    private void requireExecutable(TransitionOrder order) {
        if (!TransitionStatus.EXECUTED.equals(order.getStatus())) {
            throw ApiException.conflict("改品种单状态为 " + order.getStatus()
                    + "，掺配调整与读数仅允许执行阶段，期望状态 " + TransitionStatus.EXECUTED);
        }
    }

    private BlendControlState requireState(String orderNo) {
        return controlRepository.findState(orderNo)
                .orElseThrow(() -> ApiException.conflict("订单 " + orderNo + " 未设定掺配比例，不支持该操作"));
    }

    private void requireRunning(BlendControlState state) {
        if (BlendControlState.PAUSED_MANUAL.equals(state.getControlStatus())) {
            throw ApiException.conflict("掺配比例失控待人工复核，执行已暂停: " + state.getOrderNo());
        }
    }

    private BlendRatioVersionView toVersionView(BlendRatioVersion v) {
        return new BlendRatioVersionView(v.getOrderNo(), v.getVersionNo(), v.getTargetRatioPct(),
                v.getRatioDeltaPct(), v.getAdjustMode(), v.getRequestId(), v.getDecidedAt());
    }
}
