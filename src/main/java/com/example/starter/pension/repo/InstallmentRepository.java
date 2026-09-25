package com.example.starter.pension.repo;

import com.example.starter.pension.po.InstallmentRow;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 分期期次表数据访问。
 */
public interface InstallmentRepository {

    /** 单期建账参数。 */
    record NewInstallment(int seqNo, LocalDate dueDate, long amountCents) {
    }

    /** 按计划批量建入期次。 */
    void insertBatch(long planId, List<NewInstallment> installments);

    /** 查询计划全部期次，按期次序号升序。 */
    List<InstallmentRow> findByPlanId(long planId);

    /** 按计划与期次序号查询。 */
    Optional<InstallmentRow> findByPlanAndSeq(long planId, int seqNo);

    /** 按计划与期次序号查询并对期次行加排他锁，保证每期最多出款一次。 */
    Optional<InstallmentRow> lockByPlanAndSeq(long planId, int seqNo);

    /**
     * 记录一次出款失败并累加尝试次数。
     *
     * @return 受影响行数
     */
    int markFailed(long installmentId, int attempts);

    /**
     * 条件置为已出款：仅当期次尚不是 PAID 时成功，保证每期最多出款一次。
     *
     * @param paidAt 出款时间（UTC，来自可控时钟）
     * @return 1 表示本次完成出款；0 表示期次已出款
     */
    int markPaidIfNotYet(long installmentId, int attempts, OffsetDateTime paidAt);
}
