package com.example.starter.rolling.api;

import com.example.starter.rolling.api.dto.PhysicalRollRegisterRequest;
import com.example.starter.rolling.api.dto.PhysicalRollView;
import com.example.starter.rolling.api.dto.RollLineageView;
import com.example.starter.rolling.api.dto.ReworkApplicationRequest;
import com.example.starter.rolling.api.dto.ReworkApplicationView;
import com.example.starter.rolling.api.dto.ReworkInspectionRequest;
import com.example.starter.rolling.service.ReworkService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 到期轧辊返修放行 HTTP 接口：合成物理辊登记、返修申请、双检验员放行、代次链查询。
 */
@RestController
@RequestMapping("/api/rolling")
public class ReworkController {

    private final ReworkService service;

    public ReworkController(ReworkService service) {
        this.service = service;
    }

    @PostMapping("/physical-rolls")
    public PhysicalRollView register(@Valid @RequestBody PhysicalRollRegisterRequest request) {
        return service.registerPhysicalRoll(request);
    }

    @GetMapping("/physical-rolls/{physicalNo}")
    public RollLineageView lineage(@PathVariable String physicalNo) {
        return service.lineage(physicalNo);
    }

    @PostMapping("/rework-applications")
    public ReworkApplicationView apply(@Valid @RequestBody ReworkApplicationRequest request) {
        return service.apply(request);
    }

    @GetMapping("/rework-applications/{id}")
    public ReworkApplicationView getApplication(@PathVariable long id) {
        return service.getApplication(id);
    }

    @PostMapping("/rework-applications/{id}/inspections")
    public ReworkApplicationView inspect(@PathVariable long id,
                                         @Valid @RequestBody ReworkInspectionRequest request) {
        return service.inspect(id, request);
    }
}
