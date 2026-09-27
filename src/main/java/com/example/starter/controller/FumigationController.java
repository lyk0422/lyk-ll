package com.example.starter.controller;

import java.util.List;

import com.example.starter.dto.FumigationDtos.ApproveRequest;
import com.example.starter.dto.FumigationDtos.CreateFumigationRequest;
import com.example.starter.dto.FumigationDtos.FinishFumigationRequest;
import com.example.starter.dto.FumigationDtos.FumigationView;
import com.example.starter.dto.FumigationDtos.GasReadingRequest;
import com.example.starter.dto.FumigationDtos.GasReadingResponse;
import com.example.starter.dto.FumigationDtos.GasReadingView;
import com.example.starter.dto.FumigationDtos.ReleaseConfirmRequest;
import com.example.starter.dto.FumigationDtos.StartFumigationRequest;
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
 * 熏蒸作业接口：申请、双审批、开始（锁仓冻结版本）、结束转散气、散气采样、双复核解除及查询。
 * 写操作 requestId 幂等，修改携带 expectedVersion；业务拒绝 422，异参冲突 409。
 */
@RestController
@RequestMapping("/api")
public class FumigationController {

    private final FumigationService fumigationService;

    public FumigationController(FumigationService fumigationService) {
        this.fumigationService = fumigationService;
    }

    /** 申请熏蒸：UTC 半开作业窗与通风窗互斥，requestId 幂等。 */
    @PostMapping("/silos/{siloId}/fumigations")
    public ResponseEntity<FumigationView> create(@PathVariable long siloId,
                                                 @Valid @RequestBody CreateFumigationRequest request) {
        FumigationView view = fumigationService.create(siloId, request);
        return ResponseEntity.status(view.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(view);
    }

    /** 查询熏蒸单。 */
    @GetMapping("/fumigations/{fumigationId}")
    public FumigationView getFumigation(@PathVariable long fumigationId) {
        return fumigationService.getFumigation(fumigationId);
    }

    /** 粮仓全部熏蒸单。 */
    @GetMapping("/silos/{siloId}/fumigations")
    public List<FumigationView> listBySilo(@PathVariable long siloId) {
        return fumigationService.listBySilo(siloId);
    }

    /** 审批：两名不同审批人批准后方可开始。 */
    @PostMapping("/fumigations/{fumigationId}/approvals")
    public FumigationView approve(@PathVariable long fumigationId,
                                  @Valid @RequestBody ApproveRequest request) {
        return fumigationService.approve(fumigationId, request);
    }

    /** 开始熏蒸：再查无执行中通风，原子锁仓并冻结方案版本。 */
    @PostMapping("/fumigations/{fumigationId}/start")
    public FumigationView start(@PathVariable long fumigationId,
                                @Valid @RequestBody StartFumigationRequest request) {
        return fumigationService.start(fumigationId, request);
    }

    /** 结束熏蒸转散气：原子绑定一台空闲风机。 */
    @PostMapping("/fumigations/{fumigationId}/finish")
    public FumigationView finish(@PathVariable long fumigationId,
                                 @Valid @RequestBody FinishFumigationRequest request) {
        return fumigationService.finish(fumigationId, request);
    }

    /** 散气采样：序号唯一、时刻严格递增、浓度有限非负；解除后只记迟到。 */
    @PostMapping("/fumigations/{fumigationId}/gas-readings")
    public ResponseEntity<GasReadingResponse> sample(@PathVariable long fumigationId,
                                                     @Valid @RequestBody GasReadingRequest request) {
        GasReadingResponse response = fumigationService.sample(fumigationId, request);
        return ResponseEntity.status(response.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(response);
    }

    /** 熏蒸单全部气体读数（按序号）。 */
    @GetMapping("/fumigations/{fumigationId}/gas-readings")
    public List<GasReadingView> gasReadings(@PathVariable long fumigationId) {
        return fumigationService.gasReadings(fumigationId);
    }

    /** 解除复核：两名不同复核人，最终确认重查最新读数与版本，原子释放仓锁与风机。 */
    @PostMapping("/fumigations/{fumigationId}/release-confirmations")
    public FumigationView confirmRelease(@PathVariable long fumigationId,
                                         @Valid @RequestBody ReleaseConfirmRequest request) {
        return fumigationService.confirmRelease(fumigationId, request);
    }
}
