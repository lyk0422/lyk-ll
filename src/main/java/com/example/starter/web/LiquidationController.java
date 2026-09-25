package com.example.starter.web;

import com.example.starter.dto.ApiDtos;
import com.example.starter.service.LiquidationService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * 强平接口：逾期扫描生成强平指令、执行指令、查询指令状态。
 */
@RestController
@RequestMapping("/api/liquidations")
public class LiquidationController {

    private final LiquidationService liquidationService;

    public LiquidationController(LiquidationService liquidationService) {
        this.liquidationService = liquidationService;
    }

    /**
     * 逾期扫描：为超过补足截止时间仍未结清的追保通知生成强平指令。
     *
     * @param asOf 运维用判定时刻（ISO 本地时间，UTC+8），缺省为当前时间
     */
    @PostMapping("/process-due")
    public ApiDtos.ProcessDueResponse processDue(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime asOf) {
        return liquidationService.processDue(asOf);
    }

    @PostMapping("/{orderId}/execute")
    public ApiDtos.LiquidationView execute(@PathVariable long orderId) {
        return liquidationService.execute(orderId);
    }

    @GetMapping("/{orderId}")
    public ApiDtos.LiquidationView view(@PathVariable long orderId) {
        return liquidationService.view(orderId);
    }
}
