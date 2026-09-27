package com.example.starter.web.dto;

import java.time.Instant;

/**
 * 列尾装置建档/视图响应。
 *
 * @param deviceNo            装置编号
 * @param selfCheckExpiresAt  自检合格有效截止时刻（UTC）
 * @param version             递增版本，建档为 1
 * @param boundJobId          当前绑定作业ID；NULL 表示空闲
 * @param occupied            是否被占用
 */
public record TailDeviceResponse(
        String deviceNo,
        Instant selfCheckExpiresAt,
        int version,
        Long boundJobId,
        boolean occupied) {
}
