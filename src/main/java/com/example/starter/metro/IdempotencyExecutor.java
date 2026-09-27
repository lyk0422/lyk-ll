package com.example.starter.metro;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;
import java.util.function.Supplier;

import com.example.starter.metro.MetroRepository.IdemRow;
import com.example.starter.metro.dto.ReplayableResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 写操作幂等执行器：事务开头插入幂等占位（并发同键在此阻塞或冲突），
 * 业务、版本与幂等结果在同一事务提交；失败整体回滚、不占键。
 * 同键同参重放首次冻结结果，同键异参返回 409。
 */
@Component
public class IdempotencyExecutor {

    private final MetroRepository repository;
    private final TransactionTemplate txTemplate;
    private final ObjectMapper objectMapper;

    public IdempotencyExecutor(MetroRepository repository,
                               PlatformTransactionManager transactionManager,
                               ObjectMapper objectMapper) {
        this.repository = repository;
        this.txTemplate = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
    }

    /**
     * 在幂等保护下执行写操作。
     *
     * @param scope       操作类型
     * @param requestId   幂等键
     * @param fingerprint 结构化指纹（覆盖 requestId 与全部业务参数）
     * @param type        响应类型
     * @param action      业务动作
     * @param <T>         响应类型参数
     * @return 首次执行结果或重放结果
     */
    public <T extends ReplayableResponse<T>> T execute(String scope, String requestId,
                                                       String fingerprint, Class<T> type,
                                                       Supplier<T> action) {
        Optional<IdemRow> existing = repository.findIdem(scope, requestId);
        if (existing.isPresent()) {
            return replay(existing.get(), fingerprint, type);
        }
        try {
            return txTemplate.execute(status -> {
                // 事务开头占位：并发同键在此阻塞或冲突，随后走重放路径
                repository.insertIdemPlaceholder(scope, requestId, fingerprint);
                T response = action.get();
                repository.updateIdemResponse(scope, requestId, toJson(response));
                return response;
            });
        } catch (DuplicateKeyException ex) {
            // 并发同键：以首次提交为准
            return repository.findIdem(scope, requestId)
                    .map(row -> replay(row, fingerprint, type))
                    .orElseThrow(() -> MetroException.conflict("CONCURRENT_WRITE_CONFLICT",
                            "并发写入冲突，请重试"));
        }
    }

    private <T extends ReplayableResponse<T>> T replay(IdemRow row, String fingerprint, Class<T> type) {
        if (!row.fingerprint().equals(fingerprint)) {
            throw MetroException.conflict("IDEMPOTENCY_CONFLICT",
                    "相同 requestId 但业务参数不一致，拒绝重复入账");
        }
        try {
            return objectMapper.readValue(row.responseBody(), type).asReplayed();
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("幂等响应反序列化失败", ex);
        }
    }

    private String toJson(Object response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("幂等响应序列化失败", ex);
        }
    }

    /**
     * 结构化指纹：长度前缀编码，字段含任意分隔符也可无歧义区分；null 以 -1 长度标记。
     */
    public static String fingerprint(String scope, String... parts) {
        StringBuilder joined = new StringBuilder(scope);
        for (String part : parts) {
            if (part == null) {
                joined.append("#-1:");
            } else {
                joined.append('#').append(part.length()).append(':').append(part);
            }
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(joined.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }
}
