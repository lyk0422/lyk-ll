package com.example.starter.reinsurance.api;

import com.example.starter.reinsurance.service.ReferenceDataService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 合约与保单（合成主数据）接口。
 */
@RestController
@RequestMapping("/api/reinsurance")
public class ReferenceDataController {

    private final ReferenceDataService referenceDataService;

    public ReferenceDataController(ReferenceDataService referenceDataService) {
        this.referenceDataService = referenceDataService;
    }

    @PostMapping("/treaties")
    public ResponseEntity<TreatyResponse> createTreaty(@Valid @RequestBody CreateTreatyRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(referenceDataService.createTreaty(request));
    }

    @GetMapping("/treaties/{contractNo}")
    public TreatyResponse getTreaty(@PathVariable String contractNo) {
        return referenceDataService.getTreaty(contractNo);
    }

    @PostMapping("/policies")
    public ResponseEntity<PolicyResponse> createPolicy(@Valid @RequestBody CreatePolicyRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(referenceDataService.createPolicy(request));
    }
}
