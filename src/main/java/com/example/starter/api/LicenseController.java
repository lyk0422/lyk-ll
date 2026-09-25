package com.example.starter.api;

import com.example.starter.api.dto.LicenseResponse;
import com.example.starter.api.dto.LicenseUpsertRequest;
import com.example.starter.license.LicenseService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 合成危废经营许可库维护入口（仅本地合成数据）。
 */
@RestController
@RequestMapping("/api/licenses")
public class LicenseController {

    private final LicenseService licenseService;

    public LicenseController(LicenseService licenseService) {
        this.licenseService = licenseService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LicenseResponse upsert(@Valid @RequestBody LicenseUpsertRequest request) {
        return licenseService.upsert(request);
    }
}
