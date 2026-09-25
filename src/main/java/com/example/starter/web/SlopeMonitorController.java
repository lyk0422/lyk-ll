package com.example.starter.web;

import com.example.starter.service.SlopeMonitorService;
import com.example.starter.web.dto.AlarmEvaluateRequest;
import com.example.starter.web.dto.ArchiveRequest;
import com.example.starter.web.dto.ArrivalReportRequest;
import com.example.starter.web.dto.CreateZoneRequest;
import com.example.starter.web.dto.EvacConfirmRequest;
import com.example.starter.web.dto.PersonLocationRequest;
import com.example.starter.web.dto.ReadingBatchRequest;
import com.example.starter.web.dto.ResumeApplyRequest;
import com.example.starter.web.dto.ResumeDecisionRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * 边坡雷达监测预警 REST 入口。
 */
@RestController
@RequestMapping("/api/slope")
public class SlopeMonitorController {

    private final SlopeMonitorService service;

    public SlopeMonitorController(SlopeMonitorService service) {
        this.service = service;
    }

    @PostMapping("/zones")
    public ResponseEntity<Object> createZone(@Valid @RequestBody CreateZoneRequest request) {
        return render(service.createZone(request));
    }

    @PostMapping("/readings")
    public ResponseEntity<Object> reportReadings(@Valid @RequestBody ReadingBatchRequest request) {
        return render(service.reportReadings(request));
    }

    @PostMapping("/persons")
    public ResponseEntity<Object> reportPerson(@Valid @RequestBody PersonLocationRequest request) {
        return render(service.reportPersonLocation(request));
    }

    @GetMapping("/zones/{zoneCode}/rate-curve")
    public ResponseEntity<Object> rateCurve(@PathVariable String zoneCode,
                                            @RequestParam LocalDateTime from,
                                            @RequestParam LocalDateTime to) {
        return render(service.rateCurve(zoneCode, from, to));
    }

    @PostMapping("/alarms/evaluate")
    public ResponseEntity<Object> evaluateAlarm(@Valid @RequestBody AlarmEvaluateRequest request) {
        return render(service.evaluateAlarm(request));
    }

    @PostMapping("/evacuations/arrivals")
    public ResponseEntity<Object> reportArrival(@Valid @RequestBody ArrivalReportRequest request) {
        return render(service.reportArrival(request));
    }

    @PostMapping("/evacuations/confirm")
    public ResponseEntity<Object> confirmEvacuation(@Valid @RequestBody EvacConfirmRequest request) {
        return render(service.confirmEvacuation(request));
    }

    @PostMapping("/resumes/apply")
    public ResponseEntity<Object> applyResume(@Valid @RequestBody ResumeApplyRequest request) {
        return render(service.applyResume(request));
    }

    @PostMapping("/resumes/decision")
    public ResponseEntity<Object> decideResume(@Valid @RequestBody ResumeDecisionRequest request) {
        return render(service.decideResume(request));
    }

    @PostMapping("/chains/archive")
    public ResponseEntity<Object> archive(@Valid @RequestBody ArchiveRequest request) {
        return render(service.archive(request));
    }

    @GetMapping("/chains/{chainNo}")
    public ResponseEntity<Object> getChain(@PathVariable String chainNo) {
        return render(service.getChain(chainNo));
    }

    private ResponseEntity<Object> render(ServiceResult result) {
        return ResponseEntity.status(result.httpStatus()).body(result.body());
    }
}
