package com.example.starter.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * 限电指令接收请求。
 * planDate/periodIndex/planVersion 全部为空表示未绑定计划的原指令（行为不变）；
 * 三者同时非空表示计划绑定模式：须指定已发布计划版本的日期、时段（1..96）与版本号。
 *
 * @param requestId    幂等键，覆盖本对象全部业务字段
 * @param farmCode     风场编码
 * @param targetPowerKw 限电后全场目标出力（kW，正整数）
 * @param planDate     绑定计划日期（UTC 日期），未绑定为 null
 * @param periodIndex  绑定时段序号 1..96，未绑定为 null
 * @param planVersion  绑定计划版本号（须为当前版本），未绑定为 null
 */
public record OrderReceiveRequest(
        @NotBlank String requestId,
        @NotBlank String farmCode,
        @Positive int targetPowerKw,
        LocalDate planDate,
        Integer periodIndex,
        Integer planVersion) {

    /** 未绑定计划的原始接收请求（行为不变）。 */
    public OrderReceiveRequest(String requestId, String farmCode, int targetPowerKw) {
        this(requestId, farmCode, targetPowerKw, null, null, null);
    }

    /** 是否计划绑定模式：三个绑定字段同时非空（部分填写由服务层判 400）。 */
    public boolean planBound() {
        return planDate != null && periodIndex != null && planVersion != null;
    }

    /** 绑定字段是否全部为空（原始未绑定模式）。 */
    public boolean planFree() {
        return planDate == null && periodIndex == null && planVersion == null;
    }
}
