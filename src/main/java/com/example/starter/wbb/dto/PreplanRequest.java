package com.example.starter.wbb.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 预配舱单请求：创建航班首版舱单（状态 PREPARED）。
 *
 * @param requestId            幂等键，指纹覆盖该键与全部业务字段
 * @param flightNo             航班号
 * @param flightDate           航班日期（Asia/Shanghai 业务日）
 * @param aircraftType         机型，须已存在合成性能数据
 * @param aircraftRegistration 飞机注册号
 * @param fuelKg              起飞油量，单位 kg，非负
 * @param fuelArm             燃油合力力臂，单位 m；为空时取机型默认油臂
 * @param items               预配业载明细
 */
public record PreplanRequest(
        @NotBlank String requestId,
        @NotBlank String flightNo,
        @NotNull LocalDate flightDate,
        @NotBlank String aircraftType,
        @NotBlank String aircraftRegistration,
        @NotNull BigDecimal fuelKg,
        BigDecimal fuelArm,
        @Valid List<PayloadItemDto> items
) {
}
