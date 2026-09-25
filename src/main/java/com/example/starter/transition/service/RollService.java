package com.example.starter.transition.service;

import com.example.starter.transition.api.InspectionRequest;
import com.example.starter.transition.api.RegisterRollRequest;
import com.example.starter.transition.domain.FinalGrade;
import com.example.starter.transition.domain.InspectionStatus;
import com.example.starter.transition.domain.OrderStatus;
import com.example.starter.transition.po.Grade;
import com.example.starter.transition.po.RollJudgement;
import com.example.starter.transition.po.TransitionOrder;
import com.example.starter.transition.po.TransitionRoll;
import com.example.starter.transition.repo.JudgementRepository;
import com.example.starter.transition.repo.LateEventRepository;
import com.example.starter.transition.repo.OrderRepository;
import com.example.starter.transition.repo.RollRepository;
import com.example.starter.transition.web.ConflictException;
import com.example.starter.transition.web.NotFoundException;
import com.example.starter.transition.web.UnprocessableException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 过渡纸卷服务：按卷号唯一登记、强制降级；检验恰达正品门槛可复议改判正品一次；
 * 归档后迟到检验只记迟到事件。
 */
@Service
public class RollService {

    /** 正品门槛：实测克重相对目标克重允许偏差比例（恰边界合格）。 */
    private static final BigDecimal GATE_WEIGHT_TOLERANCE_RATIO = new BigDecimal("0.05");
    /** 正品门槛：实测灰分等级相对目标允许偏差级数（恰边界合格）。 */
    private static final int GATE_ASH_TOLERANCE_LEVEL = 1;

    private final RollRepository rolls;
    private final JudgementRepository judgements;
    private final OrderRepository orders;
    private final LateEventRepository lateEvents;
    private final GradeService gradeService;

    public RollService(RollRepository rolls, JudgementRepository judgements,
                       OrderRepository orders, LateEventRepository lateEvents,
                       GradeService gradeService) {
        this.rolls = rolls;
        this.judgements = judgements;
        this.orders = orders;
        this.lateEvents = lateEvents;
        this.gradeService = gradeService;
    }

    /**
     * 登记过渡纸卷：卷号唯一，登记即强制降级，不得按正品入库。
     */
    @Transactional
    public TransitionRoll register(RegisterRollRequest request) {
        TransitionOrder order = orders.findByOrderNo(request.orderNo())
                .orElseThrow(() -> new NotFoundException("改品种单不存在: " + request.orderNo()));
        if (!OrderStatus.EXECUTING.name().equals(order.status)
                && !OrderStatus.DISPOSED.name().equals(order.status)) {
            throw new ConflictException("改品种单当前状态 " + order.status + " 不允许登记过渡纸卷");
        }
        TransitionRoll roll = new TransitionRoll();
        roll.rollNo = request.rollNo();
        roll.orderNo = order.orderNo;
        roll.machineId = order.machineId;
        roll.lengthM = request.lengthM();
        roll.finalGrade = FinalGrade.DOWNGRADED.name();
        roll.inspectionStatus = InspectionStatus.PENDING.name();
        try {
            rolls.insert(roll);
        } catch (DuplicateKeyException e) {
            throw new ConflictException("卷号已登记: " + request.rollNo());
        }
        return rolls.findByRollNo(roll.rollNo).orElseThrow();
    }

    /**
     * 检验提交：首次判定强制降级并记录是否达正品门槛；
     * 所属改品种单已归档时只记迟到事件，不产生判定。
     */
    @Transactional
    public InspectionOutcome inspect(InspectionRequest request) {
        TransitionRoll roll = rolls.findByRollNoForUpdate(request.rollNo())
                .orElseThrow(() -> new NotFoundException("过渡纸卷不存在: " + request.rollNo()));
        TransitionOrder order = orders.findByOrderNo(roll.orderNo)
                .orElseThrow(() -> new NotFoundException("改品种单不存在: " + roll.orderNo));

        if (OrderStatus.ARCHIVED.name().equals(order.status)) {
            String payload = "measuredBasisWeight=" + request.measuredBasisWeight()
                    + ", measuredAshLevel=" + request.measuredAshLevel()
                    + ", measuredTensile=" + request.measuredTensile();
            lateEvents.insert(order.orderNo, roll.rollNo, request.requestId(), payload);
            return new InspectionOutcome(roll, null, true);
        }
        if (!InspectionStatus.PENDING.name().equals(roll.inspectionStatus)) {
            throw new ConflictException("过渡纸卷已完成首次检验判定，禁止重复判定");
        }

        Grade target = gradeService.require(order.toGradeCode);
        roll.gatePassed = passesGate(request, target);
        roll.measuredBasisWeight = request.measuredBasisWeight();
        roll.measuredAshLevel = request.measuredAshLevel();
        roll.measuredTensile = request.measuredTensile();
        if (rolls.casInspect(roll, LocalDateTime.now()) == 0) {
            throw new ConflictException("过渡纸卷检验状态已被并发修改");
        }

        RollJudgement first = new RollJudgement();
        first.rollNo = roll.rollNo;
        first.roundNo = 1;
        first.result = FinalGrade.DOWNGRADED.name();
        first.gatePassed = roll.gatePassed;
        first.reason = "过渡纸卷强制降级，不得按正品入库"
                + (Boolean.TRUE.equals(roll.gatePassed) ? "；检验达正品门槛，可申请复议一次" : "");
        first.requestId = request.requestId();
        judgements.insert(first);

        TransitionRoll saved = rolls.findByRollNo(roll.rollNo).orElseThrow();
        return new InspectionOutcome(saved, first, false);
    }

