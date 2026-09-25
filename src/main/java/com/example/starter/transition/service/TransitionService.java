package com.example.starter.transition.service;

import com.example.starter.transition.api.ApplyTransitionRequest;
import com.example.starter.transition.api.ExecuteTransitionRequest;
import com.example.starter.transition.domain.OrderStatus;
import com.example.starter.transition.domain.SwitchMode;
import com.example.starter.transition.po.Grade;
import com.example.starter.transition.po.TransitionOrder;
import com.example.starter.transition.repo.OrderRepository;
import com.example.starter.transition.repo.ResourceRepository;
import com.example.starter.transition.web.ConflictException;
import com.example.starter.transition.web.NotFoundException;
import com.example.starter.transition.web.UnprocessableException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

/**
 * 改品种流转服务：申请（方式判定）→执行（资源占用）→过渡料处置→完成→归档。
 * 克重差 ≤10%（恰 10% 含）直接切换，超出停机清洗；灰分等级差 >2 冲洗网部，恰 2 级合法直切。
 */
@Service
public class TransitionService {

    /** 直切允许的克重差上限比例（恰 10% 含）。 */
    private static final BigDecimal DIRECT_MAX_WEIGHT_DIFF_RATIO = new BigDecimal("0.10");
    /** 灰分等级差上限（恰 2 级合法直切，超出须冲洗网部）。 */
    private static final int ASH_LEVEL_FLUSH_THRESHOLD = 2;

    private final OrderRepository orders;
    private final ResourceRepository resources;
    private final GradeService gradeService;

    public TransitionService(OrderRepository orders, ResourceRepository resources, GradeService gradeService) {
        this.orders = orders;
        this.resources = resources;
        this.gradeService = gradeService;
    }

    /**
     * 申请改品种：快照前后品种参数并完成方式判定；同一纸机并发申请由执行锁唯一受理。
     */
    @Transactional
    public TransitionOrder apply(ApplyTransitionRequest request) {
        Grade from = gradeService.require(request.fromGradeCode());
        Grade to = gradeService.require(request.toGradeCode());

        if (!resources.tryAcquireMachineLock(request.machineId(), request.orderNo())) {
            throw new ConflictException("纸机 " + request.machineId() + " 已有进行中的改品种单，并发申请仅唯一受理");
        }
        try {
            TransitionOrder order = new TransitionOrder();
            order.orderNo = request.orderNo();
            order.machineId = request.machineId();
            order.fromGradeCode = from.gradeCode;
            order.toGradeCode = to.gradeCode;
            order.basisWeightFrom = from.basisWeight;
            order.basisWeightTo = to.basisWeight;
            order.ashFrom = from.ashLevel;
            order.ashTo = to.ashLevel;
            order.status = OrderStatus.APPLIED.name();
            order.switchMode = decideSwitchMode(from.basisWeight, to.basisWeight).name();
            order.wireFlush = Math.abs(to.ashLevel - from.ashLevel) > ASH_LEVEL_FLUSH_THRESHOLD;
            orders.insert(order);
            return orders.findByOrderNo(order.orderNo).orElseThrow();
        } catch (RuntimeException e) {
            resources.releaseMachineLock(request.machineId());
            throw e;
        }
    }

    /**
     * 方式判定：克重差比例 ≤10%（恰 10% 含）→ DIRECT，否则 STOP_CLEAN。
     */
    public static SwitchMode decideSwitchMode(BigDecimal fromWeight, BigDecimal toWeight) {
        BigDecimal diffRatio = toWeight.subtract(fromWeight).abs()
                .divide(fromWeight, 10, RoundingMode.HALF_UP);
        return diffRatio.compareTo(DIRECT_MAX_WEIGHT_DIFF_RATIO) <= 0
                ? SwitchMode.DIRECT : SwitchMode.STOP_CLEAN;
    }

