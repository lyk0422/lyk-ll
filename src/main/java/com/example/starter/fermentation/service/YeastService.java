package com.example.starter.fermentation.service;

import com.example.starter.fermentation.api.YeastDtos.PitchResponse;
import com.example.starter.fermentation.api.YeastDtos.PitchYeastRequest;
import com.example.starter.fermentation.api.YeastDtos.RecoverYeastRequest;
import com.example.starter.fermentation.api.YeastDtos.RecoveryResponse;
import com.example.starter.fermentation.api.YeastDtos.RegisterYeastRequest;
import com.example.starter.fermentation.api.YeastDtos.YeastUnitResponse;
import com.example.starter.fermentation.domain.BatchStatus;
import com.example.starter.fermentation.domain.FermentationBatch;
import com.example.starter.fermentation.domain.YeastRecovery;
import com.example.starter.fermentation.domain.YeastStatus;
import com.example.starter.fermentation.domain.YeastUnit;
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
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Supplier;

/**
 * 合成酵母单元受控投酵、代次限用与回收追溯服务。
 *
 * <p>核心口径：初始代次 0、AVAILABLE；当前代次 ≤ 5 可用（恰 5 允许本次投酵），代次 6 不可启动新主酵；
 * 可用单元距最近回收超过 72 小时失效（恰 72 小时仍可用），首次单元无回收时刻不受限；
 * 一单元同时刻仅供一个正在主酵的批次，投酵绑定为同一事务的条件更新，并发仅一方成功；
 * 回收仅在绑定批次完成主酵后受理，活率 ≥ 90%（恰 90% 合格）且无污染转可用并代次加 1，否则报废；
 * 回收结果、回收追溯记录与解除批次占用同事务提交，每批次（含失败批次）仅回收一次。
 */
@Service
public class YeastService {

    /** 允许投酵的最大代次：恰 5 允许本次投酵，代次 6 拒绝。 */
    static final int MAX_PITCH_GENERATION = 5;
    /** 回收活率合格下限（百分数），恰 90.00 合格。 */
    static final BigDecimal VIABILITY_LIMIT = new BigDecimal("90.00");
    /** 可用单元距最近回收的有效时长，恰 72 小时仍可用。 */
    static final Duration RECOVERY_VALIDITY = Duration.ofHours(72);

