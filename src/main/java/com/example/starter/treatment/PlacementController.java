package com.example.starter.treatment;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 治理投放接口。
 */
@RestController
@RequestMapping("/api")
public class PlacementController {

    private final PlacementService service;

    public PlacementController(PlacementService service) {
        this.service = service;
    }

    /**
     * 投放登记（第一登记人）：POST /api/cases/{caseId}/placements
     */
    @PostMapping("/cases/{caseId}/placements")
    public PlacementDtos.PlacementResponse register(@PathVariable long caseId,
            @Valid @RequestBody PlacementDtos.PlacementRequest request) {
        return service.register(caseId, request);
    }

    /**
     * 投放复核（第二登记人）：POST /api/placements/{placementId}/confirm
     */
    @PostMapping("/placements/{placementId}/confirm")
    public PlacementDtos.PlacementResponse confirm(@PathVariable long placementId,
            @Valid @RequestBody PlacementDtos.PlacementConfirmRequest request) {
        return service.confirm(placementId, request);
    }

    /**
     * 补投（一对一替代被召回投放，双人签认）：POST /api/placements/{placementId}/replacement
     */
    @PostMapping("/placements/{placementId}/replacement")
    public PlacementDtos.PlacementResponse replace(@PathVariable long placementId,
            @Valid @RequestBody PlacementDtos.ReplacementRequest request) {
        return service.replace(placementId, request);
    }

    /**
     * 投放详情（含批号、召回状态与补投对应关系）：GET /api/placements/{placementId}
     */
    @GetMapping("/placements/{placementId}")
    public PlacementDtos.PlacementDetailResponse detail(@PathVariable long placementId) {
        return service.detail(placementId);
    }
}
