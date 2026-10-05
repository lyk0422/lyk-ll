package com.example.starter.pension.repo;

/**
 * 迟到缴费事件数据访问：继承支付完成后迟到的缴费只记事件并提示人工复核。
 */
public interface LateContributionEventRepository {

    /**
     * 登记迟到缴费事件；同一缴费重复登记由唯一索引拒绝。
     *
     * @return 受影响行数；0 表示该缴费已登记过
     */
    int insert(long memberId, long contributionId, long claimId);

    /** 查询某成员待人工复核的迟到缴费事件数量。 */
    long countPendingByMember(long memberId);
}
