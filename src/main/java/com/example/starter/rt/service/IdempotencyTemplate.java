package com.example.starter.rt.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import com.example.starter.rt.domain.OperationType;
import com.example.starter.rt.dto.RecordedResponse;
import com.example.starter.rt.repository.WorkflowRepository;
import com.example.starter.rt.repository.WorkflowRepository.IdempotentRow;
import com.example.starter.rt.support.Fingerprint;
import com.example.starter.rt.web.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * requestId 幂等执行模板：
 * <ul>
 *   <li>指纹覆盖 requestId、操作类型与全部业务字段；</li>
 *   <li>同键同参：返回首次结果（含首次业务拒绝 422/409），含 replayed 字段的成功响应置 replayed=true；</li>
 *   <li>同键异参：抛 409，不执行业务、不产生新分次；</li>
 *   <li>并发同键：调用方已持有患者/版本行锁，同实体并发请求被行锁串行化，负者读到首次结果重放。</li>
 * </ul>
 * 模板不自行开启事务，参与调用方 Service 的事务边界；首次结果与业务写库同事务提交。
 */
@Component
public class IdempotencyTemplate {

    private final WorkflowRepository workflow;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public IdempotencyTemplate(WorkflowRepository workflow, ObjectMapper objectMapper, Clock clock) {
        this.workflow = workflow;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 按 requestId 幂等执行业务动作。动作须以 {@link RecordedResponse} 返回全部业务结论
     * （成功与业务拒绝），不在动作内抛业务异常，以保证首次结果与业务写库同事务提交留痕。
     */
    public RecordedResponse execute(OperationType operation, String requestId, Map<String, ?> params,
                                    Supplier<RecordedResponse> action) {
        String fingerprint = Fingerprint.of(operation, requestId, params);
        Optional<IdempotentRow> existing = workflow.findIdempotent(requestId);
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), fingerprint);
        }

        RecordedResponse result = action.get();
        try {
            workflow.insertIdempotent(requestId, operation.name(), fingerprint,
                    result.httpStatus(), writeJson(result.body()), LocalDateTime.now(clock));
        } catch (DuplicateKeyException concurrent) {
            // 非同实体跨行并发：唯一约束决胜，负者整个事务回滚并返回 409（正常同实体并发已被行锁串行化为重放）。
            throw ApiException.conflict("IDEMPOTENCY_RACE",
                    "requestId=" + requestId + " 并发冲突，仅一个请求生效");
        }
        return result;
    }

    /**
     * 新增写操作的幂等执行：指纹为结构化规范 JSON（覆盖版本、集合与精确时刻），
     * 仅首次成功（2xx）结果占键留痕；业务拒绝（4xx）不占键、可修正后重试。
     * 同键同参重放首次冻结结果，同键异参 409。
     */
    public RecordedResponse executeOnSuccess(OperationType operation, String requestId,
                                             Object structure, Supplier<RecordedResponse> action) {
        String fingerprint = Fingerprint.structured(operation, requestId, structure);
        Optional<IdempotentRow> existing = workflow.findIdempotent(requestId);
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), fingerprint);
        }

        RecordedResponse result = action.get();
        if (result.httpStatus() >= 200 && result.httpStatus() < 300) {
            try {
                workflow.insertIdempotent(requestId, operation.name(), fingerprint,
                        result.httpStatus(), writeJson(result.body()), LocalDateTime.now(clock));
            } catch (DuplicateKeyException concurrent) {
                throw ApiException.conflict("IDEMPOTENCY_RACE",
                        "requestId=" + requestId + " 并发冲突，仅一个请求生效");
            }
        }
        return result;
    }

    private RecordedResponse replayOrConflict(IdempotentRow row, String fingerprint) {
        if (!row.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("IDEMPOTENCY_FINGERPRINT_CONFLICT",
                    "requestId=" + row.requestId() + " 已绑定不同业务参数，拒绝执行（409）");
        }
        return new RecordedResponse(row.httpStatus(), markReplayed(readJson(row.responseJson())));
    }

    /** 重放响应在保留首次业务字段的前提下，将 replayed 标记置为 true。 */
    @SuppressWarnings("unchecked")
    private Object markReplayed(Object body) {
        if (body instanceof Map<?, ?> map && map.containsKey("replayed")) {
            Map<String, Object> mutable = new LinkedHashMap<>((Map<String, Object>) map);
            mutable.put("replayed", true);
            return mutable;
        }
        return body;
    }

    private String writeJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("幂等响应序列化失败", e);
        }
    }

    private Object readJson(String json) {
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("幂等响应反序列化失败", e);
        }
    }
}
