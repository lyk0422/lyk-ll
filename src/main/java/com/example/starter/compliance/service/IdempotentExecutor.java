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
 * requestId 幂等执行器（业务、版本与幂等结果同事务）：
 * 指纹覆盖 requestId 与全部业务字段，先插入占位行（status_code=0）抢占 requestId，
 * 执行业务动作后回填首次响应；同键同参返回首次结果（含首次状态码），
 * 同键异参返回 409；业务动作抛错时由外层事务回滚，占位行一并回滚，失败不占键。
 *
 * <p>调用方方法必须为 {@code @Transactional}，保证占位行、业务写入与结果回填同提交同回滚。
 */
@Component
public class IdempotentExecutor {

    private final ComplianceRepository repository;
    private final ComplianceClock clock;

    public IdempotentExecutor(ComplianceRepository repository, ComplianceClock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** 业务动作结果：响应体 + 首次 HTTP 状态码。 */
    public record Outcome<T>(T body, int status) {
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
            return resolve(existing.get(), fingerprint, type);
        }
        try {
            repository.insertIdempotent(requestId, fingerprint, 0, null, clock.nowMillis());
        } catch (DuplicateKeyException e) {
            // 并发同键：另一事务已占位或已完成，按其记录重放或报 409。
            IdempotentRow raced = repository.findIdempotent(requestId)
                    .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT,
                            "REQUEST_IN_PROGRESS", "同一 requestId 正在处理中"));
            return resolve(raced, fingerprint, type);
        }

        Outcome<T> outcome = action.get();
        repository.completeIdempotent(requestId, outcome.status(),
                JsonSupport.write(outcome.body()));
        return new ServiceResult<>(outcome.body(), false, outcome.status());
    }

    private <T> ServiceResult<T> resolve(IdempotentRow row, String fingerprint, Class<T> type) {
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

    /**
     * 结构化指纹：规范化 JSON 递归按字段名排序、集合保持有序序列，
     * 使合法文本即使包含分隔符（如逗号、竖线）也不会与多值集合混淆。
     */
    public static String fingerprint(String requestId, Object params) {
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
}
