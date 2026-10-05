package com.example.starter.pension.repo;

import com.example.starter.pension.po.InheritanceReconRow;

import java.util.List;

/**
 * 继承勾稽明细数据访问：明细引用来源行（缴费/期次），可回源表复算。
 */
public interface InheritanceReconRepository {

    /** 批量写入勾稽明细；同一来源重复计入由唯一索引拒绝。 */
    void insertBatch(long claimId, List<InheritanceReconRow> rows);

    /** 查询申领的全部勾稽明细，按主键升序。 */
    List<InheritanceReconRow> findByClaimId(long claimId);

    /** 按勾稽组成汇总金额，单位：分；无明细为 0。 */
    long sumByComponent(long claimId, String component);
}
