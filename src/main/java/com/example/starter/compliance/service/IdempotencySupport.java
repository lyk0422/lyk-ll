package com.example.starter.compliance.service;

import com.example.starter.compliance.ApiException;
import com.example.starter.compliance.repo.ComplianceRepository;
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
 * requestId 幂等执行器，语义与 {@link ComplianceService} 内既有写接口完全一致：
 * 指纹覆盖 requestId 与全部业务字段（规范化 JSON，去 BigDecimal 尾随零、键字典序排序），
 * 先插占位行（status_code=0）抢占 requestId，业务成功后回填首次响应；
 * 同键同参重放首次冻结结果（含首次状态码），同键异参 409，业务抛错时外层事务回滚、失败不占键。
 *
 * <p>必须在调用方事务内执行，保证业务写入、版本推进与幂等记录同事务提交或回滚。
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
        Optional<com.example.starter.compliance.repo.IdempotentRow> existing =
                repository.findIdempotent(requestId);
        if (existing.isPresent()) {
            return resolve(existing.get(), fingerprint, type);
        }
        try {
            repository.insertIdempotent(requestId, fingerprint, 0, null, clock.nowMillis());
        } catch (DuplicateKeyException e) {
            com.example.starter.compliance.repo.IdempotentRow raced =
                    repository.findIdempotent(requestId)
                            .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT,
                                    "REQUEST_IN_PROGRESS", "同一 requestId 正在处理中"));
            return resolve(raced, fingerprint, type);
        }

        Outcome<T> outcome = action.get();
        repository.completeIdempotent(requestId, outcome.status(),
                JsonSupport.write(outcome.body()));
        return new ServiceResult<>(outcome.body(), false, outcome.status());
    }

    private <T> ServiceResult<T> resolve(
            com.example.starter.compliance.repo.IdempotentRow row, String fingerprint,
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

    static String fingerprint(String requestId, Object params) {
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

    /** 业务动作结果：响应体 + 首次 HTTP 状态码。 */
    public record Outcome<T>(T body, int status) {
        public static <T> Outcome<T> ok(T body) {
            return new Outcome<>(body, HttpStatus.OK.value());
        }
    }
}
