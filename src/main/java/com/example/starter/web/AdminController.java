package com.example.starter.web;

import java.time.LocalDate;
import java.util.List;

import com.example.starter.service.QualificationService;
import com.example.starter.service.SettlementService;
import com.example.starter.web.dto.ExpiryRunRequest;
import com.example.starter.web.dto.SettlementRow;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运维入口：到期巡检与日结复算。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final QualificationService qualificationService;
    private final SettlementService settlementService;

    public AdminController(QualificationService qualificationService, SettlementService settlementService) {
        this.qualificationService = qualificationService;
        this.settlementService = settlementService;
    }

    /**
     * 触发一次到期巡检：失效资格线路自动停运留痕，改派与通知同事务生成。
     */
    @PostMapping("/expiry/run")
    public ExpiryRunRequest.Result runExpiry(@RequestBody(required = false) ExpiryRunRequest request) {
        return qualificationService.runExpiry(request != null ? request.now() : null);
    }

    /**
     * 复算指定日期（UTC 日界）的日结数据。
     */
    @PostMapping("/settlements/{date}")
    public List<SettlementRow> settle(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return settlementService.settle(date);
    }
}
