package com.example.starter.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.LocalDate;
import java.util.List;

/** 渠道对账文件（合成数据）。 */
public record ReconRequest(
        @NotBlank String fileNo,
        @NotNull LocalDate batchDate,
        String channelCode,
        @NotNull @Valid List<ReconEntry> entries) {

    /** 渠道单笔流水。 */
    public record ReconEntry(
            @NotBlank String channelFlowNo,
            String merchantNo,
            @NotNull @Positive Long amount) {
    }
}
