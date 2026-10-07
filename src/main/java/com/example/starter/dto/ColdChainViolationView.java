package com.example.starter.dto;

import java.time.Instant;

/**
 * 冷链核验缺口/越界点视图：缺样本如实展示为缺口，不按零度补齐。
 *
 * @param code       缺口代码（如 MISSING_END_SAMPLE/TEMPERATURE_OUT_OF_RANGE）
 * @param segmentSeq 相关片段顺序号，空值表示与具体片段无关
 * @param readingSeq 相关读数顺序号，空值表示与具体读数无关
 * @param at         相关精确时刻（UTC），空值表示无对应时刻
 * @param value      相关数值（如越界温度、实际间隔秒数），空值表示无对应数值
 * @param message    缺口/越界的人读描述
 */
public record ColdChainViolationView(String code, Integer segmentSeq, Integer readingSeq,
                                     Instant at, String value, String message) {
}
