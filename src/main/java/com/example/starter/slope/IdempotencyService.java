package com.example.starter.slope;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Supplier;

/**
 * 幂等受理服务：以 requestId 为键，指纹覆盖 requestId 与全部业务字段。
 * 同键同参返回首次受理结果；同键异参返回409且业务动作不会执行（测区状态不变）。
 * 采用“先占位插入、后执行业务、再回写响应”的顺序：并发同键时第二个事务在占位
 * 插入处冲突并直接重放首次结果，业务动作不会被重复执行。
 * 幂等记录与业务写入处于同一事务，业务回滚时幂等记录一并回滚。
 */
@Service
public class IdempotencyService {

    /** 一次受理的响应：HTTP 状态码与 JSON 报文。 */
    public record Stored(int status, String body) {
    }

    private record Row(String fingerprint, int status, String body) {
    }

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public IdempotencyService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 幂等执行。
     *
     * @param endpoint 受理端点标识
     * @param requestId 幂等键
     * @param request 请求报文（指纹覆盖其全部字段）
     * @param action 业务动作，仅在首次受理时执行
     * @return 首次受理的响应（重放时返回历史结果）
     */
    public Stored execute(String endpoint, String requestId, Object request, Supplier<Stored> action) {
        String fingerprint = fingerprint(endpoint, requestId, request);
        Row existing = findRow(requestId);
        if (existing != null) {
            return replayOrConflict(requestId, fingerprint, existing);
        }
        try {
            jdbcTemplate.update(
                    "INSERT INTO idempotency_record (request_id, endpoint, fingerprint, response_status, response_body) "
                            + "VALUES (?, ?, ?, 0, '')",
                    requestId, endpoint, fingerprint);
        } catch (DuplicateKeyException duplicate) {
            Row raced = findRow(requestId);
            if (raced != null) {
                return replayOrConflict(requestId, fingerprint, raced);
            }
            throw duplicate;
        }
        Stored result = action.get();
        jdbcTemplate.update(
                "UPDATE idempotency_record SET response_status = ?, response_body = ? WHERE request_id = ?",
                result.status(), result.body(), requestId);
        return result;
    }

    private Stored replayOrConflict(String requestId, String fingerprint, Row existing) {
        if (fingerprint.equals(existing.fingerprint())) {
            return new Stored(existing.status(), existing.body());
        }
        throw ApiException.conflict("IDEMPOTENCY_CONFLICT",
                "requestId已受理但业务字段不一致，拒绝重放: " + requestId);
    }

    private Row findRow(String requestId) {
        return jdbcTemplate.query(
                        "SELECT fingerprint, response_status, response_body FROM idempotency_record WHERE request_id = ?",
                        (rs, rowNum) -> new Row(rs.getString(1), rs.getInt(2), rs.getString(3)), requestId)
                .stream().findFirst().orElse(null);
    }

    /**
     * 结构化指纹：以 JSON 数组 [endpoint, requestId, 请求报文] 序列化后取 SHA-256。
     * JSON 字符串转义保证无歧义——即使 requestId 或业务文本本身含有分隔符、
     * 换行或引号，不同字段取值也不会拼接出相同的指纹输入。
     */
    private String fingerprint(String endpoint, String requestId, Object request) {
        try {
            String payload = objectMapper.writeValueAsString(objectMapper.createArrayNode()
                    .add(endpoint).add(requestId).add(objectMapper.valueToTree(request)));
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256不可用", e);
        } catch (Exception e) {
            throw new IllegalStateException("请求报文序列化失败", e);
        }
    }
}
