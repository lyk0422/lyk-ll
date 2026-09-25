package com.example.starter.flood.web.dto;

import java.time.LocalDateTime;

/**
 * 读数上报结果。
 *
 * @param accepted true=新入库；false=重复读数已去重；true 且 late=true=处置链已归档，仅记迟到事件
 * @param dedupKey 去重键（测点编码 + 采样时刻）
 * @param sampleTime 归一后的采样时刻
 * @param late 是否为归档后的迟到读数
 */
public record ReadingResult(
        boolean accepted,
        boolean late,
        String dedupKey,
        LocalDateTime sampleTime) {
}
