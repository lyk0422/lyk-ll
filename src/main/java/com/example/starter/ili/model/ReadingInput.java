package com.example.starter.ili.model;

import java.math.BigDecimal;

/**
 * 单条缺陷读数输入。
 *
 * @param readingKey 客户端读数键，批次+回合内唯一
 * @param stationM   绝对里程，单位米
 * @param depthPct   深度，占壁厚百分比
 */
public record ReadingInput(String readingKey, BigDecimal stationM, BigDecimal depthPct) {
}
