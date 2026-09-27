package com.example.starter.controller;

import java.util.List;

import com.example.starter.dto.FumigationDtos.ApproveRequest;
import com.example.starter.dto.FumigationDtos.CreateFumigationRequest;
import com.example.starter.dto.FumigationDtos.FinishRequest;
import com.example.starter.dto.FumigationDtos.FumigationView;
import com.example.starter.dto.FumigationDtos.ReleaseRequest;
import com.example.starter.dto.FumigationDtos.SampleRequest;
import com.example.starter.dto.FumigationDtos.SampleView;
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
 * 熏蒸审批与散气解除联锁接口：申请、双人批准、开始（原子锁仓）、结束转散气（绑风机）、
 * 散气采样、双复核人解除及查询。所有写操作 requestId 幂等，重放返回 200 与首次冻结结果。
 */
@RestController
@RequestMapping("/api")
public class FumigationController {

    private final FumigationService fumigationService;

    public FumigationController(FumigationService fumigationService) {
        this.fumigationService = fumigationService;
    }

    /** 申请熏蒸单：UTC 半开作业窗与同仓普通通风窗互斥；版本从 1 开始。 */
    @PostMapping("/silos/{siloId}/fumigations")
    public ResponseEntity<FumigationView> create(@PathVariable long siloId,
                                                 @Valid @RequestBody CreateFumigationRequest request) {
        FumigationView view = fumigationService.create(siloId, request);
        return ResponseEntity.status(view.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(view);
    }

    /** 批准：两名不同审批人共同批准；expectedVersion 乐观版本。 */
    @PostMapping("/fumigations/{fumigationId}/approvals")
    public ResponseEntity<FumigationView> approve(@PathVariable long fumigationId,
                                                  @Valid @RequestBody ApproveRequest request) {
        FumigationView view = fumigationService.approve(fumigationId, request);
        return ResponseEntity.status(view.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(view);
    }

    /** 开始熏蒸：原子锁仓并冻结方案版本，实际开始时再查无执行中通风。 */
    @PostMapping("/fumigations/{fumigationId}/start")
    public FumigationView start(@PathVariable long fumigationId,
                                @Valid @RequestBody StartRequest request) {
        return fumigationService.start(fumigationId, request);
    }

    /** 结束熏蒸转散气：绑定一台空闲风机（原子锁定，沿用唯一资源占用规则）。 */
    @PostMapping("/fumigations/{fumigationId}/finish")
    public FumigationView finish(@PathVariable long fumigationId,
                                 @Valid @RequestBody FinishRequest request) {
        return fumigationService.finish(fumigationId, request);
    }

    /** 散气采样：单号+序号唯一、时刻严格递增、浓度有限非负；解除后只记迟到。 */
    @PostMapping("/fumigations/{fumigationId}/readings")
    public ResponseEntity<SampleView> sample(@PathVariable long fumigationId,
                                             @Valid @RequestBody SampleRequest request) {
        SampleView view = fumigationService.sample(fumigationId, request);
        return ResponseEntity.status(view.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(view);
    }

    /** 解除：两名不同复核人最终确认，持锁重查最新读数与版本，原子释放仓锁与风机。 */
    @PostMapping("/fumigations/{fumigationId}/release")
    public FumigationView release(@PathVariable long fumigationId,
                                  @Valid @RequestBody ReleaseRequest request) {
        return fumigationService.release(fumigationId, request);
    }

    /** 查询熏蒸单（含审批人与版本）。 */
    @GetMapping("/fumigations/{fumigationId}")
    public FumigationView getFumigation(@PathVariable long fumigationId) {
        return fumigationService.getFumigation(fumigationId);
    }

    /** 查询熏蒸单全部散气读数（按序号排序，可复算连续三点）。 */
    @GetMapping("/fumigations/{fumigationId}/readings")
    public List<SampleView> readings(@PathVariable long fumigationId) {
        return fumigationService.readings(fumigationId);
    }

    /** 粮仓全部熏蒸单（含解除记录）。 */
    @GetMapping("/silos/{siloId}/fumigations")
    public List<FumigationView> fumigationsOfSilo(@PathVariable long siloId) {
        return fumigationService.fumigationsOfSilo(siloId);
    }
}
