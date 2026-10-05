package com.example.starter.pension.repo;

/**
 * 继承台账数据访问：继承出款的唯一性由 (claim_id, movement_type) 唯一索引保证。
 */
public interface InheritanceLedgerRepository {

    /**
     * 登记继承一次性支付出款流水；同一申领重复出款由唯一索引拒绝。
     *
     * @return 受影响行数
     */
    int insertInheritancePayout(long claimId, long amountCents);

    /** 汇总申领的继承出款总额，单位：分；未出款为 0。 */
    long sumInheritancePayouts(long claimId);
}
