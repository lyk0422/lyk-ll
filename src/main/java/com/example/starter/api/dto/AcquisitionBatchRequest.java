package com.example.starter.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 采集批次上传请求：一次至多 20 包，整批校验、整批入账。
 *
 * @param expectedVersion 会话乐观版本号，须等于当前版本，否则 409
 * @param packets         采集包列表；序号从 1 开始连续，仅缓冲水位线之后第 1～20 号
 */
public record AcquisitionBatchRequest(
        @NotBlank String requestId,
        @NotBlank String sessionNo,
        @NotNull @Positive Integer expectedVersion,
        @NotNull List<Packet> packets) {

    /**
     * 单个采集包：会话内序号、点号、UTC 测量时刻与有限正力矩。
     */
    public record Packet(
            @NotNull @Positive Integer seq,
            @NotBlank String pointCode,
            @NotNull @Positive BigDecimal measuredTorque,
            @NotNull Instant measuredAt) {
    }
}
