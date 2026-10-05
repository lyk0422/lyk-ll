package com.example.starter.flood.service;

import com.example.starter.flood.config.FloodControlProperties;
import com.example.starter.flood.domain.CapacityJudgment;
import com.example.starter.flood.domain.DangerJudgment;
import com.example.starter.flood.domain.IndicatorJudgment;
import com.example.starter.flood.domain.ReleaseJudgment;
import com.example.starter.flood.domain.WarningLevel;
import com.example.starter.flood.exception.FloodControlException;
import com.example.starter.flood.repo.HydrographPointPO;
import com.example.starter.flood.repo.HydrographRepository;
import com.example.starter.flood.repo.IdempotentRepository;
import com.example.starter.flood.repo.IdempotentRepository.IdempotencyOutcome;
import com.example.starter.flood.repo.OrderPO;
import com.example.starter.flood.repo.OrderRepository;
import com.example.starter.flood.repo.PlanPO;
import com.example.starter.flood.repo.PlanRepository;
import com.example.starter.flood.repo.PondPO;
import com.example.starter.flood.repo.PondRepository;
import com.example.starter.flood.repo.RollingRoutingPO;
import com.example.starter.flood.repo.RollingRoutingRepository;
import com.example.starter.flood.repo.SuggestionPO;
import com.example.starter.flood.repo.SuggestionRepository;
import com.example.starter.flood.repo.WarningLevelEventPO;
import com.example.starter.flood.repo.WarningLevelEventRepository;
import com.example.starter.flood.repo.WarningPO;
import com.example.starter.flood.repo.WarningRepository;
import com.example.starter.flood.service.RollingMath.HydroPoint;
import com.example.starter.flood.service.StorageCurveCalculator.CurvePoint;
import com.example.starter.flood.support.BusinessNoGenerator;
import com.example.starter.flood.support.JsonSupport;
import com.example.starter.flood.web.dto.HydrographPointItem;
import com.example.starter.flood.web.dto.HydrographRequest;
import com.example.starter.flood.web.dto.HydrographView;
import com.example.starter.flood.web.dto.LevelEventView;
import com.example.starter.flood.web.dto.LevelTraceView;
import com.example.starter.flood.web.dto.RecomputeView;
import com.example.starter.flood.web.dto.RollingCompareView;
import com.example.starter.flood.web.dto.RollingRequest;
import com.example.starter.flood.web.dto.RollingVersionView;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
 * 滚动调洪演算服务：按入库洪水过程线每 30 分钟滚动重算一次调洪结果，
 * 恰满 30 分钟触发，新结果生成新版本，旧版本保留可查。
 *
 * <p>泄流能力不足（当前方案泄流能力小于过程线剩余段峰值需求，恰等判临界不触发）时，
 * 自动提升预警级别并追加泄流方案版本，追加与级别变更在同一事务内完成；
 * 在途指令只认其创建时的方案版本，冲突时生成替代建议待人工确认。</p>
 *
 * <p>每个版本保留全部源输入快照（含过程线与库容曲线），任一版本可单独复算。</p>
 */
@Service
public class RollingService {

    static final String CRITERION = "滚动口径: 距上一版本恰满滚动间隔(默认30分钟)触发新版本; "
            + "过程线取值: 恰等点取该点, 区间线性插值, 界外端点持平; "
            + "泄流需求=剩余段峰值入库流量; 能力>需求=充足, 恰等=临界(不提级), 小于=不足(提级+追加方案版本); "
            + "预计蓄水=当前蓄水+时段入库-时段泄流, 下限0; "
            + "指标: 实测>下限=安全, 恰等=临界, 小于=险; 综合: 任一险=险, 否则不足=须泄流, 否则任一临界=临界";

