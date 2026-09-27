package com.example.starter.fermentation.service;

import com.example.starter.fermentation.api.YeastDtos.RecycleRecordResponse;
import com.example.starter.fermentation.api.YeastDtos.RecycleRequest;
import com.example.starter.fermentation.api.YeastDtos.RecycleResponse;
import com.example.starter.fermentation.api.YeastDtos.RegisterYeastRequest;
import com.example.starter.fermentation.api.YeastDtos.YeastUnitResponse;
import com.example.starter.fermentation.domain.BatchStatus;
import com.example.starter.fermentation.domain.FermentationBatch;
import com.example.starter.fermentation.domain.RecycleResult;
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
import java.util.List;
import java.util.function.Supplier;

/**
 * 酵母代次限用与投酵回收追溯服务。
 *
 * <p>判定口径（均可由落库数据复算）：登记初始代次 0、可用、版本 1；
 * 投酵要求当前代次 ≤ 5（恰 5 允许本次投酵，回收合格加 1 后达 6 即不可再投酵）；
 * 可用单元距最近回收超过 72 小时失效（恰 72 小时仍可用，首次登记无回收时刻不受限）；
 * 回收仅在绑定批次完成主酵后受理：活率 ≥ 90%（恰 90% 合格）且无污染转可用并代次加 1，
 * 否则报废（终态）；每个绑定批次仅允许回收一次，回收结果与解除批次占用同事务。
 */
@Service
public class YeastService {

    /** 允许投酵的最高代次，恰 5 允许；回收合格加 1 后达 6 不可再投酵。 */
    static final int MAX_PITCH_GENERATION = 5;
    /** 回收合格活率下限（%），恰 90.00 合格。 */
    static final BigDecimal MIN_VIABILITY_PERCENT = new BigDecimal("90.00");
    /** 可用单元自最近回收起的失效时长（小时），恰 72 小时仍可用。 */
    static final long EXPIRY_HOURS = 72;

    private final YeastRepository yeastRepository;
    private final BatchRepository batchRepository;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    public YeastService(YeastRepository yeastRepository, BatchRepository batchRepository,
                        IdempotencyService idempotencyService, ObjectMapper objectMapper) {
        this.yeastRepository = yeastRepository;
        this.batchRepository = batchRepository;
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
    }

    /**
     * 登记酵母单元：初始代次 0、可用、版本 1。编号唯一，重复登记返回 409。
     */
    @Transactional
    public StoredResponse register(RegisterYeastRequest req) {
        String endpoint = "POST /api/yeast-units";
        String fingerprint = idempotencyService.fingerprint(endpoint, req.requestId(),
                req.yeastCode());
        return idempotent(req.requestId(), endpoint, fingerprint, () -> {
            long id;
            try {
                id = yeastRepository.insert(req.yeastCode());
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("酵母单元编号已存在: " + req.yeastCode());
            }
            return new StoredResponse(201, toJson(toResponse(mustFind(id))));
        });
    }

