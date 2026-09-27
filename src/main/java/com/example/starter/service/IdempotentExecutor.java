package com.example.starter.service;

import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.starter.web.UnprocessableException;
import com.example.starter.web.dto.ReplayableResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 幂等执行器：同事务内完成业务写入与首次结果冻结；同键同参重放冻结结果，同键异参抛 409，
 * 业务失败整体回滚不占键；并发同键或唯一索引冲突时重查后按重放/409/422 处理。
 */
@Component
public class IdempotentExecutor {

    private final IdempotencyStore store;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;

    public IdempotentExecutor(IdempotencyStore store, ObjectMapper mapper,
            PlatformTransactionManager txManager) {
        this.store = store;
        this.mapper = mapper;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * 执行幂等写操作。action 内抛出的业务异常（400/404/409/422）直接传播并回滚，不占用幂等键。
     */
    public <T extends ReplayableResponse<T>> T execute(String requestId, String fingerprint,
            Class<T> type, Supplier<T> action) {
        try {
            return tx.execute(status -> {
                Optional<String> prior = store.findResult(requestId, fingerprint);
                if (prior.isPresent()) {
                    return read(prior.get(), type).asReplayed();
                }
                T result = action.get();
                store.save(requestId, fingerprint, write(result));
                return result;
            });
        } catch (DuplicateKeyException e) {
            return tx.execute(status -> store.findResult(requestId, fingerprint)
                    .map(json -> read(json, type).asReplayed())
                    .orElseThrow(() -> new UnprocessableException("资源唯一性冲突（车辆占用或编号重复）")));
        }
    }

    private <T> T read(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("幂等结果反序列化失败", e);
        }
    }

    private String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("幂等结果序列化失败", e);
        }
    }
}