    private final RollingRoutingRepository rollingRepository;
    private final HydrographRepository hydrographRepository;
    private final WarningRepository warningRepository;
    private final WarningService warningService;
    private final PlanRepository planRepository;
    private final OrderRepository orderRepository;
    private final PondRepository pondRepository;
    private final WarningLevelEventRepository levelEventRepository;
    private final SuggestionRepository suggestionRepository;
    private final IdempotentRepository idempotentRepository;
    private final StorageCurveCalculator curveCalculator;
    private final BusinessNoGenerator noGenerator;
    private final JsonSupport jsonSupport;
    private final FloodControlProperties properties;
    private final Clock clock;

    public RollingService(RollingRoutingRepository rollingRepository,
                          HydrographRepository hydrographRepository,
                          WarningRepository warningRepository, WarningService warningService,
                          PlanRepository planRepository, OrderRepository orderRepository,
                          PondRepository pondRepository,
                          WarningLevelEventRepository levelEventRepository,
                          SuggestionRepository suggestionRepository,
                          IdempotentRepository idempotentRepository,
                          StorageCurveCalculator curveCalculator,
                          BusinessNoGenerator noGenerator, JsonSupport jsonSupport,
                          FloodControlProperties properties, Clock floodClock) {
        this.rollingRepository = rollingRepository;
        this.hydrographRepository = hydrographRepository;
        this.warningRepository = warningRepository;
        this.warningService = warningService;
        this.planRepository = planRepository;
        this.orderRepository = orderRepository;
        this.pondRepository = pondRepository;
        this.levelEventRepository = levelEventRepository;
        this.suggestionRepository = suggestionRepository;
        this.idempotentRepository = idempotentRepository;
        this.curveCalculator = curveCalculator;
        this.noGenerator = noGenerator;
        this.jsonSupport = jsonSupport;
        this.properties = properties;
        this.clock = floodClock;
    }

    /**
     * 录入入库洪水过程线（requestId 幂等）；同时刻点重复上报按新值归位。
     */
    @Transactional
    public HydrographView submitHydrograph(String warningNo, HydrographRequest request) {
        WarningPO warning = warningService.requireByNo(warningNo);
        warningService.requireActive(warning);
        String fingerprint = jsonSupport.fingerprint(hydrographFingerprint(warningNo, request));
        LocalDateTime now = LocalDateTime.now(clock);
        IdempotencyOutcome outcome = idempotentRepository.register(
                request.requestId(), "HYDROGRAPH", fingerprint, now);
        if (!outcome.created()) {
            if (outcome.fingerprintMismatch()) {
                throw FloodControlException.conflict(
                        "requestId=" + request.requestId() + " 已用于不同参数的过程线录入");
            }
            return new HydrographView(warningNo, 0,
                    hydrographRepository.countByWarningId(warning.id), true);
        }
        for (HydrographPointItem point : request.points()) {
            hydrographRepository.upsertPoint(warning.id, point.forecastTime(),
                    point.inflowRate(), request.requestId(), now);
        }
        idempotentRepository.bindRef(request.requestId(), warning.id);
        return new HydrographView(warningNo, request.points().size(),
                hydrographRepository.countByWarningId(warning.id), false);
    }

