package com.example.starter.valve;

import java.math.BigDecimal;

/**
 * 门站加注阀与告警状态行（station_id 主键，行级序列化更新）。
 *
 * @param stationId                门站ID
 * @param injectionLocked          加注阀是否锁定
 * @param alarmActive              告警是否生效
 * @param breachCount              当前连续越限次数（合格归零）
 * @param currentInjectionRateMgM3 当前加注速率折算末端浓度 mg/m3
 * @param currentPressureMpa       最近联动后压力 MPa，NULL 未调节
 */
public record ValveState(
        Long stationId,
        boolean injectionLocked,
        boolean alarmActive,
        int breachCount,
        BigDecimal currentInjectionRateMgM3,
        BigDecimal currentPressureMpa) {
}
