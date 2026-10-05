package com.example.starter.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.api.dto.FeedRequest;
import com.example.starter.api.dto.FeedResponse;
import com.example.starter.api.dto.WarmupConfirmRequest;
import com.example.starter.api.dto.WarmupTicketCreateRequest;
import com.example.starter.api.dto.WarmupTicketResponse;
import com.example.starter.service.WarmupService;

import jakarta.validation.Valid;

/**
 * 窑冷修后升温操作票与投料联动 API。
 */
@RestController
@RequestMapping("/api")
public class WarmupController {

    private final WarmupService warmupService;

    public WarmupController(WarmupService warmupService) {
        this.warmupService = warmupService;
    }

    /** 创建升温操作票：按合成曲线分阶段执行，同窑同时刻只允许一张执行中票。 */
    @PostMapping("/warmup/tickets")
    public WarmupTicketResponse create(@Valid @RequestBody WarmupTicketCreateRequest request) {
        return warmupService.create(request);
    }

    /** 查询升温票及阶段状态。 */
    @GetMapping("/warmup/tickets/{ticketNo}")
    public WarmupTicketResponse ticket(@PathVariable String ticketNo) {
        return warmupService.status(ticketNo);
    }

    /** 当前阶段确认：须两名不同操作人先后复核，单人确认不生效。 */
    @PostMapping("/warmup/tickets/{ticketNo}/confirm")
    public WarmupTicketResponse confirm(@PathVariable String ticketNo,
                                        @Valid @RequestBody WarmupConfirmRequest request) {
        return warmupService.confirm(ticketNo, request);
    }

    /** 投料：与升温曲线状态在同一事务内校验，曲线未走完禁止投料。 */
    @PostMapping("/kilns/feed")
    public FeedResponse feed(@Valid @RequestBody FeedRequest request) {
        return warmupService.feed(request);
    }

    /** 手动触发升温超时扫描（恰满最长用时转保温、保温恰满4小时告警；定时任务亦会自动执行）。 */
    @PostMapping("/admin/warmup-timeout-scan")
    public int warmupTimeoutScan() {
        return warmupService.scanWarmupTimeouts();
    }
}
