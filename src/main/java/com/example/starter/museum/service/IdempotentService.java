package com.example.starter.museum.service;

import com.example.starter.museum.domain.po.IdempotentRequestPo;
import com.example.starter.museum.repo.IdempotentRepository;
import com.example.starter.museum.support.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Supplier;

/**
 * requestId 幂等执行器。
 *
 * <p>指纹覆盖 requestId 与全部业务字段（规范化 JSON 后 SHA-256）：
 * 同键同参返回首次结果（含状态码与响应体），同键异参返回 409。
 * 占位记录与业务写入处于同一事务：业务校验失败整体回滚，不吞掉 requestId；
 * 并发同键由主键唯一约束串行化，后到者回放首次响应。
 */
@Service
public class IdempotentService {

    private final IdempotentRepository repository;
    private final ObjectMapper objectMapper;

    public IdempotentService(IdempotentRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public <T> T execute(String requestId, Object request, Class<T> responseType, Supplier<T> action) {
        String fingerprint = fingerprint(requestId, request);
        IdempotentRepository.Occupy occupy = repository.occupy(requestId, fingerprint);
        if (!occupy.acquired()) {
            IdempotentRequestPo po = occupy.existing();
            if (!fingerprint.equals(po.fingerprint)) {
                throw ApiException.conflict("requestId=" + requestId + " 已用于不同业务参数");
            }
            if (po.responseJson == null) {
                // 极端并发：首个请求事务尚未提交结果，短暂等待后重读
                throw ApiException.conflict("requestId=" + requestId + " 正在处理中，请重试");
            }
            return readValue(po.responseJson, responseType);
        }
        T result = action.get();
        repository.saveResult(requestId, 200, writeValue(result));
        return result;
    }

    private String fingerprint(String requestId, Object request) {
        String canonical = requestId + "|" + writeValue(request);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String writeValue(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    private <T> T readValue(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("首次响应反序列化失败", e);
        }
    }
}
