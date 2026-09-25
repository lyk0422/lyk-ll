package com.example.starter.pv.data;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 个例主表数据访问。
 */
public interface CaseRepository {

    /**
     * 插入新个例。
     */
    void insert(CasePo po);

    /**
     * 按编号查询个例。
     */
    Optional<CasePo> findByCaseNo(String caseNo);

    /**
     * 按编号查询个例并持有行级写锁（FOR UPDATE），须在事务内调用。
     */
    Optional<CasePo> lockByCaseNo(String caseNo);

    /**
     * 查询 患者＋药品＋事件 对应的唯一活动个例（REPORTED）。
     */
    Optional<CasePo> findActive(String patientCode, String drugCode, String reactionEvent);

    /**
     * 查询该去重组合最近一个个例（含已关闭），按创建时刻倒序取首条。
     */
    Optional<CasePo> findLatestByDedup(String patientCode, String drugCode, String reactionEvent);

    /**
     * 并入一条信息后的字段更新（最近随访日、并入条数、更新时刻）；版本号 = 并入条数 + 1。
     */
    void applyFollowUp(String caseNo, LocalDate latestFollowUpDate,
                       int followUpCount, Instant updatedAt);

    /**
     * 关闭个例（终态）。
     */
    void markClosed(String caseNo, Instant updatedAt);

    /**
     * 查询全部个例（含已关闭），按编号升序。
     */
    List<CasePo> findAll();

    /**
     * 查询全部活动个例，按编号升序。
     */
    List<CasePo> findActiveAll();

    /**
     * 锁定全部活动个例行（FOR UPDATE）并按编号升序返回，须在事务内调用。
     */
    List<CasePo> lockActiveAll();

    /**
     * 按编号集合查询活动个例，按编号升序。
     */
    List<CasePo> findActiveByCaseNos(List<String> caseNos);

    /**
     * 按编号集合锁定活动个例行（FOR UPDATE）并按编号升序返回，须在事务内调用。
     */
    List<CasePo> lockActiveByCaseNos(List<String> caseNos);
}
