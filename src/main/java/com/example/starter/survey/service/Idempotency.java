package com.example.starter.survey.service;

import com.example.starter.survey.api.Dtos;
import com.example.starter.survey.domain.IdempotentRow;
import com.example.starter.survey.model.SurveyEnums.Operation;
import com.example.starter.survey.repo.IdempotentRepo;
import com.example.starter.survey.web.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 幂等支撑。流程：
 * <ol>
 *   <li>requestId 主键占坑（独立事务自动提交），并发同键仅一个请求进入业务；</li>
 *   <li>业务在各自事务内执行，成功后定稿首次响应；</li>
 *   <li>业务规则失败同样定稿错误快照（同键同参重放首次结果）；</li>
 *   <li>同键异参（操作或指纹不同）返回 409，不产生副作用；</li>
 *   <li>并发同键的后来者等待首个请求定稿后重放。</li>
 * </ol>
 */
@Component
public class Idempotency {

    /** 并发同键等待首个请求定稿的最大轮询次数与间隔。 */
    private static final int WAIT_RETRIES = 100;
    private static final long WAIT_SLEEP_MILLIS = 50;

    private final IdempotentRepo repo;
    private final ObjectMapper objectMapper;

    public Idempotency(IdempotentRepo repo, ObjectMapper objectMapper) {
        this.repo = repo;
        this.objectMapper = objectMapper;
    }

    /** 幂等执行结果：status 为 HTTP 状态码，body 为响应体（重放时为 JsonNode 快照）。 */
    public record Result(int status, Object body, boolean replay) {
    }

    /** 业务动作产出：HTTP 状态码 + 响应体。 */
    public record Outcome(int status, Object body) {
        public static Outcome ok(Object body) {
            return new Outcome(200, body);
        }
    }

    /**
     * 计算业务指纹。入参按固定顺序拼接入签，集合需由调用方排好序。
     */
    public String fingerprint(Operation operation, String requestId, Object... fields) {
        StringBuilder sb = new StringBuilder(operation.name()).append('|').append(requestId);
        for (Object f : fields) {
            sb.append('|');
            if (f != null) {
                sb.append(f);
            }
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 按 requestId 执行幂等逻辑。
     */
    public Result execute(Operation operation, String requestId, String fingerprint,
                          java.util.function.Supplier<Outcome> action) {
        IdempotentRow existing = repo.find(requestId);
        if (existing == null) {
            try {
                repo.claim(requestId, operation.name(), fingerprint);
            } catch (DuplicateKeyException concurrent) {
                // 同键并发：另一请求刚刚占坑成功，转入等待重放。
                return awaitAndReplay(operation, requestId, fingerprint);
            }
            return runAndFinalize(requestId, action);
        }
        return replayOrConflict(operation, requestId, fingerprint, existing);
    }

    private Result runAndFinalize(String requestId,
                                  java.util.function.Supplier<Outcome> action) {
        try {
            Outcome outcome = action.get();
            repo.finalizeResult(requestId, outcome.status(), toJson(outcome.body()));
            return new Result(outcome.status(), outcome.body(), false);
        } catch (ApiException e) {
            // 业务规则失败也固化首次结果，重放保持一致；业务事务已回滚。
            repo.finalizeResult(requestId, e.getStatus().value(),
                    toJson(new Dtos.ErrorResponse(e.getError(), e.getMessage())));
            throw e;
        } catch (RuntimeException e) {
            // 系统异常：释放占坑，允许同键重试。
            repo.release(requestId);
            throw e;
        }
    }

    private Result awaitAndReplay(Operation operation, String requestId, String fingerprint) {
        for (int i = 0; i < WAIT_RETRIES; i++) {
            IdempotentRow row = repo.find(requestId);
            if (row != null && row.httpStatus() != IdempotentRepo.PENDING_STATUS) {
                return replayOrConflict(operation, requestId, fingerprint, row);
            }
            try {
                Thread.sleep(WAIT_SLEEP_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw ApiException.conflict("并发请求处理中断");
            }
        }
        throw ApiException.conflict("相同 requestId 的请求正在处理中");
    }

    private Result replayOrConflict(Operation operation, String requestId, String fingerprint,
                                    IdempotentRow row) {
        if (row.httpStatus() == IdempotentRepo.PENDING_STATUS) {
            return awaitAndReplay(operation, requestId, fingerprint);
        }
        if (!row.operation().equals(operation.name()) || !row.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("requestId 已用于不同业务参数的请求");
        }
        JsonNode snapshot;
        try {
            snapshot = objectMapper.readTree(row.resultJson());
        } catch (Exception e) {
            throw new IllegalStateException("幂等快照解析失败", e);
        }
        return new Result(row.httpStatus(), snapshot, true);
    }

    private String toJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("幂等快照序列化失败", e);
        }
    }
}
