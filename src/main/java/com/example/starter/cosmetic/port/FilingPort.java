package com.example.starter.cosmetic.port;

import com.example.starter.cosmetic.domain.FilingRecord;
import com.example.starter.cosmetic.domain.FilingStatus;
import com.example.starter.cosmetic.domain.ReviewDetail;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 备案记录持久化端口。所有状态竞态（公示期满/异议受理、版本切换）
 * 均通过带状态条件的原子 UPDATE 落到数据库判定。
 */
public interface FilingPort {

    /** 生成下一个备案编号顺序号（行锁原子自增）。 */
    long nextFilingSequence();

    /** 插入一条备案版本。 */
    void insert(FilingRecord record);

    /** 按主键更新整条备案版本。 */
    void update(FilingRecord record);

    /** 按主键查询。 */
    Optional<FilingRecord> findById(long id);

    /** 按备案编号查询最新版本。 */
    Optional<FilingRecord> findLatestByNo(String filingNo);

    /** 按备案编号+版本号精确查询。 */
    Optional<FilingRecord> findVersion(String filingNo, int versionNo);

    /** 查询产品全部历史备案版本（含被替代/注销/驳回），按版本号升序。 */
    List<FilingRecord> findByProduct(String productCode);

    /** 查询产品当前有效备案槽。 */
    Optional<ActiveSlot> findActiveSlot(String productCode);

    /** 占用有效备案槽（首次生效）；产品已有槽位时主键/唯一冲突回滚整个事务。 */
    void occupySlot(String productCode, String filingNo, int versionNo, Instant now);

    /** 同事务内更换有效备案槽指向新版本（简易变更/实质变更新版本生效）。 */
    void replaceSlot(String productCode, String filingNo, int versionNo, Instant now);

    /** 释放有效备案槽（注销终态）。 */
    void releaseSlot(String productCode);

    /**
     * 原子受理公示异议：仅当当前状态为 PUBLIC 时置为 OBJECTION_REVIEW。
     *
     * @return 是否受理成功（false 说明公示已期满生效或已被其他分支终态化）
     */
    boolean acceptObjectionIfPublic(long id, Instant objectionAtUtc);

    /**
     * 原子公示期满生效：仅当当前状态为 PUBLIC 时置为 EFFECTIVE 并记录生效时刻。
     *
     * @return 是否生效成功（false 说明异议分支已获胜）
     */
    boolean expireToEffectiveIfPublic(long id, Instant effectiveAtUtc);

    /**
     * 原子替代旧版本：仅当旧版本为 EFFECTIVE 时置为 SUPERSEDED。
     *
     * @return 是否替代成功；版本切换要求恰好替代一个有效版本，否则事务回滚
     */
    boolean supersedeEffective(long id, Instant now);

    /** 置为注销终态（无条件，供已校验的注销使用）。 */
    void markWithdrawn(long id, Instant now, String reason);

    /** 异议成立终态驳回。 */
    void markRejectedAfterObjection(long id, Instant now, String reason);

    /** 异议不成立回到公示态（保留原异议受理时刻）。 */
    void reopenPublic(long id);

    /** 保存一组审查明细。 */
    void saveReviews(List<ReviewDetail> details);

    /** 查询审查明细；versionNo 为 null 时返回该备案编号全部版本明细。 */
    List<ReviewDetail> findReviews(String filingNo, Integer versionNo);

    /** 查询已到公示期满时刻（含恰期满）但仍处于 PUBLIC 的备案。 */
    List<FilingRecord> findDuePublicFilings(Instant now);

    /** 产品是否存在注销终态记录（注销后新宣称审查一律拒绝）。 */
    boolean hasWithdrawnRecord(String productCode);

    /** 产品下一个备案版本号（最大版本号 +1，无历史则为 1）。 */
    int nextProductVersion(String productCode);
}
