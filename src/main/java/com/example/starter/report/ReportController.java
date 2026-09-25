package com.example.starter.report;

import com.example.starter.common.ApiException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * 执行回报与漏报维护 REST 入口。
 */
@RestController
@RequestMapping("/api")
public class ReportController {

    private final ReportService service;

    public ReportController(ReportService service) {
        this.service = service;
    }

    /**
     * 提交执行回报（按窗口＋站去重，状态不可改；已漏报则只记迟到事件）。
     */
    @PostMapping("/reports")
    public ReportDtos.ReportResult report(@RequestBody ReportDtos.ReportRequest request) {
        return service.report(request);
    }

    /**
     * 漏报标记：结束时刻早于 now 且未回报的已编排窗口标记为漏报。now 缺省取当前 UTC 时刻。
     */
    @PostMapping("/maintenance/mark-missed")
    public ReportDtos.MarkMissedResult markMissed(@RequestBody(required = false)
                                                  ReportDtos.MarkMissedRequest request) {
        Instant now = Instant.now();
        if (request != null && request.now() != null && !request.now().isBlank()) {
            try {
                now = Instant.parse(request.now());
            } catch (DateTimeParseException e) {
                throw ApiException.badRequest("INVALID_ARGUMENT",
                        "now 格式应为 ISO-8601（如 2026-09-28T00:00:00Z）: " + request.now());
            }
        }
        return service.markMissed(now);
    }
}
