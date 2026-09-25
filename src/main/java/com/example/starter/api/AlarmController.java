package com.example.starter.api;

import com.example.starter.api.dto.AlarmDtos;
import com.example.starter.service.AlarmService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 偏差告警查询与幂等确认。
 */
@RestController
@RequestMapping("/api/alarms")
public class AlarmController {

    private final AlarmService alarmService;

    public AlarmController(AlarmService alarmService) {
        this.alarmService = alarmService;
    }

    @PostMapping("/ack")
    public AlarmDtos.AlarmResponse acknowledge(@Valid @RequestBody AlarmDtos.AckRequest request) {
        return alarmService.acknowledge(request);
    }

    @GetMapping
    public List<AlarmDtos.AlarmResponse> listActive(@RequestParam(required = false) String zoneCode) {
        return alarmService.listActive(zoneCode);
    }
}
