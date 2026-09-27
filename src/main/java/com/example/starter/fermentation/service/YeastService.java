package com.example.starter.fermentation.service;

import com.example.starter.fermentation.api.YeastDtos.RecoveryResponse;
import com.example.starter.fermentation.api.YeastDtos.RecoverYeastRequest;
import com.example.starter.fermentation.api.YeastDtos.RecoverYeastResponse;
import com.example.starter.fermentation.api.YeastDtos.RegisterYeastUnitRequest;
import com.example.starter.fermentation.api.YeastDtos.YeastUnitResponse;
import com.example.starter.fermentation.domain.BatchStatus;
import com.example.starter.fermentation.domain.FermentationBatch;
import com.example.starter.fermentation.domain.YeastRecovery;
import com.example.starter.fermentation.domain.YeastUnit;
import com.example.starter.fermentation.domain.YeastUnitStatus;
import com.example.starter.fermentation.error.ApiException;
import com.example.starter.fermentation.repository.BatchRepository;
import com.example.starter.fermentation.repository.YeastRepository;
import com.example.starter.fermentation.service.IdempotencyService.ConcurrentReplayException;
import com.example.starter.fermentation.service.IdempotencyService.StoredResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Supplier;

/**
 * 酵母代次限用与投酵回收追溯服务。
 *
 * <p>判定口径（均可由落库数据复算）：当前代次 ≤5 可投酵（恰 5 允许本次投酵，代次 6 拒绝）；
 * 回收活率 ≥90% 且无污染转可用并代次 +1（恰 90% 合格），否则报废；
 * 可用单元距最近回收超过 72 小时失效（恰 72 小时仍可用），首次单元（无回收时刻）不受此限；
 * 回收仅在绑定批次完成主酵后受理，回收结果与解除批次占用同事务，失败批次也只能回收一次。
 */
@Service
public class YeastService {

    /** 允许投酵的最大代次，恰 5 允许本次投酵。 */
    public static final int MAX_PITCH_GENERATION = 5;
    /** 回收合格活率下限（%），恰 90 合格。 */
    public static final BigDecimal MIN_VIABILITY_PERCENT = new BigDecimal("90");
    /** 可用单元距最近回收的失效时长（小时），恰 72 小时仍可用。 */
    public static final long EXPIRY_HOURS = 72;

    private final YeastRepository yeastRepository;
    private final BatchRepository batchRepository;
    private final IdempotencyService idempotencyService;
    private final YeastExpiryService yeastExpiryService;
    private final ObjectMapper objectMapper;

    public YeastService(YeastRepository yeastRepository, BatchRepository batchRepository,
                        IdempotencyService idempotencyService,
                        YeastExpiryService yeastExpiryService, ObjectMapper objectMapper) {
        this.yeastRepository = yeastRepository;
        this.batchRepository = batchRepository;
        this.idempotencyService = idempotencyService;
        this.yeastExpiryService = yeastExpiryService;
        this.objectMapper = objectMapper;
    }

    /**
     * 登记酵母单元：初始代次 0、可用、版本 1。编号唯一，重复登记 409。
     */
    @Transactional
    public StoredResponse register(RegisterYeastUnitRequest req) {
        String endpoint = "POST /api/yeast-units";
        String fingerprint = idempotencyService.structuredFingerprint(endpoint, req.requestId(),
                req.unitCode());
        return idempotent(req.requestId(), endpoint, fingerprint, () -> {
            long id;
            try {
                id = yeastRepository.insert(req.unitCode());
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("酵母单元编号已存在: " + req.unitCode());
            }
            return new StoredResponse(201, toJson(toResponse(mustFind(id))));
        });
    }

    /**
     * 回收：仅在绑定批次完成主酵后受理。活率 ≥90%（恰 90 合格）且无污染转可用并代次 +1，
     * 否则报废。回收结果与解除批次占用同事务；已回收单元（状态非占用中）不受理新回收请求。
     */
    @Transactional
    public StoredResponse recover(String unitCode, RecoverYeastRequest req) {
        String endpoint = "POST /api/yeast-units/" + unitCode + "/recovery";
        String fingerprint = idempotencyService.structuredFingerprint(endpoint, req.requestId(),
                req.recoveredAt().toString(), req.viabilityPercent().toPlainString(),
                req.contaminated().toString(), req.expectedVersion().toString());
        return idempotent(req.requestId(), endpoint, fingerprint, () -> {
            YeastUnit unit = mustFindByCode(unitCode);
            requireVersion(unit, req.expectedVersion());
            if (unit.status() != YeastUnitStatus.IN_USE) {
                throw ApiException.unprocessable(
                        "酵母单元未处于主酵占用中，不受理回收（当前状态 " + unit.status() + "）");
            }
            FermentationBatch batch = batchRepository.findById(unit.activeBatchId())
                    .orElseThrow(() -> new IllegalStateException(
                            "酵母单元绑定的批次不存在: " + unit.activeBatchId()));
            if (batch.status() == BatchStatus.FILLED || batch.status() == BatchStatus.MAIN_FERMENT) {
                throw ApiException.unprocessable(
                        "绑定批次尚未完成主酵（当前状态 " + batch.status() + "），不受理回收");
            }
            boolean recycled = req.viabilityPercent().compareTo(MIN_VIABILITY_PERCENT) >= 0
                    && !req.contaminated();
            if (!yeastRepository.applyRecovery(unit.id(), req.expectedVersion(), recycled,
                    req.recoveredAt())) {
                throw ApiException.conflict("酵母单元已被并发变更，请按最新版本重试: " + unitCode);
            }
            int generationAfter = unit.generation() + (recycled ? 1 : 0);
            String outcome = recycled
                    ? YeastRecovery.OUTCOME_RECYCLED : YeastRecovery.OUTCOME_RETIRED;
            long recoveryId;
            try {
                recoveryId = yeastRepository.insertRecovery(unit.id(), batch.id(),
                        req.recoveredAt(), req.viabilityPercent(), req.contaminated(),
                        outcome, generationAfter);
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("批次 " + batch.id() + " 已完成过酵母回收，不得重复回收");
            }
            YeastRecovery recovery = new YeastRecovery(recoveryId, unit.id(), batch.id(),
                    req.recoveredAt(), req.viabilityPercent(), req.contaminated(),
                    outcome, generationAfter);
            YeastUnit updated = mustFind(unit.id());
            return new StoredResponse(200, toJson(new RecoverYeastResponse(
                    toResponse(updated), toResponse(recovery))));
        });
    }

