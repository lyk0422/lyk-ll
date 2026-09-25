package com.example.starter.api;

import com.example.starter.api.dto.SetupDtos;
import com.example.starter.service.SetupService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 合成基础数据维护入口（作物生育期窗口、分区）。
 */
@RestController
@RequestMapping("/api/setup")
public class SetupController {

    private final SetupService setupService;

    public SetupController(SetupService setupService) {
        this.setupService = setupService;
    }

    @PostMapping("/crops")
    @ResponseStatus(HttpStatus.CREATED)
    public void upsertCrop(@Valid @RequestBody SetupDtos.CropSetupRequest request) {
        setupService.upsertCrop(request);
    }

    @PostMapping("/zones")
    @ResponseStatus(HttpStatus.CREATED)
    public SetupDtos.ZoneResponse createZone(@Valid @RequestBody SetupDtos.ZoneSetupRequest request) {
        return setupService.createZone(request);
    }

    @GetMapping("/zones")
    public List<SetupDtos.ZoneResponse> listZones() {
        return setupService.listZones();
    }
}
