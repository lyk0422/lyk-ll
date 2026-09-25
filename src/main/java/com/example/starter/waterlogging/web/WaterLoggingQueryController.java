package com.example.starter.waterlogging.web;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.waterlogging.api.PlanView;
import com.example.starter.waterlogging.api.PumpCommandResult;
import com.example.starter.waterlogging.api.ReadingResult;
import com.example.starter.waterlogging.po.LossContactOrder;
import com.example.starter.waterlogging.service.WaterLoggingQueryService;

/**
 * 只读复算接口：预降单状态、水位曲线、启停序列、失联派单台账均可复算。
 */
@RestController
@RequestMapping("/api")
public class WaterLoggingQueryController {

    public WaterLoggingQueryController(WaterLoggingQueryService queryService) {
        this.queryService = queryService;
    }

    private final WaterLoggingQueryService queryService;

    /** 查询预降单及分区内各泵站当前状态。 */
    @GetMapping("/plans/{requestId}")
    public PlanView getPlan(@PathVariable String requestId) {
        return queryService.getPlan(requestId);
    }

    /** 复算预降单水位曲线。 */
    @GetMapping("/plans/{requestId}/curve")
    public List<ReadingResult> curve(@PathVariable String requestId) {
        return queryService.getCurve(requestId);
    }

    /** 复算预降单泵站启停序列。 */
    @GetMapping("/plans/{requestId}/commands")
    public List<PumpCommandResult> commands(@PathVariable String requestId) {
        return queryService.getCommands(requestId);
    }

    /** 复算某站点失联派单台账。 */
    @GetMapping("/stations/{stationCode}/loss-orders")
    public List<LossContactOrder> lossOrders(@PathVariable String stationCode) {
        return queryService.getLossOrders(stationCode);
    }
}
