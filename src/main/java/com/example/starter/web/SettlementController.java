package com.example.starter.web;

import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.dto.SettlementView;
import com.example.starter.repo.SettlementRepository;
import com.example.starter.service.SettlementService;

/**
 * 日结接口：输出各回路调光次数与能耗，结果由调光留痕复算。
 */
@RestController
@RequestMapping("/api/settlement")
public class SettlementController {

    private final SettlementService settlementService;

    public SettlementController(SettlementService settlementService) {
        this.settlementService = settlementService;
    }

    /** 执行某日(UTC)日结，复算并覆盖快照后返回，格式 yyyy-MM-dd。 */
    @PostMapping("/daily")
    public List<SettlementView> settleDaily(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return settlementService.settle(date);
    }

    /** 查询某日已生成的日结快照。 */
    @GetMapping("/daily")
    public List<SettlementRepository.SettlementRow> snapshot(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return settlementService.findSnapshot(date);
    }
}
