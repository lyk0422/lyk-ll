package com.example.starter.fermentation.service;

import com.example.starter.fermentation.api.BatchDtos.BatchResponse;
import com.example.starter.fermentation.api.YeastDtos.PitchYeastRequest;
import com.example.starter.fermentation.api.YeastDtos.RegisterYeastRequest;
import com.example.starter.fermentation.api.YeastDtos.YeastRecoveryRequest;
import com.example.starter.fermentation.api.YeastDtos.YeastRecoveryResponse;
import com.example.starter.fermentation.api.YeastDtos.YeastUnitResponse;
import com.example.starter.fermentation.domain.BatchStatus;
import com.example.starter.fermentation.domain.FermentationBatch;
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
import java.util.Set;
import java.util.function.Supplier;

/**
 * 合成酵母单元服务：登记、受控投酵原子绑定、回收追溯。
 *
 * <p>规则：初始代次 0、AVAILABLE；当前代次 ≤5 可投酵（恰 5 允许本次，代次 6 拒绝）；
 * 一个单元同时仅供一个正在主酵批次使用；可用单元距最近回收超过 72 小时失效（恰 72 仍可用），
 * 首次单元无回收时刻不受此限；回收仅在绑定批次完成主酵（进入降温或之后）后受理，
 * 活率 ≥90%（恰 90 合格）且无污染转可用并代次 +1，否则报废不可再投酵；
 * 回收结果与解除批次占用同事务；每个批次（含失败判定）只回收一次。
 *
 * <p>并发：投酵占用为条件更新（AVAILABLE + 版本 + 代次匹配才成立），按事务提交顺序裁决；
 * 回收与投酵经行锁互斥，后提交方按最新代次与状态判定。
 */
@Service
public class YeastService {

    /** 可投酵的最大当前代次：恰 5 仍允许本次投酵，回收成 6 后不能再启动新主酵。 */
    static final int MAX_PITCH_GENERATION = 5;
    /** 可用单元距最近回收的最长可投酵时长：恰 72 小时仍可用，超过失效。 */
    static final long PITCH_FRESHNESS_HOURS = 72;
    /** 回收合格活率下限（百分比）：恰 90.00 合格。 */
    static final BigDecimal RECOVERY_VIABILITY_MIN = new BigDecimal("90.00");

    /** 完成主酵（已进入降温或之后）的批次状态，仅这些状态受理回收。 */
    private static final Set<BatchStatus> MAIN_FERMENT_DONE = Set.of(
            BatchStatus.COOLING, BatchStatus.MATURATION, BatchStatus.JUDGED,
            BatchStatus.TRANSFERRED, BatchStatus.REVIEW);

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
     * 登记合成酵母单元：代次 0、AVAILABLE、版本 1、无回收时刻。重复编号返回 409。
     */
    @Transactional
    public StoredResponse register(RegisterYeastRequest req) {
        String endpoint = "POST /api/yeast-units";
        String fingerprint = idempotencyService.structuredFingerprint(endpoint, req.requestId(),
                req.unitCode());
        return idempotent(req.requestId(), endpoint, fingerprint, () -> {
            LocalDateTime now = LocalDateTime.now(clock);
            long id;
            try {
                id = yeastRepository.insert(req.unitCode(), now);
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("酵母单元编号已存在: " + req.unitCode());
            }
            YeastUnit unit = yeastRepository.findByCode(req.unitCode())
                    .orElseThrow(() -> ApiException.notFound("酵母单元不存在: " + req.unitCode()));
            return new StoredResponse(201, toJson(toResponse(unit, now)));
        });
    }

