package com.example.starter.flood.service;

import com.example.starter.flood.config.FloodControlProperties;
import com.example.starter.flood.domain.CapacityJudgment;
import com.example.starter.flood.domain.DangerJudgment;
import com.example.starter.flood.domain.IndicatorJudgment;
import com.example.starter.flood.domain.OrderStatus;
import com.example.starter.flood.domain.WarningLevel;
import com.example.starter.flood.exception.FloodControlException;
import com.example.starter.flood.repo.AdvicePO;
import com.example.starter.flood.repo.AdviceRepository;
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
import com.example.starter.flood.repo.WarningRepository;
import com.example.starter.flood.service.StorageCurveCalculator.CurvePoint;
import com.example.starter.flood.support.BusinessNoGenerator;
import com.example.starter.flood.support.JsonSupport;
import com.example.starter.flood.web.dto.InflowPoint;
import com.example.starter.flood.web.dto.RollingRoutingRequest;
import com.example.starter.flood.web.dto.RollingRoutingView;
import com.example.starter.flood.web.dto.RoutingCompareView;
import com.example.starter.flood.web.dto.RoutingVersionView;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 滚动演算服务：按入库洪水过程线每 30 分钟（可配置）滚动重算调洪结果，
 * 恰满周期触发，新结果生成新版本，旧版本保留可查。
 *
 * <p>泄流能力不足（库容判定须泄流）时，在同一事务内自动提升预警级别并追加
 * 泄流方案新版本；在途指令只认其创建时的方案版本，冲突时生成替代建议待人工确认。</p>
 */
@Service
public class RollingRoutingService {

    private final RoutingRepository routingRepository;
    private final PondRepository pondRepository;
    private final WarningRepository warningRepository;
    private final WarningService warningService;
    private final PlanRepository planRepository;
    private final OrderRepository orderRepository;
    private final AdviceRepository adviceRepository;
    private final IdempotentRepository idempotentRepository;
    private final StorageCurveCalculator curveCalculator;
    private final BusinessNoGenerator noGenerator;
    private final JsonSupport jsonSupport;
    private final FloodControlProperties properties;
    private final Clock clock;

    public RollingRoutingService(RoutingRepository routingRepository, PondRepository pondRepository,
                                 WarningRepository warningRepository, WarningService warningService,
                                 PlanRepository planRepository, OrderRepository orderRepository,
                                 AdviceRepository adviceRepository,
                                 IdempotentRepository idempotentRepository,
                                 StorageCurveCalculator curveCalculator,
                                 BusinessNoGenerator noGenerator, JsonSupport jsonSupport,
                                 FloodControlProperties properties, Clock floodClock) {
        this.routingRepository = routingRepository;
        this.pondRepository = pondRepository;
        this.warningRepository = warningRepository;
        this.warningService = warningService;
        this.planRepository = planRepository;
        this.orderRepository = orderRepository;
        this.adviceRepository = adviceRepository;
        this.idempotentRepository = idempotentRepository;
        this.curveCalculator = curveCalculator;
        this.noGenerator = noGenerator;
        this.jsonSupport = jsonSupport;
        this.properties = properties;
        this.clock = floodClock;
    }

