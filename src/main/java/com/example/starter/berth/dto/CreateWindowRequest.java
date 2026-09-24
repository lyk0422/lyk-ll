package com.example.starter.berth.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 创建靠泊窗口申请请求。
 *
 * @param requestId      幂等请求键，由申请人生成；同键同参返回首次结果，同键异参返回 409
 * @param vesselName     船名
 * @param berthCode      泊位代码
 * @param startAt        预计靠泊开始时刻（UTC，ISO-8601），半开区间起点
 * @param endAt          预计靠泊结束时刻（UTC，ISO-8601），半开区间终点
 * @param draftMeters    船舶吃水（米），不得超过泊位限深
 * @param pilotsRequired 所需引航员人数
 * @param supersedesId   改期场景被取代的原窗口 id；普通申请为 null（由改期接口内部填充）
 */
public record CreateWindowRequest(
        @NotBlank(message = "requestId 不能为空")
        @Size(max = 64, message = "requestId 长度不能超过 64")
        String requestId,

        @NotBlank(message = "船名不能为空")
        @Size(max = 128, message = "船名长度不能超过 128")
        String vesselName,

        @NotBlank(message = "泊位代码不能为空")
        @Size(max = 32, message = "泊位代码长度不能超过 32")
        String berthCode,

        @NotNull(message = "预计靠泊开始时刻不能为空")
        Instant startAt,

        @NotNull(message = "预计靠泊结束时刻不能为空")
        Instant endAt,

        @NotNull(message = "吃水不能为空")
        @DecimalMin(value = "0.0", inclusive = false, message = "吃水必须大于 0")
        @Digits(integer = 3, fraction = 2, message = "吃水最多 3 位整数、2 位小数（米）")
        BigDecimal draftMeters,

        @Min(value = 0, message = "所需引航员数不能为负")
        int pilotsRequired,

        Long supersedesId) {
}
