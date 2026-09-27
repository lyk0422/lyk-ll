package com.example.starter.repo;

import java.time.Instant;

/**
 * 列尾装置行记录。
 *
 * @param id                 装置主键
 * @param deviceNo           装置编号（合成数据）
 * @param selfTestValidUntil 自检合格有效截止时刻（UTC）；恰达截止即失效
 * @param currentJobId       当前绑定作业ID；NULL 空闲
 * @param version            装置版本，自1起，成功变更加1
 */
public record TailDeviceRow(
        long id,
        String deviceNo,
        Instant selfTestValidUntil,
        Long currentJobId,
        int version) {
}
