package com.example.starter.web;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.service.AlarmService;
import com.example.starter.web.dto.ApiDtos.AlarmResponse;
import com.example.starter.web.dto.ApiDtos.MainsFailureAlarmRequest;
import com.example.starter.web.dto.ApiDtos.MainsRecoveryRequest;
import com.example.starter.web.dto.ApiDtos.RecoveryResponse;

/**
 * 市电告警接口：中断触发备电调度，恢复解除调度。
 */
@RestController
@RequestMapping("/api/v1/alarms")
public class AlarmController {

    private final AlarmService alarmService;

    public AlarmController(AlarmService alarmService) {
        this.alarmService = alarmService;
    }

    /**
     * 市电中断告警。
     */
    @PostMapping("/mains-failure")
    public AlarmResponse mainsFailure(@Valid @RequestBody MainsFailureAlarmRequest request) {
        return alarmService.mainsFailure(request);
    }

    /**
     * 市电恢复。
     */
    @PostMapping("/mains-recovery")
    public RecoveryResponse mainsRecovery(@Valid @RequestBody MainsRecoveryRequest request) {
        return alarmService.mainsRecovery(request);
    }
}
