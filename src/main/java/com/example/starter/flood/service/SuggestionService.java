package com.example.starter.flood.service;

import com.example.starter.flood.exception.FloodControlException;
import com.example.starter.flood.repo.IdempotentRepository;
import com.example.starter.flood.repo.IdempotentRepository.IdempotencyOutcome;
import com.example.starter.flood.repo.OrderPO;
import com.example.starter.flood.repo.OrderRepository;
import com.example.starter.flood.repo.PlanPO;
import com.example.starter.flood.repo.PlanRepository;
import com.example.starter.flood.repo.SuggestionPO;
import com.example.starter.flood.repo.SuggestionRepository;
import com.example.starter.flood.support.JsonSupport;
import com.example.starter.flood.web.dto.ConfirmSuggestionRequest;
import com.example.starter.flood.web.dto.SuggestionView;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 在途指令冲突替代建议服务：新方案版本不溯及在途指令，冲突建议由人工确认，留痕可查。
 * 确认按 requestId 幂等，同键同参重放首次结果，同键异参 409。
 */
@Service
public class SuggestionService {

    private final SuggestionRepository suggestionRepository;
    private final OrderRepository orderRepository;
    private final PlanRepository planRepository;
    private final IdempotentRepository idempotentRepository;
    private final JsonSupport jsonSupport;
    private final Clock clock;

    public SuggestionService(SuggestionRepository suggestionRepository,
                             OrderRepository orderRepository, PlanRepository planRepository,
                             IdempotentRepository idempotentRepository, JsonSupport jsonSupport,
                             Clock floodClock) {
        this.suggestionRepository = suggestionRepository;
        this.orderRepository = orderRepository;
        this.planRepository = planRepository;
        this.idempotentRepository = idempotentRepository;
        this.jsonSupport = jsonSupport;
        this.clock = floodClock;
    }

    /**
     * 查询指令的全部替代建议（留痕可查）。
     */
    public List<SuggestionView> listByOrder(String orderNo) {
        OrderPO order = orderRepository.findByNo(orderNo);
        if (order == null) {
            throw FloodControlException.notFound("泄流指令不存在: " + orderNo);
        }
        return suggestionRepository.findByOrderId(order.id).stream()
                .map(po -> toView(po, false))
                .toList();
    }

    /**
     * 人工确认替代建议（requestId 幂等）。
     */
    @Transactional
    public SuggestionView confirm(String suggestionNo, ConfirmSuggestionRequest request) {
        SuggestionPO po = suggestionRepository.findByNo(suggestionNo);
        if (po == null) {
            throw FloodControlException.notFound("替代建议不存在: " + suggestionNo);
        }
        String fingerprint = jsonSupport.fingerprint(fingerprintFields(suggestionNo, request));
        LocalDateTime now = LocalDateTime.now(clock);
        IdempotencyOutcome outcome = idempotentRepository.register(
                request.requestId(), "SUGGESTION_CONFIRM", fingerprint, now);
        if (!outcome.created()) {
            if (outcome.fingerprintMismatch()) {
                throw FloodControlException.conflict(
                        "requestId=" + request.requestId() + " 已用于不同参数的建议确认");
            }
            SuggestionPO existing = suggestionRepository.findByConfirmRequestId(
                    request.requestId());
            return toView(existing == null ? po : existing, true);
        }
        int rows = suggestionRepository.confirm(po.id, request.decision(), request.operator(),
                now, request.note(), request.requestId());
        if (rows == 0) {
            throw FloodControlException.business("SUGGESTION_ALREADY_CONFIRMED",
                    "替代建议 " + suggestionNo + " 已人工确认，当前状态 " + po.status);
        }
        idempotentRepository.bindRef(request.requestId(), po.id);
        return toView(suggestionRepository.findByNo(suggestionNo), false);
    }

    private Map<String, Object> fingerprintFields(String suggestionNo,
                                                  ConfirmSuggestionRequest request) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("requestId", request.requestId());
        fields.put("bizType", "SUGGESTION_CONFIRM");
        fields.put("suggestionNo", suggestionNo);
        fields.put("operator", request.operator());
        fields.put("decision", request.decision());
        fields.put("note", request.note());
        return fields;
    }

    private SuggestionView toView(SuggestionPO po, boolean replay) {
        OrderPO order = orderRepository.findById(po.orderId);
        PlanPO oldPlan = planRepository.findById(po.oldPlanId);
        PlanPO newPlan = planRepository.findById(po.newPlanId);
        return new SuggestionView(po.suggestionNo,
                order == null ? null : order.orderNo,
                oldPlan == null ? null : oldPlan.planNo,
                newPlan == null ? null : newPlan.planNo,
                po.oldVersionNo, po.newVersionNo, po.suggestionDetail, po.status, po.decision,
                po.confirmedBy, po.confirmedAt, po.confirmNote, replay, po.createdAt);
    }
}
