package com.example.starter.api;

import com.example.starter.service.ReplenishService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 日结核对入口：按 UTC 营业日复核当日全部加钞计划与快速通道结论留痕。
 */
@RestController
@RequestMapping("/api/reconciliation")
public class DailyReconciliationController {

    private final ReplenishService service;

    public DailyReconciliationController(ReplenishService service) {
        this.service = service;
    }

    /** 日结核对：当日计划台账 + 快速通道留痕。 */
    @GetMapping("/daily")
    public DailyReconciliationResponse daily(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.dailyReconciliation(date);
    }
}
