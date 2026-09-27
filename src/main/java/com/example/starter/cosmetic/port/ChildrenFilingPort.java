package com.example.starter.cosmetic.port;

import com.example.starter.cosmetic.domain.ChildrenFilingRecord;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 儿童备案记录持久化端口。
 *
 * <p>补正与公示期满、变更生效与旧备案失效等状态竞态均通过带状态条件的
 * 原子 UPDATE 落到数据库判定，保证原子且唯一。</p>
 */
public interface ChildrenFilingPort {

    /** 生成下一个儿童备案编号顺序号（行锁原子自增）。 */
    long nextFilingSequence();

    /** 插入一条儿童备案版本/修订。 */
    void insert(ChildrenFilingRecord record);

    /** 按主键查询。 */
    Optional<ChildrenFilingRecord> findById(long id);

    /** 按备案编号查询最新修订（版本号、修订号倒序）。 */
    Optional<ChildrenFilingRecord> findLatestByNo(String filingNo);

    /** 按备案编号+版本号精确查询（同一备案编号下版本号唯一）。 */
    Optional<ChildrenFilingRecord> findByNoAndVersion(String filingNo, int versionNo);

    /** 按备案编号+版本号+修订号精确查询。 */
    Optional<ChildrenFilingRecord> findVersion(String filingNo, int versionNo, int revisionNo);

    /** 查询某备案编号全部修订（版本号、修订号升序），供判定证据查询。 */
    List<ChildrenFilingRecord> findRevisions(String filingNo);

    /** 查询产品全部儿童备案记录（含失效修订），按版本号、修订号升序。 */
    List<ChildrenFilingRecord> findByProduct(String productCode);

    /** 产品当前版本号（最大版本号，无历史则为 0）。 */
    int currentVersion(String productCode);

    /** 查询产品当前有效儿童备案槽。 */
    Optional<ActiveSlot> findActiveSlot(String productCode);

    /** 占用有效备案槽（首次生效）；产品已有槽位时主键冲突回滚整个事务。 */
    void occupySlot(String productCode, String filingNo, int versionNo, Instant now);

    /** 同事务内更换有效备案槽指向新版本。 */
    void replaceSlot(String productCode, String filingNo, int versionNo, Instant now);

    /**
     * 原子使旧修订待审批准失效：仅当该修订处于 PUBLIC 时置为 REVISION_SUPERSEDED。
     *
     * @return 是否失效成功（false 说明公示已期满生效或被其他补正抢先，竞态唯一）
     */
    boolean supersedePublicRevision(long id, Instant now);

    /**
     * 原子公示期满生效：仅当当前状态为 PUBLIC 时置为 EFFECTIVE 并记录生效时刻。
     *
     * @return 是否生效成功（false 说明该修订已被补正失效）
     */
    boolean expireToEffectiveIfPublic(long id, Instant effectiveAtUtc);

    /**
     * 原子替代旧生效版本：仅当旧版本为 EFFECTIVE 时置为 SUPERSEDED。
     *
     * @return 是否替代成功；版本切换要求恰好替代一个有效版本，否则事务回滚
     */
    boolean supersedeEffective(long id, Instant now);

    /** 查询已到公示期满时刻（含恰期满）但仍处于 PUBLIC 的修订。 */
    List<ChildrenFilingRecord> findDuePublicFilings(Instant now);
}
