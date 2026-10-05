package com.example.starter.api;

import com.example.starter.api.dto.AlarmHandlingDtos;
import com.example.starter.service.AlarmHandlingService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 告警处置：发起（临时管控窗口）、完结、查询。
 */
@RestController
@RequestMapping("/api/alarm-handlings")
public class AlarmHandlingController {

    private final AlarmHandlingService alarmHandlingService;

    public AlarmHandlingController(AlarmHandlingService alarmHandlingService) {
        this.alarmHandlingService = alarmHandlingService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AlarmHandlingDtos.HandlingResponse start(
            @Valid @RequestBody AlarmHandlingDtos.StartRequest request) {
        return alarmHandlingService.start(request);
    }

    @PostMapping("/{handlingCode}/complete")
    public AlarmHandlingDtos.HandlingResponse complete(
            @PathVariable String handlingCode,
            @Valid @RequestBody AlarmHandlingDtos.CompleteRequest request) {
        return alarmHandlingService.complete(handlingCode, request);
    }

    @GetMapping("/{handlingCode}")
    public AlarmHandlingDtos.HandlingResponse get(@PathVariable String handlingCode) {
        return alarmHandlingService.get(handlingCode);
    }
}
