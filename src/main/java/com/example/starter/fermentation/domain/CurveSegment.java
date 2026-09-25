package com.example.starter.fermentation.domain;

import java.math.BigDecimal;

/**
 * 工艺曲线分段设定。
 *
 * @param id              分段 ID
 * @param curveId         所属曲线版本 ID
 * @param phase           阶段
 * @param seq             阶段内分段序号，从 1 开始
 * @param setpointCelsius 温度设定值，单位摄氏度，允许负值
 * @param durationHours   持续时长，单位小时
 */
public record CurveSegment(
        long id,
        long curveId,
        CurvePhase phase,
        int seq,
        BigDecimal setpointCelsius,
        int durationHours) {
}
