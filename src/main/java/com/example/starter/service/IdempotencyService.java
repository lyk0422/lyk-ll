package com.example.starter.service;

import com.example.starter.repo.IdempotencyRepository;
import com.example.starter.web.BusinessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 写操作幂等服务：指纹覆盖 requestId 与全部业务字段；
 * 同键同参返回首次结果，同键异参返回 409。
 */
@Service
public class IdempotencyService {

    private final IdempotencyRepository repository;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    /** JVM 内按 requestId 分段加锁，配合数据库唯一约束兜底并发 */
    private final Map<String, Object> stripes = new ConcurrentHashMap<>();

    public IdempotencyService(IdempotencyRepository repository, TransactionTemplate transactionTemplate,
                              Clock clock) {
        this.repository = repository;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    /**
     * 幂等执行写操作。canonicalPayload 为业务字段的规范化串（含全部业务字段）。
     *
     * @return 首次执行的响应报文；同键同参重放时返回首次报文
     * @throws BusinessException 同键异参时抛出 409
     */
    public String execute(String requestId, String endpoint, String canonicalPayload, Supplier<String> action) {
        String fingerprint = sha256(requestId + "|" + endpoint + "|" + canonicalPayload);
        Object stripe = stripes.computeIfAbsent(requestId, key -> new Object());
        synchronized (stripe) {
            return transactionTemplate.execute(status -> {
                var existing = repository.find(requestId);
                if (existing.isPresent()) {
                    IdempotencyRepository.IdemRecord record = existing.get();
                    if (record.fingerprint().equals(fingerprint)) {
                        return record.responseBody();
                    }
                    throw new BusinessException(HttpStatus.CONFLICT,
                            "requestId 已使用且业务参数不一致");
                }
                String body = action.get();
                try {
                    repository.insert(requestId, fingerprint, endpoint, body, LocalDateTime.now(clock));
                } catch (DuplicateKeyException e) {
                    // 并发兜底：唯一约束冲突后回读首次记录
                    IdempotencyRepository.IdemRecord record = repository.find(requestId)
                            .orElseThrow(() -> e);
                    if (record.fingerprint().equals(fingerprint)) {
                        return record.responseBody();
                    }
                    throw new BusinessException(HttpStatus.CONFLICT,
                            "requestId 已使用且业务参数不一致");
                }
                return body;
            });
        }
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
