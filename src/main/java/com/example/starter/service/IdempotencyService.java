package com.example.starter.service;

import com.example.starter.model.IdempotencyRecord;
import com.example.starter.repo.IdempotencyRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Supplier;

/**
 * 写操作幂等控制：按 requestId 去重，指纹覆盖 requestId 与全部业务字段；
 * 同键同参返回首次结果，同键异参返回 409。
 */
@Service
public class IdempotencyService {

    private static final int MAX_WAIT_ROUNDS = 100;
    private static final long WAIT_MILLIS = 50;

    private final IdempotencyRepository repository;
    private final TransactionTemplate txTemplate;

    public IdempotencyService(IdempotencyRepository repository, TransactionTemplate txTemplate) {
        this.repository = repository;
        this.txTemplate = txTemplate;
    }

    /**
     * 计算请求指纹：requestId 与全部业务字段拼接后的 SHA-256。
     *
     * @param parts 依次传入 requestId 与全部业务字段的规范字符串
     */
    public static String fingerprint(String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(String.join("|", parts).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * 在单事务内执行写操作并记录首次响应；并发同键请求等待首次结果后原样返回。
     *
     * @param endpoint    接口标识
     * @param requestId   幂等请求标识
     * @param fingerprint 请求指纹
     * @param action      业务动作，返回序列化后的响应
     */
    public ResponseEntity<String> execute(String endpoint, String requestId, String fingerprint,
                                          Supplier<ResponseEntity<String>> action) {
        try {
            return txTemplate.execute(status -> {
                repository.insertPending(requestId, endpoint, fingerprint);
                ResponseEntity<String> response = action.get();
                repository.complete(requestId, response.getStatusCode().value(), response.getBody());
                return response;
            });
        } catch (DuplicateKeyException e) {
            return resolveExisting(requestId, fingerprint);
        }
    }

    private ResponseEntity<String> resolveExisting(String requestId, String fingerprint) {
        for (int i = 0; i < MAX_WAIT_ROUNDS; i++) {
            var existing = repository.findByRequestId(requestId);
            if (existing.isPresent()) {
                IdempotencyRecord record = existing.get();
                if (!record.fingerprint().equals(fingerprint)) {
                    throw ApiException.conflict("相同 requestId 的业务参数不一致");
                }
                if (record.completed()) {
                    return ResponseEntity.status(record.responseStatus())
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(record.responseBody());
                }
            }
            sleep();
        }
        throw ApiException.conflict("相同 requestId 的请求正在处理中，请稍后重试");
    }

    private void sleep() {
        try {
            Thread.sleep(WAIT_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ApiException.conflict("等待幂等结果时被中断");
        }
    }
}
