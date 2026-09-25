package com.example.starter.penalty;

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
 * 处罚决定接口。
 */
@RestController
@RequestMapping("/api/penalties")
public class PenaltyController {

    private final PenaltyService service;

    public PenaltyController(PenaltyService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<PenaltyResponse> create(@Valid @RequestBody PenaltyRequest req) {
        PenaltyResponse resp = service.create(req);
        return ResponseEntity.status(resp.idempotentReplay() ? HttpStatus.OK : HttpStatus.CREATED).body(resp);
    }

    @GetMapping("/{id}")
    public PenaltyResponse get(@PathVariable long id) {
        return service.get(id);
    }
}
