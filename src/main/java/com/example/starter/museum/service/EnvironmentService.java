package com.example.starter.museum.service;

import com.example.starter.museum.api.AlertResponse;
import com.example.starter.museum.api.ContractResponse;
import com.example.starter.museum.api.ReadingResponse;
import com.example.starter.museum.api.WorkOrderResponse;
import com.example.starter.museum.domain.AlertStatus;
import com.example.starter.museum.domain.ContractStatus;
import com.example.starter.museum.domain.Party;
import com.example.starter.museum.domain.WorkOrderStatus;
import com.example.starter.museum.domain.po.EnvironmentAlertPo;
import com.example.starter.museum.domain.po.EnvironmentReadingPo;
import com.example.starter.museum.domain.po.LoanContractPo;
import com.example.starter.museum.domain.po.ProtectionWorkOrderPo;
import com.example.starter.museum.domain.po.ResumeReviewPo;
import com.example.starter.museum.domain.po.ShowcasePo;
import com.example.starter.museum.repo.AlertRepository;
import com.example.starter.museum.repo.CatalogRepository;
import com.example.starter.museum.repo.ContractRepository;
import com.example.starter.museum.repo.EventRepository;
import com.example.starter.museum.repo.ReadingRepository;
import com.example.starter.museum.repo.ResumeReviewRepository;
import com.example.starter.museum.repo.WorkOrderRepository;
import com.example.starter.museum.support.ApiException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 展柜微环境监测服务。
 *
 * <p>读数按“展柜＋采样时刻”唯一约束去重；判异按 sample_time 升序归位，乱序上报同样可在
 * 序列补齐时识别出连续 3 点越限（恰 3 个即触发）。触发时自动生成保护工单（24h 处置时限），
 * 合同自动挂起；环境恢复后须双方复核方可复展。满 24h（恰满）未确认处置升级 URGENT 并冻结展柜。
 */
@Service
public class EnvironmentService {

    /** 连续越限采样点阈值：恰 3 个即触发。 */
    static final int OUT_OF_RANGE_WINDOW = 3;
    /** 处置时限：触发后 24 小时（UTC）。 */
    static final Duration HANDLE_DEADLINE = Duration.ofHours(24);

    private final ReadingRepository readings;
    private final AlertRepository alerts;
    private final WorkOrderRepository workOrders;
    private final ResumeReviewRepository reviews;
    private final ContractRepository contracts;
    private final CatalogRepository catalog;
    private final EventRepository events;
    private final NumberingService numbering;
    private final Clock clock;

    public EnvironmentService(ReadingRepository readings, AlertRepository alerts,
                              WorkOrderRepository workOrders, ResumeReviewRepository reviews,
                              ContractRepository contracts, CatalogRepository catalog,
                              EventRepository events, NumberingService numbering, Clock clock) {
        this.readings = readings;
        this.alerts = alerts;
        this.workOrders = workOrders;
        this.reviews = reviews;
        this.contracts = contracts;
        this.catalog = catalog;
        this.events = events;
        this.numbering = numbering;
        this.clock = clock;
    }

