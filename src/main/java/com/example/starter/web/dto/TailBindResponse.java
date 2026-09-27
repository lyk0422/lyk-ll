package com.example.starter.web.dto;

import java.time.Instant;

/**
 * 列尾装置绑定响应（首次冻结结果，同键同参原样重放）。
 *
 * @param bindingId       绑定历史ID
 * @param jobId           受控作业ID
 * @param deviceNo        装置编号
 * @param tailCarNo       冻结的尾车号
 * @param consistVersion  冻结的编组版本
 * @param boundAt         绑定时刻（UTC）
 * @param deviceVersion   绑定成功后的装置版本
 */
public record TailBindResponse(
        long bindingId,
        long jobId,
        String deviceNo,
        String tailCarNo,
        int consistVersion,
        Instant boundAt,
        int deviceVersion) {
}
