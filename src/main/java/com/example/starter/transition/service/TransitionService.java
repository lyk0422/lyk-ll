package com.example.starter.transition.service;

import com.example.starter.transition.ApiException;
import com.example.starter.transition.TransitionStatus;
import com.example.starter.transition.dto.ApplyOrderRequest;
import com.example.starter.transition.dto.DisposeRollsRequest;
import com.example.starter.transition.dto.ExecuteOrderRequest;
import com.example.starter.transition.dto.IdempotentResult;
import com.example.starter.transition.dto.InspectionRequest;
import com.example.starter.transition.dto.OrderView;
import com.example.starter.transition.dto.ReexamRequest;
import com.example.starter.transition.dto.RollView;
import com.example.starter.transition.dto.TransitionRecomputeView;
import com.example.starter.transition.model.BlendControlState;
import com.example.starter.transition.model.BlendRatioVersion;
import com.example.starter.transition.model.Grade;
import com.example.starter.transition.model.IdempotentRequest;
import com.example.starter.transition.model.RollInspection;
import com.example.starter.transition.model.TransitionOrder;
import com.example.starter.transition.model.TransitionRoll;
import com.example.starter.transition.repo.BlendControlRepository;
import com.example.starter.transition.repo.BlendRatioVersionRepository;
import com.example.starter.transition.repo.CleaningWindowRepository;
import com.example.starter.transition.repo.GradeRepository;
import com.example.starter.transition.repo.LateEventRepository;
import com.example.starter.transition.repo.MachineSlotRepository;
import com.example.starter.transition.repo.RollInspectionRepository;
import com.example.starter.transition.repo.TransitionOrderRepository;
import com.example.starter.transition.repo.TransitionRollRepository;
import com.example.starter.transition.repo.VerdictEventRepository;
import com.example.starter.transition.support.FingerprintUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;

/**
 * 造纸机改品种过渡料控制服务。
 *
 * <p>流转：申请→方式判定→执行→过渡料处置→完成→归档（终态）。</p>
 */
@Service
public class TransitionService {

    static final String BIZ_APPLY = "APPLY_ORDER";
    static final String BIZ_INSPECTION = "SUBMIT_INSPECTION";
    static final String BIZ_REEXAM = "SUBMIT_REEXAM";

    static final String INSPECTION_INITIAL = "INITIAL";
    static final String INSPECTION_REEXAM = "REEXAM";