    /**
     * 查询酵母单元详情（代次、状态、最近回收时刻、版本、当前绑定批次）。
     */
    @Transactional(readOnly = true)
    public YeastUnitResponse getUnit(String unitCode) {
        return toResponse(mustFindByCode(unitCode));
    }

    /**
     * 查询酵母单元回收追溯链（按回收时刻升序）。
     */
    @Transactional(readOnly = true)
    public List<RecoveryResponse> getRecoveries(String unitCode) {
        YeastUnit unit = mustFindByCode(unitCode);
        return yeastRepository.findRecoveries(unit.id()).stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * 投酵资格校验并原子绑定（供受控启动主酵在同一事务内调用）：
     * 版本匹配、状态可用、代次 ≤5、距最近回收不超过 72 小时（恰 72 小时可用，
     * 首次单元无回收时刻不受限）；超 72 小时先置失效再拒绝。
     *
     * @return 绑定后的酵母单元（状态 IN_USE，版本 +1）
     */
    public YeastUnit bindForPitch(String unitCode, int expectedVersion, long batchId,
                                  LocalDateTime startedAt) {
        YeastUnit unit = mustFindByCode(unitCode);
        requireVersion(unit, expectedVersion);
        if (unit.status() != YeastUnitStatus.AVAILABLE) {
            throw ApiException.unprocessable(
                    "酵母单元不可投酵（当前状态 " + unit.status() + "，要求 AVAILABLE）");
        }
        if (unit.generation() > MAX_PITCH_GENERATION) {
            throw ApiException.unprocessable(
                    "酵母代次 " + unit.generation() + " 已超限（代次 6 不可再投酵）");
        }
        if (unit.lastRecoveredAt() != null
                && startedAt.isAfter(unit.lastRecoveredAt().plusHours(EXPIRY_HOURS))) {
            // 失效为独立状态变更，单独事务提交，不随本次 422 回滚
            yeastExpiryService.markExpired(unit.id(), expectedVersion);
            throw ApiException.unprocessable(
                    "酵母单元距最近回收超过 72 小时未投酵，已失效: " + unitCode);
        }
        if (!yeastRepository.bindToBatch(unit.id(), expectedVersion, batchId)) {
            throw ApiException.conflict("酵母单元已被并发变更，请按最新版本重试: " + unitCode);
        }
        return mustFind(unit.id());
    }

    private YeastUnit mustFind(long id) {
        return yeastRepository.findById(id)
                .orElseThrow(() -> new IllegalStateException("酵母单元不存在: " + id));
    }

    private YeastUnit mustFindByCode(String unitCode) {
        return yeastRepository.findByCode(unitCode)
                .orElseThrow(() -> ApiException.notFound("酵母单元不存在: " + unitCode));
    }

    private void requireVersion(YeastUnit unit, int expectedVersion) {
        if (unit.version() != expectedVersion) {
            throw ApiException.conflict("酵母单元版本不匹配：期望 " + expectedVersion
                    + "，当前 " + unit.version() + "，请按最新版本重试");
        }
    }

    private StoredResponse idempotent(String requestId, String endpoint, String fingerprint,
                                      Supplier<StoredResponse> action) {
        try {
            return idempotencyService.execute(requestId, endpoint, fingerprint, action);
        } catch (ConcurrentReplayException e) {
            // 并发同键：回滚本次业务写入，按首次受理结果裁决
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return idempotencyService.resolveConcurrent(e.requestId(), e.fingerprint());
        }
    }

    private YeastUnitResponse toResponse(YeastUnit u) {
        return new YeastUnitResponse(u.id(), u.unitCode(), u.generation(), u.status(),
                u.lastRecoveredAt(), u.version(), u.activeBatchId());
    }

    private RecoveryResponse toResponse(YeastRecovery r) {
        return new RecoveryResponse(r.id(), r.yeastUnitId(), r.batchId(), r.recoveredAt(),
                r.viabilityPercent(), r.contaminated(), r.outcome(), r.generationAfter());
    }

    private String toJson(Object dto) {
        try {
            return objectMapper.writeValueAsString(dto);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }
}
