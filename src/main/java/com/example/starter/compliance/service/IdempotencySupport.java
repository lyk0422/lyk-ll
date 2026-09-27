package com.example.starter.compliance.service;

import com.example.starter.compliance.ApiException;
import com.example.starter.compliance.repo.ComplianceRepository;
import com.example.starter.compliance.repo.IdempotentRow;
import com.example.starter.compliance.support.JsonSupport;
import com.example.starter.compliance.time.ComplianceClock;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * requestId 幂等执行器（新旧写操作共用）：指纹覆盖 requestId 与全部业务字段。
 * 先插入占位行（status_code=0）抢占 requestId，执行业务动作后回填首次响应；
 * 同键同参返回首次结果（含首次状态码），同键异参返回 409；
 * 业务动作抛错时由调用方事务回滚，占位行一并回滚，客户端可用同一 requestId 重试（失败不占键）。
 *
 * <p>指纹为规范化结构化 JSON 的 SHA-256：字段递归字典序、数组保序、BigDecimal 去尾随零，
 * 因此合法文本即使含分隔符或 JSON 元字符也不会与结构化报文混淆。
 */
@Component
public class IdempotencySupport {

    private final ComplianceRepository repository;
    private final ComplianceClock clock;

    public IdempotencySupport(ComplianceRepository repository, ComplianceClock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public <T> ServiceResult<T> execute(String requestId, Object params, Class<T> type,
            Supplier<Outcome<T>> action) {
        if (requestId == null || requestId.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "REQUEST_ID_REQUIRED",
                    "变更接口必须提供 requestId");
        }
        String fingerprint = fingerprint(requestId, params);
        Optional<IdempotentRow> existing = repository.findIdempotent(requestId);
        if (existing.isPresent()) {
            return resolveIdempotent(existing.get(), fingerprint, type);
        }
        try {
            repository.insertIdempotent(requestId, fingerprint, 0, null, clock.nowMillis());
        } catch (DuplicateKeyException e) {
            // 并发同键：另一事务已占位或已完成，按其记录重放或报 409。
            IdempotentRow raced = repository.findIdempotent(requestId)
                    .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT,
                            "REQUEST_IN_PROGRESS", "同一 requestId 正在处理中"));
            return resolveIdempotent(raced, fingerprint, type);
        }

        Outcome<T> outcome = action.get();
        repository.completeIdempotent(requestId, outcome.status(),
                JsonSupport.write(outcome.body()));
        return new ServiceResult<>(outcome.body(), false, outcome.status());
    }

    private <T> ServiceResult<T> resolveIdempotent(IdempotentRow row, String fingerprint,
            Class<T> type) {
        if (!row.fingerprint().equals(fingerprint)) {
            throw new ApiException(HttpStatus.CONFLICT, "REQUEST_PARAM_CONFLICT",
                    "requestId 已用于不同业务参数");
        }
        if (row.statusCode() == 0 || row.responseJson() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "REQUEST_IN_PROGRESS",
                    "同一 requestId 的请求正在处理中");
        }
        return ServiceResult.replay(JsonSupport.readReplayed(row.responseJson(), type),
                row.statusCode());
    }

    private static String fingerprint(String requestId, Object params) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("requestId", requestId);
        material.put("params", params);
        String canonical = JsonSupport.canonical(material);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 业务动作结果：响应体 + 首次 HTTP 状态码（逾期自动暂停等场景首次结果即 409）。 */
    public record Outcome<T>(T body, int status) {
    }
}