    /**
     * 滚动重算调洪结果（requestId 幂等）：恰满滚动间隔触发新版本；
     * 泄流能力不足时同事务提升预警级别并追加方案版本。
     */
    @Transactional
    public RollingVersionView roll(String warningNo, RollingRequest request) {
        WarningPO warning = warningService.requireByNo(warningNo);
        warningService.requireActive(warning);
        PondPO pond = pondRepository.findById(warning.pondId);
        List<HydrographPointPO> hydroPOs = hydrographRepository.findByWarningId(warning.id);
        if (hydroPOs.isEmpty()) {
            throw FloodControlException.business("HYDROGRAPH_MISSING",
                    "处置链 " + warningNo + " 尚未录入入库洪水过程线，无法滚动演算");
        }
        List<HydroPoint> hydrograph = hydroPOs.stream()
                .map(p -> new HydroPoint(p.forecastTime, p.inflowRate))
                .toList();

        LocalDateTime now = LocalDateTime.now(clock);
        RollingRoutingPO previous = rollingRepository.findLatestByWarningId(warning.id);
        int versionNo = previous == null ? 1 : previous.versionNo + 1;

        // 纯计算先于幂等登记：越界水位等非法输入失败不残留幂等键
        List<CurvePoint> curve = pondRepository.findCurvePoints(pond.id);
        BigDecimal currentStorage = curveCalculator.storageAt(curve, request.currentWaterLevel());
        BigDecimal maxStorage = pondRepository.maxStorage(pond.id);
        BigDecimal availableCapacity = maxStorage.subtract(currentStorage);
        BigDecimal inflowRate = RollingMath.rateAt(hydrograph, now);
        BigDecimal periodInflowVolume = inflowRate.multiply(RollingMath.PERIOD_SECONDS)
                .setScale(4, RoundingMode.HALF_UP);
        PlanPO effective = planRepository.findEffectiveByWarningId(warning.id);
        BigDecimal releaseCapacity = effective == null
                ? BigDecimal.ZERO : effective.totalRelease;
        BigDecimal requiredRelease = RollingMath.requiredRelease(hydrograph, now);
        ReleaseJudgment releaseJudgment = RollingMath.releaseJudgment(releaseCapacity,
                requiredRelease);
        BigDecimal projectedStorage = RollingMath.projectedStorage(currentStorage, inflowRate,
                releaseCapacity);
        IndicatorJudgment dryBeach = RoutingJudgment.indicator(request.dryBeachLength(),
                pond.dryBeachLimit);
        IndicatorJudgment phreatic = RoutingJudgment.indicator(request.phreaticDepth(),
                pond.phreaticLimit);
        DangerJudgment danger = RoutingJudgment.combine(toCapacity(releaseJudgment), dryBeach,
                phreatic);

        boolean insufficient = releaseJudgment == ReleaseJudgment.INSUFFICIENT;
        WarningLevel levelAfter = insufficient
                ? WarningLevel.valueOf(warning.warningLevel).escalate()
                : WarningLevel.valueOf(warning.warningLevel);
        Integer planVersionAfter;
        if (insufficient) {
            planVersionAfter = effective == null ? 1 : effective.versionNo + 1;
        } else {
            planVersionAfter = effective == null ? null : effective.versionNo;
        }

        String fingerprint = jsonSupport.fingerprint(fingerprintFields(warningNo, request));
        IdempotencyOutcome outcome = idempotentRepository.register(
                request.requestId(), "ROLLING", fingerprint, now);
        if (!outcome.created()) {
            if (outcome.fingerprintMismatch()) {
                throw FloodControlException.conflict(
                        "requestId=" + request.requestId() + " 已用于不同参数的滚动演算");
            }
            return toView(rollingRepository.findByRequestId(request.requestId()), true);
        }

        // 恰满滚动间隔才触发新版本（仅约束新请求；幂等重放已在上方返回首次结果）。
        // 拒绝时本事务回滚，幂等键不残留
        if (previous != null) {
            LocalDateTime due = previous.scheduledTime
                    .plusMinutes(properties.getRollingIntervalMinutes());
            if (now.isBefore(due)) {
                throw FloodControlException.business("ROLLING_NOT_DUE",
                        "距上一版本（" + previous.scheduledTime + "）未满 "
                                + properties.getRollingIntervalMinutes()
                                + " 分钟，恰满后触发，下次滚动演算时刻 " + due);
            }
        }

        RollingRoutingPO po = new RollingRoutingPO();
        po.rollingNo = noGenerator.next("GD");
        po.warningId = warning.id;
        po.pondId = pond.id;
        po.versionNo = versionNo;
        po.requestId = request.requestId();
        po.previousId = previous == null ? null : previous.id;
        po.scheduledTime = now;
        po.currentWaterLevel = request.currentWaterLevel();
        po.currentStorage = currentStorage;
        po.maxStorage = maxStorage;
        po.availableCapacity = availableCapacity;
        po.inflowRate = inflowRate;
        po.periodInflowVolume = periodInflowVolume;
        po.releaseCapacity = releaseCapacity;
        po.requiredRelease = requiredRelease;
        po.projectedStorage = projectedStorage;
        po.releaseJudgment = releaseJudgment.name();
        po.dryBeachLength = request.dryBeachLength();
        po.phreaticDepth = request.phreaticDepth();
        po.dryBeachJudgment = dryBeach.name();
        po.phreaticJudgment = phreatic.name();
        po.dangerJudgment = danger.name();
        po.warningLevelAfter = levelAfter.name();
        po.planVersionAfter = planVersionAfter;
        po.inputSnapshot = jsonSupport.toJson(snapshot(warningNo, request, now, versionNo,
                hydroPOs, curve, releaseCapacity, maxStorage, pond));
        po.criterion = CRITERION;
        po.createdAt = now;
        try {
            rollingRepository.insert(po);
        } catch (DuplicateKeyException e) {
            // 并发下同处置链版本号撞车：唯一索引兜底，本事务整体回滚
            throw FloodControlException.conflict("处置链 " + warningNo + " 并发滚动演算版本冲突，请重试");
        }

        if (insufficient) {
            // 追加方案版本与预警级别变更在同一事务内完成
            escalateWarning(warning, levelAfter, po, now);
            PlanPO newPlan = appendPlanVersion(warning, pond, effective, requiredRelease, po,
                    request.requestId(), now);
            if (effective != null) {
                suggestForInFlightOrders(effective, newPlan, now);
            }
        }

        idempotentRepository.bindRef(request.requestId(), po.id);
        return toView(po, false);
    }

