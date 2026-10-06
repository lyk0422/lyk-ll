package com.example.starter.service;

import com.example.starter.api.ApiException;
import com.example.starter.repo.IdempotencyRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/**
 * 写操作幂等控制：指纹覆盖 requestId 与全部业务字段；
 * 同键同参返回首次结果，同键异参返回 409。
 * 幂等记录与业务写入处于同一事务，保证原子提交。
 */
@Service
public class IdempotencyService {

    /** 幂等执行结果：HTTP 状态码与响应报文。 */
    public record Result(int status, String body) {
    }

    private final IdempotencyRepository repository;
    private final Clock clock;
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public IdempotencyService(IdempotencyRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * 在调用方事务内执行幂等控制与业务动作。
     *
     * @param endpoint 接口标识
     * @param requestId 幂等键
     * @param businessFields 全部业务字段（键值对，按键排序后参与指纹计算）
     * @param action 业务动作，仅在首次执行时运行
     */
    public Result execute(String endpoint, String requestId, Map<String, String> businessFields,
                          Supplier<Result> action) {
        String fingerprint = fingerprint(requestId, businessFields);
        Object lock = locks.computeIfAbsent(requestId, k -> new Object());
        synchronized (lock) {
            var existing = repository.findByRequestId(requestId);
            if (existing.isPresent()) {
                return replayOrConflict(existing.get(), fingerprint);
            }
            boolean inserted = repository.tryInsert(new IdempotencyRepository.IdempotencyRow(
                    requestId, endpoint, fingerprint, 0, "", LocalDateTime.now(clock)));
            if (!inserted) {
                // 并发事务已提交同键记录：重新读取并按指纹判定
                var row = repository.findByRequestId(requestId)
                        .orElseThrow(() -> ApiException.conflict("IDEMPOTENCY_CONFLICT", "幂等键冲突，请重试"));
                return replayOrConflict(row, fingerprint);
            }
            Result result = action.get();
            repository.updateResponse(requestId, result.status(), result.body());
            return result;
        }
    }

    private Result replayOrConflict(IdempotencyRepository.IdempotencyRow row, String fingerprint) {
        if (!row.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT",
                    "requestId 已使用且业务字段不一致");
        }
        return new Result(row.responseStatus(), row.responseBody());
    }

    /** 指纹 = SHA-256(requestId + 排序后的全部业务字段)。 */
    static String fingerprint(String requestId, Map<String, String> businessFields) {
        StringBuilder sb = new StringBuilder(requestId);
        new TreeMap<>(businessFields).forEach((k, v) -> sb.append('\n').append(k).append('=').append(v));
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
