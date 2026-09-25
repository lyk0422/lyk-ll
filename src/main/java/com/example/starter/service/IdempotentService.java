package com.example.starter.service;

import com.example.starter.dao.IdempotentRequestDao;
import com.example.starter.dao.IdempotentRequestDao.IdemRow;
import com.example.starter.support.BusinessException;
import com.example.starter.support.Fingerprints;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * requestId 幂等执行器：指纹覆盖 requestId 与全部业务字段。
 * 指纹占位与业务执行在同一事务：业务失败整体回滚；同键异参直接 409，不触碰业务状态；
 * 同键同参重放返回首次响应快照。
 */
@Service
public class IdempotentService {

    private final IdempotentRequestDao idemDao;
    private final ObjectMapper objectMapper;

    public IdempotentService(IdempotentRequestDao idemDao, ObjectMapper objectMapper) {
        this.idemDao = idemDao;
        this.objectMapper = objectMapper;
    }

    /**
     * 业务执行结果。
     */
    public record Result(String refKey, Map<String, Object> body) {
    }

    /**
     * 以 (requestId, apiType) 为幂等键执行业务；fingerprintParts 为 requestId 与全部业务字段的规范序列。
     */
    @Transactional
    public Map<String, Object> execute(String requestId, String apiType,
                                       List<String> fingerprintParts, Supplier<Result> action) {
        String fingerprint = Fingerprints.sha256(fingerprintParts);
        IdemRow existing = idemDao.find(requestId, apiType).orElse(null);
        if (existing != null) {
            if (!existing.fingerprint().equals(fingerprint)) {
                throw BusinessException.conflict(
                        "requestId=" + requestId + " 已用于不同业务参数，拒绝执行（409）");
            }
            return readSnapshot(existing);
        }
        try {
            idemDao.insert(requestId, apiType, fingerprint, null, null, 200);
        } catch (DuplicateKeyException e) {
            // 并发同键请求：读取已提交的首次记录
            IdemRow raced = idemDao.find(requestId, apiType)
                    .orElseThrow(() -> BusinessException.conflict("并发幂等键冲突，请稍后重试"));
            if (!raced.fingerprint().equals(fingerprint)) {
                throw BusinessException.conflict(
                        "requestId=" + requestId + " 已用于不同业务参数，拒绝执行（409）");
            }
            return readSnapshot(raced);
        }

        Result result = action.get();
        String doc = writeJson(result.body());
        idemDao.updateResult(requestId, apiType, result.refKey(), doc, 200);
        Map<String, Object> body = new LinkedHashMap<>(result.body());
        body.putIfAbsent("idempotentReplayed", false);
        return body;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readSnapshot(IdemRow row) {
        try {
            Map<String, Object> body = objectMapper.readValue(row.responseDoc(), Map.class);
            Map<String, Object> copy = new LinkedHashMap<>(body);
            copy.put("idempotentReplayed", true);
            return copy;
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new IllegalStateException("首次响应快照无法解析", e);
        }
    }

    private String writeJson(Map<String, Object> body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
