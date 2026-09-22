package com.example.starter.web.dto;

import com.example.starter.dbc.Endian;
import com.example.starter.dbc.MultiplexMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * 信号定义 DTO。
 * 字段单位/含义：startBit 为 DBC 位号；length 单位为位；factor/offset 为无量纲换算参数；
 * muxValue 仅在 multiplexMode=MUXED 时必填；enumerations 的键为信号原始整数值。
 */
public record SignalDto(
        @NotBlank String name,
        @NotNull Integer startBit,
        @NotNull Integer length,
        @NotNull Endian endian,
        boolean signed,
        Double factor,
        Double offset,
        @NotNull MultiplexMode multiplexMode,
        Long muxValue,
        Map<String, String> enumerations
) {
}
