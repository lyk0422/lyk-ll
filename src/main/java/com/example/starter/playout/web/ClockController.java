package com.example.starter.playout.web;

import com.example.starter.playout.clock.SyntheticClock;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 合成时钟管理接口（仅本地演示与测试；不连接真实广电设备）。
 * 省略请求时间的接口（tick time 省略、live/end 的 end 省略）取该时钟当前时刻。
 */
@RestController
public class ClockController {

    private final SyntheticClock clock;

    public ClockController(SyntheticClock clock) {
        this.clock = clock;
    }

    @GetMapping("/api/clock")
    public ClockView get() {
        return new ClockView(clock.instant());
    }

    @PutMapping("/api/clock")
    public ClockView set(@Valid @RequestBody ClockSetRequest request) {
        clock.setInstant(request.time());
        return new ClockView(clock.instant());
    }
}