    /**
     * 处置链滚动演算版本列表（旧版本保留可查）。
     */
    public List<RollingVersionView> listVersions(String warningNo) {
        WarningPO warning = warningService.requireByNo(warningNo);
        return rollingRepository.findVersionsByWarningId(warning.id).stream()
                .map(po -> toView(po, false))
                .toList();
    }

    /**
     * 演算版本对比查询：任意两版本关键指标并置与差量。
     */
    public RollingCompareView compare(String warningNo, int fromVersion, int toVersion) {
        WarningPO warning = warningService.requireByNo(warningNo);
        RollingRoutingPO from = rollingRepository.findByWarningIdAndVersion(warning.id,
                fromVersion);
        RollingRoutingPO to = rollingRepository.findByWarningIdAndVersion(warning.id, toVersion);
        if (from == null) {
            throw FloodControlException.notFound(
                    "处置链 " + warningNo + " 滚动演算版本不存在: v" + fromVersion);
        }
        if (to == null) {
            throw FloodControlException.notFound(
                    "处置链 " + warningNo + " 滚动演算版本不存在: v" + toVersion);
        }
        return new RollingCompareView(warningNo, fromVersion, toVersion,
                toView(from, false), toView(to, false),
                to.currentStorage.subtract(from.currentStorage),
                to.availableCapacity.subtract(from.availableCapacity),
                to.inflowRate.subtract(from.inflowRate),
                to.requiredRelease.subtract(from.requiredRelease),
                from.releaseJudgment + "->" + to.releaseJudgment,
                from.dangerJudgment + "->" + to.dangerJudgment,
                from.warningLevelAfter + "->" + to.warningLevelAfter,
                String.valueOf(from.planVersionAfter) + "->" + String.valueOf(to.planVersionAfter));
    }

