package com.example.starter.core;

import com.example.starter.api.dto.IdempotentEnvelope;
import java.util.function.Supplier;

/**
 * requestId 幂等支撑：指纹覆盖 requestId 与全部业务字段。
 * 同键同参返回首次结果；同键异参返回 409 且不改变执行参数。
 * 并发同键由数据库主键约束保证唯一受理，业务动作至多执行一次。
 */
public interface IdempotencyService {

    /**
     * 在幂等保护下执行业务受理。
     *
     * @param scope       业务域（如 DECLARE_PLAN / CONFIRM_COMMAND）
     * @param requestId   幂等键
     * @param fingerprint 业务指纹（覆盖全部业务字段的规范化串）
     * @param resultType  首次响应类型，用于快照反序列化回放
     * @param supplier    首次受理时执行的业务动作
     * @return 包装结果，replayed=true 表示命中首次记录
     */
    <T> IdempotentEnvelope<T> execute(String scope, String requestId, String fingerprint,
                                      Class<T> resultType, Supplier<T> supplier);
}
