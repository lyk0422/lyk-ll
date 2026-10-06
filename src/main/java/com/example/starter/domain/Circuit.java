package com.example.starter.domain;

/**
 * 照明回路聚合视图（只读快照）。
 *
 * @param id                  回路主键
 * @param code                回路编码，全局唯一
 * @param name                回路名称
 * @param currentLevel        当前档位，0 为熄灭
 * @param initialLevel        初始化档位，日结能耗复算的起点
 * @param baseLevel           保底亮度档位，车流量恰为 0 时降至该档
 * @param maxLevel            最高档位，事故模式锁定在该档
 * @param brightnessThreshold 洞外亮度合成阈值(lux)，低于则升档，恰等不触发
 * @param status              状态：NORMAL 正常 / ACCIDENT 事故锁定
 * @param lockedLevel         事故锁定档位，仅事故状态有效，否则为 null
 * @param lastAdjustedAt      最近一次调光时间(UTC)，null 表示从未调光
 * @param transitionUntil     过渡曲线截止时间(UTC)，此前禁止下调，null 表示无过渡
 */
public record Circuit(
        long id,
        String code,
        String name,
        int currentLevel,
        int initialLevel,
        int baseLevel,
        int maxLevel,
        int brightnessThreshold,
        CircuitStatus status,
        Integer lockedLevel,
        java.time.Instant lastAdjustedAt,
        java.time.Instant transitionUntil) {
}
