package com.example.starter.slot.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * 系列时刻申请请求。
 *
 * @param requestId 幂等键，同一申请系列在各次重放中必须相同
 * @param season    航季标识，例如 S26
 * @param airline   航司二字码
 * @param flightNo  航班号
 * @param airport   协调机场三字码
 * @param slotTime  申请时刻 HH:mm，必须落在机场时刻池 5 分钟粒度上
 * @param weeks     覆盖周列表（航季内周序号），整周协调
 */
public record SlotApplicationRequest(
        @NotBlank String requestId,
        @NotBlank String season,
        @NotBlank String airline,
        @NotBlank String flightNo,
        @NotBlank String airport,
        @NotBlank String slotTime,
        @NotEmpty List<Integer> weeks) {
}
