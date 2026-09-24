package com.example.starter.carbon;

import java.util.Map;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.carbon.dto.ComplianceView;
import com.example.starter.carbon.dto.CreateEnterpriseRequest;
import com.example.starter.carbon.dto.IssueBatchRequest;
import com.example.starter.carbon.dto.SetEmissionsRequest;
import com.example.starter.carbon.dto.SubmitSurrenderRequest;
import com.example.starter.carbon.dto.SurrenderResponse;

/**
 * 碳市场履约服务 REST 入口。
 */
@RestController
@RequestMapping("/api")
public class CarbonController {

    private final CarbonService carbonService;
    private final SurrenderService surrenderService;

    public CarbonController(CarbonService carbonService, SurrenderService surrenderService) {
        this.carbonService = carbonService;
        this.surrenderService = surrenderService;
    }

    @PostMapping("/enterprises")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Long> createEnterprise(@Valid @RequestBody CreateEnterpriseRequest request) {
        return Map.of("enterpriseId", carbonService.createEnterprise(request));
    }

    @PostMapping("/allowances")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Long> issueAllowance(@Valid @RequestBody IssueBatchRequest request) {
        return Map.of("batchId", carbonService.issueAllowance(request));
    }

    @PostMapping("/offsets")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Long> issueOffset(@Valid @RequestBody IssueBatchRequest request) {
        return Map.of("batchId", carbonService.issueOffset(request));
    }

    @PutMapping("/compliance/{enterpriseId}/{year}/emissions")
    public Map<String, String> setEmissions(@PathVariable long enterpriseId,
            @PathVariable int year, @Valid @RequestBody SetEmissionsRequest request) {
        carbonService.setEmissions(enterpriseId, year, request);
        return Map.of("status", "OK");
    }

    @GetMapping("/compliance/{enterpriseId}/{year}")
    public ComplianceView getCompliance(@PathVariable long enterpriseId, @PathVariable int year) {
        return carbonService.getCompliance(enterpriseId, year);
    }

    @PostMapping("/surrenders")
    @ResponseStatus(HttpStatus.CREATED)
    public SurrenderResponse submit(@Valid @RequestBody SubmitSurrenderRequest request) {
        return surrenderService.submit(request);
    }

    @GetMapping("/surrenders/{requestId}")
    public SurrenderResponse getSurrender(@PathVariable String requestId) {
        return surrenderService.getSurrender(requestId);
    }
}
