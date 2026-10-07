package com.example.starter.cosmetic.port;

import com.example.starter.cosmetic.domain.LabelDrawRecord;
import com.example.starter.cosmetic.domain.PackagingBatchRecord;
import com.example.starter.cosmetic.domain.PrintRunRecord;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 包装标签印次台账持久化端口。
 *
 * <p>领用/销毁/禁用均通过带状态与版本谓词的单条原子 UPDATE 落到数据库判定，
 * 受影响行数决定竞争归属；与备案换版/注销的事务边界由调用方事务保证。</p>
 */
public interface LabelPrintPort {

    /** 插入一条印次台账（版本=1，状态=ACTIVE）；印次号唯一冲突由数据库约束抛出。 */
    void insertPrintRun(PrintRunRecord record);

    /** 按印次号查询。 */
    Optional<PrintRunRecord> findPrintRun(String printRunNo);

    /**
     * 原子领用：仅当印次为 ACTIVE、版本匹配且未领余额足够时，
     * 已领张数 += quantity、版本 +1。
     *
     * @return 是否领用成功（false 说明并发下状态/版本/余额已变化，由调用方重读判定）
     */
    boolean drawIfActive(String printRunNo, int expectedVersion, int quantity, Instant now);

    /**
     * 原子销毁：仅当版本匹配且未领余额足够时，已毁张数 += quantity、版本 +1。
     * 禁用印次允许销毁未领剩余，但不改变状态（不允许复活）。
     *
     * @return 是否销毁成功（false 说明并发下版本/余额已变化，由调用方重读判定）
     */
    boolean destroyIfEnough(String printRunNo, int expectedVersion, int quantity, Instant now);

    /**
     * 原子禁用某备案修订的全部 ACTIVE 印次：状态置 DISABLED、版本 +1、记录原因与时刻。
     *
     * @return 禁用的印次数量
     */
    int disableActiveByFiling(String filingNo, int filingVersionNo, String reason, Instant now);

    /** 按批号查询包装批登记。 */
    Optional<PackagingBatchRecord> findBatch(String batchNo);

    /** 登记包装批所属产品（首次领用）；批号主键冲突由数据库约束抛出。 */
    void insertBatch(PackagingBatchRecord batch);

    /** 追加一条领用凭据。 */
    void insertDraw(LabelDrawRecord draw);

    /** 查询印次的全部领用凭据（按领用先后升序），供追溯。 */
    List<LabelDrawRecord> findDrawsByPrintRun(String printRunNo);

    /**
     * 给某备案修订全部印次的已领凭据追加风险关联（仅未标记的；不抹历史）。
     *
     * @return 新标记的凭据数量
     */
    int flagRiskByFiling(String filingNo, int filingVersionNo, String note, Instant now);

    /** 追加一条销毁登记（留证）。 */
    void insertDestroy(String printRunNo, int quantity, String requestId, Instant destroyedAtUtc);
}
