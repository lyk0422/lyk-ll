package com.example.starter.transition.service;

import com.example.starter.transition.ApiException;
import com.example.starter.transition.QualityThresholds;
import com.example.starter.transition.TransitionStatus;
import com.example.starter.transition.dto.AdjustBlendRatioRequest;
import com.example.starter.transition.dto.AdjustRecycleQuotaRequest;
import com.example.starter.transition.dto.ApplyOrderRequest;
import com.example.starter.transition.dto.BlendReadingRequest;
import com.example.starter.transition.dto.BlendReadingView;
import com.example.starter.transition.dto.BlendRecomputeView;
import com.example.starter.transition.dto.BlendVersionView;
import com.example.starter.transition.dto.DisposeRollsRequest;
import com.example.starter.transition.dto.ExecuteOrderRequest;
import com.example.starter.transition.dto.FeedView;
import com.example.starter.transition.dto.IdempotentResult;
import com.example.starter.transition.dto.InspectionRequest;
import com.example.starter.transition.dto.OrderView;
import com.example.starter.transition.dto.RecycleQuotaView;
import com.example.starter.transition.dto.ReexamRequest;
import com.example.starter.transition.dto.RegisterRecycleQuotaRequest;
import com.example.starter.transition.dto.ResumeExecutionRequest;
import com.example.starter.transition.dto.RollView;
import com.example.starter.transition.dto.SubmitFeedRequest;
import com.example.starter.transition.dto.TransitionRecomputeView;
import com.example.starter.transition.model.BlendRatioVersion;
import com.example.starter.transition.model.BlendReading;
import com.example.starter.transition.model.FeedBatch;
import com.example.starter.transition.model.FeedBatchItem;
import com.example.starter.transition.model.Grade;
import com.example.starter.transition.model.IdempotentRequest;
import com.example.starter.transition.model.RecycleQuota;
import com.example.starter.transition.model.RollInspection;
import com.example.starter.transition.model.TransitionOrder;
import com.example.starter.transition.model.TransitionRoll;
import com.example.starter.transition.repo.BlendControlEventRepository;
import com.example.starter.transition.repo.BlendRatioVersionRepository;
import com.example.starter.transition.repo.BlendReadingRepository;
import com.example.starter.transition.repo.CleaningWindowRepository;
import com.example.starter.transition.repo.FeedBatchRepository;
import com.example.starter.transition.repo.GradeRepository;
import com.example.starter.transition.repo.IdempotentRequestRepository;
import com.example.starter.transition.repo.LateEventRepository;
import com.example.starter.transition.repo.MachineSlotRepository;
import com.example.starter.transition.repo.RecycleQuotaRepository;
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
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

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
    static final String BIZ_ADJUST_BLEND = "ADJUST_BLEND_RATIO";
    static final String BIZ_BLEND_READING = "SUBMIT_BLEND_READING";
    static final String BIZ_RESUME = "RESUME_EXECUTION";
    static final String BIZ_RECYCLE_REGISTER = "REGISTER_RECYCLE_QUOTA";
    static final String BIZ_RECYCLE_ADJUST = "ADJUST_RECYCLE_QUOTA";
    static final String BIZ_FEED = "SUBMIT_FEED";

    static final String INSPECTION_INITIAL = "INITIAL";
    static final String INSPECTION_REEXAM = "REEXAM";

    static final String BLEND_SOURCE_APPLY = "APPLY";
    static final String BLEND_SOURCE_ADJUST = "ADJUST";
    static final String EVENT_OUT_OF_CONTROL_PAUSE = "OUT_OF_CONTROL_PAUSE";
    static final String EVENT_MANUAL_RESUME = "MANUAL_RESUME";

    private static final DateTimeFormatter NO_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final GradeRepository gradeRepository;
    private final TransitionOrderRepository orderRepository;
    private final MachineSlotRepository machineSlotRepository;
    private final CleaningWindowRepository cleaningWindowRepository;
    private final TransitionRollRepository rollRepository;
    private final RollInspectionRepository inspectionRepository;
    private final VerdictEventRepository verdictEventRepository;
    private final IdempotentRequestRepository idempotentRequestRepository;
    private final LateEventRepository lateEventRepository;
    private final BlendRatioVersionRepository blendRatioVersionRepository;
    private final BlendReadingRepository blendReadingRepository;
    private final BlendControlEventRepository blendControlEventRepository;
    private final RecycleQuotaRepository recycleQuotaRepository;
    private final FeedBatchRepository feedBatchRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public TransitionService(GradeRepository gradeRepository,
                             TransitionOrderRepository orderRepository,
                             MachineSlotRepository machineSlotRepository,
                             CleaningWindowRepository cleaningWindowRepository,
                             TransitionRollRepository rollRepository,
                             RollInspectionRepository inspectionRepository,
                             VerdictEventRepository verdictEventRepository,
                             IdempotentRequestRepository idempotentRequestRepository,
                             LateEventRepository lateEventRepository,
                             BlendRatioVersionRepository blendRatioVersionRepository,
                             BlendReadingRepository blendReadingRepository,
                             BlendControlEventRepository blendControlEventRepository,
                             RecycleQuotaRepository recycleQuotaRepository,
                             FeedBatchRepository feedBatchRepository,
                             ObjectMapper objectMapper,
                             Clock clock) {
        this.gradeRepository = gradeRepository;
        this.orderRepository = orderRepository;
        this.machineSlotRepository = machineSlotRepository;
        this.cleaningWindowRepository = cleaningWindowRepository;
        this.rollRepository = rollRepository;
        this.inspectionRepository = inspectionRepository;
        this.verdictEventRepository = verdictEventRepository;
        this.idempotentRequestRepository = idempotentRequestRepository;
        this.lateEventRepository = lateEventRepository;
        this.blendRatioVersionRepository = blendRatioVersionRepository;
        this.blendReadingRepository = blendReadingRepository;
        this.blendControlEventRepository = blendControlEventRepository;
        this.recycleQuotaRepository = recycleQuotaRepository;
        this.feedBatchRepository = feedBatchRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** 申请改品种单（requestId 幂等；同一纸机同时刻唯一受理）。携带掺配比例时须重新走方式判定，判定结论与比例版本在同一事务内落库。 */
    @Transactional
    public IdempotentResult<OrderView> apply(ApplyOrderRequest request) {
        String fingerprint = FingerprintUtil.sha256(request.requestId(), request);
        IdempotentRequest existing = lookupIdempotent(request.requestId(), BIZ_APPLY, fingerprint);
        if (existing != null) {
            return replay(existing, new TypeReference<OrderView>() {
            });
        }

        // 掺配比例须在合成允许区间内，恰在区间边界合法；先校验再占用资源。
        if (request.targetBlendRatioPct() != null
                && !TransitionRules.withinBlendInterval(request.targetBlendRatioPct())) {
            throw ApiException.badRequest("掺配比例 " + request.targetBlendRatioPct()
                    + " 超出合成允许区间 [" + QualityThresholds.BLEND_RATIO_MIN_PCT
                    + ", " + QualityThresholds.BLEND_RATIO_MAX_PCT + "] %");
        }

        Grade fromGrade = gradeRepository.findByCode(request.fromGrade())
                .orElseThrow(() -> ApiException.badRequest("原品种不存在: " + request.fromGrade()));
        Grade toGrade = gradeRepository.findByCode(request.toGrade())
                .orElseThrow(() -> ApiException.badRequest("新品种不存在: " + request.toGrade()));

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

        // 携带比例变更的申请：重新走方式判定，判定结论与比例版本在同一事务内落库。
        if (request.targetBlendRatioPct() != null) {
            applyDecision(order);
            BlendRatioVersion version = new BlendRatioVersion();
            version.setOrderNo(orderNo);
            version.setVersionNo(1);
            version.setTargetRatioPct(request.targetBlendRatioPct());
            version.setSource(BLEND_SOURCE_APPLY);
            version.setRequestId(request.requestId());
            version.setCreatedAt(now());
            blendRatioVersionRepository.tryInsert(version);
            orderRepository.updateBlendVersion(orderNo, 1);
            order.setCurrentBlendVersion(1);
        }

        registerIdempotent(request.requestId(), BIZ_APPLY, fingerprint, orderNo, toView(order));
        return new IdempotentResult<>(request.requestId(), false, toView(order));
    }

    /** 方式判定：克重差 ≤10%（含）直切；灰分等级差 &gt;2 级须冲洗网部，恰 2 级合法直切。 */
    @Transactional
    public OrderView decide(String orderNo) {
        TransitionOrder order = requireOrder(orderNo);
        requireStatus(order, TransitionStatus.APPLIED);
        applyDecision(order);
        return toView(order);
    }

    /** 判定结论计算与落库：申请携带比例时与比例版本同事务调用，独立判定走 decide。 */
    private void applyDecision(TransitionOrder order) {
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

    /** 过渡料处置：过渡卷按卷号唯一登记，强制降级、入非正品库。 */
    @Transactional
    public OrderView dispose(String orderNo, DisposeRollsRequest request) {
        TransitionOrder order = requireOrder(orderNo);
        requireStatus(order, TransitionStatus.EXECUTED);

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
        IdempotentRequest existing = lookupIdempotent(request.requestId(), BIZ_INSPECTION, fingerprint);
        if (existing != null) {
            return replay(existing, new TypeReference<RollView>() {
            });
        }

        TransitionRoll roll = requireRoll(request.rollNo());
        TransitionOrder order = requireOrder(roll.getOrderNo());

        // 归档终态后迟到：只记迟到事件，不改任何判定与入库状态；重放不重复登记事件。
        if (TransitionStatus.ARCHIVED.equals(order.getStatus())) {
            lateEventRepository.insert(order.getOrderNo(), roll.getRollNo(), "LATE_INSPECTION",
                    writeJson(request), now());
            RollView view = toRollView(roll);
            registerIdempotent(request.requestId(), BIZ_INSPECTION, fingerprint,
                    roll.getRollNo(), view);
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
        registerIdempotent(request.requestId(), BIZ_INSPECTION, fingerprint,
                roll.getRollNo(), toRollView(refreshed));
        return new IdempotentResult<>(request.requestId(), false, toRollView(refreshed));
    }

    /** 复议提交（requestId 幂等）：每卷至多受理一次，与首次判定并发形成唯一终态。 */
    @Transactional
    public IdempotentResult<RollView> submitReexam(ReexamRequest request) {
        String fingerprint = FingerprintUtil.sha256(request.requestId(), request);
        IdempotentRequest existing = lookupIdempotent(request.requestId(), BIZ_REEXAM, fingerprint);
        if (existing != null) {
            return replay(existing, new TypeReference<RollView>() {
            });
        }

        TransitionRoll roll = requireRoll(request.rollNo());
        TransitionOrder order = requireOrder(roll.getOrderNo());

        // 归档终态后迟到的复议：只记迟到事件，终态不变；重放不重复登记事件。
        if (TransitionStatus.ARCHIVED.equals(order.getStatus())) {
            lateEventRepository.insert(order.getOrderNo(), roll.getRollNo(), "LATE_REEXAM",
                    writeJson(request), now());
            RollView view = toRollView(roll);
            registerIdempotent(request.requestId(), BIZ_REEXAM, fingerprint,
                    roll.getRollNo(), view);
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
            // 改判正品：未消费回收额度同事务同步失效，已消费凭据保留；
            // 与投料扣量作用于同一额度行，行锁保证只形成一个一致次序。
            recycleQuotaRepository.invalidateOnUpgrade(roll.getRollNo(),
                    RecycleQuota.REASON_REEXAM_UPGRADED, eventTime);
        } else {
            int updated = rollRepository.applyReexamReject(roll.getRollNo());
            if (updated == 0) {
                throw ApiException.conflict("该卷终态已确定，复议驳回失败");
            }
            verdictEventRepository.insert(roll.getRollNo(), "REEXAM_REJECTED",
                    "DOWNGRADED", "DOWNGRADED", request.requestId(), eventTime);
        }

        TransitionRoll refreshed = rollRepository.getByRollNo(roll.getRollNo());
        registerIdempotent(request.requestId(), BIZ_REEXAM, fingerprint,
                roll.getRollNo(), toRollView(refreshed));
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

    /**
     * 执行中掺配比例调整（requestId 幂等）：生成新版本并切换当前版本，连续超差计数清零；
     * 同一订单同时刻只允许一次执行中调整（唯一索引 (order_no, source, created_at) 保证）。
     */
    @Transactional
    public IdempotentResult<BlendVersionView> adjustBlendRatio(String orderNo,
                                                               AdjustBlendRatioRequest request) {
        String fingerprint = FingerprintUtil.sha256(request.requestId(), request);
        IdempotentRequest existing = lookupIdempotent(request.requestId(), BIZ_ADJUST_BLEND, fingerprint);
        if (existing != null) {
            return replay(existing, new TypeReference<BlendVersionView>() {
            });
        }

        TransitionOrder order = requireOrder(orderNo);
        if (TransitionStatus.PAUSED.equals(order.getStatus())) {
            throw ApiException.conflict("改品种单已失控暂停转人工，须复核确认恢复后再调整比例");
        }
        requireStatus(order, TransitionStatus.EXECUTED);
        if (!TransitionRules.withinBlendInterval(request.targetBlendRatioPct())) {
            throw ApiException.badRequest("掺配比例 " + request.targetBlendRatioPct()
                    + " 超出合成允许区间 [" + QualityThresholds.BLEND_RATIO_MIN_PCT
                    + ", " + QualityThresholds.BLEND_RATIO_MAX_PCT + "] %");
        }

        BlendRatioVersion version = new BlendRatioVersion();
        version.setOrderNo(orderNo);
        version.setVersionNo(blendRatioVersionRepository.nextVersionNo(orderNo));
        version.setTargetRatioPct(request.targetBlendRatioPct());
        version.setSource(BLEND_SOURCE_ADJUST);
        version.setRequestId(request.requestId());
        version.setCreatedAt(now());
        if (!blendRatioVersionRepository.tryInsert(version)) {
            throw ApiException.conflict("同一订单同时刻只允许一次执行中调整: " + orderNo);
        }
        if (orderRepository.updateBlendAdjust(orderNo, version.getVersionNo()) == 0) {
            throw ApiException.conflict("改品种单执行状态已变更，比例调整失败: " + orderNo);
        }

        BlendVersionView view = toVersionView(version);
        registerIdempotent(request.requestId(), BIZ_ADJUST_BLEND, fingerprint, orderNo, view);
        return new IdempotentResult<>(request.requestId(), false, view);
    }

    /**
     * 实际掺配读数提交（requestId 幂等；按订单与采样时刻去重）：
     * 偏离目标超过 ±2%（恰 2% 合格）记超差，连续 3 个采样点超差判失控，
     * 失控即暂停执行并转人工，读数与暂停在同一事务内落库。
     */
    @Transactional
    public IdempotentResult<BlendReadingView> submitBlendReading(BlendReadingRequest request) {
        String fingerprint = FingerprintUtil.sha256(request.requestId(), request);
        IdempotentRequest existing = lookupIdempotent(request.requestId(), BIZ_BLEND_READING, fingerprint);
        if (existing != null) {
            return replay(existing, new TypeReference<BlendReadingView>() {
            });
        }

        TransitionOrder order = requireOrder(request.orderNo());
        if (TransitionStatus.PAUSED.equals(order.getStatus())) {
            throw ApiException.conflict("改品种单已失控暂停转人工，复核确认恢复前不得登记读数");
        }
        requireStatus(order, TransitionStatus.EXECUTED);
        BlendRatioVersion current = blendRatioVersionRepository.findLatest(request.orderNo())
                .orElseThrow(() -> ApiException.conflict(
                        "改品种单未携带掺配比例维度，不得登记读数: " + request.orderNo()));

        BigDecimal deviation = TransitionRules.blendDeviationPct(
                request.actualRatioPct(), current.getTargetRatioPct());
        boolean outOfTolerance = TransitionRules.isBlendOutOfTolerance(deviation);

        BlendReading reading = new BlendReading();
        reading.setOrderNo(request.orderNo());
        reading.setVersionNo(current.getVersionNo());
        reading.setSampledAt(request.sampledAt());
        reading.setActualRatioPct(request.actualRatioPct());
        reading.setDeviationPct(deviation);
        reading.setOutOfTolerance(outOfTolerance);
        reading.setRequestId(request.requestId());
        reading.setCreatedAt(now());
        if (!blendReadingRepository.tryInsert(reading)) {
            throw ApiException.conflict("同一订单同一采样时刻已存在掺配读数: "
                    + request.orderNo() + " @ " + request.sampledAt());
        }

        int streak = outOfTolerance ? order.getConsecutiveOutOfTolerance() + 1 : 0;
        order.setConsecutiveOutOfTolerance(streak);
        if (streak >= QualityThresholds.BLEND_OUT_OF_CONTROL_STREAK) {
            // 失控即暂停执行并转人工；与读数登记在同一事务内落库。
            order.setStatus(TransitionStatus.PAUSED);
            order.setPausedAt(now());
            blendControlEventRepository.insert(request.orderNo(), EVENT_OUT_OF_CONTROL_PAUSE,
                    request.requestId(), null,
                    "连续 " + streak + " 个采样点偏离目标超过 ±2%，暂停执行转人工", now());
        }
        if (orderRepository.updateBlendControl(order) == 0) {
            // 并发读数已先行暂停/变更状态：本次登记随事务回滚。
            throw ApiException.conflict("改品种单执行状态已变更，读数登记失败: " + request.orderNo());
        }

        BlendReadingView view = toReadingView(reading);
        registerIdempotent(request.requestId(), BIZ_BLEND_READING, fingerprint,
                request.orderNo(), view);
        return new IdempotentResult<>(request.requestId(), false, view);
    }

    /** 失控暂停后复核确认恢复（requestId 幂等）：恢复为执行中，连续超差计数清零。 */
    @Transactional
    public IdempotentResult<OrderView> resumeExecution(String orderNo, ResumeExecutionRequest request) {
        String fingerprint = FingerprintUtil.sha256(request.requestId(), request);
        IdempotentRequest existing = lookupIdempotent(request.requestId(), BIZ_RESUME, fingerprint);
        if (existing != null) {
            return replay(existing, new TypeReference<OrderView>() {
            });
        }

        TransitionOrder order = requireOrder(orderNo);
        requireStatus(order, TransitionStatus.PAUSED);
        if (!Boolean.TRUE.equals(request.reviewConfirmed())) {
            throw ApiException.badRequest("恢复执行须复核确认（reviewConfirmed=true）");
        }

        LocalDateTime resumedAt = now();
        if (orderRepository.updateResume(orderNo, resumedAt) == 0) {
            throw ApiException.conflict("改品种单状态已变更，恢复失败: " + orderNo);
        }
        blendControlEventRepository.insert(orderNo, EVENT_MANUAL_RESUME,
                request.requestId(), request.reviewedBy(), "复核确认恢复执行", resumedAt);

        OrderView view = getOrder(orderNo);
        registerIdempotent(request.requestId(), BIZ_RESUME, fingerprint, orderNo, view);
        return new IdempotentResult<>(request.requestId(), false, view);
    }

    /** 比例版本链查询：与改品种单号绑定留档。 */
    public List<BlendVersionView> listBlendVersions(String orderNo) {
        requireOrder(orderNo);
        return blendRatioVersionRepository.findByOrderNo(orderNo).stream()
                .map(this::toVersionView).toList();
    }

    /** 掺配轨迹复算：版本链 + 按读数留存版本的目标复算偏离，历史订单（含已归档）均可复算。 */
    public BlendRecomputeView recomputeBlend(String orderNo) {
        TransitionOrder order = requireOrder(orderNo);
        List<BlendVersionView> versions = listBlendVersions(orderNo);
        java.util.Map<Integer, BigDecimal> targetByVersion = new java.util.HashMap<>();
        for (BlendVersionView v : versions) {
            targetByVersion.put(v.versionNo(), v.targetRatioPct());
        }
        List<BlendReadingView> readings = blendReadingRepository.findByOrderNo(orderNo).stream()
                .map(r -> {
                    BigDecimal target = targetByVersion.get(r.getVersionNo());
                    BigDecimal recomputedDeviation = TransitionRules.blendDeviationPct(
                            r.getActualRatioPct(), target);
                    return new BlendReadingView(r.getOrderNo(), r.getVersionNo(), r.getSampledAt(),
                            r.getActualRatioPct(), recomputedDeviation,
                            TransitionRules.isBlendOutOfTolerance(recomputedDeviation));
                })
                .toList();
        return new BlendRecomputeView(orderNo, order.getStatus(), order.getCurrentBlendVersion(),
                order.getConsecutiveOutOfTolerance(), versions, readings);
    }

    /**
     * 回收重量登记（requestId 幂等）：每个未改判正品的已登记过渡卷至多登记一次，
     * 正数且最多三位小数；已改判正品的卷不能新增回收登记。
     */
    @Transactional
    public IdempotentResult<RecycleQuotaView> registerRecycleQuota(RegisterRecycleQuotaRequest request) {
        String fingerprint = FingerprintUtil.sha256(request.requestId(), request);
        IdempotentRequest existing = lookupIdempotent(request.requestId(), BIZ_RECYCLE_REGISTER, fingerprint);
        if (existing != null) {
            return replay(existing, new TypeReference<RecycleQuotaView>() {
            });
        }

        requirePositiveWeight(request.weightKg(), "可回收公斤数");
        BigDecimal weightKg = TransitionRules.normalizeKg(request.weightKg());
        // 行锁读取：与复议改判在同一纸卷行上串行化，先改判则此处看到 UPGRADED 拒绝登记。
        TransitionRoll roll = rollRepository.findByRollNoForUpdate(request.rollNo())
                .orElseThrow(() -> ApiException.notFound("过渡纸卷不存在: " + request.rollNo()));
        if ("UPGRADED".equals(roll.getVerdict())) {
            throw ApiException.conflict("该卷已改判正品，不能新增回收登记: " + request.rollNo());
        }

        RecycleQuota quota = new RecycleQuota();
        quota.setRollNo(roll.getRollNo());
        quota.setInitialKg(weightKg);
        quota.setRegisteredKg(weightKg);
        quota.setRequestId(request.requestId());
        try {
            recycleQuotaRepository.insert(quota, now());
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("该卷已登记回收重量，每卷至多登记一次: " + request.rollNo());
        }

        RecycleQuotaView view = getRecycleQuota(roll.getRollNo());
        registerIdempotent(request.requestId(), BIZ_RECYCLE_REGISTER, fingerprint,
                roll.getRollNo(), view);
        return new IdempotentResult<>(request.requestId(), false, view);
    }

    /**
     * 回收额度调减（requestId 幂等）：只允许减少尚未消费的额度，不得把累计消费减掉；
     * 携带 expectedVersion，版本 1 起、成功 +1、过期 409。
     */
    @Transactional
    public IdempotentResult<RecycleQuotaView> adjustRecycleQuota(String rollNo,
                                                                 AdjustRecycleQuotaRequest request) {
        // 指纹覆盖路径参数 rollNo 与全部请求字段，避免同键跨卷重放。
        String fingerprint = FingerprintUtil.sha256(request.requestId() + "#" + rollNo, request);
        IdempotentRequest existing = lookupIdempotent(request.requestId(), BIZ_RECYCLE_ADJUST, fingerprint);
        if (existing != null) {
            return replay(existing, new TypeReference<RecycleQuotaView>() {
            });
        }

        requirePositiveWeight(request.newWeightKg(), "调减后的登记额度");
        BigDecimal newWeightKg = TransitionRules.normalizeKg(request.newWeightKg());
        RecycleQuota quota = recycleQuotaRepository.findByRollNo(rollNo)
                .orElseThrow(() -> ApiException.notFound("该卷未登记回收重量: " + rollNo));
        if (!RecycleQuota.STATUS_ACTIVE.equals(quota.getStatus())) {
            throw ApiException.conflict("该卷回收额度已失效（" + quota.getInvalidReason()
                    + "），不得调减: " + rollNo);
        }
        if (quota.getVersion() != request.expectedVersion()) {
            throw ApiException.conflict("额度版本过期：期望 " + request.expectedVersion()
                    + "，当前 " + quota.getVersion() + "，请重新查询后再调减");
        }
        if (newWeightKg.compareTo(quota.getRegisteredKg()) >= 0) {
            throw ApiException.badRequest("只允许减少尚未消费的额度：新额度须小于当前登记额度 "
                    + quota.getRegisteredKg() + " kg");
        }
        if (newWeightKg.compareTo(quota.getConsumedKg()) < 0) {
            throw ApiException.conflict("不得把累计消费减掉：新额度 " + newWeightKg
                    + " kg 小于累计已消费 " + quota.getConsumedKg() + " kg");
        }
        if (recycleQuotaRepository.reduceQuota(rollNo, newWeightKg,
                request.expectedVersion(), now()) == 0) {
            throw ApiException.conflict("额度已并发变更，调减失败: " + rollNo);
        }

        RecycleQuotaView view = getRecycleQuota(rollNo);
        registerIdempotent(request.requestId(), BIZ_RECYCLE_ADJUST, fingerprint, rollNo, view);
        return new IdempotentResult<>(request.requestId(), false, view);
    }

    /** 回收额度查询：勾稽原额度、当前额度、累计消费、可用额度与失效原因，含消费凭据。 */
    public RecycleQuotaView getRecycleQuota(String rollNo) {
        RecycleQuota quota = recycleQuotaRepository.findByRollNo(rollNo)
                .orElseThrow(() -> ApiException.notFound("该卷未登记回收重量: " + rollNo));
        List<RecycleQuotaView.ConsumptionView> consumptions = feedBatchRepository
                .findConsumptionsByRollNo(rollNo).stream()
                .map(c -> new RecycleQuotaView.ConsumptionView(
                        c.feedNo(), c.orderNo(), c.weightKg(), c.createdAt()))
                .toList();
        return toQuotaView(quota, consumptions);
    }

    /**
     * 投料申请（requestId 幂等）：引用同一执行中订单、当前掺配版本、总浆量及 1 至 10 个不同回收卷。
     * 回收合计须精确等于 总浆量×该版本目标比例/100；所有卷同时扣额度，任一不足整单回滚。
     */
    @Transactional
    public IdempotentResult<FeedView> submitFeed(SubmitFeedRequest request) {
        String fingerprint = FingerprintUtil.sha256(request.requestId(), request);
        IdempotentRequest existing = lookupIdempotent(request.requestId(), BIZ_FEED, fingerprint);
        if (existing != null) {
            return replay(existing, new TypeReference<FeedView>() {
            });
        }

        // 结构校验：总浆量与各卷重量为正且最多三位小数（统一规范化标度）；批内卷号不得重复。
        requirePositiveWeight(request.totalPulpKg(), "总浆量");
        BigDecimal totalPulpKg = TransitionRules.normalizeKg(request.totalPulpKg());
        List<SubmitFeedRequest.FeedItem> items = request.items().stream()
                .map(i -> {
                    requirePositiveWeight(i.weightKg(), "卷 " + i.rollNo() + " 投料公斤数");
                    return new SubmitFeedRequest.FeedItem(
                            i.rollNo(), TransitionRules.normalizeKg(i.weightKg()));
                })
                .toList();
        List<String> rollNos = items.stream().map(SubmitFeedRequest.FeedItem::rollNo).toList();
        if (rollNos.stream().distinct().count() != rollNos.size()) {
            throw ApiException.badRequest("同一批投料中卷号重复");
        }

        TransitionOrder order = requireOrder(request.orderNo());
        if (TransitionStatus.PAUSED.equals(order.getStatus())) {
            throw ApiException.conflict("改品种单已失控暂停转人工，不得投料: " + request.orderNo());
        }
        if (TransitionStatus.ARCHIVED.equals(order.getStatus())) {
            throw ApiException.conflict("改品种单已归档，不得投料: " + request.orderNo());
        }
        requireStatus(order, TransitionStatus.EXECUTED);
        if (order.getCurrentBlendVersion() == null) {
            throw ApiException.conflict("改品种单未携带掺配比例维度，不得投料: " + request.orderNo());
        }
        if (!order.getCurrentBlendVersion().equals(request.versionNo())) {
            throw ApiException.conflict("掺配比例版本已换：请求版本 " + request.versionNo()
                    + "，当前版本 " + order.getCurrentBlendVersion() + "，拒绝投料");
        }
        BlendRatioVersion current = blendRatioVersionRepository.findLatest(request.orderNo())
                .orElseThrow(() -> ApiException.conflict(
                        "改品种单未携带掺配比例维度，不得投料: " + request.orderNo()));

        // 比例核算：应投 = 总浆量×目标比例/100，按百分数精确计算，超过三位小数精度拒绝该组合。
        BigDecimal requiredRaw = TransitionRules.requiredRecycleKg(
                totalPulpKg, current.getTargetRatioPct());
        if (!TransitionRules.withinWeightPrecision(requiredRaw)) {
            throw ApiException.badRequest("总浆量 " + totalPulpKg + " kg 与目标比例 "
                    + current.getTargetRatioPct() + "% 的应投回收量 " + requiredRaw
                    + " kg 超过三位小数精度，拒绝该组合");
        }
        BigDecimal required = TransitionRules.normalizeKg(requiredRaw);
        BigDecimal actual = items.stream().map(SubmitFeedRequest.FeedItem::weightKg)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (actual.compareTo(required) != 0) {
            throw ApiException.badRequest("回收合计 " + actual + " kg 须精确等于应投回收量 "
                    + required + " kg（总浆量×目标比例/100）");
        }

        // 额度预检：卷须已登记且额度有效；正式扣减由原子 UPDATE 守卫。
        for (String rollNo : rollNos) {
            requireRoll(rollNo);
            RecycleQuota quota = recycleQuotaRepository.findByRollNo(rollNo)
                    .orElseThrow(() -> ApiException.conflict("回收卷未登记回收重量: " + rollNo));
            if (!RecycleQuota.STATUS_ACTIVE.equals(quota.getStatus())) {
                throw ApiException.conflict("回收卷额度已失效（" + quota.getInvalidReason()
                        + "）: " + rollNo);
            }
        }

        LocalDateTime now = now();
        FeedBatch batch = new FeedBatch();
        batch.setFeedNo(newFeedNo());
        batch.setOrderNo(request.orderNo());
        batch.setVersionNo(current.getVersionNo());
        batch.setTotalPulpKg(totalPulpKg);
        batch.setTargetRatioPct(current.getTargetRatioPct());
        batch.setRequiredKg(required);
        batch.setActualKg(actual);
        batch.setRollCount(items.size());
        batch.setRequestId(request.requestId());
        batch.setCreatedAt(now);
        feedBatchRepository.insertBatch(batch, now);
        for (SubmitFeedRequest.FeedItem item : items) {
            FeedBatchItem record = new FeedBatchItem();
            record.setFeedNo(batch.getFeedNo());
            record.setRollNo(item.rollNo());
            record.setWeightKg(item.weightKg());
            feedBatchRepository.insertItem(record);
        }

        // 按卷号排序扣减，避免并发批间死锁；任一不足整单回滚（同事务）。
        List<SubmitFeedRequest.FeedItem> sorted = items.stream()
                .sorted(java.util.Comparator.comparing(SubmitFeedRequest.FeedItem::rollNo))
                .toList();
        for (SubmitFeedRequest.FeedItem item : sorted) {
            if (recycleQuotaRepository.consume(item.rollNo(), item.weightKg(), now) == 0) {
                throw ApiException.conflict(
                        "回收卷可用额度不足或已失效，整单回滚: " + item.rollNo());
            }
        }

        FeedView view = toFeedView(batch, items);
        registerIdempotent(request.requestId(), BIZ_FEED, fingerprint, batch.getFeedNo(), view);
        return new IdempotentResult<>(request.requestId(), false, view);
    }

    /** 投料批查询：总浆量、目标比例快照、应投与实际回收量及明细。 */
    public FeedView getFeed(String feedNo) {
        FeedBatch batch = feedBatchRepository.findByFeedNo(feedNo)
                .orElseThrow(() -> ApiException.notFound("投料批不存在: " + feedNo));
        List<FeedView.FeedItemView> items = feedBatchRepository.findItemsByFeedNo(feedNo).stream()
                .map(i -> new FeedView.FeedItemView(i.getRollNo(), i.getWeightKg()))
                .toList();
        return new FeedView(batch.getFeedNo(), batch.getOrderNo(), batch.getVersionNo(),
                batch.getTotalPulpKg(), batch.getTargetRatioPct(), batch.getRequiredKg(),
                batch.getActualKg(), items, batch.getRequestId(), batch.getCreatedAt());
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

    /**
     * 幂等记录查询：同键同参返回已完成的首次记录（调用方重放）；同键异参抛 409。
     * 读到未完成占位（首次事务尚未提交结果）时等待重试；查无记录则尝试插入占位，
     * 占位插入成功表示当前请求获得受理权，唯一索引冲突时重新查询。
     */
    private IdempotentRequest lookupIdempotent(String requestId, String bizType, String fingerprint) {
        for (int i = 0; i < 25; i++) {
            Optional<IdempotentRequest> found = idempotentRequestRepository.find(requestId);
            if (found.isPresent()) {
                IdempotentRequest record = found.get();
                if (!bizType.equals(record.getBizType()) || !fingerprint.equals(record.getFingerprint())) {
                    throw ApiException.conflict("requestId=" + requestId + " 已用于不同业务参数，冲突拒绝");
                }
                if (record.getResultJson() != null) {
                    return record;
                }
                // 首次事务仍在进行，等待其提交后重放。
                sleepBriefly();
                continue;
            }
            try {
                IdempotentRequest placeholder = new IdempotentRequest();
                placeholder.setRequestId(requestId);
                placeholder.setBizType(bizType);
                placeholder.setFingerprint(fingerprint);
                placeholder.setResultRef(null);
                placeholder.setResultJson(null);
                idempotentRequestRepository.insert(placeholder, now());
                return null;
            } catch (DuplicateKeyException e) {
                // 并发请求已插入占位（可能未提交），等待后重新查询。
                sleepBriefly();
            }
        }
        throw ApiException.conflict("并发幂等请求处理中，请稍后重放: " + requestId);
    }

    private void sleepBriefly() {
        try {
            Thread.sleep(20);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw ApiException.conflict("并发幂等请求处理中断");
        }
    }

    private void registerIdempotent(String requestId, String bizType, String fingerprint,
                                    String resultRef, Object resultView) {
        idempotentRequestRepository.updateResult(requestId, resultRef, writeJson(resultView));
    }

    private <T> IdempotentResult<T> replay(IdempotentRequest record, TypeReference<T> type) {
        T data;
        try {
            byte[] bytes = record.getResultJson() == null
                    ? new byte[0] : record.getResultJson().getBytes(StandardCharsets.UTF_8);
            data = objectMapper.readValue(bytes, type);
        } catch (Exception e) {
            throw new IllegalStateException("幂等首次结果反序列化失败", e);
        }
        return new IdempotentResult<>(record.getRequestId(), true, data);
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
                o.getTransitionLengthM(), o.getCurrentBlendVersion(), o.getConsecutiveOutOfTolerance(),
                o.getStatus(), o.getAppliedAt(), o.getDecidedAt(),
                o.getExecutedAt(), o.getDisposedAt(), o.getCompletedAt(), o.getArchivedAt());
    }

    private RollView toRollView(TransitionRoll r) {
        return new RollView(r.getRollNo(), r.getOrderNo(), r.getPaperMachine(), r.getLengthM(),
                r.getVerdict(), r.getWarehouseStatus(), r.isEligibleReexam(), r.getReexamCount(),
                r.getRegisteredAt(), r.getVerdictChangedAt(),
                verdictEventRepository.findByRollNo(r.getRollNo()));
    }

    private BlendVersionView toVersionView(BlendRatioVersion v) {
        return new BlendVersionView(v.getOrderNo(), v.getVersionNo(), v.getTargetRatioPct(),
                v.getSource(), v.getRequestId(), v.getCreatedAt());
    }

    private BlendReadingView toReadingView(BlendReading r) {
        return new BlendReadingView(r.getOrderNo(), r.getVersionNo(), r.getSampledAt(),
                r.getActualRatioPct(), r.getDeviationPct(), r.isOutOfTolerance());
    }

    /** 重量校验：正数且最多三位小数（尾零不计），单位 kg。 */
    private void requirePositiveWeight(BigDecimal weightKg, String label) {
        if (weightKg == null || weightKg.signum() <= 0) {
            throw ApiException.badRequest(label + "须为正数");
        }
        if (!TransitionRules.withinWeightPrecision(weightKg)) {
            throw ApiException.badRequest(label + "最多三位小数: " + weightKg);
        }
    }

    private String newFeedNo() {
        byte[] bytes = new byte[2];
        RANDOM.nextBytes(bytes);
        return "FD-" + now().format(NO_FORMAT) + "-" + HexFormat.of().formatHex(bytes);
    }

    private RecycleQuotaView toQuotaView(RecycleQuota q,
                                         List<RecycleQuotaView.ConsumptionView> consumptions) {
        BigDecimal available = RecycleQuota.STATUS_ACTIVE.equals(q.getStatus())
                ? q.getRegisteredKg().subtract(q.getConsumedKg()) : BigDecimal.ZERO;
        return new RecycleQuotaView(q.getRollNo(), q.getInitialKg(), q.getRegisteredKg(),
                q.getConsumedKg(), available, q.getInvalidatedKg(), q.getVersion(),
                q.getStatus(), q.getInvalidReason(), q.getRequestId(),
                q.getCreatedAt(), q.getUpdatedAt(), q.getInvalidatedAt(), consumptions);
    }

    private FeedView toFeedView(FeedBatch batch, List<SubmitFeedRequest.FeedItem> items) {
        List<FeedView.FeedItemView> itemViews = items.stream()
                .map(i -> new FeedView.FeedItemView(i.rollNo(), i.weightKg()))
                .toList();
        return new FeedView(batch.getFeedNo(), batch.getOrderNo(), batch.getVersionNo(),
                batch.getTotalPulpKg(), batch.getTargetRatioPct(), batch.getRequiredKg(),
                batch.getActualKg(), itemViews, batch.getRequestId(), batch.getCreatedAt());
    }
}
