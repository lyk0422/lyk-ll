package com.example.starter.cosmetic.child.port;

import java.time.Instant;
import java.util.Optional;

/**
 * 儿童写操作 requestId 幂等端口。
 *
 * <p>主张(claim)与业务处理在同一事务内：先插主张行占用 requestId，业务完成后回填首次
 * 冻结结果；业务失败整体回滚，主张行一并回滚（失败不占键）。并发同键插入触发主键冲突
 * 后重读判定重放或 409。</p>
 */
public interface ChildIdempotencyPort {

    /** 查询已存在的幂等记录。 */
    Optional<ChildIdempotencyRow> find(String requestId);

    /**
     * 插入幂等主张（响应暂为空串）。
     *
     * @throws org.springframework.dao.DuplicateKeyException 同键主张已存在（含并发）
     */
    void claim(String requestId, String operation, String fingerprint);

    /** 业务处理完成后回填首次冻结结果快照。 */
    void complete(String requestId, int httpStatus, String outcome,
                  String filingNo, String responseJson, Instant now);
}
