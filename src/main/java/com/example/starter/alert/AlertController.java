package com.example.starter.alert;

import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 产地预警台账接口。
 */
@RestController
@RequestMapping("/api/origin-alerts")
public class AlertController {

    private final AlertService alertService;

    public AlertController(AlertService alertService) {
        this.alertService = alertService;
    }

    /**
     * 预警台账列表。
     */
    @GetMapping
    public List<OriginAlert> ledger() {
        return alertService.ledger();
    }

    /**
     * 复算指定产地品种某日（UTC）的疑似累计与预警口径，与台账比对。
     */
    @GetMapping("/recompute")
    public AlertRecomputeResponse recompute(
            @RequestParam String origin,
            @RequestParam String variety,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return alertService.recompute(origin, variety, date);
    }
}