    /**
     * 滚动重算（requestId 幂等）：距上一版本恰满滚动周期才触发；
     * 级别提升与方案追加在同一事务内完成。
     */
    @Transactional
    public RollingRoutingView roll(RollingRoutingRequest request) {
        WarningPO warning = warningService.requireByNo(request.warningNo());
        warningService.requireActive(warning);
        PondPO pond = pondRepository.findById(warning.pondId);
        RoutingPO prev = routingRepository.findLatestByWarningId(warning.id);
        if (prev == null) {
            throw FloodControlException.business("ROLLING_NO_BASE",
                    "处置链 " + request.warningNo() + " 尚无演算版本，请先执行静态调洪演算");
        }
        validateHydrograph(request.hydrograph());

        // 恰满滚动周期触发：当前时刻不早于 上一版本时刻+周期
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime dueAt = prev.createdAt.plusMinutes(properties.getRollingIntervalMinutes());
        if (now.isBefore(dueAt)) {
            throw FloodControlException.business("ROLLING_NOT_DUE",
                    "距上一演算版本未满 " + properties.getRollingIntervalMinutes()
                            + " 分钟滚动周期（上一版本 " + prev.createdAt + "），暂不触发滚动重算");
        }

        // 纯计算先于幂等登记：过程线洪量、泄流折算、库容反查
        BigDecimal inflowVolume = integrateHydrograph(request.hydrograph());
        long elapsedSeconds = Duration.between(prev.createdAt, now).getSeconds();
        PlanPO activePlan = planRepository.findActiveByPond(pond.id);
        BigDecimal releasedVolume = activePlan == null
                ? BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP)
                : activePlan.totalRelease.multiply(BigDecimal.valueOf(elapsedSeconds))
                        .setScale(4, RoundingMode.HALF_UP);
        BigDecimal maxStorage = pondRepository.maxStorage(pond.id);
        BigDecimal newStorage = prev.currentStorage.add(inflowVolume).subtract(releasedVolume);
        if (newStorage.compareTo(BigDecimal.ZERO) < 0) {
            newStorage = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        if (newStorage.compareTo(maxStorage) > 0) {
            newStorage = maxStorage;
        }
        List<CurvePoint> points = pondRepository.findCurvePoints(pond.id);
        BigDecimal newLevel = curveCalculator.levelAt(points, newStorage);
        BigDecimal availableCapacity = maxStorage.subtract(newStorage);

        BigDecimal dryBeach = request.dryBeachLength() != null
                ? request.dryBeachLength() : prev.dryBeachLength;
        BigDecimal phreatic = request.phreaticDepth() != null
                ? request.phreaticDepth() : prev.phreaticDepth;
        CapacityJudgment capacityJudgment = RoutingJudgment.capacity(inflowVolume, availableCapacity);
        IndicatorJudgment dryBeachJudgment = RoutingJudgment.indicator(dryBeach, pond.dryBeachLimit);
        IndicatorJudgment phreaticJudgment = RoutingJudgment.indicator(phreatic, pond.phreaticLimit);
        DangerJudgment danger = RoutingJudgment.combine(
                capacityJudgment, dryBeachJudgment, phreaticJudgment);

        String fingerprint = jsonSupport.fingerprint(fingerprintFields(request));
        IdempotencyOutcome outcome =
                idempotentRepository.register(request.requestId(), "ROLLING_ROUTING", fingerprint, now);
        if (!outcome.created()) {
            if (outcome.fingerprintMismatch()) {
                throw FloodControlException.conflict(
                        "requestId=" + request.requestId() + " 已用于不同参数的滚动演算");
            }
            RoutingPO existing = routingRepository.findByRequestId(request.requestId());
            return toView(existing, warning, true);
        }

        RoutingPO po = new RoutingPO();
        po.routingNo = noGenerator.next("YS");
        po.pondId = pond.id;
        po.warningId = warning.id;
        po.requestId = request.requestId();
        po.currentWaterLevel = newLevel;
        po.currentStorage = newStorage;
        po.maxStorage = maxStorage;
        po.availableCapacity = availableCapacity;
        po.designFloodVolume = inflowVolume;
        po.capacityJudgment = capacityJudgment.name();
        po.dryBeachLength = dryBeach;
        po.phreaticDepth = phreatic;
        po.dryBeachJudgment = dryBeachJudgment.name();
        po.phreaticJudgment = phreaticJudgment.name();
        po.dangerJudgment = danger.name();
        po.version = prev.version + 1;
        po.triggerType = "ROLLING";
        po.inflowHydrograph = jsonSupport.toJson(request.hydrograph());
        po.prevRoutingId = prev.id;
        po.inputSnapshot = jsonSupport.toJson(snapshot(request, pond, prev, elapsedSeconds,
                inflowVolume, releasedVolume, newStorage, maxStorage, availableCapacity,
                dryBeach, phreatic));
        po.criterion = RoutingService.CRITERION;
        po.createdAt = now;
        try {
            routingRepository.insert(po);
        } catch (DuplicateKeyException e) {
            throw FloodControlException.conflict("处置链 " + request.warningNo()
                    + " 并发滚动演算产生相同版本号，请重试");
        }
        RoutingPO saved = routingRepository.findByRequestId(request.requestId());

        // 泄流能力不足：同一事务内提升预警级别并追加泄流方案版本
        String appendedPlanNo = null;
        Integer appendedPlanVersion = null;
        String conflictAdviceNo = null;
        if (CapacityJudgment.RELEASE_REQUIRED == capacityJudgment) {
            escalateWarning(warning, saved, now);
            List<PlanPO> plans = planRepository.findAllByWarningId(warning.id);
            PlanPO latestPlan = plans.isEmpty() ? null : plans.get(plans.size() - 1);
            if (latestPlan != null) {
                PlanPO newPlan = appendPlanVersion(warning, pond, latestPlan, saved, now);
                appendedPlanNo = newPlan.planNo;
                appendedPlanVersion = newPlan.version;
                OrderPO oldOrder = "EFFECTIVE".equals(latestPlan.status)
                        ? orderRepository.findByPlanId(latestPlan.id) : null;
                if (oldOrder != null && !OrderStatus.RECEIPTED.name().equals(oldOrder.status)) {
                    // 在途指令只认创建时的方案版本：冲突时生成替代建议待人工确认
                    AdvicePO advice = createAdvice(oldOrder, latestPlan, newPlan,
                            request.requestId(), now);
                    conflictAdviceNo = advice.adviceNo;
                } else {
                    issueOrder(newPlan, now, "滚动演算判定泄流能力不足，按追加方案版本下达");
                }
            }
        }

        idempotentRepository.bindRef(request.requestId(), saved.id);
        return new RollingRoutingView(saved.routingNo, warning.warningNo, saved.version,
                saved.triggerType, prev.routingNo, saved.currentWaterLevel, saved.currentStorage,
                saved.availableCapacity, inflowVolume, releasedVolume, saved.capacityJudgment,
                saved.dangerJudgment,
                CapacityJudgment.RELEASE_REQUIRED == capacityJudgment
                        ? WarningLevel.of(warning.warningLevel).escalate().name()
                        : warning.warningLevel,
                CapacityJudgment.RELEASE_REQUIRED == capacityJudgment,
                appendedPlanNo, appendedPlanVersion, conflictAdviceNo, saved.inputSnapshot,
                false, saved.createdAt);
    }