    /**
     * 任一版本单独复算：仅依据该版本留存的源输入快照重算，与存库结果比对。
     */
    public RecomputeView recompute(String rollingNo) {
        RollingRoutingPO po = rollingRepository.findByNo(rollingNo);
        if (po == null) {
            throw FloodControlException.notFound("滚动演算版本不存在: " + rollingNo);
        }
        JsonNode snap = readSnapshot(po.inputSnapshot);
        List<CurvePoint> curve = new ArrayList<>();
        for (JsonNode point : snap.get("storageCurve")) {
            curve.add(new CurvePoint(new BigDecimal(point.get("waterLevel").asText()),
                    new BigDecimal(point.get("storage").asText())));
        }
        List<HydroPoint> hydrograph = new ArrayList<>();
        for (JsonNode point : snap.get("hydrograph")) {
            hydrograph.add(new HydroPoint(LocalDateTime.parse(point.get("forecastTime").asText()),
                    new BigDecimal(point.get("inflowRate").asText())));
        }
        BigDecimal waterLevel = new BigDecimal(snap.get("currentWaterLevel").asText());
        BigDecimal maxStorage = new BigDecimal(snap.get("maxStorage").asText());
        BigDecimal releaseCapacity = new BigDecimal(snap.get("releaseCapacity").asText());
        BigDecimal dryBeachLimit = new BigDecimal(snap.get("dryBeachLimit").asText());
        BigDecimal phreaticLimit = new BigDecimal(snap.get("phreaticLimit").asText());
        LocalDateTime scheduledTime = LocalDateTime.parse(snap.get("scheduledTime").asText());
        BigDecimal dryBeachLength = snap.get("dryBeachLength").isNull() ? null
                : new BigDecimal(snap.get("dryBeachLength").asText());
        BigDecimal phreaticDepth = snap.get("phreaticDepth").isNull() ? null
                : new BigDecimal(snap.get("phreaticDepth").asText());

        BigDecimal storage = curveCalculator.storageAt(curve, waterLevel);
        BigDecimal inflowRate = RollingMath.rateAt(hydrograph, scheduledTime);
        BigDecimal required = RollingMath.requiredRelease(hydrograph, scheduledTime);
        ReleaseJudgment judgment = RollingMath.releaseJudgment(releaseCapacity, required);
        IndicatorJudgment dryBeach = RoutingJudgment.indicator(dryBeachLength, dryBeachLimit);
        IndicatorJudgment phreatic = RoutingJudgment.indicator(phreaticDepth, phreaticLimit);
        DangerJudgment danger = RoutingJudgment.combine(toCapacity(judgment), dryBeach, phreatic);

        boolean consistent = storage.compareTo(po.currentStorage) == 0
                && inflowRate.compareTo(po.inflowRate) == 0
                && required.compareTo(po.requiredRelease) == 0
                && judgment.name().equals(po.releaseJudgment)
                && danger.name().equals(po.dangerJudgment);
        return new RecomputeView(po.rollingNo, po.versionNo, consistent,
                po.currentStorage, storage, po.releaseJudgment, judgment.name(),
                po.dangerJudgment, danger.name(), po.criterion);
    }

    /**
     * 处置链预警级别轨迹（当前级别 + 全部变更事件，留痕可查）。
     */
    public LevelTraceView levelTrace(String warningNo) {
        WarningPO warning = warningService.requireByNo(warningNo);
        List<LevelEventView> events = levelEventRepository.findByWarningId(warning.id).stream()
                .map(e -> new LevelEventView(e.fromLevel, e.toLevel, e.reason,
                        rollingNoOf(e.rollingId), e.createdAt))
                .toList();
        return new LevelTraceView(warningNo, warning.warningLevel, events);
    }

