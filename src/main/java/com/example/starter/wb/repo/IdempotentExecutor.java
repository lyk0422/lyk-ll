package com.example.starter.wb.repo;

import com.example.starter.wb.domain.IdempotentRow;
import com.example.starter.wb.exception.BadRequestException;
import com.example.starter.wb.exception.ConflictException;
import com.example.starter.wb.exception.NotFoundException;
import com.example.starter.wb.exception.UnprocessableException;
import com.example.starter.wb.support.FingerprintUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 新增写操作的 requestId 幂等执行器（与业务、版本同事务）。
 *
 * <p>调用方须在事务内调用：同键同参回放首次冻结结果，同键异参 409；
 * 业务、版本与幂等结果在同一事务提交，任一违例整体回滚——失败不写业务、不占幂等键。
 * 指纹为结构化 JSON（record 字段名+值），含分隔符的合法文本仍是独立引号字段，不存在歧义拼接。
 */
@Component
public class IdempotentExecutor {

    private final WeightBalanceRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public IdempotentExecutor(WeightBalanceRepository repository, ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public <T> T execute(String requestId, Object payload, Class<T> resultType, Supplier<T> action) {
        String fp = FingerprintUtil.sha256(requestId, payload, objectMapper);
        Optional<IdempotentRow> existing = repository.findIdempotent(requestId);
        if (existing.isPresent()) {
            return replay(existing.get(), fp, requestId, resultType);
        }
        T response;
        try {
            response = action.get();
        } catch (DuplicateKeyException dke) {
            // 并发同键：唯一约束决胜。能读到对方已提交的同参结果则回放，否则冲突。
            Optional<IdempotentRow> raced = repository.findIdempotent(requestId);
            if (raced.isPresent() && raced.get().fingerprint().equals(fp)) {
                return replay(raced.get(), fp, requestId, resultType);
            }
            throw new ConflictException("相同请求并发处理，唯一一笔生效，requestId=" + requestId);
        }
        // 与业务、版本同事务落快照；外层业务异常时随事务一起回滚（失败不占键）。
        repository.insertIdempotent(new IdempotentRow(requestId, fp, 200, safeJson(response),
                LocalDateTime.now(clock)));
        return response;
    }

    private <T> T replay(IdempotentRow row, String currentFp, String requestId, Class<T> resultType) {
        if (!row.fingerprint().equals(currentFp)) {
            throw new ConflictException("requestId " + requestId
                    + " 已用于不同业务参数（指纹不一致），拒绝产生新版本");
        }
        if (row.httpStatus() == 200 || row.httpStatus() == 201) {
            try {
                return objectMapper.readValue(row.resultJson(), resultType);
            } catch (Exception e) {
                throw new IllegalStateException("回放首次结果失败", e);
            }
        }
        String message = row.resultJson();
        try {
            message = objectMapper.readTree(row.resultJson()).path("message").asText(row.resultJson());
        } catch (Exception ignored) {
            // 保留原文
        }
        throw replayException(row.httpStatus(), message);
    }

    private static RuntimeException replayException(int status, String message) {
        return switch (status) {
            case 400 -> new BadRequestException(message);
            case 404 -> new NotFoundException(message);
            case 409 -> new ConflictException(message);
            case 422 -> new UnprocessableException(message);
            default -> new UnprocessableException(message);
        };
    }

    private String safeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }
}