    /**
     * 按编号查询任一演算版本（旧版本保留可查），不存在抛 404。
     */
    public RoutingVersionView getVersion(String routingNo) {
        RoutingPO po = routingRepository.findByNo(routingNo);
        if (po == null) {
            throw FloodControlException.notFound("调洪演算不存在: " + routingNo);
        }
        return toVersionView(po);
    }

    /**
     * 查询处置链全部演算版本（按版本号升序）。
     */
    public List<RoutingVersionView> listVersions(String warningNo) {
        WarningPO warning = warningService.requireByNo(warningNo);
        return routingRepository.findByWarningIdOrderByVersion(warning.id).stream()
                .map(this::toVersionView)
                .toList();
    }

    /**
     * 演算版本对比：两个版本须属同一处置链，差量为 to 减 from。
     */
    public RoutingCompareView compare(String fromRoutingNo, String toRoutingNo) {
        RoutingPO from = routingRepository.findByNo(fromRoutingNo);
        if (from == null) {
            throw FloodControlException.notFound("调洪演算不存在: " + fromRoutingNo);
        }
        RoutingPO to = routingRepository.findByNo(toRoutingNo);
        if (to == null) {
            throw FloodControlException.notFound("调洪演算不存在: " + toRoutingNo);
        }
        if (!from.warningId.equals(to.warningId)) {
            throw FloodControlException.business("COMPARE_DIFFERENT_SERIES",
                    "两个演算版本不属于同一处置链，不能对比");
        }
        return new RoutingCompareView(toVersionView(from), toVersionView(to),
                to.currentWaterLevel.subtract(from.currentWaterLevel),
                to.currentStorage.subtract(from.currentStorage),
                to.availableCapacity.subtract(from.availableCapacity),
                to.designFloodVolume.subtract(from.designFloodVolume),
                from.capacityJudgment + "->" + to.capacityJudgment,
                from.dangerJudgment + "->" + to.dangerJudgment);
    }

