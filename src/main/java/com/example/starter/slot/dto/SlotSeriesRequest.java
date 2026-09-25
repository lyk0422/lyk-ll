package com.example.starter.slot.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.List;

/**
 * 航班时刻系列申请入参。
 *
 * @param requestId  申请幂等键，同一系列重复提交必须使用相同 requestId
 * @param airline    航司二字码，如 CA
 * @param flightNo   航班号，如 CA1234
 * @param slotTime   申请时刻（HH:mm，须为 5 分钟粒度，如 08:35）
 * @param weeks      覆盖周列表（周序号，从 1 开始），一个系列的多周分配整体成功或整体回滚
 */
public record SlotSeriesRequest(
        @NotBlank String requestId,
        @NotBlank String airline,
        @NotBlank String flightNo,
        @NotBlank
        @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$", message = "slotTime 必须为 HH:mm 格式")
        String slotTime,
        @NotNull @NotEmpty List<@NotNull Integer> weeks) {
}
