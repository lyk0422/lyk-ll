package com.example.starter.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.service.DivingService;
import com.example.starter.service.DivingService.ServiceResult;

import jakarta.validation.Valid;

/**
 * 潜水作业 API：
 * <ul>
 *   <li>POST /api/diving/applications 作业申请（同时生成减压方案）</li>
 *   <li>POST /api/diving/plans/changes 方案变更（旧记录同事务冻结）</li>
 *   <li>POST /api/diving/segments 水下记录（两人成组提交生效）</li>
 *   <li>POST /api/diving/exits 出水登记（进入 2 小时观察）</li>
 *   <li>POST /api/diving/vitals 观察期体征（症状触发加压舱预案）</li>
 *   <li>POST /api/diving/closures 结案（终态，重复结案幂等成功）</li>
 * </ul>
 * 所有接口以 requestId 幂等：同键同参返回首次结果，同键异参返回 409。
 */
@RestController
@RequestMapping("/api/diving")
public class DivingController {

    private final DivingService service;

    public DivingController(DivingService service) {
        this.service = service;
    }

    @PostMapping("/applications")
    public ResponseEntity<PlanResponse> apply(@Valid @RequestBody ApplicationRequest request) {
        return toEntity(service.apply(request));
    }

    @PostMapping("/plans/changes")
    public ResponseEntity<PlanResponse> changePlan(@Valid @RequestBody PlanChangeRequest request) {
        return toEntity(service.changePlan(request));
    }

    @PostMapping("/segments")
    public ResponseEntity<SegmentResponse> recordSegment(@Valid @RequestBody SegmentRequest request) {
        return toEntity(service.recordSegment(request));
    }

    @PostMapping("/exits")
    public ResponseEntity<ExitResponse> exit(@Valid @RequestBody ExitRequest request) {
        return toEntity(service.exit(request));
    }

    @PostMapping("/vitals")
    public ResponseEntity<VitalsResponse> vitals(@Valid @RequestBody VitalsRequest request) {
        return toEntity(service.vitals(request));
    }

    @PostMapping("/closures")
    public ResponseEntity<CloseResponse> close(@Valid @RequestBody CloseRequest request) {
        return toEntity(service.close(request));
    }

    /** 只读复算详情：减压剖面、水下记录速率/告警、累计时长台账与事件。 */
    @GetMapping("/operations/{operationNo}")
    public OperationDetailResponse detail(@PathVariable String operationNo) {
        return service.getOperationDetail(operationNo);
    }

    private static <T> ResponseEntity<T> toEntity(ServiceResult<T> result) {
        return ResponseEntity.status(result.httpStatus()).body(result.body());
    }
}
