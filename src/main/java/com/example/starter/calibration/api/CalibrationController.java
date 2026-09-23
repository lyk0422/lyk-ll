package com.example.starter.calibration.api;

import com.example.starter.calibration.service.CalibrationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 标定参数 REST 入口。
 */
@RestController
@RequestMapping("/api/calibrations")
public class CalibrationController {

    private final CalibrationService service;

    public CalibrationController(CalibrationService service) {
        this.service = service;
    }

    /** 创建标定版本（内容/范围校验失败返回 422，失败版本不可审批）。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CalibrationResponse create(@RequestBody CreateCalibrationRequest request) {
        return service.create(request);
    }

    /** 审批：草稿 → 已批准。 */
    @PostMapping("/{id}/approve")
    public CalibrationResponse approve(@PathVariable long id, @RequestBody ApproveRequest request) {
        return service.approve(id, request);
    }

    /** 全量生效：已批准 → 已生效（成为基线）。 */
    @PostMapping("/{id}/activate")
    public CalibrationResponse activate(@PathVariable long id, @RequestBody OperatorRequest request) {
        return service.activate(id, request);
    }

    /** 查询版本状态流转审计。 */
    @GetMapping("/{id}/transitions")
    public List<TransitionResponse> transitions(@PathVariable long id) {
        return service.getTransitions(id);
    }

    /** 发起灰度发布（显式车辆集合，批量原子切换）。 */
    @PostMapping("/canaries")
    @ResponseStatus(HttpStatus.CREATED)
    public CanaryReleaseResponse startCanary(@RequestBody CanaryReleaseRequest request) {
        return service.startCanary(request);
    }

    /** 灰度转正。 */
    @PostMapping("/canaries/{releaseId}/promote")
    public CanaryReleaseResponse promote(@PathVariable String releaseId, @RequestBody OperatorRequest request) {
        return service.promoteCanary(releaseId, request);
    }

    /** 灰度回滚：只影响该次灰度车辆，恢复各自发布前版本。 */
    @PostMapping("/canaries/{releaseId}/rollback")
    public CanaryReleaseResponse rollback(@PathVariable String releaseId, @RequestBody OperatorRequest request) {
        return service.rollbackCanary(releaseId, request);
    }

    /** 解析参数：按车辆/传感器/地图版本/时间匹配，返回版本与 resolutionId。 */
    @PostMapping("/resolve")
    public ResolutionResponse resolve(@RequestBody ResolveRequest request) {
        return service.resolve(request);
    }

    /** 按 resolutionId 查询当时使用的参数版本（历史引用不可变）。 */
    @GetMapping("/resolutions/{resolutionId}")
    public ResolutionHistoryResponse history(@PathVariable String resolutionId) {
        return service.getHistory(resolutionId);
    }

    /** 便捷 HEAD 探针，返回 200。 */
    @GetMapping("/ping")
    public ResponseEntity<String> ping() {
        return ResponseEntity.ok("ok");
    }
}