    @Transactional
    public ReadingResponse ingestReading(String showcaseNo, Instant sampleTime,
                                         BigDecimal temperature, BigDecimal humidity) {
        ShowcasePo showcase = catalog.findShowcaseByNo(showcaseNo)
                .orElseThrow(() -> ApiException.notFound("展柜不存在: " + showcaseNo));
        if (sampleTime == null) {
            throw ApiException.badRequest("sampleTime 不能为空");
        }
        Instant now = clock.instant();

        // 结案后到达的读数只记迟到事件，不入库参与判异。
        LoanContractPo latestContract = contracts.findLatestByShowcase(showcase.id).orElse(null);
        boolean late = latestContract != null && latestContract.status == ContractStatus.CLOSED;
        if (late) {
            events.record(latestContract.id, showcase.id, "LATE_READING",
                    "结案后迟到读数（仅记事件）：" + sampleTime + "，温度 " + temperature
                            + "℃，湿度 " + humidity + "%RH");
            return new ReadingResponse(showcaseNo, sampleTime, false, true, false, false, null, null);
        }

        ReadingRepository.InsertResult inserted = readings.insert(
                showcase.id, sampleTime, temperature, humidity, false);
        if (!inserted.inserted()) {
            // 展柜＋采样时刻去重：重复读数直接忽略。
            return new ReadingResponse(showcaseNo, sampleTime, true, false,
                    outOfRange(showcase, temperature, humidity), false, null, null);
        }

        boolean oor = outOfRange(showcase, temperature, humidity);
        events.record(latestContract == null ? null : latestContract.id, showcase.id,
                "READING_RECEIVED",
                "读数入库：" + sampleTime + "，温度 " + temperature + "℃，湿度 "
                        + humidity + "%RH，" + (oor ? "越限" : "正常"));

        // 任何读数到达时先驱动一次逾期升级，保证“恰满 24h”边界确定性生效。
        escalateDueWorkOrders();

        String alertNo = null;
        String orderNo = null;
        boolean triggered = false;
        if (oor) {
            TriggerOutcome outcome = maybeTrigger(showcase, latestContract, now);
            triggered = outcome.triggered;
            alertNo = outcome.alertNo;
            orderNo = outcome.orderNo;
        } else {
            // 读数回归正常：若工单已在 24h 内确认则进入双方复核；否则维持 OPEN/URGENT，
            // 处置时限仍可正常触发升级。
            tryEnterAwaitReview(showcase);
        }
        return new ReadingResponse(showcaseNo, sampleTime, false, false, oor, triggered, alertNo, orderNo);
    }

    private record TriggerOutcome(boolean triggered, String alertNo, String orderNo) {
    }

    /**
     * 取截至本次采样时刻、按采样时刻归位的最近 3 个读数；恰为 3 个且全部越限，
     * 且展柜当前无未关闭异常时触发。
     */
    private TriggerOutcome maybeTrigger(ShowcasePo showcase, LoanContractPo activeContract, Instant now) {
        if (alerts.findOpenByShowcase(showcase.id).isPresent()) {
            return new TriggerOutcome(false, null, null);
        }
        List<EnvironmentReadingPo> window = readings.recentOrdered(
                showcase.id, now, OUT_OF_RANGE_WINDOW);
        if (window.size() < OUT_OF_RANGE_WINDOW) {
            return new TriggerOutcome(false, null, null);
        }
        if (window.stream().anyMatch(r -> !outOfRange(showcase, r.temperature, r.humidity))) {
            return new TriggerOutcome(false, null, null);
        }
        // 乱序归位：触发点为窗口内采样时刻最晚的读数。
        EnvironmentReadingPo triggerReading = window.get(window.size() - 1);

        EnvironmentAlertPo alert = new EnvironmentAlertPo();
        alert.alertNo = numbering.nextAlertNo();
        alert.showcaseId = showcase.id;
        // 仅在展/挂起中的借展合同与异常关联；申请中、已归还等不挂起合同，异常仅记展柜。
        alert.contractId = activeContract != null
                && (activeContract.status == ContractStatus.IN_EXHIBITION
                || activeContract.status == ContractStatus.SUSPENDED)
                ? activeContract.id : null;
        alert.triggerReadingId = triggerReading.id;
        alert.triggerTime = triggerReading.sampleTime;
        alert.status = AlertStatus.OPEN;
        alert.createdAt = now;
        Long alertId = alerts.insert(alert);

        ProtectionWorkOrderPo order = new ProtectionWorkOrderPo();
        order.orderNo = numbering.nextOrderNo();
        order.alertId = alertId;
        order.showcaseId = showcase.id;
        order.status = WorkOrderStatus.PENDING;
        order.deadline = triggerReading.sampleTime.plus(HANDLE_DEADLINE);
        order.createdAt = now;
        workOrders.insert(order);

        // 在展合同自动挂起（乐观锁，避免与归还/续展并发踩踏）。
        if (activeContract != null && activeContract.status == ContractStatus.IN_EXHIBITION) {
            activeContract.status = ContractStatus.SUSPENDED;
            activeContract.suspendedAt = now;
            if (contracts.updateWithVersion(activeContract) == 0) {
                throw ApiException.conflict("并发冲突：环境异常挂起时合同已被另一流程裁决");
            }
        }
        events.record(alert.contractId, showcase.id, "ALERT_TRIGGERED",
                "连续 3 个采样点越限，环境异常 " + alert.alertNo + " 触发，触发时刻 "
                        + triggerReading.sampleTime + "，自动生成工单 " + order.orderNo
                        + "，处置时限 " + order.deadline + "（24 小时）");
        return new TriggerOutcome(true, alert.alertNo, order.orderNo);
    }

