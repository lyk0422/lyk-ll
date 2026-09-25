package com.example.starter.service;

import com.example.starter.api.ApiException;
import com.example.starter.repo.IdempotencyDao;
import com.example.starter.repo.IdempotencyDao.IdemRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 幂等受理：指纹覆盖 requestId 与全部业务字段；同键同参返回首次结果，同键异参返回 409。
 * 受理记录与业务写入在同一事务内提交，业务失败时受理记录一并回滚。
 */
@Service
public class IdempotencyService {

    private final IdempotencyDao dao;
    private final ObjectMapper objectMapper;

    public IdempotencyService(IdempotencyDao dao, ObjectMapper objectMapper) {
        this.dao = dao;
        this.objectMapper = objectMapper;
    }

    /**
     * 以 requestId 幂等执行业务动作。
     *
     * @param requestId    幂等请求 ID
     * @param endpoint     接口标识（参与指纹计算）
     * @param request      请求报文（全部业务字段参与指纹计算）
     * @param responseType 响应类型
     * @param action       首次受理时执行的业务动作
     */
    public <T> T execute(String requestId, String endpoint, Object request,
                         Class<T> responseType, Supplier<T> action) {
        String fingerprint = fingerprint(endpoint, request);
        Optional<IdemRow> existing = dao.find(requestId);
        if (existing.isPresent()) {
            return replay(requestId, fingerprint, existing.get(), responseType);
        }
        T result = action.get();
        try {
            dao.insert(requestId, endpoint, fingerprint, 200, writeJson(result));
        } catch (DuplicateKeyException concurrent) {
            // 同键并发：以已提交的首次受理为准
            IdemRow row = dao.find(requestId)
                    .orElseThrow(() -> concurrent);
            return replay(requestId, fingerprint, row, responseType);
        }
        return result;
    }

    private <T> T replay(String requestId, String fingerprint, IdemRow row, Class<T> responseType) {
        if (!row.fingerprint().equals(fingerprint)) {
            throw ApiException.idempotencyConflict(requestId);
        }
        try {
            return objectMapper.readValue(row.responseBody(), responseType);
        } catch (Exception e) {
            throw new IllegalStateException("幂等记录响应反序列化失败: " + requestId, e);
        }
    }

    private String fingerprint(String endpoint, Object request) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String material = endpoint + "|" + writeJson(request);
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
