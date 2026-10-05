package com.example.starter.flood.service;

import com.example.starter.flood.config.FloodControlProperties;
import com.example.starter.flood.domain.OrderStatus;
import com.example.starter.flood.exception.FloodControlException;
import com.example.starter.flood.repo.IdempotentRepository;
import com.example.starter.flood.repo.IdempotentRepository.IdempotencyOutcome;
import com.example.starter.flood.repo.OrderPO;
import com.example.starter.flood.repo.OrderRepository;
import com.example.starter.flood.repo.PlanPO;
import com.example.starter.flood.repo.PlanRepository;
import com.example.starter.flood.support.JsonSupport;
import com.example.starter.flood.web.dto.OpeningItem;
import com.example.starter.flood.web.dto.OrderActionRequest;
import com.example.starter.flood.web.dto.OrderView;
import com.example.starter.flood.web.dto.ReceiptRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 泄流指令链服务：下达（随方案）→签收→执行→回执。
 *
 * <p>签收时限：以指令签收截止时刻为准，当前时刻不早于截止时刻（恰满 15 分钟）仍未签收，
 * 签收动作自动登记升级值班领导；截止时刻前签收正常。</p>
 * <p>回执口径：实际总泄量与方案总泄量偏差比例恰等 10% 视为一致；超过 10% 必须附原因，
 * 否则拒绝回执。回执以 requestId 幂等，同键重放返回首次回执，异参 409。</p>
 */
@Service
public class OrderService {

    /** 自动升级的值班领导（合成值班角色）。 */
    static final String DUTY_LEADER = "值班领导";

    private final OrderRepository orderRepository;
    private final PlanRepository planRepository;
    private final IdempotentRepository idempotentRepository;
    private final JsonSupport jsonSupport;
    private final FloodControlProperties properties;
    private final Clock clock;

    public OrderService(OrderRepository orderRepository, PlanRepository planRepository,
                        IdempotentRepository idempotentRepository, JsonSupport jsonSupport,
                        FloodControlProperties properties, Clock floodClock) {
        this.orderRepository = orderRepository;
        this.planRepository = planRepository;
        this.idempotentRepository = idempotentRepository;
        this.jsonSupport = jsonSupport;
        this.properties = properties;
        this.clock = floodClock;
    }

    /**
     * 签收指令；恰满签收时限未签收则自动升级值班领导（升级留痕，签收仍可补登）。
     */
    @Transactional
    public OrderView sign(String orderNo, OrderActionRequest request) {
        OrderPO order = requireOrder(orderNo);
        LocalDateTime now = LocalDateTime.now(clock);
        if (order.status.equals(OrderStatus.SIGNED.name())
                || order.status.equals(OrderStatus.EXECUTING.name())
                || order.status.equals(OrderStatus.RECEIPTED.name())) {
            throw FloodControlException.business("ORDER_ALREADY_SIGNED",
                    "指令 " + orderNo + " 已签收，当前状态 " + order.status);
        }
        boolean timedOut = !now.isBefore(order.signDeadline);
        if (OrderStatus.ESCALATED.name().equals(order.status)) {
            // 已被超时巡检升级：仅允许补签收一次（升级态保留）
            if (order.signedAt != null) {
                throw FloodControlException.business("ORDER_ALREADY_SIGNED",
                        "指令 " + orderNo + " 已补签收，当前状态 " + order.status);
            }
            int signed = orderRepository.signKeepEscalated(order.id, request.operator(), now);
            if (signed == 0) {
                throw FloodControlException.business("ORDER_SIGN_FAILED",
                        "指令 " + orderNo + " 升级后补签收失败");
            }
            order.signedAt = now;
            order.signer = request.operator();
            orderRepository.addEvent(order.id, "SIGNED", now,
                    request.operator() + " 在升级值班领导后补签收");
        } else if (timedOut) {
            // 恰满时限（当前时刻不早于截止时刻）：先自动升级值班领导，再登记补签收（保留升级态）
            int escalated = orderRepository.escalate(order.id, DUTY_LEADER, now);
            if (escalated == 0) {
                throw FloodControlException.business("ORDER_SIGN_FAILED",
                        "指令 " + orderNo + " 当前状态不可签收: " + order.status);
            }
            int signed = orderRepository.signKeepEscalated(order.id, request.operator(), now);
            if (signed == 0) {
                throw FloodControlException.business("ORDER_SIGN_FAILED",
                        "指令 " + orderNo + " 升级后补签收失败: " + order.status);
            }
            order.status = OrderStatus.ESCALATED.name();
            order.escalatedAt = now;
            order.escalatedTo = DUTY_LEADER;
            order.signedAt = now;
            order.signer = request.operator();
            orderRepository.addEvent(order.id, "ESCALATED", now,
                    "恰满签收时限 " + properties.getSignTimeoutMinutes()
                            + " 分钟未签收，自动升级值班领导，后由 " + request.operator() + " 补签收");
        } else {
            int rows = orderRepository.sign(order.id, request.operator(), now);
            if (rows == 0) {
                throw FloodControlException.business("ORDER_SIGN_FAILED",
                        "指令 " + orderNo + " 当前状态不可签收: " + order.status);
            }
            order.signedAt = now;
            order.signer = request.operator();
            order.status = OrderStatus.SIGNED.name();
            orderRepository.addEvent(order.id, "SIGNED", now,
                    request.operator() + " 在签收时限内签收");
        }
        return toView(order);
    }