    /**
     * 进入“待双方复核”需要同时满足：
     * 1) 异常仍处于 OPEN/URGENT；2) 保护工单已完成处置确认；3) 最新读数（按采样时刻归位）回到区间。
     * 任一不满足则维持现状；处置未确认时 24h 时限仍会继续生效。
     */
    private void tryEnterAwaitReview(ShowcasePo showcase) {
        alerts.findOpenByShowcase(showcase.id).ifPresent(alert -> {
            if (alert.status != AlertStatus.OPEN && alert.status != AlertStatus.URGENT) {
                return;
            }
            boolean orderConfirmed = workOrders.findByAlert(alert.id)
                    .map(o -> o.status == WorkOrderStatus.CONFIRMED).orElse(false);
            boolean latestHealthy = readings.findLatest(showcase.id)
                    .map(r -> !outOfRange(showcase, r.temperature, r.humidity))
                    .orElse(false);
            if (orderConfirmed && latestHealthy && alerts.markRecovered(alert.id) > 0) {
                events.record(alert.contractId, showcase.id, "ENV_RECOVERED",
                        "微环境读数恢复正常且工单已处置，异常 " + alert.alertNo + " 待借出借入双方复核");
            }
        });
    }

    @Transactional
    public WorkOrderResponse confirmWorkOrder(String orderNo, String handler, String note) {
        ProtectionWorkOrderPo order = workOrders.findByNo(orderNo)
                .orElseThrow(() -> ApiException.notFound("保护工单不存在: " + orderNo));
        Long showcaseId = order.showcaseId;
        ShowcasePo showcase = catalog.findShowcaseById(showcaseId)
                .orElseThrow(() -> ApiException.notFound("展柜不存在: id=" + showcaseId));

        // 恰满 24h（now>=deadline）即逾期：先驱动升级，再判定本次确认是否在时限内。
        boolean onTime = clock.instant().isBefore(order.deadline)
                && order.status == WorkOrderStatus.PENDING;
        escalateDueWorkOrders();
        order = workOrders.getByNo(orderNo);

        int rows = workOrders.confirmIfPending(order.id, handler, note);
        if (rows == 0) {
            // 重复处置确认（并发或重放）：工单已确认，按幂等返回当前状态。
            ShowcasePo current = catalog.findShowcaseById(order.showcaseId).orElseThrow();
            return toOrderResponse(order, current.frozen);
        }
        if (onTime) {
            events.record(null, showcase.id, "WORK_ORDER_CONFIRMED",
                    "工单 " + orderNo + " 在 24 小时处置时限内确认处置");
        } else {
            // 逾期（恰满 24h）后确认：紧急升级事实保留，处置完成解除展柜冻结。
            catalog.setFrozen(showcase.id, false, null);
            events.record(null, showcase.id, "WORK_ORDER_LATE_CONFIRMED",
                    "工单 " + orderNo + " 在 24 小时处置时限之后确认，紧急升级已记录，展柜冻结解除");
        }
        // 若此时最新读数已回到区间，处置确认即推动异常进入待双方复核。
        tryEnterAwaitReview(showcase);
        order = workOrders.getByNo(orderNo);
        ShowcasePo refreshed = catalog.findShowcaseById(showcase.id).orElseThrow();
        return toOrderResponse(order, refreshed.frozen);
    }

