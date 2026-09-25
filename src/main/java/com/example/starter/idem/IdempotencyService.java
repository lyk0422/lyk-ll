package com.example.starter.idem;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Supplier;

import com.example.starter.error.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 幂等服务：以 requestId 为键，指纹覆盖 requestId 与全部业务字段；
 * 同键同参返回首次结果，同键异参返回 409。
 */
@Service
public class IdempotencyService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public IdempotencyService(JdbcTemplate jdbc, ObjectMapper objectMapper, Clock clock) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** 业务执行结果：HTTP 状态码与响应体。 */
    public record ApiResult(int status, Object body) {
    }

    /**
     * 计算请求指纹：SHA-256(requestId + 业务字段串)。
     */
    public String fingerprint(String requestId, String canonicalFields) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((requestId + "|" + canonicalFields).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 幂等执行：同键同参直接返回首次响应；同键异参抛 409；否则执行并留痕。
     * 调用方须处于事务内，留痕与业务写入同生共死。
     */
    public ResponseEntity<String> handle(String requestId, String fingerprint,
                                         Supplier<ApiResult> action) {
        if (requestId == null || requestId.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "REQUEST_ID_REQUIRED", "requestId 不能为空");
        }
        List<StoredResponse> existing = find(requestId);
        if (!existing.isEmpty()) {
            StoredResponse stored = existing.get(0);
            if (!stored.fingerprint().equals(fingerprint)) {
                throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
                        "相同 requestId 的业务参数不一致");
            }
            return ResponseEntity.status(stored.status())
                    .contentType(MediaType.APPLICATION_JSON).body(stored.body());
        }
        ApiResult result = action.get();
        String body = toJson(result.body());
        try {
            jdbc.update("INSERT INTO idempotency_record "
                            + "(request_id, fingerprint, response_status, response_body, created_at) "
                            + "VALUES (?,?,?,?,?)",
                    requestId, fingerprint, result.status(), body, Timestamp.from(clock.instant()));
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
                    "相同 requestId 的请求正在并发处理");
        }
        return ResponseEntity.status(result.status())
                .contentType(MediaType.APPLICATION_JSON).body(body);
    }

    private List<StoredResponse> find(String requestId) {
        return jdbc.query("SELECT fingerprint, response_status, response_body "
                        + "FROM idempotency_record WHERE request_id = ?",
                (rs, rowNum) -> new StoredResponse(
                        rs.getString("fingerprint"), rs.getInt("response_status"),
                        rs.getString("response_body")),
                requestId);
    }

    private String toJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    private record StoredResponse(String fingerprint, int status, String body) {
    }
}
