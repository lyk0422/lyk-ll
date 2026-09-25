package com.example.starter.web;

import com.example.starter.domain.BreachEvent;
import com.example.starter.domain.BrakeCommand;

import java.time.Instant;
import java.util.List;

/**
 * 位姿上报请求/响应。timestamp 为采集时刻（UTC，1 秒粒度）。
 */
public record PoseDtos() {

    public record Request(Instant timestamp, double hookX, double hookY) {
    }

    public record Response(boolean duplicate, List<BreachEvent> breachEvents,
                           List<BrakeCommand> brakeCommands) {
    }
}