    /**
     * 提升预警级别并留痕（与滚动演算同事务）。
     */
    private void escalateWarning(WarningPO warning, RoutingPO newVersion, LocalDateTime now) {
        WarningLevel current = WarningLevel.of(warning.warningLevel);
        WarningLevel next = current.escalate();
        warningRepository.updateLevel(warning.id, next.name());
        warningRepository.addEvent(warning.id, "LEVEL_ESCALATED", now,
                "滚动演算版本 " + newVersion.version + "（" + newVersion.routingNo
                        + "）判定泄流能力不足，预警级别 " + current.name() + "→" + next.name());
    }

    /**
     * 追加泄流方案新版本：旧版本若仍生效则置 SUPERSEDED 留痕，
     * 新版本按下游安全泄量上限等比放大孔口泄量。
     */
    private PlanPO appendPlanVersion(WarningPO warning, PondPO pond, PlanPO oldPlan,
                                     RoutingPO newRouting, LocalDateTime now) {
        if ("EFFECTIVE".equals(oldPlan.status)) {
            int rows = planRepository.markSuperseded(oldPlan.id, now);
            if (rows == 0) {
                throw FloodControlException.conflict("方案 " + oldPlan.planNo + " 已被并发取代，请重试");
            }
        }
        List<com.example.starter.flood.web.dto.OpeningItem> oldOpenings =
                jsonSupport.fromJsonList(oldPlan.openings,
                        new com.fasterxml.jackson.core.type.TypeReference<
                                List<com.example.starter.flood.web.dto.OpeningItem>>() {
                        });
        List<com.example.starter.flood.web.dto.OpeningItem> newOpenings =
                scaleOpenings(oldOpenings, oldPlan.totalRelease, pond.downstreamSafeRelease);

        PlanPO newPlan = new PlanPO();
        newPlan.planNo = noGenerator.next("FA");
        newPlan.pondId = pond.id;
        newPlan.warningId = warning.id;
        newPlan.routingId = newRouting.id;
        newPlan.requestId = "AUTO-" + newRouting.routingNo;
        newPlan.openings = jsonSupport.toJson(newOpenings);
        newPlan.totalRelease = PlanService.sumRelease(newOpenings);
        newPlan.downstreamSafeRelease = pond.downstreamSafeRelease;
        newPlan.status = "EFFECTIVE";
        newPlan.version = oldPlan.version + 1;
        newPlan.activeKey = pond.id;
        newPlan.createdAt = now;
        try {
            planRepository.insert(newPlan);
        } catch (DuplicateKeyException e) {
            throw FloodControlException.conflict("处置链 " + warning.warningNo
                    + " 并发追加方案版本冲突，请重试");
        }
        return newPlan;
    }