    private final YeastRepository yeastRepository;
    private final BatchRepository batchRepository;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public YeastService(YeastRepository yeastRepository, BatchRepository batchRepository,
                        IdempotencyService idempotencyService, ObjectMapper objectMapper,
                        Clock clock) {
        this.yeastRepository = yeastRepository;
        this.batchRepository = batchRepository;
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 登记合成酵母单元：初始代次 0、AVAILABLE、版本 1。编号重复返回 409。
     */
    @Transactional
    public StoredResponse register(RegisterYeastRequest req) {
        String endpoint = "POST /api/yeast-units";
        String fingerprint = idempotencyService.fingerprint(endpoint, req.requestId(),
                structured(req.unitCode()));
        return idempotent(req.requestId(), endpoint, fingerprint, () -> {
            try {
                yeastRepository.insert(req.unitCode());
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("酵母单元编号已存在: " + req.unitCode());
            }
            YeastUnit unit = mustFindUnit(req.unitCode());
            return new StoredResponse(201, toJson(toUnitResponse(unit)));
        });
    }

    /**
     * 受控投酵：指定酵母原子绑定并启动主酵（FILLED → MAIN_FERMENT）。
     * 重复启动（批次已非 FILLED）返回 422 且不增加任何计数；并发绑定同单元最多一方成功。
     */
    @Transactional
    public StoredResponse pitch(long batchId, PitchYeastRequest req) {
        String endpoint = "POST /api/batches/" + batchId + "/yeast-pitch";
        String fingerprint = idempotencyService.fingerprint(endpoint, req.requestId(),
                structured(req.yeastUnitCode(), req.expectedVersion(), req.startedAt().toString()));
        return idempotent(req.requestId(), endpoint, fingerprint, () -> {
            FermentationBatch batch = mustFindBatch(batchId);
            if (batch.status() != BatchStatus.FILLED) {
                throw ApiException.unprocessable(
                        "仅满罐批次可受控投酵开始主酵（当前状态 " + batch.status() + "），重复启动不增次数");
            }
            YeastUnit unit = mustFindUnit(req.yeastUnitCode());
            assertPitchable(unit, req.expectedVersion(), req.startedAt());

            // 批次与单元的条件更新在同一事务内：任一条件被并发改变则整体回滚
            int started = batchRepository.tryStartMainFerment(batchId, req.startedAt());
            if (started == 0) {
                throw ApiException.unprocessable("批次已启动主酵，重复启动不予受理");
            }
            int bound = yeastRepository.bindForPitch(unit.unitCode(), req.expectedVersion(), batchId);
            if (bound == 0) {
                throw ApiException.conflict(
                        "酵母单元 " + unit.unitCode() + " 已被并发占用或版本已变更，本次投酵未生效");
            }

            YeastUnit after = mustFindUnit(unit.unitCode());
            PitchResponse response = new PitchResponse(batchId, batch.batchNo(),
                    BatchStatus.MAIN_FERMENT.name(), after.unitCode(), after.generation(),
                    after.status(), after.version());
            return new StoredResponse(200, toJson(response));
        });
    }

    /**
     * 回收：仅在绑定批次完成主酵（已离开主酵阶段）后受理。回收判定、追溯记录与
     * 解除批次占用同事务提交；不修改批次的成熟度判定与清酒转出记录。每批次仅回收一次。
     */
    @Transactional
    public StoredResponse recover(long batchId, RecoverYeastRequest req) {
        String endpoint = "POST /api/batches/" + batchId + "/yeast-recovery";
        String fingerprint = idempotencyService.fingerprint(endpoint, req.requestId(),
                structured(req.expectedVersion(), req.recoveredAt().toString(),
                        req.viabilityPercent().toPlainString(), req.contaminated()));
        return idempotent(req.requestId(), endpoint, fingerprint, () -> {
            mustFindBatch(batchId);
            if (yeastRepository.recoveryExistsForBatch(batchId)) {
                throw ApiException.unprocessable("该批次已受理过回收，失败批次也只能回收一次");
            }
            YeastUnit unit = yeastRepository.findOccupiedByBatch(batchId)
                    .orElseThrow(() -> ApiException.unprocessable(
                            "批次未处于受控投酵后的待回收状态（未绑定在用酵母或已解除占用）"));
            FermentationBatch batch = mustFindBatch(batchId);
            if (batch.status() == BatchStatus.FILLED || batch.status() == BatchStatus.MAIN_FERMENT) {
                throw ApiException.unprocessable(
                        "绑定批次尚未完成主酵（当前状态 " + batch.status() + "），不受理回收");
            }
            if (unit.version() != req.expectedVersion()) {
                throw ApiException.conflict(
                        "酵母单元版本已变更：期望 " + req.expectedVersion() + "，实际 " + unit.version());
            }

            boolean qualified = req.viabilityPercent().compareTo(VIABILITY_LIMIT) >= 0
                    && !req.contaminated();
            int generationAfter = qualified ? unit.generation() + 1 : unit.generation();
            YeastStatus statusAfter = qualified ? YeastStatus.AVAILABLE : YeastStatus.SCRAPPED;

            int updated = qualified
                    ? yeastRepository.applyQualifiedRecovery(unit.unitCode(), req.expectedVersion(),
                            req.recoveredAt())
                    : yeastRepository.applyScrappedRecovery(unit.unitCode(), req.expectedVersion());
            if (updated == 0) {
                throw ApiException.conflict(
                        "酵母单元 " + unit.unitCode() + " 已被并发变更，本次回收未生效");
            }
            long recoveryId;
            try {
                recoveryId = yeastRepository.insertRecovery(unit.unitCode(), batchId,
                        req.recoveredAt(), req.viabilityPercent(), req.contaminated(), qualified,
                        generationAfter, statusAfter, LocalDateTime.now(clock));
            } catch (DuplicateKeyException e) {
                // 并发回收同批次：唯一约束兜底，失败批次也只能回收一次
                throw ApiException.unprocessable("该批次已受理过回收，每批次仅允许回收一次");
            }

            YeastUnit after = mustFindUnit(unit.unitCode());
            RecoveryResponse response = new RecoveryResponse(recoveryId, after.unitCode(), batchId,
                    req.recoveredAt(), req.viabilityPercent(), req.contaminated(), qualified,
                    generationAfter, statusAfter, after.version());
            return new StoredResponse(200, toJson(response));
        });
    }

    /** 查询酵母单元，不存在返回 404。 */
    @Transactional(readOnly = true)
    public YeastUnitResponse getUnit(String unitCode) {
        return toUnitResponse(mustFindUnit(unitCode));
    }

    /** 查询批次的酵母回收追溯记录，不存在返回 404。 */
    @Transactional(readOnly = true)
    public RecoveryResponse getRecovery(long batchId) {
        mustFindBatch(batchId);
        YeastRecovery r = yeastRepository.findRecoveryByBatch(batchId)
                .orElseThrow(() -> ApiException.notFound("批次无酵母回收记录: " + batchId));
        int yeastVersion = yeastRepository.findByCode(r.unitCode())
                .map(YeastUnit::version).orElse(0);
        return new RecoveryResponse(r.id(), r.unitCode(), r.batchId(), r.recoveredAt(),
                r.viability(), r.contaminated(), r.qualified(), r.generationAfter(),
                r.statusAfter(), yeastVersion);
    }

    /**
     * 投酵前置判定：版本一致、状态可用、代次 ≤ 5、回收未超 72 小时。
     */
    private void assertPitchable(YeastUnit unit, int expectedVersion, LocalDateTime startedAt) {
        if (unit.version() != expectedVersion) {
            throw ApiException.conflict(
                    "酵母单元版本已变更：期望 " + expectedVersion + "，实际 " + unit.version());
        }
        if (unit.status() == YeastStatus.SCRAPPED) {
            throw ApiException.unprocessable("酵母单元已报废，不可再投酵");
        }
        if (unit.status() == YeastStatus.IN_USE) {
            throw ApiException.unprocessable(
                    "酵母单元已被批次 " + unit.occupiedByBatchId() + " 占用，一单元仅供一个主酵批次");
        }
        if (unit.generation() > MAX_PITCH_GENERATION) {
            throw ApiException.unprocessable(
                    "酵母单元代次 " + unit.generation() + " 已达限，代次 6 不能启动新主酵");
        }
        if (unit.lastRecoveredAt() != null) {
            Duration elapsed = Duration.between(unit.lastRecoveredAt(), startedAt);
            if (elapsed.compareTo(RECOVERY_VALIDITY) > 0) {
                throw ApiException.unprocessable(
                        "酵母单元距最近回收已超过 72 小时（实际 " + elapsed.toHours()
                                + " 小时），已失效不可投酵");
            }
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

    private FermentationBatch mustFindBatch(long batchId) {
        return batchRepository.findById(batchId)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchId));
    }

    private YeastUnit mustFindUnit(String unitCode) {
        return yeastRepository.findByCode(unitCode)
                .orElseThrow(() -> ApiException.notFound("酵母单元不存在: " + unitCode));
    }

    private YeastUnitResponse toUnitResponse(YeastUnit u) {
        return new YeastUnitResponse(u.unitCode(), u.generation(), u.status(),
                u.lastRecoveredAt(), u.occupiedByBatchId(), u.version());
    }

    /**
     * 结构化业务字段：JSON 数组序列化，字段中的分隔符（如 '|'）经 JSON 转义后仍无歧义。
     */
    private String structured(Object... fields) {
        try {
            return objectMapper.writeValueAsString(List.of(fields));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("请求指纹序列化失败", e);
        }
    }

    private String toJson(Object dto) {
        try {
            return objectMapper.writeValueAsString(dto);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }
}