    private static final DateTimeFormatter NO_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final GradeRepository gradeRepository;
    private final TransitionOrderRepository orderRepository;
    private final MachineSlotRepository machineSlotRepository;
    private final CleaningWindowRepository cleaningWindowRepository;
    private final TransitionRollRepository rollRepository;
    private final RollInspectionRepository inspectionRepository;
    private final VerdictEventRepository verdictEventRepository;
    private final LateEventRepository lateEventRepository;
    private final BlendRatioVersionRepository blendRatioVersionRepository;
    private final BlendControlRepository blendControlRepository;
    private final IdempotencyGuard idempotencyGuard;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public TransitionService(GradeRepository gradeRepository,
                             TransitionOrderRepository orderRepository,
                             MachineSlotRepository machineSlotRepository,
                             CleaningWindowRepository cleaningWindowRepository,
                             TransitionRollRepository rollRepository,
                             RollInspectionRepository inspectionRepository,
                             VerdictEventRepository verdictEventRepository,
                             LateEventRepository lateEventRepository,
                             BlendRatioVersionRepository blendRatioVersionRepository,
                             BlendControlRepository blendControlRepository,
                             IdempotencyGuard idempotencyGuard,
                             ObjectMapper objectMapper,
                             Clock clock) {
        this.gradeRepository = gradeRepository;
        this.orderRepository = orderRepository;
        this.machineSlotRepository = machineSlotRepository;
        this.cleaningWindowRepository = cleaningWindowRepository;
        this.rollRepository = rollRepository;
        this.inspectionRepository = inspectionRepository;
        this.verdictEventRepository = verdictEventRepository;
        this.lateEventRepository = lateEventRepository;
        this.blendRatioVersionRepository = blendRatioVersionRepository;
        this.blendControlRepository = blendControlRepository;
        this.idempotencyGuard = idempotencyGuard;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** 申请改品种单（requestId 幂等；同一纸机同时刻唯一受理）；可携带回抄浆掺配比例。 */
    @Transactional
    public IdempotentResult<OrderView> apply(ApplyOrderRequest request) {
        String fingerprint = FingerprintUtil.sha256(request.requestId(), request);
        IdempotentRequest existing = idempotencyGuard.lookup(request.requestId(), BIZ_APPLY, fingerprint);
        if (existing != null) {
            return idempotencyGuard.replay(existing, new TypeReference<OrderView>() {
            });
        }

        Grade fromGrade = gradeRepository.findByCode(request.fromGrade())
                .orElseThrow(() -> ApiException.badRequest("原品种不存在: " + request.fromGrade()));
        Grade toGrade = gradeRepository.findByCode(request.toGrade())
                .orElseThrow(() -> ApiException.badRequest("新品种不存在: " + request.toGrade()));

        // 掺配比例须在合成允许区间内，恰在区间边界合法；越界拒绝且随事务回滚不留任何记录。
        if (request.targetBlendRatioPct() != null
                && !TransitionRules.withinBlendRatioRange(request.targetBlendRatioPct())) {
            throw ApiException.badRequest("掺配比例 " + request.targetBlendRatioPct()
                    + " 超出合成允许区间 [5.000, 30.000] %");
        }

        // 纸机占用：唯一索引保证同一纸机同时刻唯一受理；失败随事务回滚，不留幂等占位。
        String orderNo = newOrderNo();
        if (!machineSlotRepository.tryOccupy(request.paperMachine(), orderNo)) {
            throw ApiException.conflict("纸机 " + request.paperMachine() + " 已有执行中改品种单");
        }

        TransitionOrder order = new TransitionOrder();
        order.setOrderNo(orderNo);
        order.setPaperMachine(request.paperMachine());
        order.setFromGrade(fromGrade.getGradeCode());
        order.setToGrade(toGrade.getGradeCode());
        order.setBasisWeightFrom(fromGrade.getBasisWeight());
        order.setBasisWeightTo(toGrade.getBasisWeight());
        order.setAshLevelFrom(fromGrade.getAshLevel());
        order.setAshLevelTo(toGrade.getAshLevel());
        order.setStatus(TransitionStatus.APPLIED);
        order.setAppliedRequestId(request.requestId());
        order.setAppliedAt(now());
        orderRepository.insert(order);

        // 携带掺配比例：初始比例版本（版本 1）与执行控制状态随申请在同一事务落库。
        if (request.targetBlendRatioPct() != null) {
            BlendRatioVersion initial = new BlendRatioVersion();
            initial.setOrderNo(orderNo);
            initial.setVersionNo(1);
            initial.setTargetRatioPct(request.targetBlendRatioPct());
            initial.setRatioDeltaPct(null);
            initial.setAdjustMode("INITIAL");
            initial.setRequestId(request.requestId());
            initial.setDecidedAt(now());
            blendRatioVersionRepository.insert(initial);
            blendControlRepository.insertState(orderNo, 1, now());
        }

        idempotencyGuard.register(request.requestId(), orderNo, toView(order));
        return new IdempotentResult<>(request.requestId(), false, toView(order));
    }

    /** 方式判定：克重差 ≤10%（含）直切；灰分等级差 &gt;2 级须冲洗网部，恰 2 级合法直切。 */
    @Transactional
    public OrderView decide(String orderNo) {
        TransitionOrder order = requireOrder(orderNo);
        requireStatus(order, TransitionStatus.APPLIED);

        BigDecimal from = order.getBasisWeightFrom();
        BigDecimal delta = TransitionRules.basisWeightDeltaPct(from, order.getBasisWeightTo());
        int ashDelta = TransitionRules.ashLevelDelta(order.getAshLevelFrom(), order.getAshLevelTo());

        order.setBasisWeightDeltaPct(delta);
        order.setAshLevelDelta(ashDelta);
        // 恰 10.0000% 视为直切。
        order.setDirectSwitch(TransitionRules.isDirectSwitch(delta));
        // 恰 2 级合法直切；超过 2 级才冲洗网部。
        order.setWireFlush(TransitionRules.needsWireFlush(ashDelta));
        order.setStatus(TransitionStatus.DECIDED);
        order.setDecidedAt(now());
        orderRepository.updateDecision(order);
        return toView(order);
    }

    /** 执行：参数须在新品种工艺窗口内（含边界）；过渡料长度 = 车速×切换时间/60。 */
    @Transactional
    public OrderView execute(String orderNo, ExecuteOrderRequest request) {
        TransitionOrder order = requireOrder(orderNo);
        requireStatus(order, TransitionStatus.DECIDED);
        if (request.switchTimeSec() <= 0) {
            throw ApiException.badRequest("切换时间须为正整数秒");
        }
        Grade toGrade = gradeRepository.findByCode(order.getToGrade())
                .orElseThrow(() -> ApiException.badRequest("新品种不存在: " + order.getToGrade()));

        BigDecimal speed = request.machineSpeedMMin();
        BigDecimal consistency = request.stockConsistencyPct();
        // 恰在窗口边界视为合格。
        if (!TransitionRules.withinWindow(speed, toGrade.getSpeedMin(), toGrade.getSpeedMax())) {
            throw ApiException.badRequest("执行车速 " + speed + " 超出新品种工艺窗口 ["
                    + toGrade.getSpeedMin() + ", " + toGrade.getSpeedMax() + "] m/min");
        }
        if (!TransitionRules.withinWindow(consistency, toGrade.getConsistencyMin(),
                toGrade.getConsistencyMax())) {
            throw ApiException.badRequest("上料浓度 " + consistency + " 超出新品种工艺窗口 ["
                    + toGrade.getConsistencyMin() + ", " + toGrade.getConsistencyMax() + "] %");
        }

        BigDecimal length = TransitionRules.transitionLengthM(speed, request.switchTimeSec());

        order.setSwitchTimeSec(request.switchTimeSec());
        order.setMachineSpeedMMin(speed);
        order.setStockConsistencyPct(consistency);
        order.setTransitionLengthM(length);
        order.setStatus(TransitionStatus.EXECUTED);
        order.setExecutedAt(now());
        orderRepository.updateExecution(order);

        // 停机清洗才占用清洗窗口；与执行结果在同一事务内登记，直切绝不产生清洗占用。
        if (Boolean.FALSE.equals(order.getDirectSwitch())) {
            String windowNo = "CW-" + orderNo;
            if (!cleaningWindowRepository.tryOccupy(windowNo, order.getPaperMachine(), orderNo, now())) {
                throw ApiException.conflict("停机清洗窗口冲突，纸机 " + order.getPaperMachine());
            }
        }
        if (cleaningWindowRepository.countActiveForOrder(orderNo) > 0
                && Boolean.TRUE.equals(order.getDirectSwitch())) {
            // 防御性不变式：直切单不得占用清洗窗口。
            throw ApiException.conflict("直切单不得占用清洗窗口");
        }
        return toView(order);
    }

    /** 过渡料处置：过渡卷按卷号唯一登记，强制降级、入非正品库；掺配失控暂停期间不得处置。 */
    @Transactional
    public OrderView dispose(String orderNo, DisposeRollsRequest request) {
        TransitionOrder order = requireOrder(orderNo);
        requireStatus(order, TransitionStatus.EXECUTED);
        // 掺配失控即暂停执行并转人工：恢复前阻断处置；未设定掺配比例的订单不受影响。
        blendControlRepository.findState(orderNo)
                .filter(s -> BlendControlState.PAUSED_MANUAL.equals(s.getControlStatus()))
                .ifPresent(s -> {
                    throw ApiException.conflict("掺配比例失控待人工复核，执行已暂停，不得处置过渡卷");
                });

        List<String> rollNos = request.rolls().stream().map(DisposeRollsRequest.RollItem::rollNo).toList();
        if (rollNos.stream().distinct().count() != rollNos.size()) {
            throw ApiException.conflict("同一次处置中卷号重复");
        }
        LocalDateTime registeredAt = now();
        try {
            for (DisposeRollsRequest.RollItem item : request.rolls()) {
                TransitionRoll roll = new TransitionRoll();
                roll.setRollNo(item.rollNo());
                roll.setOrderNo(orderNo);
                roll.setPaperMachine(order.getPaperMachine());
                roll.setLengthM(item.lengthM());
                rollRepository.insertDowngraded(roll, registeredAt);
                verdictEventRepository.insert(item.rollNo(), "FORCED_DOWNGRADE", null,
                        "DOWNGRADED", null, registeredAt);
            }
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("卷号已登记，过渡纸卷按卷号唯一");
        }

        order.setStatus(TransitionStatus.DISPOSED);
        order.setDisposedAt(registeredAt);
        orderRepository.updateStatus(orderNo, TransitionStatus.DISPOSED, "disposed_at", registeredAt);
        return toView(order);
    }

    /** 完成：释放纸机占用与清洗窗口，允许同机后续申请。 */
    @Transactional
    public OrderView complete(String orderNo) {
        TransitionOrder order = requireOrder(orderNo);
        requireStatus(order, TransitionStatus.DISPOSED);
        LocalDateTime completedAt = now();
        orderRepository.updateStatus(orderNo, TransitionStatus.COMPLETED, "completed_at", completedAt);
        machineSlotRepository.release(order.getPaperMachine(), orderNo);
        cleaningWindowRepository.release(orderNo, completedAt);
        order.setStatus(TransitionStatus.COMPLETED);
        order.setCompletedAt(completedAt);
        return toView(order);
    }

    /** 归档（终态）：重复归档返回幂等成功；未完成不得归档。 */
    @Transactional
    public OrderView archive(String orderNo) {
        TransitionOrder order = requireOrder(orderNo);
        if (TransitionStatus.ARCHIVED.equals(order.getStatus())) {
            return toView(order);
        }
        requireStatus(order, TransitionStatus.COMPLETED);
        LocalDateTime archivedAt = now();
        orderRepository.updateStatus(orderNo, TransitionStatus.ARCHIVED, "archived_at", archivedAt);
        order.setStatus(TransitionStatus.ARCHIVED);
        order.setArchivedAt(archivedAt);
        return toView(order);
    }

    public OrderView getOrder(String orderNo) {
        return toView(requireOrder(orderNo));
    }

    /** 首次检验提交（requestId 幂等）；达门槛仅取得复议资格，过渡卷仍强制降级。 */
    @Transactional
    public IdempotentResult<RollView> submitInspection(InspectionRequest request) {
        String fingerprint = FingerprintUtil.sha256(request.requestId(), request);
        IdempotentRequest existing = idempotencyGuard.lookup(request.requestId(), BIZ_INSPECTION, fingerprint);
        if (existing != null) {
            return idempotencyGuard.replay(existing, new TypeReference<RollView>() {
            });
        }

        TransitionRoll roll = requireRoll(request.rollNo());
        TransitionOrder order = requireOrder(roll.getOrderNo());

        // 归档终态后迟到：只记迟到事件，不改任何判定与入库状态；重放不重复登记事件。
        if (TransitionStatus.ARCHIVED.equals(order.getStatus())) {
            lateEventRepository.insert(order.getOrderNo(), roll.getRollNo(), "LATE_INSPECTION",
                    writeJson(request), now());
            RollView view = toRollView(roll);
            idempotencyGuard.register(request.requestId(), roll.getRollNo(), view);
            return new IdempotentResult<>(request.requestId(), false, view);
        }

        boolean meets = meetsThreshold(request, order);
        try {
            RollInspection inspection = new RollInspection();
            inspection.setRollNo(roll.getRollNo());
            inspection.setInspectionType(INSPECTION_INITIAL);
            inspection.setRequestId(request.requestId());
            inspection.setAshValuePct(request.ashValuePct());
            inspection.setMoisturePct(request.moisturePct());
            inspection.setTensileIndex(request.tensileIndex());
            inspection.setBasisWeightGM2(request.basisWeightGM2());
            inspection.setMeetsThreshold(meets);
            // 过渡卷强制降级：达门槛只标记可复议，不自动改判。
            inspection.setVerdict(meets ? "GENUINE_ELIGIBLE" : "BELOW_THRESHOLD");
            inspectionRepository.insert(inspection, now());
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("该卷首次检验已提交");
        }
        rollRepository.updateInitialEligibility(roll.getRollNo(), meets);

        TransitionRoll refreshed = rollRepository.getByRollNo(roll.getRollNo());
        idempotencyGuard.register(request.requestId(), roll.getRollNo(), toRollView(refreshed));
        return new IdempotentResult<>(request.requestId(), false, toRollView(refreshed));
    }

    /** 复议提交（requestId 幂等）：每卷至多受理一次，与首次判定并发形成唯一终态。 */
    @Transactional
    public IdempotentResult<RollView> submitReexam(ReexamRequest request) {
        String fingerprint = FingerprintUtil.sha256(request.requestId(), request);
        IdempotentRequest existing = idempotencyGuard.lookup(request.requestId(), BIZ_REEXAM, fingerprint);
        if (existing != null) {
            return idempotencyGuard.replay(existing, new TypeReference<RollView>() {
            });
        }

        TransitionRoll roll = requireRoll(request.rollNo());
        TransitionOrder order = requireOrder(roll.getOrderNo());

        // 归档终态后迟到的复议：只记迟到事件，终态不变；重放不重复登记事件。
        if (TransitionStatus.ARCHIVED.equals(order.getStatus())) {
            lateEventRepository.insert(order.getOrderNo(), roll.getRollNo(), "LATE_REEXAM",
                    writeJson(request), now());
            RollView view = toRollView(roll);
            idempotencyGuard.register(request.requestId(), roll.getRollNo(), view);
            return new IdempotentResult<>(request.requestId(), false, view);
        }

        boolean initialEligible = inspectionRepository.find(roll.getRollNo(), INSPECTION_INITIAL)
                .map(RollInspection::isMeetsThreshold).orElse(false);
        if (!initialEligible) {
            throw ApiException.conflict("首次检验未达正品门槛或尚未检验，不具备复议资格");
        }
        if (roll.getReexamCount() > 0 || "UPGRADED".equals(roll.getVerdict())) {
            throw ApiException.conflict("该卷复议仅允许一次，终态已确定");
        }

        boolean meets = meetsThreshold(request, order);
        LocalDateTime eventTime = now();
        try {
            RollInspection inspection = new RollInspection();
            inspection.setRollNo(roll.getRollNo());
            inspection.setInspectionType(INSPECTION_REEXAM);
            inspection.setRequestId(request.requestId());
            inspection.setAshValuePct(request.ashValuePct());
            inspection.setMoisturePct(request.moisturePct());
            inspection.setTensileIndex(request.tensileIndex());
            inspection.setBasisWeightGM2(request.basisWeightGM2());
            inspection.setMeetsThreshold(meets);
            inspection.setVerdict(meets ? "REEXAM_UPGRADED" : "REEXAM_REJECTED");
            // 唯一约束 (roll_no, inspection_type)：并发复议只有一个能插入，形成唯一终态。
            inspectionRepository.insert(inspection, eventTime);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("该卷复议已受理，终态唯一");
        }

        if (meets) {
            int updated = rollRepository.applyUpgrade(roll.getRollNo(), eventTime);
            if (updated == 0) {
                throw ApiException.conflict("该卷终态已确定，复议改判失败");
            }
            verdictEventRepository.insert(roll.getRollNo(), "REEXAM_UPGRADED",
                    "DOWNGRADED", "UPGRADED", request.requestId(), eventTime);
        } else {
            int updated = rollRepository.applyReexamReject(roll.getRollNo());
            if (updated == 0) {
                throw ApiException.conflict("该卷终态已确定，复议驳回失败");
            }
            verdictEventRepository.insert(roll.getRollNo(), "REEXAM_REJECTED",
                    "DOWNGRADED", "DOWNGRADED", request.requestId(), eventTime);
        }

        TransitionRoll refreshed = rollRepository.getByRollNo(roll.getRollNo());
        idempotencyGuard.register(request.requestId(), roll.getRollNo(), toRollView(refreshed));
        return new IdempotentResult<>(request.requestId(), false, toRollView(refreshed));
    }

    public RollView getRoll(String rollNo) {
        return toRollView(requireRoll(rollNo));
    }

    /** 过渡料长度、降级卷明细与改判链复算。 */
    public TransitionRecomputeView recompute(String orderNo) {
        TransitionOrder order = requireOrder(orderNo);
        BigDecimal recomputed = null;
        if (order.getMachineSpeedMMin() != null && order.getSwitchTimeSec() != null) {
            recomputed = TransitionRules.transitionLengthM(
                    order.getMachineSpeedMMin(), order.getSwitchTimeSec());
        }
        List<RollView> rolls = rollRepository.findByOrderNo(orderNo).stream()
                .map(this::toRollView).toList();
        BigDecimal sumLength = rolls.stream()
                .map(RollView::lengthM)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new TransitionRecomputeView(orderNo, order.getSwitchTimeSec(),
                order.getMachineSpeedMMin(), order.getTransitionLengthM(), recomputed,
                sumLength, rolls);
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

    private TransitionRoll requireRoll(String rollNo) {
        return rollRepository.findByRollNo(rollNo)
                .orElseThrow(() -> ApiException.notFound("过渡纸卷不存在: " + rollNo));
    }

    private void requireStatus(TransitionOrder order, String expected) {
        if (!expected.equals(order.getStatus())) {
            throw ApiException.conflict("改品种单状态为 " + order.getStatus()
                    + "，不允许该操作，期望状态 " + expected);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    /**
     * 合成正品门槛判定（恰达边界视为合格）：
     * 灰分 ≤18%，水分 ∈ [4%,8%]，抗张指数 ≥45 Nm/g，定量与新品种标称克重偏差 ≤3%。
     */
    private boolean meetsThreshold(Object request, TransitionOrder order) {
        BigDecimal ash;
        BigDecimal moisture;
        BigDecimal tensile;
        BigDecimal basisWeight;
        if (request instanceof InspectionRequest ir) {
            ash = ir.ashValuePct();
            moisture = ir.moisturePct();
            tensile = ir.tensileIndex();
            basisWeight = ir.basisWeightGM2();
        } else {
            ReexamRequest rr = (ReexamRequest) request;
            ash = rr.ashValuePct();
            moisture = rr.moisturePct();
            tensile = rr.tensileIndex();
            basisWeight = rr.basisWeightGM2();
        }
        Grade toGrade = gradeRepository.findByCode(order.getToGrade())
                .orElseThrow(() -> ApiException.notFound("新品种不存在: " + order.getToGrade()));
        return TransitionRules.meetsQualityThreshold(ash, moisture, tensile,
                basisWeight, toGrade.getBasisWeight());
    }

    private String newOrderNo() {
        byte[] bytes = new byte[2];
        RANDOM.nextBytes(bytes);
        return "TO-" + now().format(NO_FORMAT) + "-" + HexFormat.of().formatHex(bytes);
    }

    private OrderView toView(TransitionOrder o) {
        return new OrderView(o.getOrderNo(), o.getPaperMachine(), o.getFromGrade(), o.getToGrade(),
                o.getBasisWeightFrom(), o.getBasisWeightTo(), o.getAshLevelFrom(), o.getAshLevelTo(),
                o.getBasisWeightDeltaPct(), o.getAshLevelDelta(), o.getDirectSwitch(), o.getWireFlush(),
                o.getSwitchTimeSec(), o.getMachineSpeedMMin(), o.getStockConsistencyPct(),
                o.getTransitionLengthM(), o.getStatus(), o.getAppliedAt(), o.getDecidedAt(),
                o.getExecutedAt(), o.getDisposedAt(), o.getCompletedAt(), o.getArchivedAt());
    }

    private RollView toRollView(TransitionRoll r) {
        return new RollView(r.getRollNo(), r.getOrderNo(), r.getPaperMachine(), r.getLengthM(),
                r.getVerdict(), r.getWarehouseStatus(), r.isEligibleReexam(), r.getReexamCount(),
                r.getRegisteredAt(), r.getVerdictChangedAt(),
                verdictEventRepository.findByRollNo(r.getRollNo()));
    }
}
