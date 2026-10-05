package com.example.starter.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.api.dto.FeedRequest;
import com.example.starter.api.dto.FeedResponse;
import com.example.starter.api.dto.StageConfirmRequest;
import com.example.starter.api.dto.StageConfirmResponse;
import com.example.starter.api.dto.WarmUpTicketCreateRequest;
import com.example.starter.api.dto.WarmUpTicketResponse;
import com.example.starter.service.WarmUpService;

import jakarta.validation.Valid;

/**
 * 窑冷修后升温操作票 API：按合成曲线分阶段升温、双人复核、曲线走完方可投料。
 */
@RestController
@RequestMapping("/api/warmup")
public class WarmUpController {

    private final WarmUpService warmUpService;

    public WarmUpController(WarmUpService warmUpService) {
        this.warmUpService = warmUpService;
    }

    /** 开具升温操作票（同窑同时刻仅一张执行中/保温中票）。 */
    @PostMapping("/tickets")
    public WarmUpTicketResponse create(@Valid @RequestBody WarmUpTicketCreateRequest request) {
        return warmUpService.createTicket(request);
    }

    /** 查询升温票状态。 */
    @GetMapping("/tickets/{ticketNo}")
    public WarmUpTicketResponse status(@PathVariable String ticketNo) {
        return warmUpService.status(ticketNo);
    }

    /** 阶段确认（双人复核，单人确认不生效）。 */
    @PostMapping("/tickets/{ticketNo}/stages/confirm")
    public StageConfirmResponse confirm(@PathVariable String ticketNo,
                                        @Valid @RequestBody StageConfirmRequest request) {
        return warmUpService.confirmStage(ticketNo, request);
    }

    /** 恢复投料（曲线未走完禁止投料，同事务校验）。 */
    @PostMapping("/feed")
    public FeedResponse feed(@Valid @RequestBody FeedRequest request) {
        return warmUpService.feed(request);
    }

    /** 手动触发保温超时告警扫描（定时任务亦会自动执行）。 */
    @PostMapping("/admin/hold-timeout-scan")
    public int holdTimeoutScan() {
        return warmUpService.scanHoldTimeouts();
    }
}
