package com.example.starter.food.service;

import com.example.starter.food.repo.FoodRepository;
import com.example.starter.food.repo.StoredResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * requestId 幂等执行器：指纹覆盖 requestId 与全部业务字段。
 * 同键同参返回首次结果（含首次状态码与响应体），同键异参返回 409。
 * 幂等记录与业务变更在同一事务内提交，重放不会读到部分效果。
 */
@Service
public class IdempotencyService {

    /** 并发同键时等待获胜事务提交的最大轮次（每轮 50ms，共约 2.5s）。 */
    private static final int WINNER_RETRY = 50;

    private final FoodRepository repo;
    private final ObjectMapper mapper;

    public IdempotencyService(FoodRepository repo, ObjectMapper mapper) {
        this.repo = repo;
        this.mapper = mapper;
    }

    /** 业务动作结果；replay=true 时 body 为首次响应的原始 JSON。 */
    public record Result(boolean replay, int statusCode, Object body) {
    }

    /**
     * @param requestId 幂等键
     * @param fingerprintParts 除 requestId 外的全部业务字段（顺序敏感的列表需保持提交顺序）
     */
    @Transactional
    public Result execute(String requestId, Object fingerprintParts, Supplier action) {
        String fingerprint = Fingerprint.sha256(mapper, List.of(requestId, fingerprintParts));

        StoredResponse stored = repo.findIdempotent(requestId);
        if (stored != null) {
            return replayOrConflict(requestId, fingerprint, stored);
        }

        Result result;
        try {
            ServiceResult sr = action.get();
            result = new Result(false, sr.statusCode(), sr.body());
        } catch (DuplicateKeyException dup) {
            // 并发同键（配方/备案/批号唯一约束）：等待并发获胜者提交后回放其首次结果
            return awaitWinner(requestId, fingerprint, dup);
        }

        try {
            repo.insertIdempotent(requestId, fingerprint, result.statusCode(), writeJson(result.body()));
        } catch (DuplicateKeyException dup) {
            return awaitWinner(requestId, fingerprint, dup);
        }
        return result;
    }

    private Result replayOrConflict(String requestId, String fingerprint, StoredResponse stored) {
        if (!stored.fingerprint().equals(fingerprint)) {
            throw new BusinessException(409,
                    "requestId=" + requestId + " 已用于不同的业务参数，拒绝同键异参请求");
        }
        return new Result(true, stored.statusCode(), stored.responseJson());
    }

    private Result awaitWinner(String requestId, String fingerprint, RuntimeException original) {
        for (int i = 0; i < WINNER_RETRY; i++) {
            StoredResponse winner = repo.findIdempotent(requestId);
            if (winner != null) {
                return replayOrConflict(requestId, fingerprint, winner);
            }
            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new BusinessException(409, "并发冲突：唯一键冲突且未取得获胜者首次结果（requestId=" + requestId + "）");
    }

    private String writeJson(Object body) {
        try {
            return mapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 业务动作，返回 HTTP 状态码与响应体。 */
    @FunctionalInterface
    public interface Supplier {
        ServiceResult get();
    }

    /** 业务动作的正常结果（含 4xx 的已受理终态，如逾期复审）。 */
    public record ServiceResult(int statusCode, Object body) {
        public static ServiceResult ok(Object body) {
            return new ServiceResult(200, body);
        }

        public static ServiceResult of(int statusCode, Object body) {
            return new ServiceResult(statusCode, body);
        }
    }
}
