package com.example.starter.dr.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * 冻结请求中单块电表的逐区间证据版本。
 *
 * @param meterId   电表业务编码
 * @param intervals 该电表每个区间对应的基线与实测版本号
 */
public record MeterEvidence(
        @NotBlank String meterId,
        @NotEmpty @Valid List<IntervalEvidence> intervals) {
}
