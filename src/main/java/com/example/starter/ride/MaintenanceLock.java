package com.example.starter.ride;

import java.time.Instant;

/**
 * 检修挂牌锁实体：每张进行中检修单为每名进场人员建立唯一未撤锁。
 *
 * @param id             挂牌锁主键
 * @param orderId        检修单ID，关联 maintenance_order.id
 * @param rideId         设备ID，关联 ride.id
 * @param personName     挂牌人员姓名（合成检修人员），同一检修单内唯一
 * @param removed        是否已撤除：FALSE 为未撤锁，开放/复开被阻断
 * @param removedAt      撤锁时刻（UTC），NULL 表示未撤除
 * @param lockHolder     本人签认人，须等于挂牌人员本人；NULL 表示未撤除
 * @param guardian       监护员签认人，须为与本人不同的另一人；NULL 表示未撤除
 * @param removeRequestId 撤锁幂等请求ID，NULL 表示未撤除
 */
public record MaintenanceLock(
        long id,
        long orderId,
        long rideId,
        String personName,
        boolean removed,
        Instant removedAt,
        String lockHolder,
        String guardian,
        String removeRequestId) {
}
