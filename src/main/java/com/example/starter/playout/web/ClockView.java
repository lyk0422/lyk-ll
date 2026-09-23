package com.example.starter.playout.web;

import java.time.Instant;

/**
 * 合成时钟当前时刻视图。
 *
 * @param now 当前时刻(UTC)
 */
public record ClockView(Instant now) {
}
