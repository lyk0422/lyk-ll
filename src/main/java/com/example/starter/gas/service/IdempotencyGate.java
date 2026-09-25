package com.example.starter.gas.service;

import com.example.starter.gas.repo.GasRepository;
import org.springframework.stereotype.Component;

/**
 * requestId 幂等门控：业务事务开始前先在独立事务中占位，
 * 使并发同键请求在触碰任何业务数据之前被串行化。
 *
 * <ul>
 *   <li>NEW：本键首次出现，占位成功，调用方继续执行业务；</li>
 *   <li>SAME：同键同参，等待首发事务提交后取首次响应快照；</li>
 *   <li>DIFF：同键异参，调用方应直接返回 409，不得改动现场。</li>
 * </ul>
 *
 * <p>本类自身不持有事务，真正的独立事务由 {@link IdempotencyGateTx} 提供，
 * 以保证跨 Bean 调用时代理生效、唯一键冲突只回滚占位插入。
 */
@Component
public class IdempotencyGate {

    /** 并发等待首发事务提交的最长时间（毫秒），防止无限挂起。 */
    private static final long WAIT_TIMEOUT_MS = 20_000L;
    private static final long POLL_INTERVAL_MS = 20L;

    private final IdempotencyGateTx tx;

    public IdempotencyGate(IdempotencyGateTx tx) {
        this.tx = tx;
    }

    /** 门控判定结果。 */
    public sealed interface Attempt permits Attempt.NewAttempt, Attempt.SameAttempt, Attempt.DiffAttempt {
        record NewAttempt() implements Attempt {
        }

        /**
         * @param responseBody 首次响应快照；首发仍在进行时为 null
         */
        record SameAttempt(String responseBody) implements Attempt {
        }

        record DiffAttempt() implements Attempt {
        }
    }

    /**
     * 门控入口不开事务：占位与读取各自使用独立事务，
     * 避免唯一键冲突污染业务事务后继续读取。
     */
    public Attempt begin(String requestId, String requestType, String fingerprint) {
        if (tx.tryInsertPending(requestId, requestType, fingerprint)) {
            return new Attempt.NewAttempt();
        }
        GasRepository.IdempotentRow existing = tx.readFresh(requestId);
        if (existing == null) {
            // 占位行恰被首发回滚清理：立即重试一次占位
            if (tx.tryInsertPending(requestId, requestType, fingerprint)) {
                return new Attempt.NewAttempt();
            }
            existing = tx.readFresh(requestId);
            if (existing == null) {
                throw new IllegalStateException("requestId=" + requestId + " 占位状态无法确定");
            }
        }
        if (!existing.fingerprint().equals(fingerprint)) {
            return new Attempt.DiffAttempt();
        }
        return new Attempt.SameAttempt(existing.responseBody());
    }

    /**
     * 同键同参重放且首发事务尚未提交时，轮询等待首次响应快照出现。
     *
     * @return 首次响应快照；若等待期间首发事务回滚并清理了占位，则返回 null，调用方应重新门控
     */
    public String awaitFirstResponse(String requestId) {
        long deadline = System.currentTimeMillis() + WAIT_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            GasRepository.IdempotentRow row = tx.readFresh(requestId);
            if (row == null) {
                // 占位已被首发回滚清理：本键可重新受理
                return null;
            }
            if (row.responseBody() != null) {
                return row.responseBody();
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待首次响应时被中断", ex);
            }
        }
        throw new IllegalStateException("等待 requestId=" + requestId + " 首次响应超时");
    }

    /** 业务事务回滚时，在独立事务中清理占位，避免后续请求误判为重放。 */
    public void abort(String requestId) {
        tx.abort(requestId);
    }
}
