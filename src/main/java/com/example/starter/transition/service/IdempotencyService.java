package com.example.starter.transition.service;

import com.example.starter.transition.domain.BizType;
import com.example.starter.transition.repo.RequestRegistryRepository;
import com.example.starter.transition.repo.RequestRegistryRepository.RegistryRow;
import com.example.starter.transition.web.ConflictException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 幂等受理服务：requestId 唯一登记，指纹覆盖 requestId 与全部业务字段；
 * 同键同参返回首次结果，同键异参返回 409 且不产生新记录。
 */
@Service
public class IdempotencyService {

    /** 同键并发处理中的轮询等待上限（毫秒）。 */
    private static final long WAIT_TIMEOUT_MS = 10_000;
    private static final long WAIT_INTERVAL_MS = 50;

    private final RequestRegistryRepository registry;
    private final ObjectMapper objectMapper;

    public IdempotencyService(RequestRegistryRepository registry, ObjectMapper objectMapper) {
        this.registry = registry;
        this.objectMapper = objectMapper;
    }

    /**
     * 计算幂等指纹：SHA-256(requestId | bizType | 全部业务字段按声明顺序拼接)。
     * BigDecimal 统一去尾零后按普通十进制表示，避免数值相等但标度不同导致误判。
     */
    public static String fingerprint(String requestId, String bizType, Object... businessFields) {
        StringBuilder sb = new StringBuilder();
        sb.append(requestId).append('|').append(bizType);
        for (Object field : businessFields) {
            sb.append('|').append(normalize(field));
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * 幂等执行：首次受理执行业务并登记结果；同键同参重放返回首次响应；
     * 同键异参抛 409；并发同键等待首次处理完成后返回同一结果。
     *
     * @param bizType    业务类型
     * @param requestId  幂等键
     * @param fingerprint 幂等指纹
     * @param refKey     业务主键提取器输入由 action 结果提供
     * @param action     首次执行的业务动作，返回响应对象（将被序列化为 JSON 快照）
     * @return 响应 JSON 与是否重放标记
     */
    public IdempotentResponse execute(BizType bizType, String requestId, String fingerprint,
                                      Supplier<Object> action) {
        if (registry.tryRegister(requestId, bizType.name(), fingerprint)) {
            try {
                Object result = action.get();
                String json = objectMapper.writeValueAsString(result);
                registry.markDone(requestId, extractRefKey(result), json);
                return new IdempotentResponse(json, false);
            } catch (RuntimeException e) {
                registry.deleteByRequestId(requestId);
                throw e;
            } catch (Exception e) {
                registry.deleteByRequestId(requestId);
                throw new IllegalStateException("响应序列化失败", e);
            }
        }
        return awaitExisting(requestId, fingerprint);
    }

    private static String normalize(Object field) {
        if (field == null) {
            return "<null>";
        }
        if (field instanceof java.math.BigDecimal decimal) {
            return decimal.stripTrailingZeros().toPlainString();
        }
        return field.toString();
    }

    private IdempotentResponse awaitExisting(String requestId, String fingerprint) {
        long deadline = System.currentTimeMillis() + WAIT_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            Optional<RegistryRow> row = registry.findByRequestId(requestId);
            if (row.isPresent()) {
                RegistryRow existing = row.get();
                if (!existing.fingerprint().equals(fingerprint)) {
                    throw new ConflictException("相同 requestId 但业务字段不一致，已拒绝（不产生新记录）");
                }
                if ("DONE".equals(existing.status())) {
                    return new IdempotentResponse(existing.responseJson(), true);
                }
            }
            try {
                Thread.sleep(WAIT_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ConflictException("等待首次处理完成时被中断");
            }
        }
        throw new ConflictException("相同 requestId 的请求仍在处理中，请稍后重试");
    }

    private String extractRefKey(Object result) {
        if (result instanceof RefKeyCarrier carrier) {
            return carrier.refKey();
        }
        return null;
    }

    /**
     * 响应对象可携带业务主键，便于对账。
     */
    public interface RefKeyCarrier {
        String refKey();
    }

    /**
     * 幂等执行结果。
     *
     * @param body     响应 JSON（首次序列化快照或重放快照）
     * @param replayed 是否为同键同参重放
     */
    public record IdempotentResponse(String body, boolean replayed) {
    }
}
