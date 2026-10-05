package com.example.starter.pension.repo;

import com.example.starter.pension.po.InheritanceClaimRow;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * 继承申领表数据访问。唯一受理（requestId 唯一、占用成员唯一）由数据库唯一索引兜底。
 */
public interface InheritanceClaimRepository {

    /**
     * 插入继承申领；requestId 冲突或成员已被其他申领占用时由唯一索引拒绝。
     *
     * @return 新申领主键
     */
    long insert(InheritanceClaimRow claim);

    /** 按申领幂等键查询。 */
    Optional<InheritanceClaimRow> findByRequestId(String requestId);

    /** 按申领号查询。 */
    Optional<InheritanceClaimRow> findByClaimNo(String claimNo);

    /** 按主键查询并对申领行加排他锁，串行化同一申领的完成操作。 */
    Optional<InheritanceClaimRow> lockById(long claimId);

    /** 查询某成员的继承申领数量（0 或 1，唯一索引兜底），用于与支付计划互斥判定。 */
    long countByMember(long memberId);

    /** 查询某成员已完成（终态）的继承申领；用于迟到缴费事件判定。 */
    Optional<InheritanceClaimRow> findCompletedByMember(long memberId);

    /**
     * 完成继承支付（终态）：置 COMPLETED 并记录完成时间；成员占用不释放。
     *
     * @return 受影响行数；0 表示申领已完成（幂等场景）
     */
    int markCompleted(long claimId, OffsetDateTime completedAt);
}
