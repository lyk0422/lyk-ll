package com.example.starter.flood.service;

import com.example.starter.flood.config.FloodControlProperties;
import com.example.starter.flood.domain.CapacityJudgment;
import com.example.starter.flood.domain.ChainStatus;
import com.example.starter.flood.domain.OrderStatus;
import com.example.starter.flood.exception.FloodControlException;
import com.example.starter.flood.repo.IdempotentRepository;
import com.example.starter.flood.repo.IdempotentRepository.IdempotencyOutcome;
import com.example.starter.flood.repo.OrderPO;
import com.example.starter.flood.repo.OrderRepository;
import com.example.starter.flood.repo.PlanPO;
import com.example.starter.flood.repo.PlanRepository;
import com.example.starter.flood.repo.PondPO;
import com.example.starter.flood.repo.PondRepository;
import com.example.starter.flood.repo.RoutingPO;
import com.example.starter.flood.repo.RoutingRepository;
import com.example.starter.flood.repo.WarningPO;
import com.example.starter.flood.support.BusinessNoGenerator;
import com.example.starter.flood.support.JsonSupport;
import com.example.starter.flood.web.dto.OpeningItem;
import com.example.starter.flood.web.dto.PlanRequest;
import com.example.starter.flood.web.dto.PlanView;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 泄流方案服务。
 *
 * <p>规则：孔口组合泄量之和不得超过下游安全泄量（恰等合法）；
 * 同一尾矿库同时刻只允许一个执行中方案，由 release_plan.active_key 唯一索引兜底，
 * 险情与方案并发也不会产生两个生效方案；方案生效同时下达“下达”态指令，
 * 签收时限为配置值（默认 15 分钟）。</p>
 */
@Service
public class PlanService {

    private final PlanRepository planRepository;
    private final OrderRepository orderRepository;
    private final PondRepository pondRepository;
    private final RoutingRepository routingRepository;
    private final WarningService warningService;
    private final IdempotentRepository idempotentRepository;
    private final BusinessNoGenerator noGenerator;
    private final JsonSupport jsonSupport;
    private final FloodControlProperties properties;
    private final Clock clock;

    public PlanService(PlanRepository planRepository, OrderRepository orderRepository,
                       PondRepository pondRepository, RoutingRepository routingRepository,
                       WarningService warningService,
                       IdempotentRepository idempotentRepository,
                       BusinessNoGenerator noGenerator, JsonSupport jsonSupport,
                       FloodControlProperties properties, Clock floodClock) {
        this.planRepository = planRepository;
        this.orderRepository = orderRepository;
        this.pondRepository = pondRepository;
        this.routingRepository = routingRepository;
        this.warningService = warningService;
        this.idempotentRepository = idempotentRepository;
        this.noGenerator = noGenerator;
        this.jsonSupport = jsonSupport;
        this.properties = properties;
        this.clock = floodClock;
    }

    /**
     * 制定泄流方案并下达指令（requestId 幂等）。
     */
    @Transactional
    public PlanView create(PlanRequest request) {
        WarningPO warning = warningService.requireByNo(request.warningNo());
        warningService.requireActive(warning);
        RoutingPO routing = routingRepository.findByNo(request.routingNo());
        if (routing == null || !warning.id.equals(routing.warningId)) {
            throw FloodControlException.notFound(
                    "处置链 " + request.warningNo() + " 下调洪演算不存在: " + request.routingNo());
        }
        if (!CapacityJudgment.RELEASE_REQUIRED.name().equals(routing.capacityJudgment)
                && !"DANGER".equals(routing.dangerJudgment)) {
            throw FloodControlException.business("RELEASE_NOT_REQUIRED",
                    "演算结论非“须泄流/险”（当前库容判定=" + routing.capacityJudgment
                            + "），无需制定泄流方案");
        }
        PondPO pond = pondRepository.findById(warning.pondId);

        // 纯计算校验先于幂等登记：总泄量不得超过下游安全泄量（恰等合法）
        BigDecimal totalRelease = sumRelease(request.openings());
        if (!ReleaseMath.withinSafeLimit(totalRelease, pond.downstreamSafeRelease)) {
            throw FloodControlException.business("RELEASE_EXCEEDS_SAFE_LIMIT",
                    "方案总泄量 " + totalRelease + " 超过下游安全泄量 "
                            + pond.downstreamSafeRelease);
        }

        String fingerprint = jsonSupport.fingerprint(fingerprintFields(request));
        LocalDateTime now = LocalDateTime.now(clock);
        IdempotencyOutcome outcome =
                idempotentRepository.register(request.requestId(), "PLAN", fingerprint, now);
        if (!outcome.created()) {
            if (outcome.fingerprintMismatch()) {
                throw FloodControlException.conflict(
                        "requestId=" + request.requestId() + " 已用于不同参数的泄流方案");
            }
            return toView(planRepository.findByRequestId(request.requestId()), true);
        }

        PlanPO plan = new PlanPO();
        plan.planNo = noGenerator.next("FA");
        plan.pondId = pond.id;
        plan.warningId = warning.id;
        plan.routingId = routing.id;
        plan.requestId = request.requestId();
        plan.openings = jsonSupport.toJson(request.openings());
        plan.totalRelease = totalRelease;
        plan.downstreamSafeRelease = pond.downstreamSafeRelease;
        plan.status = "EFFECTIVE";
        plan.version = planRepository.maxVersionByWarningId(warning.id) + 1;
        plan.activeKey = pond.id;
        plan.createdAt = now;
        try {
            planRepository.insert(plan);
        } catch (DuplicateKeyException e) {
            throw FloodControlException.business("PLAN_ALREADY_EFFECTIVE",
                    "尾矿库 " + pond.pondCode + " 已存在执行中的泄流方案，同一时刻不得重复生效");
        }

        // 方案生效即下达指令，签收时限恰为配置分钟数（默认 15 分钟）
        OrderPO order = new OrderPO();
        order.orderNo = noGenerator.next("ZL");
        order.planId = plan.id;
        // 指令钉住创建时的方案版本：后续新版本不溯及在途指令
        order.planVersion = plan.version;
        order.status = OrderStatus.ISSUED.name();
        order.issuedAt = now;
        order.signDeadline = now.plusMinutes(properties.getSignTimeoutMinutes());
        orderRepository.insert(order);
        orderRepository.addEvent(order.id, "ISSUED", now,
                "泄流方案 " + plan.planNo + " 已下达，签收时限 "
                        + properties.getSignTimeoutMinutes() + " 分钟");

        idempotentRepository.bindRef(request.requestId(), plan.id);
        return toView(plan, false);
    }

