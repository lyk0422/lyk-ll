package com.example.starter.service;

import com.example.starter.domain.IdempotencyRow;
import com.example.starter.repo.IdempotencyRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.function.Supplier;

/**
 * 幂等受理: 指纹覆盖 requestId 与全部业务字段; 同键同参返回首次结果, 同键异参返回 409 且不改变作业状态。
 * 受理记录与业务写入在同一事务内提交, 业务失败时受理占位一并回滚。
 */
@Service
public class IdempotencyService {

    private final IdempotencyRepository repository;
    private final ObjectMapper objectMapper;

    public IdempotencyService(IdempotencyRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * 以 requestId 为幂等键执行业务受理。
     *
     * @param endpoint 业务端点标识
     * @param requestId 幂等键
     * @param request  业务请求(全部业务字段参与指纹计算)
     * @param supplier 业务受理逻辑
     * @return 首次受理结果或重放的首次结果
     */
    @Transactional
    public ResponseEntity<String> execute(String endpoint, String requestId, Object request,
                                          Supplier<ApiResult> supplier) {
        String fingerprint = fingerprint(endpoint, requestId, request);
        try {
            repository.insertPending(requestId, endpoint, fingerprint);
        } catch (DuplicateKeyException e) {
            IdempotencyRow existing = repository.find(requestId)
                    .orElseThrow(() -> e);
            if (!existing.fingerprint().equals(fingerprint)) {
                throw new ApiException(409, "IDEMPOTENCY_CONFLICT",
                        "相同requestId携带不同业务参数, 已拒绝且不改变作业状态");
            }
            if (existing.responseStatus() == null) {
                throw new ApiException(409, "REQUEST_IN_FLIGHT", "相同请求正在处理中, 请稍后重试");
            }
            return ResponseEntity.status(existing.responseStatus())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(existing.responseBody());
        }
        ApiResult result = supplier.get();
        String body = toJson(result.body());
        repository.complete(requestId, result.status(), body);
        return ResponseEntity.status(result.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    private String fingerprint(String endpoint, String requestId, Object request) {
        String material = endpoint + '\n' + requestId + '\n' + toJson(request);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(material.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("请求序列化失败", e);
        }
    }
}
