package com.example.starter.calibration.web;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.calibration.domain.CalibrationVersion;
import com.example.starter.calibration.domain.CanaryVehicle;
import com.example.starter.calibration.domain.ResolutionRecord;
import com.example.starter.calibration.service.CalibrationService;
import com.example.starter.calibration.web.dto.CanaryReleaseRequest;
import com.example.starter.calibration.web.dto.CanaryReleaseResponse;
import com.example.starter.calibration.web.dto.CreateVersionRequest;
import com.example.starter.calibration.web.dto.ResolveRequest;
import com.example.starter.calibration.web.dto.ResolveResponse;
import com.example.starter.calibration.web.dto.StateTransitionRequest;
import com.example.starter.calibration.web.dto.VersionResponse;

/**
 * 标定参数仓库与灰度发布接口。
 */
@RestController
@RequestMapping("/api/calibrations")
public class CalibrationController {

    private final CalibrationService service;

    public CalibrationController(CalibrationService service) {
        this.service = service;
    }

    /** 创建标定草稿；内容校验失败返回 422。 */
    @PostMapping("/versions")
    public ResponseEntity<VersionResponse> create(@Valid @RequestBody CreateVersionRequest request) {
        CalibrationVersion created = service.createVersion(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(VersionResponse.from(created));
    }

    /** 查询版本详情。 */
    @GetMapping("/{key}/versions/{version}")
    public VersionResponse get(@PathVariable String key, @PathVariable String version) {
        return VersionResponse.from(service.getVersion(key, version));
    }

    /** 审批草稿：校验失败 422，范围冲突 409。 */
    @PostMapping("/{key}/versions/{version}/approve")
    public VersionResponse approve(@PathVariable String key, @PathVariable String version,
            @Valid @RequestBody StateTransitionRequest request) {
        return VersionResponse.from(service.approve(key, version, request.operator()));
    }

    /** 全量生效。 */
    @PostMapping("/{key}/versions/{version}/activate")
    public VersionResponse activate(@PathVariable String key, @PathVariable String version,
            @Valid @RequestBody StateTransitionRequest request) {
        return VersionResponse.from(service.activate(key, version, request.operator()));
    }

    /** 灰度发布：显式车辆集合，整批原子切换。 */
    @PostMapping("/canary-releases")
    public ResponseEntity<CanaryReleaseResponse> releaseCanary(
            @Valid @RequestBody CanaryReleaseRequest request) {
        CalibrationService.CanaryReleaseContext context = service.canaryRelease(request);
        List<String> vehicles = context.vehicles().stream().map(CanaryVehicle::getVehicleId).toList();
        List<Long> previous = context.vehicles().stream()
                .map(CanaryVehicle::getPreviousVersionId).toList();
        return ResponseEntity.status(HttpStatus.CREATED).body(CanaryReleaseResponse.of(
                context.release(), context.newVersion().getVersion(), vehicles, previous));
    }

    /** 查询灰度发布单（含每辆车发布前版本）。 */
    @GetMapping("/canary-releases/{releaseId}")
    public CanaryReleaseResponse getRelease(@PathVariable long releaseId) {
        var release = service.getRelease(releaseId);
        List<CanaryVehicle> vehicles = service.getReleaseVehicles(releaseId);
        CalibrationVersion newVersion = service.getVersionById(release.getNewVersionId());
        List<String> vehicleIds = vehicles.stream().map(CanaryVehicle::getVehicleId).toList();
        List<Long> previous = vehicles.stream().map(CanaryVehicle::getPreviousVersionId).toList();
        return CanaryReleaseResponse.of(release, newVersion.getVersion(), vehicleIds, previous);
    }

    /** 回滚灰度：仅影响该发布单车辆。 */
    @PostMapping("/canary-releases/{releaseId}/rollback")
    public CanaryReleaseResponse rollback(@PathVariable long releaseId,
            @Valid @RequestBody StateTransitionRequest request) {
        service.rollback(releaseId, request.operator(), request.reason());
        return getRelease(releaseId);
    }

    /** 按车辆/传感器/地图版本/时间解析参数，返回 resolutionId。 */
    @PostMapping("/resolve")
    public ResolveResponse resolve(@Valid @RequestBody ResolveRequest request) {
        ResolutionRecord record = service.resolve(request);
        return ResolveResponse.from(record);
    }

    /** 用 resolutionId 查询融合当时使用的参数版本快照。 */
    @GetMapping("/resolutions/{resolutionId}")
    public ResolveResponse getResolution(@PathVariable String resolutionId) {
        return ResolveResponse.from(service.getResolution(resolutionId));
    }
}
