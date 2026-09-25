package com.example.starter.web;

import com.example.starter.dto.BrakeCurveResponse;
import com.example.starter.dto.BrakeTestRequest;
import com.example.starter.dto.ConsistCheckRequest;
import com.example.starter.dto.ConsistVersionsResponse;
import com.example.starter.dto.CutoutCarsResponse;
import com.example.starter.dto.OperationDetailResponse;
import com.example.starter.dto.PressureRequest;
import com.example.starter.dto.ReceiveRequest;
import com.example.starter.dto.RequestIdRequest;
import com.example.starter.dto.ShuntRequest;
import com.example.starter.service.IdempotencyService;
import com.example.starter.service.OperationService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 列车制动机试验与摘挂核对 API。
 */
@RestController
@RequestMapping("/api/operations")
public class OperationController {

    private final OperationService operationService;
    private final IdempotencyService idempotencyService;

    public OperationController(OperationService operationService,
                               IdempotencyService idempotencyService) {
        this.operationService = operationService;
        this.idempotencyService = idempotencyService;
    }

    /**
     * 接入: 建立列车作业并登记初始编组。
     */
    @PostMapping
    public ResponseEntity<String> receive(@Valid @RequestBody ReceiveRequest request) {
        return idempotencyService.execute("RECEIVE", request.requestId(), request,
                () -> operationService.receive(request));
    }

    /**
     * 摘挂: 变更编组并在同一事务内作废原试验结论。
     */
    @PostMapping("/{id}/shunts")
    public ResponseEntity<String> shunt(@PathVariable long id,
                                        @Valid @RequestBody ShuntRequest request) {
        return idempotencyService.execute("SHUNT", request.requestId(), request,
                () -> operationService.shunt(id, request));
    }

    /**
     * 编组核对: 与合成运统1 核对辆数/换长/总重。
     */
    @PostMapping("/{id}/consist-checks")
    public ResponseEntity<String> consistCheck(@PathVariable long id,
                                               @Valid @RequestBody ConsistCheckRequest request) {
        return idempotencyService.execute("CONSIST_CHECK", request.requestId(), request,
                () -> operationService.consistCheck(id, request));
    }

    /**
     * 风压上报: 销记后迟到数据仅记迟到事件。
     */
    @PostMapping("/{id}/pressures")
    public ResponseEntity<String> reportPressure(@PathVariable long id,
                                                 @Valid @RequestBody PressureRequest request) {
        return idempotencyService.execute("PRESSURE", request.requestId(), request,
                () -> operationService.reportPressure(id, request));
    }

    /**
     * 制动试验: 感度(减压50kPa)与安定(减压140kPa)。
     */
    @PostMapping("/{id}/brake-tests")
    public ResponseEntity<String> brakeTest(@PathVariable long id,
                                            @Valid @RequestBody BrakeTestRequest request) {
        return idempotencyService.execute("BRAKE_TEST", request.requestId(), request,
                () -> operationService.brakeTest(id, request));
    }

    /**
     * 发车确认: 运统1 核对一致、关门车比例不超 6%、感度与安定均合格方可发车。
     */
    @PostMapping("/{id}/departure-confirm")
    public ResponseEntity<String> confirmDeparture(@PathVariable long id,
                                                   @Valid @RequestBody RequestIdRequest request) {
        return idempotencyService.execute("DEPARTURE_CONFIRM", request.requestId(), request,
                () -> operationService.confirmDeparture(id));
    }

    /**
     * 销记(终态): 重复销记返回幂等成功。
     */
    @PostMapping("/{id}/close")
    public ResponseEntity<String> close(@PathVariable long id,
                                        @Valid @RequestBody RequestIdRequest request) {
        return idempotencyService.execute("CLOSE", request.requestId(), request,
                () -> operationService.close(id));
    }

    /**
     * 作业详情。
     */
    @GetMapping("/{id}")
    public OperationDetailResponse detail(@PathVariable long id) {
        return operationService.getOperation(id);
    }

    /**
     * 关门车清单复算(默认当前编组版本)。
     */
    @GetMapping("/{id}/cutout-cars")
    public CutoutCarsResponse cutoutCars(@PathVariable long id,
                                         @RequestParam(required = false) Integer version) {
        return operationService.cutoutCars(id, version);
    }

    /**
     * 编组版本复算。
     */
    @GetMapping("/{id}/consist-versions")
    public ConsistVersionsResponse consistVersions(@PathVariable long id) {
        return operationService.consistVersions(id);
    }

    /**
     * 制动试验曲线复算。
     */
    @GetMapping("/{id}/brake-curve")
    public BrakeCurveResponse brakeCurve(@PathVariable long id) {
        return operationService.brakeCurve(id);
    }
}
