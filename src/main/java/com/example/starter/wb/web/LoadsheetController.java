package com.example.starter.wb.web;

import com.example.starter.wb.dto.AssemblyPlanResponse;
import com.example.starter.wb.dto.AssemblySubmitRequest;
import com.example.starter.wb.dto.CheckinRequest;
import com.example.starter.wb.dto.CloseRequest;
import com.example.starter.wb.dto.CreateManifestRequest;
import com.example.starter.wb.dto.LateCheckinRequest;
import com.example.starter.wb.dto.LmcRequest;
import com.example.starter.wb.dto.ManifestResponse;
import com.example.starter.wb.dto.ReviewRequest;
import com.example.starter.wb.dto.StageRequest;
import com.example.starter.wb.service.AssemblyService;
import com.example.starter.wb.service.WeightBalanceService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 航班载重平衡舱单与复核放行接口（实现阶段补齐委托）。
 */
@RestController
@RequestMapping("/api/flights/{flightId}/loadsheet")
public class LoadsheetController {

    private final WeightBalanceService service;
    private final AssemblyService assemblyService;

    public LoadsheetController(WeightBalanceService service, AssemblyService assemblyService) {
        this.service = service;
        this.assemblyService = assemblyService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ManifestResponse create(@PathVariable Long flightId,
                                   @Valid @RequestBody CreateManifestRequest request) {
        if (!flightId.equals(request.flightId())) {
            throw new com.example.starter.wb.exception.BadRequestException(
                    "路径航班 " + flightId + " 与请求体航班 " + request.flightId() + " 不一致");
        }
        return service.createManifest(request);
    }

    @PostMapping("/checkin")
    public ManifestResponse checkin(@PathVariable Long flightId,
                                    @Valid @RequestBody CheckinRequest request) {
        return service.checkin(flightId, request);
    }

    @PostMapping("/confirm-load")
    public ManifestResponse confirmLoad(@PathVariable Long flightId,
                                        @Valid @RequestBody StageRequest request) {
        return service.confirmLoad(flightId, request);
    }

    @PostMapping("/lmc")
    public ManifestResponse lmc(@PathVariable Long flightId,
                                @Valid @RequestBody LmcRequest request) {
        return service.applyLmc(flightId, request);
    }

    @PostMapping("/review")
    public ManifestResponse review(@PathVariable Long flightId,
                                   @Valid @RequestBody ReviewRequest request) {
        return service.review(flightId, request);
    }

    @PostMapping("/close")
    public ManifestResponse close(@PathVariable Long flightId,
                                  @Valid @RequestBody CloseRequest request) {
        return service.close(flightId, request);
    }

    @GetMapping
    public ManifestResponse active(@PathVariable Long flightId) {
        return service.getActive(flightId);
    }

    @GetMapping("/versions")
    public java.util.List<ManifestResponse> versions(@PathVariable Long flightId) {
        return service.listVersions(flightId);
    }

    @PostMapping("/late-checkin")
    public ManifestResponse lateCheckin(@PathVariable Long flightId,
                                        @Valid @RequestBody LateCheckinRequest request) {
        return service.recordLateCheckin(flightId, request);
    }

    @PostMapping("/assembly")
    @ResponseStatus(HttpStatus.CREATED)
    public AssemblyPlanResponse submitAssembly(@PathVariable Long flightId,
                                               @Valid @RequestBody AssemblySubmitRequest request) {
        return assemblyService.submit(flightId, request);
    }

    @GetMapping("/assembly")
    public AssemblyPlanResponse activeAssembly(@PathVariable Long flightId) {
        return assemblyService.getActive(flightId);
    }

    @GetMapping("/assembly/versions")
    public java.util.List<AssemblyPlanResponse> assemblyVersions(@PathVariable Long flightId) {
        return assemblyService.listVersions(flightId);
    }
}
