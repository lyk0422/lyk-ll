package com.example.starter.roaming.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.roaming.service.RoamingService;
import com.example.starter.roaming.service.ServiceResult;
import com.example.starter.roaming.web.dto.AuthRequest;
import com.example.starter.roaming.web.dto.FreezeRequest;
import com.example.starter.roaming.web.dto.MeterRequest;
import com.example.starter.roaming.web.dto.SealRequest;
import com.example.starter.roaming.web.dto.StopRequest;
import com.example.starter.roaming.web.dto.WriteOffRequest;

import jakarta.validation.Valid;

/**
 * 充电漫游 HTTP 入口（骨架，逐文件填充）。
 */
@RestController
@RequestMapping("/api/roaming")
public class RoamingController {

    private final RoamingService service;

    public RoamingController(RoamingService service) {
        this.service = service;
    }

    @PostMapping("/authorize")
    public ResponseEntity<Object> authorize(@Valid @RequestBody AuthRequest request) {
        ServiceResult result = service.authorize(request);
        return ResponseEntity.status(result.status()).body(result.body());
    }

    @PostMapping("/sessions/stop")
    public ResponseEntity<Object> stop(@RequestBody StopRequest request) {
        ServiceResult result = service.stopSession(request);
        return ResponseEntity.status(result.status()).body(result.body());
    }

    @PostMapping("/meters")
    public ResponseEntity<Object> meters(@RequestBody MeterRequest request) {
        ServiceResult result = service.uploadMeter(request);
        return ResponseEntity.status(result.status()).body(result.body());
    }

    @PostMapping("/clearing/freeze")
    public ResponseEntity<Object> freeze(@RequestBody FreezeRequest request) {
        ServiceResult result = service.freezeBatch(request);
        return ResponseEntity.status(result.status()).body(result.body());
    }

    @PostMapping("/clearing/seal")
    public ResponseEntity<Object> seal(@RequestBody SealRequest request) {
        ServiceResult result = service.sealBatch(request);
        return ResponseEntity.status(result.status()).body(result.body());
    }

    @PostMapping("/discrepancies/write-off")
    public ResponseEntity<Object> writeOff(@RequestBody WriteOffRequest request) {
        ServiceResult result = service.writeOff(request);
        return ResponseEntity.status(result.status()).body(result.body());
    }
}