    /**
     * 孔口泄量按目标总泄量等比放大，末项吸收舍入残差保证合计恰为目标值。
     */
    private List<com.example.starter.flood.web.dto.OpeningItem> scaleOpenings(
            List<com.example.starter.flood.web.dto.OpeningItem> oldOpenings,
            BigDecimal oldTotal, BigDecimal targetTotal) {
        List<com.example.starter.flood.web.dto.OpeningItem> result = new ArrayList<>();
        if (oldTotal.compareTo(BigDecimal.ZERO) <= 0) {
            return new ArrayList<>(oldOpenings);
        }
        BigDecimal factor = targetTotal.divide(oldTotal, 12, RoundingMode.HALF_UP);
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < oldOpenings.size(); i++) {
            com.example.starter.flood.web.dto.OpeningItem item = oldOpenings.get(i);
            BigDecimal release = item.release().multiply(factor)
                    .setScale(3, RoundingMode.HALF_UP);
            if (i == oldOpenings.size() - 1) {
                release = targetTotal.subtract(sum).setScale(3, RoundingMode.HALF_UP);
            }
            sum = sum.add(release);
            result.add(new com.example.starter.flood.web.dto.OpeningItem(
                    item.name(), item.opening(), release));
        }
        return result;
    }

    /**
     * 在途指令与新方案版本冲突：生成替代建议（PENDING），在途指令仍钉住旧版本。
     */
    private AdvicePO createAdvice(OrderPO oldOrder, PlanPO oldPlan, PlanPO newPlan,
                                  String sourceRequestId, LocalDateTime now) {
        Map<String, Object> suggestion = new LinkedHashMap<>();
        suggestion.put("orderNo", oldOrder.orderNo);
        suggestion.put("orderStatus", oldOrder.status);
        suggestion.put("orderPlanNo", oldPlan.planNo);
        suggestion.put("orderPlanVersion", oldPlan.version);
        suggestion.put("newPlanNo", newPlan.planNo);
        suggestion.put("newPlanVersion", newPlan.version);
        suggestion.put("newOpenings", jsonSupport.fromJsonList(newPlan.openings,
                new com.fasterxml.jackson.core.type.TypeReference<
                        List<com.example.starter.flood.web.dto.OpeningItem>>() {
                }));
        suggestion.put("recommendation",
                "在途指令仍按方案版本 " + oldPlan.version + " 执行至回执；建议人工确认后按新版本方案 "
                        + newPlan.planNo + "（版本 " + newPlan.version + "）另行下达指令，"
                        + "或维持当前指令不变");

        AdvicePO advice = new AdvicePO();
        advice.adviceNo = noGenerator.next("JY");
        advice.orderId = oldOrder.id;
        advice.newPlanId = newPlan.id;
        advice.suggestion = jsonSupport.toJson(suggestion);
        advice.status = "PENDING";
        advice.requestId = sourceRequestId;
        advice.createdAt = now;
        adviceRepository.insert(advice);
        orderRepository.addEvent(oldOrder.id, "CONFLICT_ADVICE", now,
                "新方案版本 " + newPlan.planNo + "（版本 " + newPlan.version + "）已生效，"
                        + "在途指令仍钉住版本 " + oldPlan.version + "，替代建议 "
                        + advice.adviceNo + " 待人工确认");
        return advice;
    }

    /**
     * 为新方案版本下达指令（钉住新版本号）。
     */
    private void issueOrder(PlanPO plan, LocalDateTime now, String detail) {
        OrderPO order = new OrderPO();
        order.orderNo = noGenerator.next("ZL");
        order.planId = plan.id;
        order.planVersion = plan.version;
        order.status = OrderStatus.ISSUED.name();
        order.issuedAt = now;
        order.signDeadline = now.plusMinutes(properties.getSignTimeoutMinutes());
        orderRepository.insert(order);
        orderRepository.addEvent(order.id, "ISSUED", now,
                detail + "，方案 " + plan.planNo + "（版本 " + plan.version + "），签收时限 "
                        + properties.getSignTimeoutMinutes() + " 分钟");
    }

    private void validateHydrograph(List<InflowPoint> hydrograph) {
        if (hydrograph.get(0).minute() != 0) {
            throw FloodControlException.business("HYDROGRAPH_INVALID",
                    "入库洪水过程线首点分钟偏移必须为 0");
        }
        for (int i = 1; i < hydrograph.size(); i++) {
            if (hydrograph.get(i).minute() <= hydrograph.get(i - 1).minute()) {
                throw FloodControlException.business("HYDROGRAPH_INVALID",
                        "入库洪水过程线分钟偏移必须严格递增");
            }
        }
    }

    /**
     * 过程线梯形积分：相邻点流量均值 × 时段秒数，得到时段洪量（立方米，4 位小数）。
     */
    static BigDecimal integrateHydrograph(List<InflowPoint> hydrograph) {
        BigDecimal volume = BigDecimal.ZERO;
        for (int i = 1; i < hydrograph.size(); i++) {
            InflowPoint prev = hydrograph.get(i - 1);
            InflowPoint curr = hydrograph.get(i);
            BigDecimal avgRate = prev.inflowRate().add(curr.inflowRate())
                    .divide(BigDecimal.valueOf(2), 12, RoundingMode.HALF_UP);
            long seconds = (long) (curr.minute() - prev.minute()) * 60L;
            volume = volume.add(avgRate.multiply(BigDecimal.valueOf(seconds)));
        }
        return volume.setScale(4, RoundingMode.HALF_UP);
    }

    private Map<String, Object> fingerprintFields(RollingRoutingRequest request) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("requestId", request.requestId());
        fields.put("bizType", "ROLLING_ROUTING");
        fields.put("warningNo", request.warningNo());
        List<Map<String, Object>> points = new ArrayList<>();
        for (InflowPoint point : request.hydrograph()) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("minute", point.minute());
            p.put("inflowRate", point.inflowRate().stripTrailingZeros().toPlainString());
            points.add(p);
        }
        fields.put("hydrograph", points);
        fields.put("dryBeachLength", request.dryBeachLength() == null
                ? null : request.dryBeachLength().stripTrailingZeros().toPlainString());
        fields.put("phreaticDepth", request.phreaticDepth() == null
                ? null : request.phreaticDepth().stripTrailingZeros().toPlainString());
        return fields;
    }

    private Map<String, Object> snapshot(RollingRoutingRequest request, PondPO pond,
                                         RoutingPO prev, long elapsedSeconds,
                                         BigDecimal inflowVolume, BigDecimal releasedVolume,
                                         BigDecimal newStorage, BigDecimal maxStorage,
                                         BigDecimal availableCapacity,
                                         BigDecimal dryBeach, BigDecimal phreatic) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.putAll(fingerprintFields(request));
        snap.put("pondCode", pond.pondCode);
        snap.put("prevRoutingNo", prev.routingNo);
        snap.put("prevVersion", prev.version);
        snap.put("prevCurrentStorage", prev.currentStorage);
        snap.put("prevCreatedAt", prev.createdAt.toString());
        snap.put("elapsedSeconds", elapsedSeconds);
        snap.put("inflowVolume", inflowVolume);
        snap.put("releasedVolume", releasedVolume);
        snap.put("currentStorage", newStorage);
        snap.put("maxStorage", maxStorage);
        snap.put("availableCapacity", availableCapacity);
        snap.put("dryBeachLength", dryBeach);
        snap.put("phreaticDepth", phreatic);
        return snap;
    }

    private RollingRoutingView toView(RoutingPO po, WarningPO warning, boolean replay) {
        RoutingPO prev = po.prevRoutingId == null
                ? null : routingRepository.findById(po.prevRoutingId);
        PlanPO appended = planRepository.findByRequestId("AUTO-" + po.routingNo);
        String adviceNo = null;
        if (appended != null) {
            // 追加版本若引发冲突，建议挂在新方案上一版本的在途指令上
            List<PlanPO> plans = planRepository.findAllByWarningId(po.warningId);
            for (PlanPO plan : plans) {
                OrderPO order = orderRepository.findByPlanId(plan.id);
                if (order != null) {
                    AdvicePO advice = adviceRepository.findByOrderId(order.id);
                    if (advice != null && advice.newPlanId.equals(appended.id)) {
                        adviceNo = advice.adviceNo;
                    }
                }
            }
        }
        boolean escalated = CapacityJudgment.RELEASE_REQUIRED.name().equals(po.capacityJudgment);
        return new RollingRoutingView(po.routingNo, warning.warningNo, po.version,
                po.triggerType, prev == null ? null : prev.routingNo, po.currentWaterLevel,
                po.currentStorage, po.availableCapacity, po.designFloodVolume, null,
                po.capacityJudgment, po.dangerJudgment, warning.warningLevel, escalated,
                appended == null ? null : appended.planNo,
                appended == null ? null : appended.version,
                adviceNo, po.inputSnapshot, replay, po.createdAt);
    }

    private RoutingVersionView toVersionView(RoutingPO po) {
        RoutingPO prev = po.prevRoutingId == null
                ? null : routingRepository.findById(po.prevRoutingId);
        WarningPO warning = warningRepository.findById(po.warningId);
        return new RoutingVersionView(po.routingNo,
                warning == null ? null : warning.warningNo, po.version, po.triggerType,
                prev == null ? null : prev.routingNo, po.currentWaterLevel, po.currentStorage,
                po.availableCapacity, po.designFloodVolume, po.capacityJudgment,
                po.dangerJudgment, po.inflowHydrograph, po.inputSnapshot, po.criterion,
                po.createdAt);
    }
}
