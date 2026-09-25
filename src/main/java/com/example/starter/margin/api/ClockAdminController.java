package com.example.starter.margin.api;

import com.example.starter.margin.support.MarginClock;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * 演示/测试用业务时钟管理：数据仅在 JVM 内保留，拨动时钟以复现“次一交易日 15:00 截止/逾期”。
 * 生产行情接入后应替换为真实时钟，不应作为业务 API 对外依赖。
 */
@RestController
@RequestMapping("/api/margin/admin/clock")
public class ClockAdminController {

    private final MarginClock clock;

    public ClockAdminController(MarginClock clock) {
        this.clock = clock;
    }

    /** 固定业务时钟，body: {"iso":"2026-09-28T07:00:00Z"}。 */
    @PostMapping
    public Map<String, Object> set(@RequestBody Map<String, String> request) {
        Instant fixed = Instant.parse(request.get("iso"));
        clock.setFixed(fixed);
        return Map.of("now", clock.now().toString());
    }

    /** 恢复系统 UTC 时钟。 */
    @DeleteMapping
    public Map<String, Object> reset() {
        clock.reset();
        return Map.of("now", clock.now().toString());
    }
}
