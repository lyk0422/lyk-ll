package com.example.starter.recheck;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 复检申诉接口。
 */
@RestController
@RequestMapping("/api/rechecks")
public class RecheckController {

    private final RecheckService service;

    public RecheckController(RecheckService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<RecheckResponse> apply(@Valid @RequestBody RecheckApplyRequest req) {
        RecheckResponse resp = service.apply(req);
        return ResponseEntity.status(resp.idempotentReplay() ? HttpStatus.OK : HttpStatus.CREATED).body(resp);
    }
}
