package com.example.starter.controller;

import java.util.List;

import com.example.starter.dto.GrainDtos.AddRosterPointsRequest;
import com.example.starter.dto.GrainDtos.ApplyFumigationRequest;
import com.example.starter.dto.GrainDtos.ApproveFumigationRequest;
import com.example.starter.dto.GrainDtos.BeginAerationRequest;
import com.example.starter.dto.GrainDtos.FumigationView;
import com.example.starter.dto.GrainDtos.GasFrameRequest;
import com.example.starter.dto.GrainDtos.GasFrameView;
import com.example.starter.dto.GrainDtos.GasReadingRequest;
import com.example.starter.dto.GrainDtos.GasReadingView;
import com.example.starter.dto.GrainDtos.GasSafetyView;
import com.example.starter.dto.GrainDtos.RegisterRosterRequest;
import com.example.starter.dto.GrainDtos.ReleaseFumigationRequest;
import com.example.starter.dto.GrainDtos.ReleaseView;
import com.example.starter.dto.GrainDtos.RosterView;
import com.example.starter.dto.GrainDtos.StartFumigationRequest;
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
 * 熏蒸作业接口：申请、双审批、开始锁仓、转散气、气体采样、双复核解除及查询。
 */
@RestController
@RequestMapping("/api")
public class FumigationController {

    private final FumigationService fumigationService;

    public FumigationController(FumigationService fumigationService) {
        this.fumigationService = fumigationService;
    }

    /** 申请熏蒸单。 */
    @PostMapping("/silos/{siloId}/fumigation-orders")
    public ResponseEntity<FumigationView> apply(@PathVariable long siloId,
                                                @Valid @RequestBody ApplyFumigationRequest request) {
        FumigationView view = fumigationService.apply(siloId, request);
        return ResponseEntity.status(view.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(view);
    }

    /** 查询熏蒸单。 */
    @GetMapping("/fumigation-orders/{orderId}")
    public FumigationView getOrder(@PathVariable long orderId) {
        return fumigationService.getOrder(orderId);
    }

    /** 审批（每名审批人一次，两名不同审批人共同批准）。 */
    @PostMapping("/fumigation-orders/{orderId}/approvals")
    public ResponseEntity<FumigationView> approve(@PathVariable long orderId,
                                                  @Valid @RequestBody ApproveFumigationRequest request) {
        FumigationView view = fumigationService.approve(orderId, request);
        return ResponseEntity.status(view.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(view);
    }

    /** 实际开始：锁仓并冻结方案版本。 */
    @PostMapping("/fumigation-orders/{orderId}/start")
    public ResponseEntity<FumigationView> start(@PathVariable long orderId,
                                                @Valid @RequestBody StartFumigationRequest request) {
        FumigationView view = fumigationService.start(orderId, request);
        return ResponseEntity.status(view.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(view);
    }

    /** 结束熏蒸转散气：绑定空闲风机。 */
    @PostMapping("/fumigation-orders/{orderId}/aeration")
    public ResponseEntity<FumigationView> beginAeration(@PathVariable long orderId,
                                                        @Valid @RequestBody BeginAerationRequest request) {
        FumigationView view = fumigationService.beginAeration(orderId, request);
        return ResponseEntity.status(view.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(view);
    }

    /** 散气合成气体采样。 */
    @PostMapping("/fumigation-orders/{orderId}/gas-readings")
    public ResponseEntity<GasReadingView> gasReading(@PathVariable long orderId,
                                                     @Valid @RequestBody GasReadingRequest request) {
        GasReadingView view = fumigationService.ingestGasReading(orderId, request);
        return ResponseEntity.status(view.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(view);
    }

    /** 双复核解除锁仓。 */
    @PostMapping("/fumigation-orders/{orderId}/release")
    public ResponseEntity<ReleaseView> release(@PathVariable long orderId,
                                               @Valid @RequestBody ReleaseFumigationRequest request) {
        ReleaseView view = fumigationService.release(orderId, request);
        return ResponseEntity.status(view.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(view);
    }

    /** 查询熏蒸单全部气体读数。 */
    @GetMapping("/fumigation-orders/{orderId}/gas-readings")
    public List<GasReadingView> readings(@PathVariable long orderId) {
        return fumigationService.readings(orderId);
    }

    /** 查询解除双复核记录。 */
    @GetMapping("/fumigation-orders/{orderId}/release")
    public ReleaseView getRelease(@PathVariable long orderId) {
        return fumigationService.getRelease(orderId);
    }

    /** 登记测点名单（散气前，2 至 10 个不同测点，冻结为名单版本 1）。 */
    @PostMapping("/fumigation-orders/{orderId}/roster")
    public ResponseEntity<RosterView> registerRoster(@PathVariable long orderId,
                                                     @Valid @RequestBody RegisterRosterRequest request) {
        RosterView view = fumigationService.registerRoster(orderId, request);
        return ResponseEntity.status(view.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(view);
    }

    /** 增测点（只增不删，生成新名单版本，待解除确认失效）。 */
    @PostMapping("/fumigation-orders/{orderId}/roster/points")
    public ResponseEntity<RosterView> addRosterPoints(@PathVariable long orderId,
                                                      @Valid @RequestBody AddRosterPointsRequest request) {
        RosterView view = fumigationService.addRosterPoints(orderId, request);
        return ResponseEntity.status(view.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(view);
    }

    /** 查询当前测点名单。 */
    @GetMapping("/fumigation-orders/{orderId}/roster")
    public RosterView getRoster(@PathVariable long orderId) {
        return fumigationService.getRoster(orderId);
    }

    /** 多测点采样帧（全名单同一时刻，缺点/重复点/未知点/非递增时刻全帧拒绝）。 */
    @PostMapping("/fumigation-orders/{orderId}/gas-frames")
    public ResponseEntity<GasFrameView> gasFrame(@PathVariable long orderId,
                                                 @Valid @RequestBody GasFrameRequest request) {
        GasFrameView view = fumigationService.ingestGasFrame(orderId, request);
        return ResponseEntity.status(view.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(view);
    }

    /** 查询全部采样帧（含各点读数与每帧最差值）。 */
    @GetMapping("/fumigation-orders/{orderId}/gas-frames")
    public List<GasFrameView> gasFrames(@PathVariable long orderId) {
        return fumigationService.gasFrames(orderId);
    }

    /** 复算当前名单版本的有效连续安全段。 */
    @GetMapping("/fumigation-orders/{orderId}/gas-frames/safety")
    public GasSafetyView gasSafety(@PathVariable long orderId) {
        return fumigationService.gasSafety(orderId);
    }
}
