package com.example.starter.dispense;

import java.time.Instant;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 分装计算、双人核对、发药勾稽与测量数据接口。
 */
@RestController
@RequestMapping("/api/dispenses")
public class DispenseController {

    private final DispenseService service;

    public DispenseController(DispenseService service) {
        this.service = service;
    }

    /**
     * 分装计算请求。
     *
     * @param requestId                幂等请求键
     * @param motherLiquorId           母液
     * @param prescriptionActivityMbq  处方活度，单位 MBq
     * @param administerAt             给药时刻（UTC）
     */
    public record CalculateRequest(@NotBlank String requestId, @NotNull Long motherLiquorId,
                                   @Positive double prescriptionActivityMbq,
                                   @NotNull Instant administerAt) {
    }

    /**
     * 双人核对请求。
     */
    public record CheckRequest(@NotBlank String requestId, @NotBlank String checkerId) {
    }

    /**
     * 发药请求。
     *
     * @param remainingActivityMbq 发药时实测剩余活度，单位 MBq
     * @param remainingMeasuredAt  剩余活度测量时刻（UTC），勾稽基准时刻
     */
    public record DeliverRequest(@NotBlank String requestId, @PositiveOrZero double remainingActivityMbq,
                                 @NotNull Instant remainingMeasuredAt) {
    }

    /**
     * 测量数据请求。
     */
    public record MeasurementRequest(@NotBlank String requestId, @Positive double measuredActivityMbq,
                                     @NotNull Instant measuredAt) {
    }

    /**
     * 分装计算；偏差超限返回 422 且批次记为 REJECTED 终态，同键重放返回首次结果。
     */
    @PostMapping
    public ResponseEntity<DispenseService.DispenseView> calculate(
            @Valid @RequestBody CalculateRequest request) {
        DispenseService.DispenseView view = service.calculate(request.requestId(),
                request.motherLiquorId(), request.prescriptionActivityMbq(), request.administerAt());
        if ("REJECTED".equals(view.status())) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(view);
        }
        return ResponseEntity.status(view.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(view);
    }

    /**
     * 双人核对：两名不同人员先后核对，单人不生效。
     */
    @PostMapping("/{id}/checks")
    public ResponseEntity<DispenseService.CheckView> check(@PathVariable long id,
                                                           @Valid @RequestBody CheckRequest request) {
        DispenseService.CheckView view = service.check(request.requestId(), id, request.checkerId());
        return ResponseEntity.status(view.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(view);
    }

    /**
     * 发药：勾稽不等返回 422；成功后批次进入 DELIVERED 终态。
     */
    @PostMapping("/{id}/delivery")
    public ResponseEntity<DispenseService.DeliveryView> deliver(
            @PathVariable long id, @Valid @RequestBody DeliverRequest request) {
        DispenseService.DeliveryView view = service.deliver(request.requestId(), id,
                request.remainingActivityMbq(), request.remainingMeasuredAt());
        return ResponseEntity.status(view.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(view);
    }

    /**
     * 登记测量数据；批次完成后到达的只记迟到事件（HTTP 202）。
     */
    @PostMapping("/{id}/measurements")
    public ResponseEntity<DispenseService.MeasurementView> measure(
            @PathVariable long id, @Valid @RequestBody MeasurementRequest request) {
        DispenseService.MeasurementView view = service.recordMeasurement(request.requestId(), id,
                request.measuredActivityMbq(), request.measuredAt());
        HttpStatus status = view.late() ? HttpStatus.ACCEPTED
                : (view.replayed() ? HttpStatus.OK : HttpStatus.CREATED);
        return ResponseEntity.status(status).body(view);
    }

    /**
     * 查询分装批次详情（含双人核对链）。
     */
    @GetMapping("/{id}")
    public DispenseService.DispenseDetail detail(@PathVariable long id) {
        return service.detail(id);
    }
}
