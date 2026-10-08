package com.example.starter.water;

import com.example.starter.common.ApiException;
import com.example.starter.config.WaterQualityProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 处置指令生命周期：回执确认（5 分钟时限，恰满未确认即升级）与超时升级巡检。
 */
@Service
public class DisposalService {

    private final WaterRepository repository;
    private final WaterQualityProperties properties;
    private final Clock clock;

    public DisposalService(WaterRepository repository, WaterQualityProperties properties, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 回执确认结果；escalatedNow 为 true 表示本次确认恰满时限已触发升级（升级已随事务提交）。
     */
    public record ConfirmResult(long orderId, DisposalStatus status, Instant confirmedAt,
                                boolean escalatedNow) {
    }

    /**
     * 超时升级巡检结果。
     */
    public record SweepResult(List<Long> escalatedOrderIds, List<Long> followUpOrderIds) {
    }

    /**
     * 确认处置指令回执：恰满 5 分钟（含）未确认即升级（升级随事务提交，由调用方返回 409）。
     */
    @Transactional
    public ConfirmResult confirm(long orderId, String confirmedBy) {
        DisposalOrderRow order = repository.lockOrder(orderId)
                .orElseThrow(() -> ApiException.notFound("处置指令不存在: " + orderId));
        if (order.status() == DisposalStatus.CONFIRMED) {
            return new ConfirmResult(order.id(), order.status(), order.confirmedAt(), false);
        }
        if (order.status() == DisposalStatus.ESCALATED) {
            throw ApiException.conflict("处置指令回执超时已升级，无法确认: " + orderId);
        }
        Instant now = clock.instant();
        if (!now.isBefore(order.confirmDeadline())) {
            escalateWithFollowUp(order, now);
            return new ConfirmResult(orderId, DisposalStatus.ESCALATED, null, true);
        }
        repository.markOrderConfirmed(orderId, now);
        return new ConfirmResult(orderId, DisposalStatus.CONFIRMED, now, false);
    }

    /**
     * 巡检并升级全部恰满确认时限仍待确认的指令；升级为停展隔离后续指令。
     */
    @Transactional
    public SweepResult sweepOverdueOrders() {
        Instant now = clock.instant();
        List<Long> escalated = new ArrayList<>();
        List<Long> followUps = new ArrayList<>();
        for (DisposalOrderRow order : repository.findOverduePendingOrders(now)) {
            escalated.add(order.id());
            followUps.add(escalateWithFollowUp(order, now));
        }
        return new SweepResult(escalated, followUps);
    }

    private long escalateWithFollowUp(DisposalOrderRow order, Instant now) {
        // 升级时间取确认截止时刻，保证“恰满 5 分钟”语义确定可复算。
        repository.markOrderEscalated(order.id(), order.confirmDeadline());
        return repository.insertDisposalOrder(order.alarmId(), order.tankId(), DisposalType.ISOLATE, now,
                now.plus(Duration.ofMinutes(properties.getConfirmTimeoutMinutes())));
    }}
