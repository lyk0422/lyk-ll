package com.example.starter.blast.service;

import com.example.starter.blast.api.BlastBusinessException;
import com.example.starter.blast.api.ServiceResult;
import com.example.starter.blast.domain.BizType;
import com.example.starter.blast.repo.IdempotencyRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Supplier;

/**
 * 基于 requestId 的幂等执行模板。
 * 指纹覆盖 requestId、业务类型与全部业务字段；同键同参重放首次结果（含首次失败状态），
 * 同键异参返回 409 且不落任何记录、不改变流程状态。
 */
@Component
public class IdempotencyTemplate {

    private final IdempotencyRepository idempotencyRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public IdempotencyTemplate(IdempotencyRepository idempotencyRepository,
                               ObjectMapper objectMapper, Clock clock) {
        this.idempotencyRepository = idempotencyRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 首次业务动作的输出：HTTP 状态与响应体。失败的业务结果同样落幂等记录以保证重放一致。
     */
    public record Outcome<T>(int status, T body) {
        public static <T> Outcome<T> of(int status, T body) {
            return new Outcome<>(status, body);
        }
    }

    /**
     * 以幂等方式执行业务动作。
     *
     * @param requestId       幂等键
     * @param bizType         业务类型，隔离不同接口的同名键
     * @param fingerprintPart 参与指纹的业务字段（请求体，或包含路径参数的复合列表）
     * @param responseType    首次响应类型，用于重放反序列化
     * @param action          首次业务动作
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public <T> ServiceResult<T> perform(String requestId, BizType bizType, Object fingerprintPart,
                                        Class<T> responseType, Supplier<Outcome<T>> action) {
        String fingerprint = digest(List.of(bizType.name(), requestId, fingerprintPart));

        var existing = idempotencyRepository.find(requestId, bizType);
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), fingerprint, responseType);
        }

        Outcome<T> outcome = action.get();
        String responseJson = writeJson(outcome.body());
        boolean inserted = idempotencyRepository.insert(requestId, bizType, fingerprint,
                outcome.status(), responseJson, clock.instant());
        if (!inserted) {
            var winner = idempotencyRepository.find(requestId, bizType)
                    .orElseThrow(() -> new BlastBusinessException(409, "并发幂等处理冲突，请重试"));
            return replayOrConflict(winner, fingerprint, responseType);
        }
        return ServiceResult.of(outcome.status(), outcome.body());
    }

    private <T> ServiceResult<T> replayOrConflict(IdempotencyRepository.IdempotentRecord record,
                                                  String expectedFingerprint, Class<T> responseType) {
        if (!record.fingerprint().equals(expectedFingerprint)) {
            throw new BlastBusinessException(409,
                    "requestId=" + record.requestId() + " 已用于不同业务参数，拒绝处理");
        }
        return ServiceResult.replay(record.httpStatus(), readJson(record.responseJson(), responseType));
    }

    /**
     * 计算业务类型＋requestId＋全部业务字段的 SHA-256 指纹。
     */
    public String digest(Object fingerprintSource) {
        byte[] canonical = writeJson(fingerprintSource).getBytes(StandardCharsets.UTF_8);
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(messageDigest.digest(canonical));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("幂等指纹序列化失败", ex);
        }
    }

    private <T> T readJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception ex) {
            throw new IllegalStateException("幂等响应反序列化失败", ex);
        }
    }
}
