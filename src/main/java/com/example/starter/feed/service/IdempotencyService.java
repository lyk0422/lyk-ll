package com.example.starter.feed.service;

import com.example.starter.feed.repo.IdempotentRepository;
import com.example.starter.feed.web.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.function.Supplier;

/**
 * requestId 幂等执行模板。
 *
 * <p>并发协议（同一事务内完成）：先向 idempotent_request 插入 PENDING 占位行占用 requestId，
 * 再执行业务写入并把首次响应 JSON 回填。若并发同键请求插入占位行时撞唯一约束，
 * 则阻塞至首请求事务结束后读取其最终响应：指纹相同回放（携带首次 HTTP 状态），
 * 指纹不同返回 409 且不改变状态。</p>
 */
@Service
public class IdempotencyService {

    private static final String PENDING = "PENDING";
    private static final long AWAIT_TIMEOUT_MS = 5000;

    private final JdbcTemplate jdbc;
    private final IdempotentRepository idempotentRepository;
    private final ObjectMapper objectMapper;

    public IdempotencyService(JdbcTemplate jdbc,
                              IdempotentRepository idempotentRepository,
                              ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.idempotentRepository = idempotentRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 业务执行结果：响应对象与对应 HTTP 状态码（拒绝类决策也作为首次结果落库）。
     */
    public record Outcome(Object response, int httpStatus) {
        public static Outcome ok(Object response) {
            return new Outcome(response, 200);
        }
    }

    /**
     * 幂等执行结果。
     *
     * @param response   响应体（首次或重放还原）
     * @param httpStatus 首次结果对应的 HTTP 状态
     * @param replayed   是否为同键同参重放
     */
    public record Result<T>(T response, int httpStatus, boolean replayed) {
    }

    /**
     * 以 requestId 幂等执行业务回调。
     *
     * @param requestId 幂等键
     * @param kind      请求类型
     * @param payload   全部业务字段（指纹输入，含 requestId）
     * @param action    首次业务动作（在占位行事务内执行）
     * @param type      响应反序列化类型（重放时还原 JSON）
     */
    @Transactional
    public <T> Result<T> execute(String requestId, String kind, Object payload,
                                 Supplier<Outcome> action, Class<T> type) {
        String fingerprint = FingerprintUtil.sha256(payload);

        var existing = idempotentRepository.find(requestId);
        if (existing.isPresent()) {
            return resolve(existing.get(), fingerprint, type);
        }

        boolean acquired;
        try {
            jdbc.update("INSERT INTO idempotent_request (request_id, request_kind, fingerprint, http_status, response_json) "
                            + "VALUES (?,?,?,?,?)",
                    requestId, kind, fingerprint, 0, PENDING);
            acquired = true;
        } catch (DuplicateKeyException e) {
            acquired = false;
        }

        if (!acquired) {
            // 并发首请求刚刚提交：读取最终结果（必要时短暂等待回填）。
            return resolve(awaitFinal(requestId), fingerprint, type);
        }

        // 业务异常直接抛出，事务（含 PENDING 占位行）整体回滚，允许同键重试。
        Outcome outcome = action.get();
        jdbc.update("UPDATE idempotent_request SET http_status = ?, response_json = ? WHERE request_id = ?",
                outcome.httpStatus(), toJson(outcome.response()), requestId);

        @SuppressWarnings("unchecked")
        T typed = (T) outcome.response();
        return new Result<>(typed, outcome.httpStatus(), false);
    }

    private <T> Result<T> resolve(IdempotentRepository.IdempotentRow row, String fingerprint, Class<T> type) {
        if (!row.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("requestId=" + row.requestId()
                    + " 已存在但业务参数不一致（同键异参），拒绝执行且不改变状态");
        }
        if (PENDING.equals(row.responseJson())) {
            throw ApiException.conflict("requestId=" + row.requestId() + " 的首请求仍在处理中");
        }
        return new Result<>(fromJson(row.responseJson(), type), row.httpStatus(), true);
    }

    private IdempotentRepository.IdempotentRow awaitFinal(String requestId) {
        long deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MS;
        while (true) {
            var row = idempotentRepository.find(requestId);
            if (row.isPresent() && !PENDING.equals(row.get().responseJson())) {
                return row.get();
            }
            if (System.currentTimeMillis() > deadline) {
                throw ApiException.conflict("requestId=" + requestId + " 首请求结果等待超时");
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw ApiException.conflict("等待首请求结果被中断");
            }
        }
    }

    private String toJson(Object response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("幂等响应序列化失败", e);
        }
    }

    private <T> T fromJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("幂等响应反序列化失败", e);
        }
    }
}