    /**
     * 受控投酵：批次必须处于 FILLED，酵母单元原子绑定并启动主酵（同事务）。
     * 重复启动不重复占用、不增代次；代次 6、已占用、报废、失效均拒绝。
     */
    @Transactional
    public StoredResponse pitch(long batchId, PitchYeastRequest req) {
        String endpoint = "POST /api/batches/" + batchId + "/yeast-pitch";
        String fingerprint = idempotencyService.structuredFingerprint(endpoint, req.requestId(),
                req.yeastUnitCode(), req.expectedVersion(), req.pitchedAt());
        return idempotent(req.requestId(), endpoint, fingerprint, () -> {
            FermentationBatch batch = mustFindBatch(batchId);
            if (batch.status() != BatchStatus.FILLED) {
                throw ApiException.unprocessable(
                        "仅满罐批次可受控投酵启动主酵（当前状态 " + batch.status() + "）");
            }
            YeastUnit unit = mustFindUnit(req.yeastUnitCode());
            assertPitchable(unit, req.expectedVersion(), req.pitchedAt());

            // 条件更新原子占用：并发两批投同一单元时，仅先提交事务成功
            int updated = yeastRepository.tryOccupy(req.yeastUnitCode(), req.expectedVersion(),
                    batchId, MAX_PITCH_GENERATION, req.pitchedAt());
            if (updated == 0) {
                // 状态/版本/代次/新鲜度已被并发事务改变：重读后按最新数据裁决
                YeastUnit latest = mustFindUnit(req.yeastUnitCode());
                if (latest.version() != req.expectedVersion()) {
                    throw ApiException.conflict(
                            "酵母单元版本已变化，请重读最新版本后再投酵（当前版本 "
                                    + latest.version() + "）");
                }
                assertPitchable(latest, req.expectedVersion(), req.pitchedAt());
                throw ApiException.conflict("酵母单元当前不可投酵: " + req.yeastUnitCode());
            }
            batchRepository.startMainFermentWithYeast(batchId, req.yeastUnitCode(), req.pitchedAt());
            return new StoredResponse(200, toJson(toBatchResponse(mustFindBatch(batchId))));
        });
    }

    /**
     * 回收：绑定批次完成主酵后受理。合格（活率 ≥90% 恰 90 合格且无污染）转可用并代次 +1；
     * 否则报废。回收结论、回收记录、解除批次占用与幂等受理同事务，每批次仅一次。
     */
    @Transactional
    public StoredResponse recover(long batchId, YeastRecoveryRequest req) {
        String endpoint = "POST /api/batches/" + batchId + "/yeast-recovery";
        String fingerprint = idempotencyService.structuredFingerprint(endpoint, req.requestId(),
                req.expectedVersion(), req.recoveredAt(), req.viabilityPercent(),
                req.contaminated());
        return idempotent(req.requestId(), endpoint, fingerprint, () -> {
            FermentationBatch batch = mustFindBatch(batchId);
            if (batch.yeastUnitCode() == null) {
                throw ApiException.unprocessable("该批次未启用受控投酵，无绑定酵母可回收");
            }
            if (yeastRepository.findRecoveryByBatch(batchId).isPresent()) {
                throw ApiException.unprocessable("该批次已受理过回收，失败批次也只能回收一次");
            }
            if (!MAIN_FERMENT_DONE.contains(batch.status())) {
                throw ApiException.unprocessable(
                        "仅绑定批次完成主酵（进入降温或之后）后可回收酵母，当前状态 "
                                + batch.status());
            }
            YeastUnit unit = mustFindUnit(batch.yeastUnitCode());
            if (unit.status() != YeastStatus.IN_USE
                    || unit.occupiedByBatchId() == null
                    || unit.occupiedByBatchId() != batchId) {
                throw ApiException.unprocessable(
                        "酵母单元未被本批次占用，不能由本批次回收（当前状态 " + unit.status() + "）");
            }
            if (unit.version() != req.expectedVersion()) {
                throw ApiException.conflict(
                        "酵母单元版本已变化，请重读最新版本后再回收（当前版本 "
                                + unit.version() + "）");
            }
            if (req.recoveredAt().isBefore(unit.lastRecoveredAt() != null
                    ? unit.lastRecoveredAt() : java.time.LocalDateTime.MIN)) {
                throw ApiException.unprocessable("回收时刻早于该单元最近回收时刻");
            }

            boolean qualified = !req.contaminated()
                    && req.viabilityPercent().compareTo(RECOVERY_VIABILITY_MIN) >= 0;
            int generationAfter = qualified ? unit.generation() + 1 : unit.generation();
            YeastStatus newStatus = qualified ? YeastStatus.AVAILABLE : YeastStatus.SCRAPPED;

            int updated = yeastRepository.applyRecovery(unit.unitCode(), req.expectedVersion(),
                    newStatus, generationAfter, req.recoveredAt());
            if (updated == 0) {
                YeastUnit latest = mustFindUnit(unit.unitCode());
                throw ApiException.conflict(
                        "酵母单元并发变更，回收未受理（当前版本 " + latest.version() + "，状态 "
                                + latest.status() + "）");
            }
            // 回收同事务后重读单元，取得回收后的最新版本（业务结论与版本结果同事务可见）
            int unitVersionAfter = mustFindUnit(unit.unitCode()).version();
            LocalDateTime now = LocalDateTime.now(clock);
            long recordId;
            try {
                recordId = yeastRepository.insertRecovery(unit.unitCode(), batchId,
                        req.viabilityPercent(), req.contaminated(), qualified, generationAfter,
                        unitVersionAfter, req.recoveredAt(), now);
            } catch (DuplicateKeyException e) {
                throw ApiException.unprocessable("该批次已受理过回收，失败批次也只能回收一次");
            }
            YeastRecoveryResponse response = new YeastRecoveryResponse(recordId, unit.unitCode(),
                    batchId, req.viabilityPercent(), req.contaminated(), qualified, generationAfter,
                    unitVersionAfter, req.recoveredAt(), now);
            return new StoredResponse(200, toJson(response));
        });
    }