    private void escalateWarning(WarningPO warning, WarningLevel to, RollingRoutingPO rolling,
                                 LocalDateTime now) {
        warningRepository.updateLevel(warning.id, to.name());
        WarningLevelEventPO event = new WarningLevelEventPO();
        event.warningId = warning.id;
        event.fromLevel = warning.warningLevel;
        event.toLevel = to.name();
        event.reason = "滚动演算 v" + rolling.versionNo + " 判定泄流能力不足（需求 "
                + rolling.requiredRelease + " m3/s > 能力 " + rolling.releaseCapacity
                + " m3/s），自动提升预警级别";
        event.rollingId = rolling.id;
        event.createdAt = now;
        levelEventRepository.insert(event);
    }

    private PlanPO appendPlanVersion(WarningPO warning, PondPO pond, PlanPO previous,
                                     BigDecimal requiredRelease, RollingRoutingPO rolling,
                                     String requestId, LocalDateTime now) {
        if (previous != null) {
            planRepository.markSuperseded(previous.id, now);
        }
        BigDecimal totalRelease = requiredRelease.min(pond.downstreamSafeRelease);
        Map<String, Object> opening = new LinkedHashMap<>();
        opening.put("name", "滚动演算自动泄流孔口");
        opening.put("opening", new BigDecimal("1.000"));
        opening.put("release", totalRelease);
        PlanPO plan = new PlanPO();
        plan.planNo = noGenerator.next("FA");
        plan.pondId = pond.id;
        plan.warningId = warning.id;
        plan.routingId = previous == null ? null : previous.routingId;
        plan.requestId = requestId + "#auto-v" + (previous == null ? 1 : previous.versionNo + 1);
        plan.openings = jsonSupport.toJson(List.of(opening));
        plan.totalRelease = totalRelease;
        plan.downstreamSafeRelease = pond.downstreamSafeRelease;
        plan.status = "EFFECTIVE";
        plan.activeKey = pond.id;
        plan.versionNo = previous == null ? 1 : previous.versionNo + 1;
        plan.rollingId = rolling.id;
        plan.createdAt = now;
        try {
            planRepository.insert(plan);
        } catch (DuplicateKeyException e) {
            // 同库其他处置链已有执行中方案：整体回滚（级别提升一并撤销）
            throw FloodControlException.business("PLAN_ALREADY_EFFECTIVE",
                    "尾矿库 " + pond.pondCode + " 已存在执行中的泄流方案，"
                            + "滚动演算追加版本与级别提升已整体回滚");
        }
        return plan;
    }

    private void suggestForInFlightOrders(PlanPO oldPlan, PlanPO newPlan, LocalDateTime now) {
        for (OrderPO order : orderRepository.findInFlightByPlanId(oldPlan.id)) {
            SuggestionPO suggestion = new SuggestionPO();
            suggestion.suggestionNo = noGenerator.next("JY");
            suggestion.orderId = order.id;
            suggestion.oldPlanId = oldPlan.id;
            suggestion.newPlanId = newPlan.id;
            suggestion.oldVersionNo = oldPlan.versionNo;
            suggestion.newVersionNo = newPlan.versionNo;
            suggestion.suggestionDetail = "在途指令 " + order.orderNo + " 仍绑定方案版本 v"
                    + oldPlan.versionNo + "（泄量 " + oldPlan.totalRelease + " m3/s），"
                    + "新版本 v" + newPlan.versionNo + " 泄量 " + newPlan.totalRelease
                    + " m3/s；新版本不溯及在途指令。替代建议：ADOPT_NEW_VERSION=按新版本另行下达指令；"
                    + "KEEP_ORIGINAL_VERSION=维持原指令按创建时版本执行，请人工确认。";
            suggestion.status = "PENDING";
            suggestion.createdAt = now;
            suggestionRepository.insertIfAbsent(suggestion);
        }
    }

    private String rollingNoOf(Long rollingId) {
        if (rollingId == null) {
            return null;
        }
        RollingRoutingPO po = rollingRepository.findById(rollingId);
        return po == null ? null : po.rollingNo;
    }

