package com.example.starter.web;

import java.time.LocalDate;

import com.example.starter.service.SettlementService;
import com.example.starter.web.dto.QualificationDtos.SettlementResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 日结接口：按 UTC 日界复算打卡、漏乘与改派数据。
 */
@RestController
@RequestMapping("/api/settlements")
public class SettlementController {

    private final SettlementService settlementService;

    public SettlementController(SettlementService settlementService) {
        this.settlementService = settlementService;
    }

    /**
     * 查询指定日期（UTC 日界）的日结结果。
     */
    @GetMapping("/daily")
    public SettlementResponse daily(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return settlementService.settle(date);
    }
}
