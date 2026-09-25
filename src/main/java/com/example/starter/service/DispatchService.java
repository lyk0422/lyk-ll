package com.example.starter.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.starter.model.DispatchOrder;
import com.example.starter.model.OrderStatus;
import com.example.starter.repository.DispatchOrderRepository;
import com.example.starter.repository.GeneratorRepository;
import com.example.starter.repository.OrderEventRepository;
import com.example.starter.web.dto.ApiDtos.CloseOrderRequest;
import com.example.starter.web.dto.ApiDtos.ConfirmGenerationRequest;
import com.example.starter.web.dto.ApiDtos.DispatchOrderView;
import com.example.starter.web.dto.ApiDtos.ReassignRequest;

/**
 * 调度单生命周期服务：发电确认、确认超时升级、改派、结案。
 * 状态迁移全部使用带前置状态的条件更新，并发下形成唯一终态。
 */
@Service
public class DispatchService {

    /** 发电确认超时阈值：30 分钟，恰满判超时。 */
    static final Duration CONFIRM_TIMEOUT = Duration.ofMinutes(30);

    private final DispatchOrderRepository orderRepository;
    private final GeneratorRepository generatorRepository;
    private final OrderEventRepository eventRepository;
    private final IdempotencyService idempotencyService;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public DispatchService(DispatchOrderRepository orderRepository,
                           GeneratorRepository generatorRepository,
                           OrderEventRepository eventRepository,
                           IdempotencyService idempotencyService,
                           TransactionTemplate transactionTemplate, Clock clock) {
        this.orderRepository = orderRepository;
        this.generatorRepository = generatorRepository;
        this.eventRepository = eventRepository;
        this.idempotencyService = idempotencyService;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    /**
     * 发电确认：油机到位启动后回传。超时（30 分钟，恰满判超时）升级“发电异常”；
     * 已结案/已解除的迟到确认只记事件、不改结论。
     */
    public DispatchOrderView confirmGeneration(long orderId, ConfirmGenerationRequest request) {
        return idempotencyService.execute("confirmGeneration", request.requestId(),
                new Object[]{orderId},
                DispatchOrderView.class,
                () -> doConfirm(orderId));
    }

    private DispatchOrderView doConfirm(long orderId) {
        Instant now = Instant.now(clock);
        DispatchOrder order = requireOrderForUpdate(orderId);
        if (order.status() == OrderStatus.CLOSED || order.status() == OrderStatus.RELEASED) {
            // 迟到确认：只记事件，不改结论。
            eventRepository.append(orderId, "LATE_CONFIRM",
                    "调度单已" + order.status() + "，迟到发电确认仅记录事件", now);
            return DispatchOrderViews.toView(reload(orderId));
        }
        if (order.status() == OrderStatus.GENERATING) {
            return DispatchOrderViews.toView(order);
        }
        if (order.status() == OrderStatus.GENERATION_ABNORMAL) {
            return DispatchOrderViews.toView(order);
        }
        if (isTimedOut(order, now)) {
            escalate(order, now);
            return DispatchOrderViews.toView(reload(orderId));
        }
        orderRepository.confirmGeneration(orderId, now);
        eventRepository.append(orderId, "CONFIRMED", "油机 " + order.generatorCode() + " 发电确认", now);
        return DispatchOrderViews.toView(reload(orderId));
    }

    /**
     * 改派：仅“发电异常”单可改派；升级与改派在同一事务内完成，
     * 不会两台油机同时在途服务同站。
     */
    public DispatchOrderView reassign(long orderId, ReassignRequest request) {
        return idempotencyService.execute("reassign", request.requestId(),
                new Object[]{orderId, request.generatorCode()},
                DispatchOrderView.class,
                () -> doReassign(orderId, request.generatorCode()));
    }

    private DispatchOrderView doReassign(long orderId, String newGeneratorCode) {
        Instant now = Instant.now(clock);
        DispatchOrder order = requireOrderForUpdate(orderId);
        if (order.status() == OrderStatus.DISPATCHED && isTimedOut(order, now)) {
            // 先就地升级（释放原油机），再在同一事务内改派。
            escalate(order, now);
            order = reload(orderId);
        }
        if (order.status() != OrderStatus.GENERATION_ABNORMAL) {
            throw BusinessException.conflict("ORDER_NOT_REASSIGNABLE",
                    "仅发电异常的调度单允许改派，当前状态: " + order.status());
        }
        generatorRepository.findByCode(newGeneratorCode)
                .orElseThrow(() -> BusinessException.notFound("GENERATOR_NOT_FOUND",
                        "油机不存在: " + newGeneratorCode));
        if (generatorRepository.tryAssign(newGeneratorCode) != 1) {
            throw BusinessException.conflict("GENERATOR_BUSY",
                    "油机正在服务其他基站: " + newGeneratorCode);
        }
        orderRepository.reassign(orderId, newGeneratorCode, now);
        eventRepository.append(orderId, "REASSIGNED", "改派油机 " + newGeneratorCode, now);
        return DispatchOrderViews.toView(reload(orderId));
    }

    /**
     * 结案：终态。重复结案返回幂等成功。
     */
    public DispatchOrderView close(long orderId, CloseOrderRequest request) {
        return idempotencyService.execute("close", request.requestId(),
                new Object[]{orderId},
                DispatchOrderView.class,
                () -> doClose(orderId));
    }

    private DispatchOrderView doClose(long orderId) {
        Instant now = Instant.now(clock);
        DispatchOrder order = requireOrderForUpdate(orderId);
        if (order.status() == OrderStatus.CLOSED) {
            // 重复结案：幂等成功，直接返回当前结论。
            return DispatchOrderViews.toView(order);
        }
        String generatorCode = order.generatorCode();
        orderRepository.close(orderId, now);
        if (generatorCode != null) {
            generatorRepository.release(generatorCode);
        }
        eventRepository.append(orderId, "CLOSED", "调度单结案", now);
        return DispatchOrderViews.toView(reload(orderId));
    }

    /**
     * 查询调度单；在途单超时未确认时惰性升级为发电异常。
     */
    public DispatchOrderView getOrder(long orderId) {
        return transactionTemplate.execute(status -> {
            Instant now = Instant.now(clock);
            DispatchOrder order = requireOrderForUpdate(orderId);
            if (order.status() == OrderStatus.DISPATCHED && isTimedOut(order, now)) {
                escalate(order, now);
                order = reload(orderId);
            }
            return DispatchOrderViews.toView(order);
        });
    }

    /**
     * 判断在途单是否确认超时：分配时刻起满 30 分钟即判超时（恰满判超时）。
     */
    private boolean isTimedOut(DispatchOrder order, Instant now) {
        return !now.isBefore(order.assignedAt().plus(CONFIRM_TIMEOUT));
    }

    private void escalate(DispatchOrder order, Instant now) {
        String generatorCode = order.generatorCode();
        if (orderRepository.escalateToAbnormal(order.id(), now) > 0) {
            if (generatorCode != null) {
                generatorRepository.release(generatorCode);
            }
            eventRepository.append(order.id(), "ESCALATED",
                    "发电确认超时（30 分钟），升级发电异常"
                            + (generatorCode != null ? "，释放油机 " + generatorCode : ""),
                    now);
        }
    }

    private DispatchOrder requireOrderForUpdate(long orderId) {
        return orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> BusinessException.notFound("ORDER_NOT_FOUND",
                        "调度单不存在: " + orderId));
    }

    private DispatchOrder reload(long orderId) {
        return orderRepository.findById(orderId).orElseThrow();
    }
}
