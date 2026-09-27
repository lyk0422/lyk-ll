package com.example.starter.fermentation.service;

import com.example.starter.fermentation.error.IdempotencyConflictException;
import com.example.starter.fermentation.repository.IdempotencyRepository;
import com.example.starter.fermentation.repository.IdempotencyRepository.IdempotencyRecord;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 幂等受理：指纹覆盖 requestId 与全部业务字段。
 * 同键同参返回首次结果；同键异参返回 409 且不改变任何业务状态；
 * 仅成功（2xx）结果落库，失败请求可修正后重试。
 */
@Service
public class IdempotencyService {

    /**
     * 幂等响应：HTTP 状态码 + JSON 响应体。
     */
    public record StoredResponse(int status, String body) {
    }

    private final IdempotencyRepository repository;
    private final Clock clock;

    public IdempotencyService(IdempotencyRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * 计算请求指纹：SHA-256(长度前缀编码(endpoint, requestId, 业务字段...))。
     * 每个字段以 "长度:内容;" 编码，自描述无歧义：字段内容含 '|'、':'、';' 等
     * 分隔符时仍能与相邻字段严格区分。
     */
    public String fingerprint(String endpoint, String requestId, String... businessFields) {
        StringBuilder canonical = new StringBuilder();
        appendField(canonical, endpoint);
        appendField(canonical, requestId);
        for (String field : businessFields) {
            appendField(canonical, field);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static void appendField(StringBuilder canonical, String field) {
        canonical.append(field.length()).append(':').append(field).append(';');
    }

    /**
     * 幂等执行：与调用方同一事务，业务写入与受理记录同时提交或回滚。
     *
     * @param requestId   幂等请求键
     * @param fingerprint 请求指纹
     * @param action      首次受理时执行的业务动作，返回状态码与 JSON 响应体
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public StoredResponse execute(String requestId, String endpoint, String fingerprint,
                                  Supplier<StoredResponse> action) {
        Optional<IdempotencyRecord> existing = repository.findByRequestId(requestId);
        if (existing.isPresent()) {
            return replay(requestId, existing.get(), fingerprint);
        }
        StoredResponse response = action.get();
        try {
            repository.insert(requestId, endpoint, fingerprint, response.status(), response.body(),
                    LocalDateTime.now(clock));
        } catch (DuplicateKeyException e) {
            // 并发下同键请求已被另一事务受理：回滚本次业务写入，按首次结果裁决
            throw new ConcurrentReplayException(requestId, fingerprint);
        }
        return response;
    }

    /**
     * 并发冲突后的裁决：在独立新事务中重读首次受理记录（调用方需捕获 ConcurrentReplayException 后调用）。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public StoredResponse resolveConcurrent(String requestId, String fingerprint) {
        return repository.findByRequestId(requestId)
                .map(record -> replay(requestId, record, fingerprint))
                .orElseThrow(() -> new IllegalStateException(
                        "requestId '" + requestId + "' 并发受理记录不存在，请重试"));
    }

    private StoredResponse replay(String requestId, IdempotencyRecord record, String fingerprint) {
        if (!record.fingerprint().equals(fingerprint)) {
            throw new IdempotencyConflictException(requestId);
        }
        return new StoredResponse(record.responseStatus(), record.responseBody());
    }

    /**
     * 并发下同键请求冲突的内部信号，触发当前事务回滚后由调用方重读裁决。
     */
    public static class ConcurrentReplayException extends RuntimeException {

        private final String requestId;
        private final String fingerprint;

        ConcurrentReplayException(String requestId, String fingerprint) {
            super("requestId '" + requestId + "' 并发受理冲突");
            this.requestId = requestId;
            this.fingerprint = fingerprint;
        }

        public String requestId() {
            return requestId;
        }

        public String fingerprint() {
            return fingerprint;
        }
    }
}
