package com.example.starter.fermentation.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 温度采样记录。偏差恰 0.50℃ 判定为合格；连续超差达到 3 个采样点（恰 3 点）触发告警。
 *
 * @param id              记录 ID
 * @param batchId         所属批次 ID
 * @param curveVersion    采样时生效的曲线版本号
 * @param phase           采样时批次所处阶段
 * @param sampledAt       采样时间
 * @param temperature     实测温度，单位摄氏度
 * @param setpoint        按曲线复算的设定值，单位摄氏度
 * @param deviation       绝对偏差，单位摄氏度
 * @param outOfTolerance  是否超差（偏差 &gt; 0.5℃）
 * @param consecutiveOut  截至本点的连续超差点数
 * @param alarmTriggered  是否触发告警（连续超差点数 &gt;= 3）
 * @param late            是否迟到数据（批次已转出后到达）
 */
public record TemperatureReading(
        long id,
        long batchId,
        int curveVersion,
        CurvePhase phase,
        LocalDateTime sampledAt,
        BigDecimal temperature,
        BigDecimal setpoint,
        BigDecimal deviation,
        boolean outOfTolerance,
        int consecutiveOut,
        boolean alarmTriggered,
        boolean late) {
}
