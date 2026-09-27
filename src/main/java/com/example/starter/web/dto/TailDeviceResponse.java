package com.example.starter.web.dto;

import java.time.Instant;

/**
 * 列尾装置视图。
 *
 * @param deviceNo           装置编号
 * @param selfTestValidUntil 自检合格有效截止时刻（UTC）
 * @param currentJobId       当前绑定作业ID；null 空闲
 * @param version            装置版本，自1起，成功变更加1
 */
public record TailDeviceResponse(
        String deviceNo,
        Instant selfTestValidUntil,
        Long currentJobId,
        int version) {
}
