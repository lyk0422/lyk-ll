package com.example.starter.service;

import com.example.starter.repo.IdempotencyRepository;
import com.example.starter.repo.IdempotencyRepository.IdempotencyRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Supplier;

/**
 * 幂等执行器：指纹覆盖 requestId 与全部业务字段；同键同参返回首次结果快照，
 * 同键异参返回 409 且不改变作业状态。幂等记录与业务写入同事务提交。
 */
@Service
public class IdempotencyService {

    private final IdempotencyRepository repository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public IdempotencyService(IdempotencyRepository repository, ObjectMapper objectMapper,
                              TransactionTemplate transactionTemplate) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 幂等执行操作。
     *
     * @param requestId    幂等请求键
     * @param operation    操作类型
     * @param request      业务请求（指纹覆盖其全部字段）
     * @param responseType 响应类型
     * @param action       业务动作
     * @return 首次执行结果或重放的首次结果快照
     */
    public <T> T execute(String requestId, String operation, Object request,
                         Class<T> responseType, Supplier<T> action) {
        String fingerprint = fingerprint(operation, requestId, request);
        var existing = repository.findByRequestId(requestId);
        if (existing.isPresent()) {
            return replay(existing.get(), operation, fingerprint, responseType);
        }
        try {
            return transactionTemplate.execute(status -> {
                repository.insert(requestId, operation, fingerprint, "{}");
                T result = action.get();
                repository.updateResponseBody(requestId, writeJson(result));
                return result;
            });
        } catch (DuplicateKeyException e) {
            var row = repository.findByRequestId(requestId);
            if (row.isEmpty()) {
                throw e;
            }
            return replay(row.get(), operation, fingerprint, responseType);
        }
    }

    private <T> T replay(IdempotencyRow row, String operation, String fingerprint, Class<T> responseType) {
        if (!row.operation().equals(operation) || !row.fingerprint().equals(fingerprint)) {
            throw new ConflictException("幂等键冲突：同键异参，不改变作业状态");
        }
        try {
            return objectMapper.readValue(row.responseBody(), responseType);
        } catch (Exception e) {
            throw new IllegalStateException("幂等快照反序列化失败", e);
        }
    }

    private String fingerprint(String operation, String requestId, Object request) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            // 结构化指纹材料：JSON 数组包装，字段边界由 JSON 字符串转义保证，
            // 合法文本含分隔符（如换行）也不会与结构混淆
            String material = writeJson(java.util.List.of(operation, requestId, writeJson(request)));
            return HexFormat.of().formatHex(digest.digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("请求序列化失败", e);
        }
    }
}
