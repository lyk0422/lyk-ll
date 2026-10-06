package com.example.starter.web;

import com.example.starter.service.DefectService;
import com.example.starter.web.dto.ApiDtos.InspectionResponse;
import com.example.starter.web.dto.ApiDtos.RegisterInspectionRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 巡检登记入口。
 */
@RestController
@RequestMapping("/api/inspections")
public class InspectionController {

    private final DefectService defectService;

    public InspectionController(DefectService defectService) {
        this.defectService = defectService;
    }

    @PostMapping
    public InspectionResponse register(@Valid @RequestBody RegisterInspectionRequest req) {
        return defectService.registerInspection(req);
    }
}
