package com.example.starter.web;

import com.example.starter.service.MaintenanceService;
import com.example.starter.web.dto.MaintenanceAcceptanceRequest;
import com.example.starter.web.dto.MaintenanceCancelRequest;
import com.example.starter.web.dto.MaintenanceClearanceRequest;
import com.example.starter.web.dto.MaintenanceOrderCreateRequest;
import com.example.starter.web.dto.MaintenanceOrderView;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 检修封锁窗口接口。
 */
@RestController
@RequestMapping("/api")
public class MaintenanceController {

    private final MaintenanceService maintenanceService;

    public MaintenanceController(MaintenanceService maintenanceService) {
        this.maintenanceService = maintenanceService;
    }

    /**
     * 登记检修单（窗口 [start,end)，同线路内部重叠拒绝）。
     */
    @PostMapping("/lines/{lineId}/maintenance-orders")
    public ResponseEntity<String> createOrder(@PathVariable long lineId,
                                              @Valid @RequestBody MaintenanceOrderCreateRequest request) {
        return maintenanceService.createOrder(lineId, request);
    }

    /**
     * 取消检修单（仅未生效单可取消）。
     */
    @PostMapping("/maintenance-orders/{orderId}/cancellations")
    public ResponseEntity<String> cancel(@PathVariable long orderId,
                                         @Valid @RequestBody MaintenanceCancelRequest request) {
        return maintenanceService.cancel(orderId, request);
    }

    /**
     * 登记分段清空证据（生效后逐段登记）。
     */
    @PostMapping("/maintenance-orders/{orderId}/segment-clearances")
    public ResponseEntity<String> recordClearance(@PathVariable long orderId,
                                                  @Valid @RequestBody MaintenanceClearanceRequest request) {
        return maintenanceService.recordClearance(orderId, request);
    }

    /**
     * 检修验收（机务与检修两岗、不同人员，不得早于窗口终点）。
     */
    @PostMapping("/maintenance-orders/{orderId}/acceptances")
    public ResponseEntity<String> accept(@PathVariable long orderId,
                                         @Valid @RequestBody MaintenanceAcceptanceRequest request) {
        return maintenanceService.accept(orderId, request);
    }

    /**
     * 查询检修单（含分段清空证据与验收人员意见）。
     */
    @GetMapping("/maintenance-orders/{orderId}")
    public MaintenanceOrderView order(@PathVariable long orderId) {
        return maintenanceService.orderView(orderId);
    }
}
