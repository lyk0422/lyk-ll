package com.example.starter.weigh;

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
 * 过磅检测记录接口。
 */
@RestController
@RequestMapping("/api/weigh-records")
public class WeighController {

    private final WeighService service;

    public WeighController(WeighService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<WeighRecordResponse> create(@Valid @RequestBody WeighRecordRequest req) {
        WeighRecordResponse resp = service.create(req);
        HttpStatus status = (resp.deduplicated() || resp.idempotentReplay()) ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(resp);
    }

    @GetMapping("/{id}")
    public WeighRecordResponse get(@PathVariable long id) {
        return service.get(id);
    }
}