    /**
     * 手动触发超时巡检升级（无补签收的纯升级路径，供定时/接口演练边界）。
     * 恰满签收时限未签收即升级，返回 true；未超时或已处理返回 false。
     */
    @Transactional
    public boolean escalateIfTimeout(String orderNo) {
        OrderPO order = requireOrder(orderNo);
        LocalDateTime now = LocalDateTime.now(clock);
        if (!OrderStatus.ISSUED.name().equals(order.status)) {
            return false;
        }
        if (now.isBefore(order.signDeadline)) {
            return false;
        }
        int rows = orderRepository.escalate(order.id, DUTY_LEADER, now);
        if (rows > 0) {
            orderRepository.addEvent(order.id, "ESCALATED", now,
                    "恰满签收时限未签收，自动升级值班领导");
        }
        return rows > 0;
    }

    /**
     * 执行指令（现场开闸）。
     */
    @Transactional
    public OrderView execute(String orderNo, OrderActionRequest request) {
        OrderPO order = requireOrder(orderNo);
        LocalDateTime now = LocalDateTime.now(clock);
        int rows = orderRepository.execute(order.id, request.operator(), now);
        if (rows == 0) {
            throw FloodControlException.business("ORDER_EXECUTE_FAILED",
                    "指令 " + orderNo + " 当前状态 " + order.status + " 不可执行，需先签收");
        }
        orderRepository.addEvent(order.id, "EXECUTED", now,
                request.operator() + " 按方案开闸泄流");
        order.status = OrderStatus.EXECUTING.name();
        order.executedAt = now;
        order.executor = request.operator();
        return toView(order);
    }