    /**
     * 执行改品种：关键参数须在新品种工艺窗口内（恰边界合格）；
     * 执行登记与停机清洗窗口占用在同一事务内完成，直切单不得占用清洗窗口。
     */
    @Transactional
    public TransitionOrder execute(ExecuteTransitionRequest request) {
        TransitionOrder order = orders.findByOrderNoForUpdate(request.orderNo())
                .orElseThrow(() -> new NotFoundException("改品种单不存在: " + request.orderNo()));
        if (!OrderStatus.APPLIED.name().equals(order.status)) {
            throw new ConflictException("改品种单当前状态 " + order.status + " 不允许执行");
        }
        Grade to = gradeService.require(order.toGradeCode);
        if (request.machineSpeed().compareTo(to.speedMin) < 0
                || request.machineSpeed().compareTo(to.speedMax) > 0) {
            throw new UnprocessableException("车速 " + request.machineSpeed()
                    + " m/min 超出新品种工艺窗口 [" + to.speedMin + ", " + to.speedMax + "]");
        }
        if (request.consistency().compareTo(to.consistencyMin) < 0
                || request.consistency().compareTo(to.consistencyMax) > 0) {
            throw new UnprocessableException("上料浓度 " + request.consistency()
                    + "% 超出新品种工艺窗口 [" + to.consistencyMin + ", " + to.consistencyMax + "]");
        }

        order.switchSeconds = request.switchSeconds();
        order.machineSpeed = request.machineSpeed();
        order.consistency = request.consistency();
        order.transitionLengthM = computeTransitionLength(request.machineSpeed(), request.switchSeconds());

        if (SwitchMode.STOP_CLEAN.name().equals(order.switchMode)) {
            resources.insertCleaningWindow(order.machineId, order.orderNo);
        }
        int updated = orders.casExecute(order, LocalDateTime.now());
        if (updated == 0) {
            throw new ConflictException("改品种单状态已被并发修改，执行失败");
        }
        return orders.findByOrderNo(order.orderNo).orElseThrow();
    }

    /**
     * 过渡料长度（米）= 车速（m/min）× 切换秒数 / 60。
     */
    public static BigDecimal computeTransitionLength(BigDecimal machineSpeed, int switchSeconds) {
        return machineSpeed.multiply(BigDecimal.valueOf(switchSeconds))
                .divide(BigDecimal.valueOf(60), 3, RoundingMode.HALF_UP);
    }

    /**
     * 过渡料处置完成：执行中 → 已处置；重复处置返回当前单（幂等）。
     */
    @Transactional
    public TransitionOrder dispose(String orderNo) {
        TransitionOrder order = orders.findByOrderNoForUpdate(orderNo)
                .orElseThrow(() -> new NotFoundException("改品种单不存在: " + orderNo));
        if (OrderStatus.DISPOSED.name().equals(order.status)) {
            return order;
        }
        if (orders.casDispose(orderNo) == 0) {
            throw new ConflictException("改品种单当前状态 " + order.status + " 不允许处置完成");
        }
        return orders.findByOrderNo(orderNo).orElseThrow();
    }

    /**
     * 完成：释放纸机执行锁并关闭清洗窗口；重复完成返回当前单（幂等）。
     */
    @Transactional
    public TransitionOrder complete(String orderNo) {
        TransitionOrder order = orders.findByOrderNoForUpdate(orderNo)
                .orElseThrow(() -> new NotFoundException("改品种单不存在: " + orderNo));
        if (OrderStatus.COMPLETED.name().equals(order.status)) {
            return order;
        }
        if (orders.casComplete(orderNo, LocalDateTime.now()) == 0) {
            throw new ConflictException("改品种单当前状态 " + order.status + " 不允许完成");
        }
        resources.closeCleaningWindow(orderNo);
        resources.releaseMachineLock(order.machineId);
        return orders.findByOrderNo(orderNo).orElseThrow();
    }

    /**
     * 归档（终态）：重复归档返回幂等成功；归档后迟到检验数据只记迟到事件。
     */
    @Transactional
    public TransitionOrder archive(String orderNo) {
        TransitionOrder order = orders.findByOrderNoForUpdate(orderNo)
                .orElseThrow(() -> new NotFoundException("改品种单不存在: " + orderNo));
        if (OrderStatus.ARCHIVED.name().equals(order.status)) {
            return order;
        }
        if (orders.casArchive(orderNo, LocalDateTime.now()) == 0) {
            throw new ConflictException("改品种单当前状态 " + order.status + " 不允许归档（须先完成）");
        }
        return orders.findByOrderNo(orderNo).orElseThrow();
    }

    public TransitionOrder require(String orderNo) {
        return orders.findByOrderNo(orderNo)
                .orElseThrow(() -> new NotFoundException("改品种单不存在: " + orderNo));
    }
}