    private JsonNode readSnapshot(String snapshot) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(snapshot);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("滚动演算输入快照解析失败", e);
        }
    }

    private static CapacityJudgment toCapacity(ReleaseJudgment judgment) {
        return switch (judgment) {
            case INSUFFICIENT -> CapacityJudgment.RELEASE_REQUIRED;
            case CRITICAL -> CapacityJudgment.CRITICAL;
            case SUFFICIENT -> CapacityJudgment.SAFE;
        };
    }

    private Map<String, Object> fingerprintFields(String warningNo, RollingRequest request) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("requestId", request.requestId());
        fields.put("bizType", "ROLLING");
        fields.put("warningNo", warningNo);
        fields.put("currentWaterLevel", request.currentWaterLevel()
                .stripTrailingZeros().toPlainString());
        fields.put("dryBeachLength", request.dryBeachLength() == null ? null
                : request.dryBeachLength().stripTrailingZeros().toPlainString());
        fields.put("phreaticDepth", request.phreaticDepth() == null ? null
                : request.phreaticDepth().stripTrailingZeros().toPlainString());
        return fields;
    }

    private Map<String, Object> hydrographFingerprint(String warningNo,
                                                      HydrographRequest request) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("requestId", request.requestId());
        fields.put("bizType", "HYDROGRAPH");
        fields.put("warningNo", warningNo);
        List<Map<String, Object>> points = new ArrayList<>();
        for (HydrographPointItem point : request.points()) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("forecastTime", point.forecastTime().toString());
            p.put("inflowRate", point.inflowRate().stripTrailingZeros().toPlainString());
            points.add(p);
        }
        fields.put("points", points);
        return fields;
    }

    private Map<String, Object> snapshot(String warningNo, RollingRequest request,
                                         LocalDateTime scheduledTime, int versionNo,
                                         List<HydrographPointPO> hydrograph,
                                         List<CurvePoint> curve, BigDecimal releaseCapacity,
                                         BigDecimal maxStorage, PondPO pond) {
        Map<String, Object> snap = new LinkedHashMap<>(fingerprintFields(warningNo, request));
        snap.put("versionNo", versionNo);
        snap.put("scheduledTime", scheduledTime.toString());
        List<Map<String, Object>> hydroPoints = new ArrayList<>();
        for (HydrographPointPO point : hydrograph) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("forecastTime", point.forecastTime.toString());
            p.put("inflowRate", point.inflowRate);
            hydroPoints.add(p);
        }
        snap.put("hydrograph", hydroPoints);
        List<Map<String, Object>> curvePoints = new ArrayList<>();
        for (CurvePoint point : curve) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("waterLevel", point.waterLevel());
            p.put("storage", point.storage());
            curvePoints.add(p);
        }
        snap.put("storageCurve", curvePoints);
        snap.put("releaseCapacity", releaseCapacity);
        snap.put("maxStorage", maxStorage);
        snap.put("downstreamSafeRelease", pond.downstreamSafeRelease);
        snap.put("dryBeachLimit", pond.dryBeachLimit);
        snap.put("phreaticLimit", pond.phreaticLimit);
        snap.put("rollingIntervalMinutes", properties.getRollingIntervalMinutes());
        return snap;
    }

    private RollingVersionView toView(RollingRoutingPO po, boolean replay) {
        WarningPO warning = warningRepository.findById(po.warningId);
        return new RollingVersionView(po.rollingNo,
                warning == null ? null : warning.warningNo,
                po.versionNo, po.scheduledTime, po.currentWaterLevel, po.currentStorage,
                po.maxStorage, po.availableCapacity, po.inflowRate, po.periodInflowVolume,
                po.releaseCapacity, po.requiredRelease, po.projectedStorage, po.releaseJudgment,
                po.dryBeachLength, po.phreaticDepth, po.dryBeachJudgment, po.phreaticJudgment,
                po.dangerJudgment, po.warningLevelAfter, po.planVersionAfter, po.inputSnapshot,
                po.criterion, replay, po.createdAt);
    }
}