    /**
     * 回执（requestId 幂等）；偏差恰 10% 一致，超 10% 须附原因。
     */
    @Transactional
    public OrderView receipt(String orderNo, ReceiptRequest request) {
        OrderPO order = requireOrder(orderNo);
        // 幂等重放：同 requestId 已回执则直接返回首次结果
        OrderPO replayed = orderRepository.findByReceiptRequestId(request.requestId());
        if (replayed != null) {
            String fingerprint = jsonSupport.fingerprint(fingerprintFields(orderNo, request));
            Map<String, Object> existing = idempotentRepository
                    .findByRequestId(request.requestId());
            if (existing != null && !fingerprint.equals(existing.get("fingerprint"))) {
                throw FloodControlException.conflict(
                        "requestId=" + request.requestId() + " 已用于不同参数的回执");
            }
            return toView(replayed);
        }

        PlanPO plan = planRepository.findById(order.planId);
        if (plan == null) {
            throw FloodControlException.notFound("指令关联方案不存在");
        }
        BigDecimal actualTotal = PlanService.sumRelease(request.actualOpenings());
        BigDecimal ratio = ReleaseMath.deviationRatio(actualTotal, plan.totalRelease);
        boolean consistent = ReleaseMath.deviationConsistent(
                ratio, properties.getReceiptDeviationRatio());
        String reason = request.deviationReason() == null ? null : request.deviationReason().trim();
        if (!consistent && (reason == null || reason.isEmpty())) {
            throw FloodControlException.business("RECEIPT_REASON_REQUIRED",
                    "实际泄量 " + actualTotal + " 与方案 " + plan.totalRelease
                            + " 偏差 " + ratio.toPlainString()
                            + " 超过 10%（恰 10% 视为一致），必须附原因说明");
        }

        String fingerprint = jsonSupport.fingerprint(fingerprintFields(orderNo, request));
        LocalDateTime now = LocalDateTime.now(clock);
        IdempotencyOutcome outcome = idempotentRepository.register(
                request.requestId(), "RECEIPT", fingerprint, now);
        if (!outcome.created()) {
            if (outcome.fingerprintMismatch()) {
                throw FloodControlException.conflict(
                        "requestId=" + request.requestId() + " 已用于不同参数的回执");
            }
            OrderPO other = orderRepository.findByReceiptRequestId(request.requestId());
            if (other != null) {
                return toView(other);
            }
        }

        String storedReason = consistent ? reason : reason;
        int rows = orderRepository.receipt(order.id, request.requestId(),
                jsonSupport.toJson(request.actualOpenings()), actualTotal, ratio,
                storedReason, now);
        if (rows == 0) {
            throw FloodControlException.business("ORDER_RECEIPT_FAILED",
                    "指令 " + orderNo + " 当前状态 " + order.status + " 不可回执，需先执行");
        }
        orderRepository.addEvent(order.id, "RECEIPTED", now,
                "实际总泄量 " + actualTotal + " m3/s，偏差比例 " + ratio.toPlainString()
                        + (consistent ? "（一致）" : "（超阈值，原因：" + storedReason + "）"));
        planRepository.markCompleted(plan.id, now);
        idempotentRepository.bindRef(request.requestId(), order.id);

        OrderPO saved = orderRepository.findByNo(orderNo);
        return toView(saved);
    }

    /**
     * 查询指令视图。
     */
    public OrderView get(String orderNo) {
        return toView(requireOrder(orderNo));
    }

    private OrderPO requireOrder(String orderNo) {
        OrderPO order = orderRepository.findByNo(orderNo);
        if (order == null) {
            throw FloodControlException.notFound("泄流指令不存在: " + orderNo);
        }
        return order;
    }

    private Map<String, Object> fingerprintFields(String orderNo, ReceiptRequest request) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("requestId", request.requestId());
        fields.put("bizType", "RECEIPT");
        fields.put("orderNo", orderNo);
        fields.put("operator", request.operator());
        List<Map<String, Object>> openings = new java.util.ArrayList<>();
        for (OpeningItem item : request.actualOpenings()) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("name", item.name());
            o.put("opening", item.opening().stripTrailingZeros().toPlainString());
            o.put("release", item.release().stripTrailingZeros().toPlainString());
            openings.add(o);
        }
        fields.put("actualOpenings", openings);
        fields.put("deviationReason", request.deviationReason());
        return fields;
    }

    private OrderView toView(OrderPO po) {
        List<OpeningItem> actual = po.actualOpenings == null ? List.of()
                : jsonSupport.fromJsonList(po.actualOpenings,
                        new TypeReference<List<OpeningItem>>() {
                        });
        return new OrderView(po.orderNo, planNo(po.planId), po.planVersionNo, po.status,
                po.issuedAt, po.signDeadline, po.signedAt, po.signer, po.escalatedAt,
                po.escalatedTo, po.executedAt, po.executor, po.receiptedAt, actual,
                po.actualTotalRelease, po.deviationRatio, po.deviationReason, false);
    }

    private String planNo(Long planId) {
        PlanPO plan = planRepository.findById(planId);
        return plan == null ? null : plan.planNo;
    }
}
