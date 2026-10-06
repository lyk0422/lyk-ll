package com.example.starter.service;

import com.example.starter.dto.AlertView;
import com.example.starter.dto.CreateRepairOrderRequest;
import com.example.starter.dto.CreateReinspectionRequest;
import com.example.starter.dto.EscalateResponse;
import com.example.starter.dto.ReinspectionResponse;
import com.example.starter.dto.RepairOrderView;
import com.example.starter.error.ApiException;
import com.example.starter.model.DefectRow;
import com.example.starter.model.ReinspectionResult;
import com.example.starter.model.RepairOrderRow;
import com.example.starter.repository.AlertRepository;
import com.example.starter.repository.DefectRepository;
import com.example.starter.repository.ReinspectionRepository;
import com.example.starter.repository.RepairOrderRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 维修与复检：7 日期限（恰第 7 日闭环有效）、复检合格同事务结案并撤销限功率、
 * 复检不合格维持限功率不结案、逾期升级保留首次缺陷登记时刻。
 */
@Service
public class RepairService {

    /** 结构级缺陷维修期限天数。 */
    public static final int REPAIR_DEADLINE_DAYS = 7;

    private final RepairOrderRepository repairOrderRepository;
    private final DefectRepository defectRepository;
    private final ReinspectionRepository reinspectionRepository;
    private final AlertRepository alertRepository;
    private final PowerLimitService powerLimitService;
    private final IdempotencyService idempotencyService;
    private final Clock clock;

    public RepairService(RepairOrderRepository repairOrderRepository,
                         DefectRepository defectRepository,
                         ReinspectionRepository reinspectionRepository,
                         AlertRepository alertRepository,
                         PowerLimitService powerLimitService,
                         IdempotencyService idempotencyService,
                         Clock clock) {
        this.repairOrderRepository = repairOrderRepository;
        this.defectRepository = defectRepository;
        this.reinspectionRepository = reinspectionRepository;
        this.alertRepository = alertRepository;
        this.powerLimitService = powerLimitService;
        this.idempotencyService = idempotencyService;
        this.clock = clock;
    }

    /**
     * 创建维修单（幂等）；同一叶片同时刻只允许一张进行中维修单。
     */
    public RepairOrderView createOrder(CreateRepairOrderRequest request) {
        return idempotencyService.execute("repair-orders", request.requestId(), request,
                RepairOrderView.class, () -> doCreateOrder(request));
    }

    private RepairOrderView doCreateOrder(CreateRepairOrderRequest request) {
        DefectRow defect = defectRepository.findById(request.defectId())
                .orElseThrow(() -> ApiException.notFound("缺陷记录不存在: " + request.defectId()));
        if (repairOrderRepository.findByDefectId(defect.id()).isPresent()) {
            throw ApiException.conflict("该缺陷已存在维修单");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        long orderId;
        try {
            orderId = repairOrderRepository.insert(defect.id(), defect.bladeId(),
                    defect.detectedAt().plusDays(REPAIR_DEADLINE_DAYS), now);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("同一叶片同时刻只允许一张进行中维修单");
        }
        return toView(repairOrderRepository.findById(orderId).orElseThrow());
    }

    /**
     * 登记复检（幂等）：合格则同事务结案并按需撤销限功率；不合格则维持限功率、不结案。
     */
    public ReinspectionResponse reinspect(long orderId, CreateReinspectionRequest request) {
        return idempotencyService.execute("reinspections:" + orderId, request.requestId(), request,
                ReinspectionResponse.class, () -> doReinspect(orderId, request));
    }

    private ReinspectionResponse doReinspect(long orderId, CreateReinspectionRequest request) {
        RepairOrderRow order = repairOrderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> ApiException.notFound("维修单不存在: " + orderId));
        if (!order.inProgress()) {
            throw ApiException.conflict("维修单已结案，不得重复复检");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        long reinspectionId = reinspectionRepository.insert(
                orderId, request.result().name(), now, now);
        if (request.result() == ReinspectionResult.PASS) {
            // 恰第 7 日闭环有效：now 严格大于期限才算逾期
            String closeResult = now.isAfter(order.deadlineAt()) ? "LATE" : "ON_TIME";
            repairOrderRepository.close(orderId, now, closeResult);
        }
        powerLimitService.sync(order.bladeId(), now);
        String status = request.result() == ReinspectionResult.PASS ? "CLOSED" : "IN_PROGRESS";
        return new ReinspectionResponse(reinspectionId, orderId, request.result(), now,
                status, powerLimitService.isActive(order.bladeId()));
    }

    /**
     * 逾期升级扫描：对逾期未闭环的进行中维修单记录告警并标记升级；
     * 每单仅告警一次，重复执行幂等。告警保留首次缺陷登记时刻，不改动原缺陷。
     */
    @Transactional
    public EscalateResponse escalateOverdue() {
        LocalDateTime now = LocalDateTime.now(clock);
        List<AlertView> alerts = new ArrayList<>();
        for (RepairOrderRow order : repairOrderRepository.findOverdueInProgress(now)) {
            DefectRow defect = defectRepository.findById(order.defectId()).orElseThrow();
            String message = "维修单#" + order.id() + " 超过 7 日期限未闭环，限功率维持并升级告警";
            long alertId = alertRepository.insert(order.id(), order.bladeId(), order.defectId(),
                    defect.detectedAt(), now, message, now);
            repairOrderRepository.markEscalated(order.id());
            alerts.add(new AlertView(alertId, order.id(), order.bladeId(), order.defectId(),
                    defect.detectedAt(), now, message));
        }
        return new EscalateResponse(alerts.size(), alerts);
    }

    static RepairOrderView toView(RepairOrderRow row) {
        return new RepairOrderView(row.id(), row.defectId(), row.bladeId(), row.status(),
                row.deadlineAt(), row.closedAt(), row.closeResult(), row.escalated());
    }
}
