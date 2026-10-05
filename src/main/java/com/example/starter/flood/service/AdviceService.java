package com.example.starter.flood.service;

import com.example.starter.flood.config.FloodControlProperties;
import com.example.starter.flood.domain.OrderStatus;
import com.example.starter.flood.exception.FloodControlException;
import com.example.starter.flood.repo.AdvicePO;
import com.example.starter.flood.repo.AdviceRepository;
import com.example.starter.flood.repo.IdempotentRepository;
import com.example.starter.flood.repo.IdempotentRepository.IdempotencyOutcome;
import com.example.starter.flood.repo.OrderPO;
import com.example.starter.flood.repo.OrderRepository;
import com.example.starter.flood.repo.PlanPO;
import com.example.starter.flood.repo.PlanRepository;
import com.example.starter.flood.support.BusinessNoGenerator;
import com.example.starter.flood.support.JsonSupport;
import com.example.starter.flood.web.dto.AdviceConfirmRequest;
import com.example.starter.flood.web.dto.AdviceView;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 替代建议服务：在途指令与新方案版本冲突时由人工确认，确认留痕可查；
 * 确认按 requestId 幂等，同键同参重放首次结果，同键异参 409。
 */
@Service
public class AdviceService {

    private final AdviceRepository adviceRepository;
    private final OrderRepository orderRepository;
    private final PlanRepository planRepository;
    private final IdempotentRepository idempotentRepository;
    private final BusinessNoGenerator noGenerator;
    private final JsonSupport jsonSupport;
    private final FloodControlProperties properties;
    private final Clock clock;

    public AdviceService(AdviceRepository adviceRepository, OrderRepository orderRepository,
                         PlanRepository planRepository, IdempotentRepository idempotentRepository,
                         BusinessNoGenerator noGenerator, JsonSupport jsonSupport,
                         FloodControlProperties properties, Clock floodClock) {
        this.adviceRepository = adviceRepository;
        this.orderRepository = orderRepository;
        this.planRepository = planRepository;
        this.idempotentRepository = idempotentRepository;
        this.noGenerator = noGenerator;
        this.jsonSupport = jsonSupport;
        this.properties = properties;
        this.clock = floodClock;
    }

    /**
     * 查询替代建议（留痕可查），不存在抛 404。
     */
    public AdviceView get(String adviceNo) {
        return toView(requireAdvice(adviceNo), false);
    }

    /**
     * 人工确认替代建议（requestId 幂等）。
     * ACCEPT_NEW_VERSION：按新方案版本下达新指令；KEEP_CURRENT：维持在途指令。
     */
    @Transactional
    public AdviceView confirm(String adviceNo, AdviceConfirmRequest request) {
        AdvicePO advice = requireAdvice(adviceNo);
        String fingerprint = jsonSupport.fingerprint(fingerprintFields(adviceNo, request));
        if (!"PENDING".equals(advice.status)) {
            // 终态：同键同参重放首次确认结果，同键异参 409，异键重复确认 422
            Map<String, Object> existing = idempotentRepository.findByRequestId(request.requestId());
            if (existing != null && fingerprint.equals(existing.get("fingerprint"))) {
                return toView(advice, true);
            }
            if (existing != null) {
                throw FloodControlException.conflict(
                        "requestId=" + request.requestId() + " 已用于不同参数的建议确认");
            }
            throw FloodControlException.business("ADVICE_ALREADY_CONFIRMED",
                    "替代建议 " + adviceNo + " 已确认（决策 " + advice.decision + "），不可重复确认");
        }

        LocalDateTime now = LocalDateTime.now(clock);
        IdempotencyOutcome outcome = idempotentRepository.register(
                request.requestId(), "ADVICE_CONFIRM", fingerprint, now);
        if (!outcome.created()) {
            if (outcome.fingerprintMismatch()) {
                throw FloodControlException.conflict(
                        "requestId=" + request.requestId() + " 已用于不同参数的建议确认");
            }
            return toView(adviceRepository.findByNo(adviceNo), true);
        }

        int rows = adviceRepository.confirm(advice.id, request.decision(),
                request.note(), request.operator(), now);
        if (rows == 0) {
            throw FloodControlException.business("ADVICE_ALREADY_CONFIRMED",
                    "替代建议 " + adviceNo + " 已被并发确认");
        }

        if ("ACCEPT_NEW_VERSION".equals(request.decision())) {
            // 按新版本方案下达新指令（钉住新版本号）；在途旧指令继续有效至回执
            PlanPO newPlan = planRepository.findById(advice.newPlanId);
            OrderPO order = new OrderPO();
            order.orderNo = noGenerator.next("ZL");
            order.planId = newPlan.id;
            order.planVersion = newPlan.version;
            order.status = OrderStatus.ISSUED.name();
            order.issuedAt = now;
            order.signDeadline = now.plusMinutes(properties.getSignTimeoutMinutes());
            orderRepository.insert(order);
            orderRepository.addEvent(order.id, "ISSUED", now,
                    "人工确认替代建议 " + adviceNo + "，按新方案版本 " + newPlan.planNo
                            + "（版本 " + newPlan.version + "）下达，确认人 " + request.operator());
        } else {
            OrderPO oldOrder = orderRepository.findById(advice.orderId);
            if (oldOrder != null) {
                orderRepository.addEvent(oldOrder.id, "ADVICE_KEEP_CURRENT", now,
                        "人工确认替代建议 " + adviceNo + "：维持在途指令按原方案版本执行，确认人 "
                                + request.operator());
            }
        }

        idempotentRepository.bindRef(request.requestId(), advice.id);
        return toView(adviceRepository.findByNo(adviceNo), false);
    }

    private AdvicePO requireAdvice(String adviceNo) {
        AdvicePO advice = adviceRepository.findByNo(adviceNo);
        if (advice == null) {
            throw FloodControlException.notFound("替代建议不存在: " + adviceNo);
        }
        return advice;
    }

    private Map<String, Object> fingerprintFields(String adviceNo, AdviceConfirmRequest request) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("requestId", request.requestId());
        fields.put("bizType", "ADVICE_CONFIRM");
        fields.put("adviceNo", adviceNo);
        fields.put("operator", request.operator());
        fields.put("decision", request.decision());
        fields.put("note", request.note());
        return fields;
    }

    private AdviceView toView(AdvicePO po, boolean replay) {
        OrderPO order = orderRepository.findById(po.orderId);
        PlanPO newPlan = planRepository.findById(po.newPlanId);
        return new AdviceView(po.adviceNo,
                order == null ? null : order.orderNo,
                order == null ? null : order.planVersion,
                newPlan == null ? null : newPlan.planNo,
                newPlan == null ? null : newPlan.version,
                po.suggestion, po.status, po.decision, po.confirmNote, po.confirmedBy,
                po.createdAt, po.confirmedAt, replay);
    }
}