    /**
     * 回收酵母：仅在绑定批次完成主酵后受理。活率 ≥ 90%（恰 90% 合格）且无污染
     * 转可用并代次加 1，否则报废；回收结果与解除批次占用同事务，每批次仅回收一次。
     */
    @Transactional
    public StoredResponse recycle(String yeastCode, RecycleRequest req) {
        String endpoint = "POST /api/yeast-units/" + yeastCode + "/recycle";
        String fingerprint = idempotencyService.fingerprint(endpoint, req.requestId(),
                req.batchId().toString(), req.expectedVersion().toString(),
                req.viabilityPercent().toPlainString(), String.valueOf(req.contaminated()),
                req.recoveredAt().toString());
        return idempotent(req.requestId(), endpoint, fingerprint, () -> {
            YeastUnit unit = mustFindByCode(yeastCode);
            requireVersion(unit, req.expectedVersion());
            requireBoundToBatch(unit, req.batchId());
            FermentationBatch batch = batchRepository.findById(req.batchId())
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + req.batchId()));
            if (batch.status() == BatchStatus.FILLED || batch.status() == BatchStatus.MAIN_FERMENT) {
                throw ApiException.unprocessable(
                        "绑定批次尚未完成主酵（当前状态 " + batch.status() + "），不受理回收");
            }
            boolean qualified = req.viabilityPercent().compareTo(MIN_VIABILITY_PERCENT) >= 0
                    && !req.contaminated();
            RecycleResult result = qualified ? RecycleResult.RETURNED : RecycleResult.SCRAPPED;
            YeastUnitStatus newStatus =
                    qualified ? YeastUnitStatus.AVAILABLE : YeastUnitStatus.SCRAPPED;
            int newGeneration = qualified ? unit.generation() + 1 : unit.generation();
            int updated = yeastRepository.applyRecycle(unit.id(), req.batchId(), newStatus,
                    newGeneration, req.recoveredAt(), req.expectedVersion());
            if (updated == 0) {
                throw concurrentChange(unit.id(), req.batchId(), req.expectedVersion());
            }
            long recordId;
            try {
                recordId = yeastRepository.insertRecycleRecord(unit.id(), req.batchId(),
                        unit.generation(), newGeneration, req.viabilityPercent(),
                        req.contaminated(), result, req.recoveredAt());
            } catch (DuplicateKeyException e) {
                throw ApiException.unprocessable("批次 " + req.batchId() + " 的酵母已回收过，仅允许回收一次");
            }
            return new StoredResponse(200, toJson(new RecycleResponse(recordId, yeastCode,
                    req.batchId(), result, unit.generation(), newGeneration,
                    req.expectedVersion() + 1, req.recoveredAt())));
        });
    }

    /**
     * 查询酵母单元详情（代次、状态、版本、最近回收时刻、当前绑定批次）。
     */
    @Transactional(readOnly = true)
    public YeastUnitResponse getUnit(String yeastCode) {
        return toResponse(mustFindByCode(yeastCode));
    }

    /**
     * 查询酵母单元回收追溯链（按回收时刻升序，可复算代次演进）。
     */
    @Transactional(readOnly = true)
    public List<RecycleRecordResponse> getRecycles(String yeastCode) {
        YeastUnit unit = mustFindByCode(yeastCode);
        return yeastRepository.findRecycles(unit.id()).stream()
                .map(r -> new RecycleRecordResponse(r.id(), r.yeastUnitId(), r.batchId(),
                        r.generationBefore(), r.generationAfter(), r.viabilityPercent(),
                        r.contaminated(), r.result(), r.recoveredAt()))
                .toList();
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

    private YeastUnit mustFind(long id) {
        return yeastRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("酵母单元不存在: " + id));
    }

    private YeastUnit mustFindByCode(String yeastCode) {
        return yeastRepository.findByCode(yeastCode)
                .orElseThrow(() -> ApiException.notFound("酵母单元不存在: " + yeastCode));
    }

    private void requireVersion(YeastUnit unit, int expectedVersion) {
        if (unit.version() != expectedVersion) {
            throw ApiException.conflict("酵母单元版本不匹配：期望 " + expectedVersion
                    + "，当前 " + unit.version() + "，请刷新后重试");
        }
    }

    private void requireBoundToBatch(YeastUnit unit, long batchId) {
        if (unit.status() != YeastUnitStatus.IN_USE
                || !java.util.Objects.equals(unit.activeBatchId(), batchId)) {
            throw ApiException.unprocessable("酵母单元未投酵绑定批次 " + batchId
                    + "（当前状态 " + unit.status() + "），不受理回收");
        }
    }

    /**
     * 条件更新未生效（并发变更）：重读最新状态，按提交顺序裁决错误语义。
     */
    private ApiException concurrentChange(long unitId, long batchId, int expectedVersion) {
        YeastUnit latest = mustFind(unitId);
        if (latest.status() != YeastUnitStatus.IN_USE
                || !java.util.Objects.equals(latest.activeBatchId(), batchId)) {
            return ApiException.unprocessable("酵母单元已并发变更（当前状态 " + latest.status()
                    + "），本次回收不予受理");
        }
        return ApiException.conflict("酵母单元版本已并发变更：期望 " + expectedVersion
                + "，当前 " + latest.version());
    }

    private YeastUnitResponse toResponse(YeastUnit u) {
        return new YeastUnitResponse(u.id(), u.yeastCode(), u.generation(), u.status(),
                u.version(), u.lastRecoveredAt(), u.activeBatchId());
    }

    private String toJson(Object dto) {
        try {
            return objectMapper.writeValueAsString(dto);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }
}
