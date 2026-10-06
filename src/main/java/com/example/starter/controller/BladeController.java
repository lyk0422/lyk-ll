package com.example.starter.controller;

import com.example.starter.dto.AlertView;
import com.example.starter.dto.AssessmentView;
import com.example.starter.dto.CompleteAssessmentRequest;
import com.example.starter.dto.CreateDefectRequest;
import com.example.starter.dto.CreateRepairOrderRequest;
import com.example.starter.dto.CreateReinspectionRequest;
import com.example.starter.dto.DefectResponse;
import com.example.starter.dto.EscalateResponse;
import com.example.starter.dto.PowerLimitView;
import com.example.starter.dto.RecurrenceStatView;
import com.example.starter.dto.ReinspectionResponse;
import com.example.starter.dto.RepairOrderView;
import com.example.starter.service.AssessmentService;
import com.example.starter.service.DefectService;
import com.example.starter.service.QueryService;
import com.example.starter.service.RepairService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 叶片巡检与维修服务接口。
 */
@RestController
@RequestMapping("/api")
public class BladeController {

    private final DefectService defectService;
    private final RepairService repairService;
    private final AssessmentService assessmentService;
    private final QueryService queryService;

    public BladeController(DefectService defectService,
                           RepairService repairService,
                           AssessmentService assessmentService,
                           QueryService queryService) {
        this.defectService = defectService;
        this.repairService = repairService;
        this.assessmentService = assessmentService;
        this.queryService = queryService;
    }

    /**
     * 登记巡检缺陷（幂等）。结构级缺陷立即限功率。
     */
    @PostMapping("/defects")
    public DefectResponse registerDefect(@Valid @RequestBody CreateDefectRequest request) {
        return defectService.register(request);
    }

    /**
     * 创建维修单（幂等）。同一叶片同时刻只允许一张进行中维修单。
     */
    @PostMapping("/repair-orders")
    public RepairOrderView createRepairOrder(@Valid @RequestBody CreateRepairOrderRequest request) {
        return repairService.createOrder(request);
    }

    /**
     * 登记复检（幂等）。合格则同事务结案并撤销限功率，不合格维持限功率。
     */
    @PostMapping("/repair-orders/{orderId}/reinspections")
    public ReinspectionResponse reinspect(@PathVariable long orderId,
                                          @Valid @RequestBody CreateReinspectionRequest request) {
        return repairService.reinspect(orderId, request);
    }

    /**
     * 逾期升级扫描：对超过 7 日期限未闭环的进行中维修单记录告警（每单一次，幂等）。
     */
    @PostMapping("/maintenance/escalate-overdue")
    public EscalateResponse escalateOverdue() {
        return repairService.escalateOverdue();
    }

    /**
     * 完成根因评估（幂等）。
     */
    @PostMapping("/assessments/{assessmentId}/complete")
    public AssessmentView completeAssessment(@PathVariable long assessmentId,
                                             @Valid @RequestBody CompleteAssessmentRequest request) {
        return assessmentService.complete(assessmentId, request);
    }

    /**
     * 限功率链查询。
     */
    @GetMapping("/blades/{bladeId}/power-limits")
    public List<PowerLimitView> powerLimits(@PathVariable long bladeId) {
        return queryService.powerLimits(bladeId);
    }

    /**
     * 维修台账查询。
     */
    @GetMapping("/blades/{bladeId}/repair-orders")
    public List<RepairOrderView> repairOrders(@PathVariable long bladeId) {
        return queryService.repairOrders(bladeId);
    }

    /**
     * 同类缺陷复发统计查询（默认 90 日窗口）。
     */
    @GetMapping("/blades/{bladeId}/recurrence")
    public List<RecurrenceStatView> recurrence(@PathVariable long bladeId,
                                               @RequestParam(defaultValue = "90") int days) {
        return queryService.recurrence(bladeId, days);
    }

    /**
     * 根因评估查询。
     */
    @GetMapping("/blades/{bladeId}/assessments")
    public List<AssessmentView> assessments(@PathVariable long bladeId) {
        return queryService.assessments(bladeId);
    }

    /**
     * 逾期升级告警查询。
     */
    @GetMapping("/blades/{bladeId}/alerts")
    public List<AlertView> alerts(@PathVariable long bladeId) {
        return queryService.alerts(bladeId);
    }
}
