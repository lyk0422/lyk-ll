package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.List;
import java.util.Map;

/**
 * DBC 版本原子导入请求。
 *
 * @param versionName 版本名称（同版本名重复导入将冲突）
 * @param requestId   导入请求幂等键
 * @param messages    报文及其信号定义
 */
public record DbcImportRequest(
        @NotBlank String versionName,
        @NotBlank String requestId,
        @NotEmpty @Valid List<MessageDefDto> messages) {

    /**
     * 报文定义。
     *
     * @param canId       CAN ID（0..0x1FFFFFFF）
     * @param name        报文名称
     * @param dlc         期望负载字节数（1..8，经典 CAN）
     * @param signals     信号定义
     */
    public record MessageDefDto(
            @NotNull @PositiveOrZero Long canId,
            @NotBlank String name,
            @Positive Integer dlc,
            @NotEmpty @Valid List<SignalDefDto> signals) {
    }

    /**
     * 信号定义。
     *
     * @param name           信号名称
     * @param byteOrder      BIG_ENDIAN 或 LITTLE_ENDIAN
     * @param startBit       DBC 起始位编号
     * @param bitLength      位长度（1..64）
     * @param signed         是否带符号
     * @param factor         缩放系数（不能为 0）
     * @param offset         偏移量
     * @param unit           单位，可空
     * @param multiplexRole  NONE / MULTIPLEXOR / MULTIPLEXED
     * @param multiplexValue 被选择信号要求的选择器原始值，仅 MULTIPLEXED 时必填
     * @param valueTable     枚举映射（原始值 -> 文本），可空
     */
    public record SignalDefDto(
            @NotBlank String name,
            @NotBlank String byteOrder,
            @NotNull @PositiveOrZero Integer startBit,
            @NotNull @Positive Integer bitLength,
            @NotNull Boolean signed,
            @NotNull Double factor,
            @NotNull Double offset,
            String unit,
            @NotBlank String multiplexRole,
            Long multiplexValue,
            Map<Long, String> valueTable) {
    }
}