    /** 查询酵母单元；有效状态叠加按当前时钟计算的 72 小时失效。 */
    @Transactional(readOnly = true)
    public YeastUnitResponse getUnit(String unitCode) {
        return toResponse(mustFindUnit(unitCode), LocalDateTime.now(clock));
    }

    /** 查询单元回收记录链（按回收时刻升序）。 */
    @Transactional(readOnly = true)
    public List<YeastRecoveryResponse> getRecoveries(String unitCode) {
        mustFindUnit(unitCode);
        return yeastRepository.findRecoveriesByUnit(unitCode).stream()
                .map(r -> new YeastRecoveryResponse(r.id(), r.yeastUnitCode(), r.batchId(),
                        r.viabilityPercent(), r.contaminated(), r.qualified(), r.generationAfter(),
                        r.unitVersionAfter(), r.recoveredAt(), r.createdAt()))
                .toList();
    }

    /**
     * 投酵前置业务校验：版本、代次上限、状态与 72 小时新鲜度。
     */
    private void assertPitchable(YeastUnit unit, int expectedVersion, LocalDateTime pitchedAt) {
        if (unit.version() != expectedVersion) {
            throw ApiException.conflict(
                    "酵母单元版本不匹配，请重读最新版本后再投酵（当前版本 " + unit.version() + "）");
        }
        if (unit.status() == YeastStatus.SCRAPPED) {
            throw ApiException.unprocessable("酵母单元已报废，不可再投酵");
        }
        if (unit.status() == YeastStatus.EXPIRED) {
            throw ApiException.unprocessable("酵母单元已失效，不可再投酵");
        }
        if (unit.status() == YeastStatus.IN_USE) {
            throw ApiException.unprocessable(
                    "酵母单元已被批次 " + unit.occupiedByBatchId() + " 占用，一个单元同时仅供一个主酵批次");
        }
        if (unit.generation() > MAX_PITCH_GENERATION) {
            throw ApiException.unprocessable(
                    "酵母单元代次 " + unit.generation() + " 不能启动新主酵（最高允许代次 5）");
        }
        if (unit.lastRecoveredAt() != null) {
            if (pitchedAt.isBefore(unit.lastRecoveredAt())) {
                throw ApiException.unprocessable("投酵时刻早于酵母单元最近回收时刻");
            }
            Duration age = Duration.between(unit.lastRecoveredAt(), pitchedAt);
            if (age.compareTo(Duration.ofHours(PITCH_FRESHNESS_HOURS)) > 0) {
                throw ApiException.unprocessable(
                        "酵母单元距最近回收已 " + age.toHours() + " 小时，超过 72 小时已失效，不可投酵");
            }
        }
    }

    private StoredResponse idempotent(String requestId, String endpoint, String fingerprint,
                                      Supplier<StoredResponse> action) {
        try {
            return idempotencyService.execute(requestId, endpoint, fingerprint, action);
        } catch (ConcurrentReplayException e) {
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

    private YeastUnitResponse toResponse(YeastUnit u, LocalDateTime now) {
        boolean expired = u.status() == YeastStatus.AVAILABLE && u.lastRecoveredAt() != null
                && Duration.between(u.lastRecoveredAt(), now)
                        .compareTo(Duration.ofHours(PITCH_FRESHNESS_HOURS)) > 0;
        YeastStatus effective = expired ? YeastStatus.EXPIRED : u.status();
        return new YeastUnitResponse(u.id(), u.unitCode(), u.generation(), u.status(), effective,
                expired, u.lastRecoveredAt(), u.occupiedByBatchId(), u.version(), u.createdAt());
    }

    private BatchResponse toBatchResponse(FermentationBatch b) {
        return new BatchResponse(b.id(), b.batchNo(), b.status(), b.curveCode(), b.curveVersion(),
                b.maturationExtended(), b.filledAt(), b.mainFermentStartedAt(), b.coolingStartedAt(),
                b.maturationStartedAt(), b.judgedAt(), b.transferredAt(), b.yeastUnitCode());
    }

    private String toJson(Object dto) {
        try {
            return objectMapper.writeValueAsString(dto);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }
}
