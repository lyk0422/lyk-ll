package com.example.starter.pension.repo;

import com.example.starter.pension.po.PlanRow;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * 支付计划表数据访问。计划的唯一受理（requestId 唯一、进行中成员唯一）由数据库唯一索引兜底。
 */
public interface PlanRepository {

    /**
     * 插入支付计划；requestId 冲突或成员已有进行中计划时由唯一索引拒绝。
     *
     * @return 新计划主键
     */
    long insert(PlanRow plan);

    /** 按申请幂等键查询。 */
    Optional<PlanRow> findByRequestId(String requestId);

    /** 按主键查询。 */
    Optional<PlanRow> findById(long planId);

    /** 按计划号查询。 */
    Optional<PlanRow> findByPlanNo(String planNo);

    /** 按主键查询并对计划行加排他锁，串行化同计划的付款与结清。 */
    Optional<PlanRow> lockById(long planId);

    /** 查询占用某成员进行中槽位的计划数量（0 或 1，唯一索引兜底）。 */
    long countActiveByMember(long memberId);

    /** 查询某成员当前进行中的支付计划（占用成员槽位的唯一计划）；无为空。 */
    Optional<PlanRow> findActiveByMember(long memberId);

    /** 查询某成员的全部历史计划数量（含已结清），用于区分首次申请与补发。 */
    long countByMember(long memberId);

    /**
     * 计划结清（终态）：置 SETTLED、释放成员占用、记录结清时间。
     *
     * @return 受影响行数；0 表示计划已被结清（幂等场景）
     */
    int markSettled(long planId, OffsetDateTime settledAt);

    /**
     * 首次出款后将计划由 ACCEPTED 置为 IN_PROGRESS；已在支付中/结清时无影响。
     */
    int markInProgress(long planId);

    /**
     * 标记未归属企业缴费已划回企业账户。
     *
     * @return 受影响行数；0 表示已划转，调用方不得重复入账
     */
    int markEnterpriseReturned(long planId);

    /**
     * 继承申领受理时终止计划（终态）：置 TERMINATED、释放成员占用、记录终止时间。
     *
     * @return 受影响行数；0 表示计划已处于终态
     */
    int markTerminated(long planId, OffsetDateTime terminatedAt);
}