    /**
     * 按方案编号查询（不存在 404）。
     */
    public PlanPO requireByNo(String planNo) {
        PlanPO plan = planRepository.findByNo(planNo);
        if (plan == null) {
            throw FloodControlException.notFound("泄流方案不存在: " + planNo);
        }
        return plan;
    }

    /**
     * 查询方案所在处置链的全部版本（旧版本留痕可查，按版本号升序）。
     */
    public List<com.example.starter.flood.web.dto.PlanVersionView> listVersions(String planNo) {
        PlanPO plan = requireByNo(planNo);
        return planRepository.findAllByWarningId(plan.warningId).stream()
                .map(p -> {
                    OrderPO order = orderRepository.findByPlanId(p.id);
                    return new com.example.starter.flood.web.dto.PlanVersionView(
                            p.planNo, p.version, p.status, p.totalRelease,
                            order == null ? null : order.orderNo,
                            p.createdAt, p.supersededAt, p.archivedAt);
                })
                .toList();
    }

    /**
     * 孔口泄量求和（保留 3 位小数）。
     */
    static BigDecimal sumRelease(List<OpeningItem> openings) {
        BigDecimal total = BigDecimal.ZERO;
        for (OpeningItem item : openings) {
            total = total.add(item.release());
        }
        return total.setScale(3, java.math.RoundingMode.HALF_UP);
    }

    private Map<String, Object> fingerprintFields(PlanRequest request) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("requestId", request.requestId());
        fields.put("bizType", "PLAN");
        fields.put("warningNo", request.warningNo());
        fields.put("routingNo", request.routingNo());
        List<Map<String, Object>> openingList = new ArrayList<>();
        for (OpeningItem item : request.openings()) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("name", item.name());
            o.put("opening", item.opening().stripTrailingZeros().toPlainString());
            o.put("release", item.release().stripTrailingZeros().toPlainString());
            openingList.add(o);
        }
        fields.put("openings", openingList);
        return fields;
    }

    private PlanView toView(PlanPO plan, boolean replay) {
        if (plan == null) {
            throw FloodControlException.notFound("泄流方案尚未生成成功");
        }
        List<OpeningItem> openings = jsonSupport.fromJsonList(plan.openings,
                new com.fasterxml.jackson.core.type.TypeReference<List<OpeningItem>>() {
                });
        OrderPO order = orderRepository.findByPlanId(plan.id);
        RoutingPO routing = routingRepository.findById(plan.routingId);
        PondPO pond = pondRepository.findById(plan.pondId);
        return new PlanView(plan.planNo, warningNo(plan.warningId),
                routing == null ? null : routing.routingNo,
                pond == null ? null : pond.pondCode, openings, plan.totalRelease,
                plan.downstreamSafeRelease, plan.status,
                order == null ? null : order.orderNo,
                order == null ? null : order.status, replay, plan.createdAt);
    }

    private String warningNo(Long warningId) {
        WarningPO warning = warningService.findWarning(warningId);
        return warning == null ? null : warning.warningNo;
    }
}
