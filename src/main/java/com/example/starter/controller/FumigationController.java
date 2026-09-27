package com.example.starter.controller;

import java.util.List;

import com.example.starter.dto.FumigationDtos.ApplyFumigationRequest;
import com.example.starter.dto.FumigationDtos.ApprovalView;
import com.example.starter.dto.FumigationDtos.ApproveRequest;
import com.example.starter.dto.FumigationDtos.ConfirmationView;
import com.example.starter.dto.FumigationDtos.FinishRequest;
import com.example.starter.dto.FumigationDtos.FumigationDetailView;
import com.example.starter.dto.FumigationDtos.FumigationView;
import com.example.starter.dto.FumigationDtos.GasReadingRequest;
import com.example.starter.dto.FumigationDtos.GasReadingView;
import com.example.starter.dto.FumigationDtos.ReleaseConfirmRequest;
import com.example.starter.dto.FumigationDtos.StartRequest;
import com.example.starter.service.FumigationService;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 熏蒸闭环接口：申请、双人批准、开始（锁仓）、结束转散气（绑风机）、散气采样、双人复核解除及查询。
 */
@RestController
@RequestMapping("/api")
public class FumigationController {

    private final FumigationService fumigationService;

    public FumigationController(FumigationService fumigationService) {
        this.fumigationService = fumigationService;
    }

    /** 申请熏蒸单：UTC 半开作业窗互斥、requestId 幂等，版本从 1 开始。 */
    @PostMapping("/silos/{siloId}/fumigation-orders")
    public ResponseEntity<FumigationView> apply(@PathVariable long siloId,
                                                @Valid @RequestBody ApplyFumigationRequest request) {
        FumigationView view = fumigationService.apply(siloId, request);
        return ResponseEntity.status(view.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(view);
    }

    /** 审批：两名不同审批人共同批准，expectedVersion 乐观锁，requestId 幂等。 */
    @PostMapping("/fumigation-orders/{orderId}/approvals")
    public ResponseEntity<ApprovalView> approve(@PathVariable long orderId,
                                                @Valid @RequestBody ApproveRequest request) {
        ApprovalView view = fumigationService.approve(orderId, request);
        return ResponseEntity.status(view.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(view);
    }

    /** 开始熏蒸：再查无执行中通风，原子锁仓并冻结方案版本。 */
    @PostMapping("/fumigation-orders/{orderId}/start")
    public FumigationView start(@PathVariable long orderId, @Valid @RequestBody StartRequest request) {
        return fumigationService.start(orderId, request);
    }

    /** 结束熏蒸转散气：原子绑定一台空闲风机。 */
    @PostMapping("/fumigation-orders/{orderId}/finish")
    public FumigationView finish(@PathVariable long orderId, @Valid @RequestBody FinishRequest request) {
        return fumigationService.finish(orderId, request);
    }

    /** 散气采样：序号单内唯一、时刻严格递增、浓度非负；解除后迟到只记事件。 */
    @PostMapping("/fumigation-orders/{orderId}/gas-readings")
    public ResponseEntity<GasReadingView> sample(@PathVariable long orderId,
                                                 @Valid @RequestBody GasReadingRequest request) {
        GasReadingView view = fumigationService.sample(orderId, request);
        return ResponseEntity.status(view.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(view);
    }

    /** 解除确认：两名不同复核人最终确认，重查最新读数与版本，原子释放仓锁与风机。 */
    @PostMapping("/fumigation-orders/{orderId}/release-confirmations")
    public ResponseEntity<ConfirmationView> confirmRelease(
            @PathVariable long orderId, @Valid @RequestBody ReleaseConfirmRequest request) {
        ConfirmationView view = fumigationService.confirmRelease(orderId, request);
        return ResponseEntity.status(view.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(view);
    }

    /** 熏蒸单详情（含解除条件评估），供解除前查询。 */
    @GetMapping("/fumigation-orders/{orderId}")
    public FumigationDetailView getOrder(@PathVariable long orderId) {
        return fumigationService.getOrder(orderId);
    }

    /** 散气读数列表（按序号排序，可复算连续三点）。 */
    @GetMapping("/fumigation-orders/{orderId}/gas-readings")
    public List<GasReadingView> readings(@PathVariable long orderId) {
        return fumigationService.readings(orderId);
    }

    /** 粮仓熏蒸窗口列表（UTC 半开区间 [start, end)，可复算占用）。 */
    @GetMapping("/silos/{siloId}/fumigation-windows")
    public List<FumigationView> windowsOfSilo(@PathVariable long siloId) {
        return fumigationService.windowsOfSilo(siloId);
    }
}
