package com.example.starter.cosmetic.port;

import java.util.Optional;

/**
 * requestId 幂等记录端口。
 *
 * <p>主张(claim)与业务处理在同一事务内：先插入主张行占用 requestId，
 * 业务完成后回填首次响应；并发同键插入触发主键冲突后重新读取判定重放或 409。</p>
 */
public interface IdempotencyPort {

    /** 查询已存在的幂等记录。 */
    Optional<IdempotencyRow> find(String requestId);

    /**
     * 插入幂等主张（response 暂为空串）。
     *
     * @throws org.springframework.dao.DuplicateKeyException 同键主张已存在（含并发）
     */
    void claim(String requestId, String operation, String fingerprint);

    /** 业务处理完成后回填首次结果快照。 */
    void complete(String requestId, int httpStatus, String outcome,
                  String filingNo, String responseJson, java.time.Instant now);
}
