package com.example.starter.rebate.service;

import com.example.starter.rebate.repo.IdempotentRow;
import com.example.starter.rebate.repo.RebateJdbcRepository;
import com.example.starter.rebate.support.BusinessConflictException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.locks.ReentrantLock;

/**
 * requestId 幂等执行器。
 * <p>
 * 指纹覆盖 requestId（作为幂等键）与全部业务字段：同键同参返回首次结果（replayed=true），
 * 同键异参抛 409 且不执行业务逻辑、事务回滚，批次状态不变。
 * <p>
 * 单 JVM 内存库下用 requestId 条带锁串行化同键并发，数据库主键为最终唯一约束；
 * 唯一约束冲突时回退为重新读取已提交结果。
 */
@Component
public class IdempotentExecutor {

    private static final int STRIPES = 32;

    private final RebateJdbcRepository repository;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final ReentrantLock[] locks;

    public IdempotentExecutor(RebateJdbcRepository repository,
                              PlatformTransactionManager transactionManager,
                              ObjectMapper objectMapper) {
        this.repository = repository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
        this.locks = new ReentrantLock[STRIPES];
        for (int i = 0; i < STRIPES; i++) {
            this.locks[i] = new ReentrantLock();
        }
    }

    /**
     * 幂等执行业务动作。
     *
     * @param requestId   幂等键
     * @param bizType     业务类型（参与指纹）
     * @param fingerprint 业务参数指纹（不含 requestId 时由本方法补入 requestId）
     * @param action      首次执行的业务动作，事务内调用
     * @param resultType  响应类型（重放时反序列化）
     * @return 首次结果或重放结果
     */
    public <T> IdemOutcome<T> execute(String requestId, String bizType, String fingerprint,
                                      IdemAction<T> action, Class<T> resultType) {
        ReentrantLock lock = locks[Math.floorMod(requestId.hashCode(), STRIPES)];
        lock.lock();
        try {
            return transactionTemplate.execute(status -> doInTransaction(
                    requestId, bizType, fingerprint, action, resultType));
        } finally {
            lock.unlock();
        }
    }

    private <T> IdemOutcome<T> doInTransaction(String requestId, String bizType, String fingerprint,
                                                IdemAction<T> action, Class<T> resultType) {
        String fullFingerprint = sha256(requestId + "|" + bizType + "|" + fingerprint);
        IdempotentRow existing = repository.findIdempotent(requestId);
        if (existing != null) {
            if (!existing.fingerprint().equals(fullFingerprint)) {
                throw new BusinessConflictException(
                        "requestId=" + requestId + " 已用于不同业务参数，拒绝重复提交");
            }
            return new IdemOutcome<>(readResponse(existing.responseJson(), resultType),
                    true, existing.statusCode());
        }

        T result = action.run();
        String json;
        try {
            json = objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            throw new IllegalStateException("幂等响应序列化失败", e);
        }
        try {
            repository.insertIdempotent(requestId, bizType, fullFingerprint, 200, json);
        } catch (DuplicateKeyException lostRace) {
            // 并发下另一事务已提交同键：重查校验指纹后重放。
            IdempotentRow winner = repository.findIdempotent(requestId);
            if (winner == null || !winner.fingerprint().equals(fullFingerprint)) {
                throw new BusinessConflictException(
                        "requestId=" + requestId + " 已用于不同业务参数，拒绝重复提交");
            }
            return new IdemOutcome<>(readResponse(winner.responseJson(), resultType),
                    true, winner.statusCode());
        }
        return new IdemOutcome<>(result, false, 200);
    }

    /** 反序列化首次响应，重放时重建 replayed=true 标志。 */
    private <T> T readResponse(String json, Class<T> type) {
        try {
            T data = objectMapper.readValue(json, type);
            if (data instanceof com.example.starter.rebate.api.ReplayableResponse<?> replayable) {
                @SuppressWarnings("unchecked")
                T rebuilt = (T) replayable.withReplayed(true);
                return rebuilt;
            }
            return data;
        } catch (Exception e) {
            throw new IllegalStateException("幂等响应反序列化失败", e);
        }
    }

    /** 对业务参数对象计算稳定指纹（规范化 JSON）。 */
    public String fingerprint(Object payload) {
        try {
            String json = objectMapper.writer().withDefaultPrettyPrinter()
                    .writeValueAsString(payload);
            return sha256(json);
        } catch (Exception e) {
            throw new IllegalStateException("指纹计算失败", e);
        }
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 事务内执行业务动作。 */
    @FunctionalInterface
    public interface IdemAction<T> {
        T run();
    }

    /**
     * 幂等执行结果。
     *
     * @param data       响应数据
     * @param replayed   是否为重放（同键同参）
     * @param statusCode 首次登记的 HTTP 状态码
     */
    public record IdemOutcome<T>(T data, boolean replayed, int statusCode) {
    }
}
