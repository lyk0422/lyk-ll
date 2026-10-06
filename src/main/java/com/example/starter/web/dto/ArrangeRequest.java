package com.example.starter.web.dto;

import java.time.LocalDateTime;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 编排请求：绑定引航员、拖轮组合与潮水窗口（半开区间 [tideStart, tideEnd)）。
 *
 * @param requestId  幂等键
 * @param pilotId    引航员ID
 * @param tugboatIds 拖轮组合（至少一条）
 * @param tideStart  潮水窗口开始（含）
 * @param tideEnd    潮水窗口结束（不含）
 */
public record ArrangeRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotNull Long pilotId,
        @NotEmpty List<@NotNull Long> tugboatIds,
        @NotNull LocalDateTime tideStart,
        @NotNull LocalDateTime tideEnd) {
}
