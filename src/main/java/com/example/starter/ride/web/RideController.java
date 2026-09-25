package com.example.starter.ride.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.ride.ApiResult;
import com.example.starter.ride.dto.ArchiveRequest;
import com.example.starter.ride.dto.DailyCheckRequest;
import com.example.starter.ride.dto.OpenRequest;
import com.example.starter.ride.dto.ReadingRequest;
import com.example.starter.ride.dto.RecheckRequest;
import com.example.starter.ride.dto.SafetyFaultRequest;
import com.example.starter.ride.dto.TrialRunRequest;
import com.example.starter.ride.service.RideOperationService;

/**
 * 游乐设施日检与载客联锁 API。
 */
@RestController
@RequestMapping("/api/rides")
public class RideController {

    private final RideOperationService service;

    public RideController(RideOperationService service) {
        this.service = service;
    }

    @PostMapping("/daily-check")
    public ResponseEntity<ApiResult> dailyCheck(@RequestBody DailyCheckRequest request) {
        return toResponse(service.dailyCheck(request));
    }

    @PostMapping("/trial-run")
    public ResponseEntity<ApiResult> trialRun(@RequestBody TrialRunRequest request) {
        return toResponse(service.trialRun(request));
    }

    @PostMapping("/open")
    public ResponseEntity<ApiResult> open(@RequestBody OpenRequest request) {
        return toResponse(service.open(request));
    }

    @PostMapping("/safety-fault")
    public ResponseEntity<ApiResult> safetyFault(@RequestBody SafetyFaultRequest request) {
        return toResponse(service.safetyFault(request));
    }

    @PostMapping("/readings")
    public ResponseEntity<ApiResult> reading(@RequestBody ReadingRequest request) {
        return toResponse(service.reading(request));
    }

    @PostMapping("/recheck")
    public ResponseEntity<ApiResult> recheck(@RequestBody RecheckRequest request) {
        return toResponse(service.recheck(request));
    }

    @PostMapping("/archive")
    public ResponseEntity<ApiResult> archive(@RequestBody ArchiveRequest request) {
        return toResponse(service.archive(request));
    }

    @GetMapping("/{rideId}")
    public ResponseEntity<ApiResult> status(@PathVariable String rideId) {
        return toResponse(service.status(rideId));
    }

    @GetMapping("/{rideId}/replay-chain")
    public ResponseEntity<ApiResult> replayChain(@PathVariable String rideId) {
        return toResponse(service.replayChain(rideId));
    }

    private ResponseEntity<ApiResult> toResponse(ApiResult result) {
        return ResponseEntity.status(result.httpStatus()).body(result);
    }
}
