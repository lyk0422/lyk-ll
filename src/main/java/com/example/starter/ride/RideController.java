package com.example.starter.ride;

import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.ride.Idempotency.ApiResult;
import com.example.starter.ride.dto.RideDtos.ArchiveRequest;
import com.example.starter.ride.dto.RideDtos.CreateRideRequest;
import com.example.starter.ride.dto.RideDtos.DailyCheckRequest;
import com.example.starter.ride.dto.RideDtos.OpenRequest;
import com.example.starter.ride.dto.RideDtos.ReadingRequest;
import com.example.starter.ride.dto.RideDtos.RecheckRequest;
import com.example.starter.ride.dto.RideDtos.ReopenRequest;
import com.example.starter.ride.dto.RideDtos.SafetyFaultRequest;
import com.example.starter.ride.dto.RideDtos.TrialRunRequest;

/**
 * 游乐设施日检与载客联锁 API。
 */
@RestController
@RequestMapping("/api/rides")
public class RideController {

    private final RideService service;

    public RideController(RideService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(
            @Valid @RequestBody CreateRideRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createRide(request));
    }

    @GetMapping("/{rideId}")
    public Map<String, Object> get(@PathVariable long rideId) {
        return service.getRide(rideId);
    }

    @PostMapping("/{rideId}/daily-check")
    public ResponseEntity<String> dailyCheck(@PathVariable long rideId,
            @Valid @RequestBody DailyCheckRequest request) {
        return render(service.dailyCheck(rideId, request));
    }

    @PostMapping("/{rideId}/trial-runs")
    public ResponseEntity<String> trialRun(@PathVariable long rideId,
            @Valid @RequestBody TrialRunRequest request) {
        return render(service.trialRun(rideId, request));
    }

    @PostMapping("/{rideId}/open")
    public ResponseEntity<String> open(@PathVariable long rideId,
            @Valid @RequestBody OpenRequest request) {
        return render(service.open(rideId, request));
    }

    @PostMapping("/{rideId}/safety-fault")
    public ResponseEntity<String> safetyFault(@PathVariable long rideId,
            @Valid @RequestBody SafetyFaultRequest request) {
        return render(service.safetyFault(rideId, request));
    }

    @PostMapping("/{rideId}/recheck")
    public ResponseEntity<String> recheck(@PathVariable long rideId,
            @Valid @RequestBody RecheckRequest request) {
        return render(service.recheck(rideId, request));
    }

    @PostMapping("/{rideId}/reopen")
    public ResponseEntity<String> reopen(@PathVariable long rideId,
            @Valid @RequestBody ReopenRequest request) {
        return render(service.reopen(rideId, request));
    }

    @PostMapping("/{rideId}/archive")
    public ResponseEntity<String> archive(@PathVariable long rideId,
            @Valid @RequestBody ArchiveRequest request) {
        return render(service.archive(rideId, request));
    }

    @PostMapping("/{rideId}/readings")
    public Map<String, Object> reading(@PathVariable long rideId,
            @Valid @RequestBody ReadingRequest request) {
        return service.reading(rideId, request);
    }

    @GetMapping("/{rideId}/events")
    public List<Map<String, Object>> events(@PathVariable long rideId) {
        return service.listEvents(rideId);
    }

    @GetMapping("/{rideId}/daily-checks")
    public List<Map<String, Object>> dailyChecks(@PathVariable long rideId) {
        return service.listDailyChecks(rideId);
    }

    @GetMapping("/{rideId}/trial-runs")
    public List<Map<String, Object>> trialRuns(@PathVariable long rideId) {
        return service.listTrialRuns(rideId);
    }

    @GetMapping("/{rideId}/readings")
    public List<Map<String, Object>> readings(@PathVariable long rideId) {
        return service.listReadings(rideId);
    }

    private ResponseEntity<String> render(ApiResult result) {
        return ResponseEntity.status(result.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(result.body());
    }
}
