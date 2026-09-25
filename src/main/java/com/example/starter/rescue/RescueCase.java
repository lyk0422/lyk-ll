package com.example.starter.rescue;

import java.time.Instant;

/**
 * 救援案件实体：接报→派单→到场→施救→复检恢复→回访结案，CLOSED 为终态。
 *
 * @param id                  主键
 * @param requestId           接报幂等键
 * @param reportFingerprint   接报指纹（requestId + 全部业务字段）
 * @param elevatorId          电梯ID
 * @param elevatorCode        电梯编号
 * @param status              状态：RECEIVED/DISPATCHED/ARRIVED/RESCUING/RECHECKED/CLOSED
 * @param reportedAt          接报时间（UTC）
 * @param dispatchRequestId   派单幂等键
 * @param dispatchFingerprint 派单指纹
 * @param dispatchedAt        派单时间（UTC）
 * @param dispatcher          派单人
 * @param arrivedAt           到场时间（UTC）
 * @param escalated           是否已超时升级通报
 * @param escalatedAt         升级时间（UTC）
 * @param rescuedAt           施救完成时间（UTC）
 * @param rescuer1Id          施救人员1（持证）
 * @param rescuer2Id          施救人员2（持证）
 * @param recheckedAt         复检时间（UTC）
 * @param recheckResult       复检结果 PASS/FAIL
 * @param closeRequestId      结案幂等键
 * @param closeFingerprint    结案指纹
 * @param closedAt            结案时间（UTC）
 * @param version             乐观锁版本号
 */
public record RescueCase(
        Long id,
        String requestId,
        String reportFingerprint,
        long elevatorId,
        String elevatorCode,
        String status,
        Instant reportedAt,
        String dispatchRequestId,
        String dispatchFingerprint,
        Instant dispatchedAt,
        String dispatcher,
        Instant arrivedAt,
        boolean escalated,
        Instant escalatedAt,
        Instant rescuedAt,
        Long rescuer1Id,
        Long rescuer2Id,
        Instant recheckedAt,
        String recheckResult,
        String closeRequestId,
        String closeFingerprint,
        Instant closedAt,
        long version) {

    /** 已接报。 */
    public static final String RECEIVED = "RECEIVED";
    /** 已派单。 */
    public static final String DISPATCHED = "DISPATCHED";
    /** 已到场。 */
    public static final String ARRIVED = "ARRIVED";
    /** 施救完成。 */
    public static final String RESCUING = "RESCUING";
    /** 复检合格并恢复运行。 */
    public static final String RECHECKED = "RECHECKED";
    /** 回访结案（终态）。 */
    public static final String CLOSED = "CLOSED";

    /** 到场时限：接报后 30 分钟，恰满即超时。 */
    public static final long ARRIVE_TIMEOUT_MINUTES = 30;
}
