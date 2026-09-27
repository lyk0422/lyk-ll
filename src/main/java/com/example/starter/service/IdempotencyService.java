package com.example.starter.service;

import com.example.starter.dao.IdempotencyRepository;
import com.example.starter.dao.IdempotencyRow;
import com.example.starter.support.Fingerprints;
import com.example.starter.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * 幂等执行模板。
 *
 * <p>指纹覆盖 requestId 与全部业务字段：</p>
 * <ul>
 *   <li>同键同参：返回首次结果（重放），不重复执行业务；</li>
 *   <li>同键异参：返回 409，且不改变潜次状态；</li>
 *   <li>并发同键：唯一索引唯一受理；败方业务改动随事务整体回滚，再读取胜方首次结果。</li>
 * </ul>
 */
@Service
public class IdempotencyService {

    /** 并发冲突时等待胜方事务提交的最长轮询时间（毫秒）。 */
    private static final int WAIT_TRIES = 100;
    private static final long WAIT_SLEEP_MS = 20;

    private final IdempotencyRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final IdempotencyService self;

    public IdempotencyService(IdempotencyRepository repository, ObjectMapper objectMapper,
                              Clock clock, @Lazy IdempotencyService self) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.self = self;
    }

    /** 一次幂等执行的业务产出。 */
    public record Outcome<T>(T body, int statusCode, boolean replayed) {
    }

    /** 首次受理的业务结果。 */
    public record FirstResult<T>(T body, int statusCode) {
        public static <T> FirstResult<T> ok(T body) {
            return new FirstResult<>(body, 200);
        }

        public static <T> FirstResult<T> created(T body) {
            return new FirstResult<>(body, 201);
        }
    }

    /**
     * 非事务编排：先查重放/冲突，再在独立事务内执行业务并登记。
     *
     * @param requestId 操作幂等键
     * @param operation 操作类型
     * @param fields    全部业务字段（指纹素材，自动补 requestId）
     * @param action    首次受理时执行的业务动作
     */
    public <T> Outcome<T> execute(String requestId, String operation, Map<String, Object> fields,
                                  Supplier<FirstResult<T>> action) {
        return doExecute(requestId, operation, Fingerprints.sha256(withRequestId(fields, requestId)),
                action);
    }

    /**
     * 与 {@link #execute} 相同的幂等语义，但指纹采用长度前缀的结构化编码，
     * 供新增写操作使用：参数值内含任意分隔符也不会发生指纹碰撞。
     */
    public <T> Outcome<T> executeStructured(String requestId, String operation,
                                            Map<String, Object> fields,
                                            Supplier<FirstResult<T>> action) {
        return doExecute(requestId, operation,
                Fingerprints.sha256Structured(withRequestId(fields, requestId)), action);
    }

    private <T> Outcome<T> doExecute(String requestId, String operation, String fingerprint,
                                     Supplier<FirstResult<T>> action) {
        Optional<IdempotencyRow> existing = repository.find(requestId);
        if (existing.isPresent()) {
            return replay(existing.get(), fingerprint);
        }
        try {
            FirstResult<T> result = self.runFirst(requestId, operation, fingerprint, action);
            return new Outcome<>(result.body(), result.statusCode(), false);
        } catch (DuplicateKeyException dup) {
            // 败方事务已整体回滚；等待胜方提交后按重放/409 返回
            IdempotencyRow winner = awaitWinner(requestId);
            if (winner == null) {
                throw new ApiException(409, "并发冲突：同一潜水员/潜水组同时刻只允许一个受控下潜或申请");
            }
            return replay(winner, fingerprint);
        }
    }

    /**
     * 业务动作与幂等登记在同一事务：动作抛错或登记撞唯一键时整体回滚，
     * 不会留下半成品业务数据。
     */
    @Transactional
    public <T> FirstResult<T> runFirst(String requestId, String operation, String fingerprint,
                                       Supplier<FirstResult<T>> action) {
        FirstResult<T> result = action.get();
        String json;
        try {
            json = objectMapper.writeValueAsString(result.body());
        } catch (Exception e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
        repository.insert(new IdempotencyRow(requestId, operation, fingerprint,
                result.statusCode(), json, Instant.now(clock)));
        return result;
    }

    private <T> Outcome<T> replay(IdempotencyRow row, String fingerprint) {
        if (!row.fingerprint().equals(fingerprint)) {
            throw new ApiException(409,
                    "requestId=" + row.requestId() + " 已用于不同参数的请求，拒绝执行且不改变潜次状态");
        }
        try {
            @SuppressWarnings("unchecked")
            T body = (T) objectMapper.readValue(row.responseJson(), Object.class);
            // 首次响应体里 replayed=false；重放返回同一份业务结果但标记为重放
            if (body instanceof Map<?, ?> map && map.containsKey("replayed")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> mutable = (Map<String, Object>) map;
                mutable.put("replayed", true);
            }
            return new Outcome<>(body, row.statusCode(), true);
        } catch (Exception e) {
            throw new IllegalStateException("首次响应无法反序列化", e);
        }
    }

    private IdempotencyRow awaitWinner(String requestId) {
        for (int i = 0; i < WAIT_TRIES; i++) {
            Optional<IdempotencyRow> row = repository.find(requestId);
            if (row.isPresent()) {
                return row.get();
            }
            try {
                Thread.sleep(WAIT_SLEEP_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    private static Map<String, Object> withRequestId(Map<String, Object> fields, String requestId) {
        Map<String, Object> all = new TreeMap<>(fields);
        all.put("requestId", requestId);
        return all;
    }
}