    @Transactional
    public ContractResponse reviewResume(String contractNo, Party party) {
        LoanContractPo contract = contracts.findByNo(contractNo)
                .orElseThrow(() -> ApiException.notFound("借展合同不存在: " + contractNo));
        if (contract.status != ContractStatus.SUSPENDED) {
            throw ApiException.badRequest("合同当前状态 " + contract.status + "，仅挂起合同需要恢复复核");
        }
        EnvironmentAlertPo alert = alerts.findOpenByShowcase(contract.showcaseId)
                .orElseThrow(() -> ApiException.badRequest("合同没有待复核的环境异常"));
        if (alert.status != AlertStatus.AWAIT_REVIEW) {
            throw ApiException.badRequest("微环境尚未恢复正常，当前异常状态 " + alert.status);
        }
        ProtectionWorkOrderPo order = workOrders.findByAlert(alert.id)
                .orElseThrow(() -> ApiException.badRequest("异常缺少保护工单"));
        if (order.status != WorkOrderStatus.CONFIRMED) {
            throw ApiException.badRequest("保护工单尚未完成处置确认，不能复核复展");
        }

        ResumeReviewPo review = reviews.getOrCreate(contract.id, alert.id);
        boolean lender = review.lenderConfirmed || party == Party.LENDER;
        boolean borrower = review.borrowerConfirmed || party == Party.BORROWER;
        reviews.updateConfirmation(review.id, lender, borrower);
        if (!(lender && borrower)) {
            events.record(contract.id, contract.showcaseId, "RESUME_PARTY_REVIEWED",
                    "恢复复核收到 " + party + " 确认，尚待另一方确认，合同继续挂起");
            return contracts.findByNo(contractNo).map(this::toContractResponse)
                    .orElseThrow();
        }
        reviews.markCompleted(review.id);
        if (alerts.markResolved(alert.id) == 0) {
            throw ApiException.conflict("并发冲突：异常复核状态已变更");
        }
        contract.status = ContractStatus.IN_EXHIBITION;
        contract.resumedAt = clock.instant();
        if (contracts.updateWithVersion(contract) == 0) {
            throw ApiException.conflict("并发冲突：恢复复展时合同已被另一流程裁决");
        }
        catalog.setFrozen(contract.showcaseId, false, null);
        events.record(contract.id, contract.showcaseId, "LOAN_RESUMED",
                "借出借入双方复核完成，异常 " + alert.alertNo + " 关闭，合同恢复在展");
        return toContractResponse(contracts.getByNo(contractNo));
    }

    /**
     * 扫描所有 PENDING 工单，deadline &lt;= 当前时刻（恰满 24h）即升级 URGENT 并冻结展柜。
     * 由定时任务与各业务入口共同调用，时钟可控时边界确定可测。
     */
    @Scheduled(fixedDelay = 1000L)
    @Transactional
    public int escalateDueWorkOrders() {
        Instant now = clock.instant();
        int count = 0;
        for (ProtectionWorkOrderPo order : workOrders.findPending()) {
            if (now.isBefore(order.deadline)) {
                continue;
            }
            EnvironmentAlertPo alert = alerts.findById(order.alertId).orElse(null);
            if (alerts.markEscalated(order.alertId) > 0) {
                catalog.setFrozen(order.showcaseId, true,
                        "异常工单 " + order.orderNo + " 满 24 小时未确认处置，升级紧急");
                events.record(alert != null ? alert.contractId : null, order.showcaseId,
                        "ALERT_ESCALATED",
                        "工单 " + order.orderNo + " 恰满/超过 24 小时未确认，异常升级紧急，展柜冻结新入藏");
                count++;
            }
        }
        return count;
    }

    private boolean outOfRange(ShowcasePo showcase, BigDecimal temperature, BigDecimal humidity) {
        boolean tempBad = temperature.compareTo(showcase.tempMin) < 0
                || temperature.compareTo(showcase.tempMax) > 0;
        boolean humBad = humidity.compareTo(showcase.humMin) < 0
                || humidity.compareTo(showcase.humMax) > 0;
        return tempBad || humBad;
    }

    private WorkOrderResponse toOrderResponse(ProtectionWorkOrderPo order, boolean frozen) {
        EnvironmentAlertPo alert = alerts.findById(order.alertId).orElse(null);
        ShowcasePo showcase = catalog.findShowcaseById(order.showcaseId).orElseThrow();
        return new WorkOrderResponse(order.orderNo,
                alert != null ? alert.alertNo : null,
                showcase.showcaseNo, order.status, order.deadline, order.confirmedAt,
                order.handler, order.note, frozen);
    }

    private ContractResponse toContractResponse(LoanContractPo po) {
        String artifactNo = catalog.findArtifactById(po.artifactId).artifactNo;
        String showcaseNo = po.showcaseId == null ? null
                : catalog.findShowcaseById(po.showcaseId).map(s -> s.showcaseNo).orElse(null);
        return new ContractResponse(po.contractNo, artifactNo, po.borrower,
                po.startDate, po.endDate, po.status, showcaseNo, po.renewCount,
                po.suspendedAt, po.resumedAt, po.returnedAt, po.closedAt, po.version);
    }
}
