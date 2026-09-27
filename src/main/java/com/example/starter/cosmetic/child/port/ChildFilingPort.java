package com.example.starter.cosmetic.child.port;

import com.example.starter.cosmetic.child.domain.ChildFilingRecord;
import com.example.starter.cosmetic.child.domain.ChildFilingStatus;
import com.example.starter.cosmetic.child.domain.ChildRevisionRecord;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 儿童备案持久化端口。
 *
 * <p>所有竞态（补正 vs 公示期满、变更生效 vs 旧修订失效、版本乐观锁）
 * 均通过带状态/版本条件的原子 UPDATE 落到数据库判定，受影响行数决定唯一结果。</p>
 */
public interface ChildFilingPort {

    /** 生成下一个儿童备案编号顺序号（行锁原子自增）。 */
    long nextFilingSequence();

    /** 插入儿童备案聚合（初始版本 1）。 */
    void insertFiling(ChildFilingRecord record);

    /** 按备案编号查询聚合。 */
    Optional<ChildFilingRecord> findFiling(String filingNo);

    /** 按产品编码查询聚合（同一产品仅允许一个儿童备案）。 */
    Optional<ChildFilingRecord> findFilingByProduct(String productCode);

    /**
     * 原子版本递增（乐观锁）：仅当当前版本等于 expectedVersion 时，
     * 同事务内将版本 +1 并更新产品名称、状态与最新修订号。
     *
     * @return 是否更新成功（false=版本不一致，业务拒绝 422）
     */
    boolean incrementVersionIfExpected(String filingNo, int expectedVersion, String productName,
                                       ChildFilingStatus newStatus, int currentRevisionNo, Instant now);

    /** 公示期满生效时更新聚合状态（不涉及版本递增）。 */
    void updateFilingStatus(String filingNo, ChildFilingStatus status, Instant now);

    /** 插入一条修订。 */
    void insertRevision(ChildRevisionRecord revision);

    /** 按备案编号+修订号精确查询。 */
    Optional<ChildRevisionRecord> findRevision(String filingNo, int revisionNo);

    /** 查询备案全部修订，按修订号升序。 */
    List<ChildRevisionRecord> findRevisions(String filingNo);

    /**
     * 原子使旧修订待审批准失效：仅当修订仍处于 PUBLIC/REJECTED（待审批准中）时置为 INVALIDATED。
     *
     * @return 是否失效成功（false=该修订已生效，补正在与公示期满的竞争中落败）
     */
    boolean invalidateRevisionIfPending(long revisionId, Instant now);

    /**
     * 原子公示期满生效：仅当修订仍处于 PUBLIC 时置为 EFFECTIVE 并记录生效时刻。
     *
     * @return 是否生效成功（false=补正分支已使该修订失效，唯一结果）
     */
    boolean effectuateIfPublic(long revisionId, Instant effectiveAtUtc);

    /**
     * 原子替代旧生效修订：仅当旧修订为 EFFECTIVE 时置为 SUPERSEDED。
     *
     * @return 是否替代成功；变更生效要求恰好替代一个生效修订，否则事务回滚
     */
    boolean supersedeIfEffective(long revisionId, Instant now);

    /** 查询备案当前生效修订（无则空）。 */
    Optional<ChildRevisionRecord> findEffectiveRevision(String filingNo);

    /** 查询已到公示期满时刻（含恰期满）但仍处于 PUBLIC 的修订。 */
    List<ChildRevisionRecord> findDuePublicRevisions(Instant now);
}
