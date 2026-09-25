package com.example.starter.legality.api;

import com.example.starter.legality.api.dto.CheckRequest;
import com.example.starter.legality.api.dto.CheckResponse;
import com.example.starter.legality.api.dto.DelayRequest;
import com.example.starter.legality.api.dto.DelayResponse;
import com.example.starter.legality.api.dto.SealResponse;
import com.example.starter.legality.service.LegalityService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 机组值勤合规校验 HTTP 入口。
 */
@RestController
@RequestMapping("/api/legality")
public class LegalityController {

    private final LegalityService legalityService;

    public LegalityController(LegalityService legalityService) {
        this.legalityService = legalityService;
    }

    @PostMapping("/checks")
    public ResponseEntity<CheckResponse> check(@Valid @RequestBody CheckRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(legalityService.check(request));
    }

    @PostMapping("/delays")
    public ResponseEntity<DelayResponse> reportDelay(@Valid @RequestBody DelayRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(legalityService.reportDelay(request));
    }

    @PostMapping("/batches/{batchId}/seal")
    public SealResponse seal(@PathVariable String batchId) {
        return legalityService.seal(batchId);
    }
}
