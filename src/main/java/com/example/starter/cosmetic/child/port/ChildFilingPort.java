package com.example.starter.cosmetic.child.port;

import com.example.starter.cosmetic.child.domain.ChildFilingRecord;
import com.example.starter.cosmetic.child.domain.ChildReviewDetail;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 儿童备案修订持久化端口。
 *
 * <p>状态竞态（补正使旧修订失效、公示期满生效、版本替代）均通过带状态/版本条件的
 * 原子 UPDATE 落到数据库判定，受影响行数决定唯一归属。</p>
 */
public interface ChildFilingPort {

    /** 生成下一个儿童备案编号顺序号（行锁原子自增）。 */
    long nextFilingSequence();

    /** 插入一个备案修订。 */
    void insert(ChildFilingRecord record);

    /** 按主键查询。 */
    Optional<ChildFilingRecord> findById(long id);

    /** 按备案编号查询最新修订（最大版本号）。 */
    Optional<ChildFilingRecord> findLatestByNo(String filingNo);

    /** 按备案编号+版本号精确查询。 */
    Optional<ChildFilingRecord> findVersion(String filingNo, int versionNo);

    /** 查询产品全部修订（按版本号升序）。 */
    List<ChildFilingRecord> findByProduct(String productCode);

    /** 查询产品当前有效备案槽。 */
    Optional<ChildActiveSlot> findActiveSlot(String productCode);

    /** 占用有效备案槽（首次生效）；已有槽位时主键冲突回滚整个事务。 */
    void occupySlot(String productCode, String filingNo, int versionNo, Instant now);

    /** 同事务内更换有效备案槽指向新版本。 */
    void replaceSlot(String productCode, String filingNo, int versionNo, Instant now);

    /** 释放有效备案槽。 */
    void releaseSlot(String productCode);

    /**
     * 原子公示期满生效：仅当该修订为 PUBLIC 且为本编号最新修订时置 EFFECTIVE。
     *
     * @return 是否生效成功（false 说明已被补正新修订取代/非最新，或非公示态）
     */
    boolean expireToEffectiveIfLatestPublic(long id, String filingNo, Instant effectiveAtUtc);

    /** 原子替代旧有效版本：仅当旧版本为 EFFECTIVE 时置 SUPERSEDED。 */
    boolean supersedeEffective(long id, Instant now);

    /**
     * 原子使旧待审修订失效：仅当旧修订状态为 FORM_REJECTED（补正场景）或 PUBLIC（理论不允许，保留唯一约束）
     * 且为指定 id 时置 VOID。
     *
     * @return 是否恰好失效一个修订；false 时补正事务回滚，保证唯一在审修订
     */
    boolean voidPendingRevision(long id, Instant now);

    /** 保存一组儿童分龄审查明细。 */
    void saveReviews(List<ChildReviewDetail> details);

    /** 查询审查明细；versionNo 为 null 时返回该备案编号全部修订明细。 */
    List<ChildReviewDetail> findReviews(String filingNo, Integer versionNo);

    /** 查询已到公示期满时刻（含恰期满）且仍为 PUBLIC 的修订（仅最新修订可生效）。 */
    List<ChildFilingRecord> findDuePublicFilings(Instant now);

    /** 产品下一个修订版本号（最大版本号 +1，无历史则为 1）。 */
    int nextProductVersion(String productCode);
}
