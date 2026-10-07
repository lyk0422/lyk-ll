package com.example.starter.ride;

import java.time.Instant;

/**
 * 检修挂牌锁实体：启动检修时为每名挂牌人员建立的唯一锁。
 *
 * @param id             挂牌锁主键
 * @param maintenanceId  检修单ID
 * @param personName     锁归属人员姓名，只能由本人与一名不同监护员共同确认撤除
 * @param removed        锁是否已撤除
 * @param removedAt      撤锁时刻（UTC），NULL 表示未撤锁
 */
public record MaintenanceLock(
        long id,
        long maintenanceId,
        String personName,
        boolean removed,
        Instant removedAt) {
}
