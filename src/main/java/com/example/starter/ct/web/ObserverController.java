package com.example.starter.ct.web;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.ct.ObserverService;

/**
 * 观察者上报与分裂视图告警查询 API。
 */
@RestController
@RequestMapping("/api/ct")
@Validated
public class ObserverController {

    private final ObserverService observerService;

    public ObserverController(ObserverService observerService) {
        this.observerService = observerService;
    }

    /** 观察者上报请求：signature 为 Ed25519 签名的 Base64，timestamp 为 UTC 纪元毫秒。 */
    public record ObserverReportRequest(@NotBlank String observerId, @Positive long treeSize,
            @NotBlank String rootHash, long timestamp, @NotBlank String signature) {
    }

    /** 上报处理响应。 */
    public record ObserverReportResponse(boolean accepted, boolean alertCreated, Long alertId,
            long observerPosition) {
    }

    /** 告警条目。 */
    public record AlertDto(long id, String type, String observerId, long treeSize, String localRootHash,
            String reportedRootHash, long reportedAt, long createdAt) {
    }

    /** 告警列表响应。 */
    public record AlertListResponse(List<AlertDto> alerts) {
    }

    @PostMapping("/observers/reports")
    public ObserverReportResponse report(@RequestBody @Validated ObserverReportRequest request) {
        var result = observerService.report(request.observerId(), request.treeSize(), request.rootHash(),
                request.timestamp(), request.signature());
        return new ObserverReportResponse(result.accepted(), result.alertCreated(), result.alertId(),
                result.observerPosition());
    }

    @GetMapping("/alerts")
    public AlertListResponse alerts() {
        List<AlertDto> alerts = observerService.alerts().stream()
                .map(row -> new AlertDto(row.id(), row.alertType(), row.observerId(), row.treeSize(),
                        row.localRootHash(), row.reportedRootHash(), row.reportedAt(), row.createdAt()))
                .toList();
        return new AlertListResponse(alerts);
    }
}
