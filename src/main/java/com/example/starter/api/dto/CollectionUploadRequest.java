package com.example.starter.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 采集包批量上传请求：一次至多 20 包；序号重复、未知点或超窗任一错误整批拒绝。
 *
 * @param expectedVersion 会话乐观锁版本（从 1 开始，成功变更加 1）
 * @param packets         采集包列表，序号从 1 开始连续，仅缓冲水位线之后第 1～20 号乱序包
 */
public record CollectionUploadRequest(
        @NotBlank String requestId,
        @NotBlank String sessionNo,
        @NotNull @Positive Integer expectedVersion,
        @NotNull @Size(min = 1, max = 20) List<@Valid PacketSpec> packets) {

    /**
     * 单个采集包：会话内序号、点号、UTC 测量时刻与有限正力矩。
     */
    public record PacketSpec(
            @NotNull @Positive Integer seq,
            @NotBlank String pointCode,
            @NotNull @Positive BigDecimal measuredTorque,
            @NotNull Instant measuredAt) {
    }
}
