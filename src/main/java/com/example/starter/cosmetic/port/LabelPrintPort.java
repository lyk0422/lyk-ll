package com.example.starter.cosmetic.port;

import com.example.starter.cosmetic.domain.LabelDestruction;
import com.example.starter.cosmetic.domain.LabelIssue;
import com.example.starter.cosmetic.domain.LabelPrintRun;
import com.example.starter.cosmetic.domain.PackagingBatch;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 包装标签印次台账持久化端口。
 *
 * <p>领用/销毁的余额扣减以带状态与版本谓词的原子 UPDATE 实现；
 * 禁用与备案换版/注销在同一事务内完成。</p>
 */
public interface LabelPrintPort {

    /** 插入一条印次台账（印次号唯一，冲突时抛数据约束异常）。 */
    void insertRun(LabelPrintRun run);

    /** 按印次号查询。 */
    Optional<LabelPrintRun> findRun(String printRunNo);

    /** 按印次号查询并锁定行（FOR UPDATE），供领用/销毁在事务内重查状态与版本。 */
    Optional<LabelPrintRun> findRunForUpdate(String printRunNo);

    /**
     * 原子领用扣减：仅当版本匹配且未领余额充足时，已领张数 += quantity、版本号 +1。
     *
     * @return 受影响行数（1=成功；0=版本或余额竞争失败，调用方重查后判定）
     */
    int applyIssue(String printRunNo, int expectedVersion, int quantity, Instant now);

    /**
     * 原子销毁登记：仅当版本匹配且未领余额充足时，已毁张数 += quantity、版本号 +1。
     * ACTIVE 与 DISABLED 印次均允许销毁未领剩余。
     *
     * @return 受影响行数（1=成功；0=版本或余额竞争失败，调用方重查后判定）
     */
    int applyDestruction(String printRunNo, int expectedVersion, int quantity, Instant now);

    /** 插入领用凭据，返回生成的凭据主键。 */
    long insertIssue(LabelIssue issue);

    /** 插入销毁登记，返回生成的登记主键。 */
    long insertDestruction(LabelDestruction destruction);

    /** 查询印次的全部领用凭据（按主键升序，可追溯）。 */
    List<LabelIssue> findIssuesByRun(String printRunNo);

    /** 查询包装批的全部领用凭据（按主键升序，可追溯）。 */
    List<LabelIssue> findIssuesByBatch(String batchNo);

    /** 查询印次的全部销毁登记（按主键升序）。 */
    List<LabelDestruction> findDestructionsByRun(String printRunNo);

    /** 查询包装批登记。 */
    Optional<PackagingBatch> findBatch(String batchNo);

    /** 登记包装批所属产品（首次领用时）。 */
    void insertBatch(String batchNo, String productCode, Instant now);

    /**
     * 原子禁用指定备案编号的全部 ACTIVE 印次：状态置 DISABLED、版本号 +1、记录原因与时刻。
     * 与备案换版/注销同事务调用；禁用为终态，不提供复活。
     *
     * @return 本次禁用的印次数量
     */
    int disableActiveRunsByFiling(String filingNo, String reason, Instant now);

    /**
     * 为指定备案编号印次的全部已领凭据追加风险关联（仅未标记的，幂等可重入），不抹历史。
     *
     * @return 本次追加风险关联的凭据数量
     */
    int flagRiskByFiling(String filingNo, String note, Instant now);
}