    /**
     * 合成正品门槛：抗张指数 ≥ 门槛，且克重偏差 ≤5%，且灰分等级差 ≤1（恰达边界均合格）。
     */
    public static boolean passesGate(InspectionRequest request, Grade target) {
        if (request.measuredTensile().compareTo(target.tensileMin) < 0) {
            return false;
        }
        BigDecimal weightDiffRatio = request.measuredBasisWeight()
                .subtract(target.basisWeight).abs()
                .divide(target.basisWeight, 10, RoundingMode.HALF_UP);
        if (weightDiffRatio.compareTo(GATE_WEIGHT_TOLERANCE_RATIO) > 0) {
            return false;
        }
        return Math.abs(request.measuredAshLevel() - target.ashLevel) <= GATE_ASH_TOLERANCE_LEVEL;
    }

    /**
     * 复议改判正品：仅首次检验达正品门槛（含恰达）且未复议过的卷可改判一次；
     * 与首次判定并发时由行锁与 CAS 保证唯一终态；原降级判定记录保留可查。
     */
    @Transactional
    public TransitionRoll appeal(String requestId, String rollNo) {
        TransitionRoll roll = rolls.findByRollNoForUpdate(rollNo)
                .orElseThrow(() -> new NotFoundException("过渡纸卷不存在: " + rollNo));
        TransitionOrder order = orders.findByOrderNo(roll.orderNo)
                .orElseThrow(() -> new NotFoundException("改品种单不存在: " + roll.orderNo));
        if (OrderStatus.ARCHIVED.name().equals(order.status)) {
            throw new ConflictException("改品种单已归档（终态），禁止复议改判");
        }
        if (!InspectionStatus.INSPECTED.name().equals(roll.inspectionStatus)) {
            throw new UnprocessableException("过渡纸卷尚未完成首次检验判定，不能复议");
        }
        if (roll.appealCount != null && roll.appealCount > 0) {
            throw new ConflictException("过渡纸卷已复议改判过一次，不允许再次复议");
        }
        if (!Boolean.TRUE.equals(roll.gatePassed)) {
            throw new UnprocessableException("首次检验未达正品门槛，不符合复议改判条件");
        }
        if (rolls.casAppeal(rollNo, LocalDateTime.now()) == 0) {
            throw new ConflictException("复议与首次判定并发冲突，终态已被其他请求确定");
        }

        RollJudgement second = new RollJudgement();
        second.rollNo = rollNo;
        second.roundNo = 2;
        second.result = FinalGrade.QUALIFIED.name();
        second.gatePassed = roll.gatePassed;
        second.reason = "复议改判正品：首次检验达正品门槛（含恰达边界），原降级判定记录保留";
        second.requestId = requestId;
        judgements.insert(second);

        return rolls.findByRollNo(rollNo).orElseThrow();
    }

    public TransitionRoll requireRoll(String rollNo) {
        return rolls.findByRollNo(rollNo)
                .orElseThrow(() -> new NotFoundException("过渡纸卷不存在: " + rollNo));
    }

    public List<RollJudgement> judgementChain(String rollNo) {
        return judgements.findByRollNo(rollNo);
    }

    public List<TransitionRoll> rollsOfOrder(String orderNo) {
        return rolls.findByOrderNo(orderNo);
    }

    public int lateEventCount(String orderNo) {
        return lateEvents.countByOrderNo(orderNo);
    }

    /**
     * 检验提交结果。
     *
     * @param roll          卷当前状态（迟到时为未变更状态）
     * @param firstJudgement 首次判定记录；迟到时为 null
     * @param late          是否归档后迟到（只记迟到事件）
     */
    public record InspectionOutcome(TransitionRoll roll, RollJudgement firstJudgement, boolean late) {
    }
}
