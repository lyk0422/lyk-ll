package com.example.starter.pension.repo;

/**
 * 账务流水数据访问：个人出款与企业划回的唯一性由数据库唯一索引保证。
 */
public interface LedgerRepository {

    /**
     * 登记成员收款出款流水；同一期次重复出款由唯一索引拒绝。
     *
     * @return 受影响行数；0 表示该期已出款
     */
    int insertMemberPayout(long planId, long installmentId, long amountCents);

    /**
     * 登记未归属企业缴费划回流水；同一计划重复划回由唯一索引拒绝。
     *
     * @return 受影响行数；0 表示该计划已划回
     */
    int insertEnterpriseReturn(long planId, long amountCents);

    /** 汇总计划已出款给成员的总金额，单位：分。 */
    long sumMemberPayouts(long planId);

    /** 查询计划已划回企业的金额；未划回为 0。 */
    long sumEnterpriseReturns(long planId);
}
