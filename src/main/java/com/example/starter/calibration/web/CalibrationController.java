package com.example.starter.calibration.web;

import com.example.starter.calibration.api.CanaryReleaseRequest;
import com.example.starter.calibration.api.CreateParamRequest;
import com.example.starter.calibration.api.ParamView;
import com.example.starter.calibration.api.ReleaseView;
import com.example.starter.calibration.api.ResolutionView;
import com.example.starter.calibration.api.ResolveRequest;
import com.example.starter.calibration.api.ResolveResponse;
import com.example.starter.calibration.api.RollbackRequest;
import com.example.starter.calibration.domain.CanaryRelease;
import com.example.starter.calibration.service.CalibrationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 标定参数仓库与灰度发布 HTTP 接口。
 */
@RestController
@RequestMapping("/api/calibration")
public class CalibrationController {

    private final CalibrationService service;

    public CalibrationController(CalibrationService service) {
        this.service = service;
    }

    @PostMapping("/params")
    @ResponseStatus(HttpStatus.CREATED)
    public ParamView create(@Valid @RequestBody CreateParamRequest request) {
        return service.create(request);
    }

    @GetMapping("/params/{paramKey}")
    public List<ParamView> list(@PathVariable String paramKey) {
        return service.listVersions(paramKey);
    }

    @PostMapping("/params/{paramKey}/versions/{version}/approve")
    public ParamView approve(@PathVariable String paramKey,
                             @PathVariable String version,
                             @RequestBody(required = false) Map<String, String> body) {
        return service.approve(paramKey, version, operator(body));
    }

    @PostMapping("/params/{paramKey}/versions/{version}/activate")
    public ParamView activate(@PathVariable String paramKey,
                              @PathVariable String version,
                              @RequestBody(required = false) Map<String, String> body) {
        return service.activate(paramKey, version, operator(body));
    }

    @PostMapping("/releases")
    @ResponseStatus(HttpStatus.CREATED)
    public CanaryRelease release(@Valid @RequestBody CanaryReleaseRequest request) {
        return service.canaryRelease(request);
    }

    @GetMapping("/releases/{releaseId}")
    public ReleaseView getRelease(@PathVariable String releaseId) {
        return service.getRelease(releaseId);
    }

    @PostMapping("/releases/{releaseId}/rollback")
    public CanaryRelease rollback(@PathVariable String releaseId,
                                  @RequestBody(required = false) RollbackRequest body) {
        RollbackRequest req = body != null
                ? new RollbackRequest(releaseId, body.operator(), body.remark())
                : new RollbackRequest(releaseId, null, null);
        return service.rollback(req);
    }

    @PostMapping("/releases/{releaseId}/promote")
    public CanaryRelease promote(@PathVariable String releaseId,
                                 @RequestBody(required = false) Map<String, String> body) {
        return service.promote(releaseId, operator(body));
    }

    @PostMapping("/resolutions")
    public ResolveResponse resolve(@RequestBody ResolveRequest request) {
        return service.resolve(request);
    }

    @GetMapping("/resolve")
    public ResolveResponse resolveByQuery(@RequestParam String paramKey,
                                          @RequestParam String vehicleId,
                                          @RequestParam(required = false) String sensorId,
                                          @RequestParam(required = false) String mapVersion,
                                          @RequestParam(required = false) Instant at) {
        return service.resolve(new ResolveRequest(paramKey, vehicleId, sensorId, mapVersion, at));
    }

    @GetMapping("/resolutions/{resolutionId}")
    public ResolutionView getResolution(@PathVariable String resolutionId) {
        return service.getResolution(resolutionId);
    }

    private static String operator(Map<String, String> body) {
        return body == null ? null : body.get("operator");
    }
}
