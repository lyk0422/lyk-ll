package com.example.starter.dto;

import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;

/** 挂账调账指令；toBatchDate 缺省为挂账原批次的次日（须为开放批次）。 */
public record AdjustRequest(
        @NotBlank String adjustNo,
        @NotBlank String channelFlowNo,
        LocalDate fromBatchDate,
        LocalDate toBatchDate) {
}
