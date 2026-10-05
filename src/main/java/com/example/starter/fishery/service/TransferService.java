package com.example.starter.fishery.service;

import com.example.starter.fishery.api.BusinessRuleException;
import com.example.starter.fishery.api.ConflictException;
import com.example.starter.fishery.api.NotFoundException;
import com.example.starter.fishery.api.dto.TransferConfirmRequest;
import com.example.starter.fishery.api.dto.TransferRequest;
import com.example.starter.fishery.api.dto.TransferResponse;
import com.example.starter.fishery.db.CatalogRepository;
import com.example.starter.fishery.db.IdempotencyRepository;
import com.example.starter.fishery.db.IdempotencyRepository.AcquireResult;
import com.example.starter.fishery.db.QuotaRepository;
import com.example.starter.fishery.db.QuotaRow;
import com.example.starter.fishery.db.TransferRepository;
import com.example.starter.fishery.db.TransferRow;
import com.example.starter.fishery.domain.TransferStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW;

/**
 * 跨船配额调剂应用服务。
 *
 * <p>关键口径：同鱼种同年度跨船调剂，发起即视为发起方确认，对方确认后生效；
 * 调剂量恰等调出船剩余额度合法，超出整笔拒绝；确认时限按 UTC 计 48 小时，
 * 恰满未确认自动失效且额度不变，失效单可重新发起；同船同鱼种同年度至多一张
 * 进行中调剂单（数据库护栏唯一受理）；按 requestId 幂等，指纹覆盖 requestId
 * 与全部业务字段，同键同参重放首次结果，异参 409。
 */
@Service
public class TransferService {

    /** 双方确认时限（UTC 48 小时，恰满即失效）。 */
    static final Duration CONFIRM_TIMEOUT = Duration.ofHours(48);

    private static final String OP_TRANSFER_CREATE = "TRANSFER_CREATE";
    private static final String OP_TRANSFER_CONFIRM = "TRANSFER_CONFIRM";

    private final TransferRepository transferRepo;
    private final QuotaRepository quotaRepo;
    private final CatalogRepository catalogRepo;
    private final IdempotencyRepository idempotencyRepo;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionTemplate requiresNewTx;

    public TransferService(TransferRepository transferRepo, QuotaRepository quotaRepo,
                           CatalogRepository catalogRepo, IdempotencyRepository idempotencyRepo,
                           ObjectMapper objectMapper, Clock clock,
                           PlatformTransactionManager transactionManager) {
        this.transferRepo = transferRepo;
        this.quotaRepo = quotaRepo;
        this.catalogRepo = catalogRepo;
        this.idempotencyRepo = idempotencyRepo;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.requiresNewTx = new TransactionTemplate(transactionManager);
        this.requiresNewTx.setPropagationBehavior(PROPAGATION_REQUIRES_NEW);
    }

    // ------------------------------------------------------------------
    // 发起调剂
    // ------------------------------------------------------------------

    @Transactional
    public TransferResponse create(TransferRequest request) {
        String fingerprint = JsonSupport.fingerprint(objectMapper, Map.of(
                "op", OP_TRANSFER_CREATE,
                "requestId", request.requestId(),
                "fromVesselCode", request.fromVesselCode(),
                "toVesselCode", request.toVesselCode(),
                "speciesCode", request.speciesCode(),
                "quotaYear", request.quotaYear().toString(),
                "amountKg", request.amountKg().toPlainString()));
        AcquireResult acquired = idempotencyRepo.acquire(
                request.requestId(), OP_TRANSFER_CREATE, fingerprint, Instant.now(clock));
        switch (acquired.outcome()) {
            case REPLAY -> {
                return JsonSupport.replay(objectMapper, acquired.responseJson(), TransferResponse.class);
            }
            case MISMATCH -> throw new ConflictException("相同 requestId 已用于不同业务参数");
            case IN_PROGRESS -> throw new ConflictException("相同 requestId 请求正在处理中");
            default -> { }
        }

        requireVessel(request.fromVesselCode());
        requireVessel(request.toVesselCode());
        if (catalogRepo.findSpecies(request.speciesCode()) == null) {
            throw new NotFoundException("鱼种 " + request.speciesCode() + " 不存在");
        }
        if (request.fromVesselCode().equals(request.toVesselCode())) {
            throw new BusinessRuleException("调出与调入渔船不能相同");
        }

        Instant now = Instant.now(clock);
        // 先失效双方涉及的超时在途单，失效单可重新发起。
        expireOverduePending(request.fromVesselCode(), request.toVesselCode(),
                request.speciesCode(), request.quotaYear(), now);

        // 持锁校验：调剂量恰等调出船剩余额度合法，超出整笔拒绝。
        QuotaRow fromQuota = quotaRepo.findForUpdate(request.fromVesselCode(),
                request.speciesCode(), request.quotaYear()).orElse(null);
        BigDecimal remaining = remainingOf(fromQuota);
        if (request.amountKg().compareTo(remaining) > 0) {
            throw new BusinessRuleException("调剂量 " + request.amountKg().toPlainString()
                    + "kg 超出调出船 " + request.fromVesselCode() + " 鱼种 " + request.speciesCode()
                    + " 剩余额度 " + remaining.toPlainString() + "kg，整笔拒绝");
        }

        long transferId = transferRepo.insert(request.requestId(), request.fromVesselCode(),
                request.toVesselCode(), request.speciesCode(), request.quotaYear(),
                request.amountKg(), now, now.plus(CONFIRM_TIMEOUT));

        // 同船同鱼种同年度至多一张进行中调剂单（双方均占用护栏，数据库唯一约束串行化并发）。
        if (!transferRepo.tryOccupyGuard(request.fromVesselCode(), request.speciesCode(),
                request.quotaYear(), transferId)
                || !transferRepo.tryOccupyGuard(request.toVesselCode(), request.speciesCode(),
                request.quotaYear(), transferId)) {
            throw new ConflictException("渔船 " + request.fromVesselCode() + " 或 "
                    + request.toVesselCode() + " 鱼种 " + request.speciesCode()
                    + " 已存在进行中调剂单，同船同鱼种同时刻只允许一张");
        }

        TransferRow saved = transferRepo.findById(transferId).orElseThrow();
        TransferResponse response = toResponse(saved, false);
        idempotencyRepo.saveResponse(request.requestId(), JsonSupport.toJson(objectMapper, response));
        return response;
    }

    // ------------------------------------------------------------------
    // 对方确认（生效与双方额度变更在同一事务内完成）
    // ------------------------------------------------------------------

    @Transactional
    public TransferResponse confirm(long transferId, TransferConfirmRequest request) {
        String fingerprint = JsonSupport.fingerprint(objectMapper, Map.of(
                "op", OP_TRANSFER_CONFIRM,
                "requestId", request.requestId(),
                "transferId", String.valueOf(transferId)));
        AcquireResult acquired = idempotencyRepo.acquire(
                request.requestId(), OP_TRANSFER_CONFIRM, fingerprint, Instant.now(clock));
        switch (acquired.outcome()) {
            case REPLAY -> {
                return JsonSupport.replay(objectMapper, acquired.responseJson(), TransferResponse.class);
            }
            case MISMATCH -> throw new ConflictException("相同 requestId 已用于不同业务参数");
            case IN_PROGRESS -> throw new ConflictException("相同 requestId 请求正在处理中");
            default -> { }
        }

        // 恰满 48 小时未确认自动失效（独立事务提交失效，额度不变），再按最新状态处理。
        expireIfOverdue(transferId, Instant.now(clock));

        TransferRow transfer = transferRepo.findByIdForUpdate(transferId)
                .orElseThrow(() -> new NotFoundException("调剂单 " + transferId + " 不存在"));
        if (transfer.status() == TransferStatus.EXPIRED) {
            throw new ConflictException("调剂单 " + transferId + " 已失效，不能确认");
        }
        if (transfer.status() == TransferStatus.EFFECTIVE) {
            throw new ConflictException("调剂单 " + transferId + " 已生效，不能重复确认");
        }

        Instant now = Instant.now(clock);
        // 生效前持锁复核调出船剩余额度：期间额度可能已被核销/调出，超出整笔拒绝。
        QuotaRow fromQuota = quotaRepo.findForUpdate(transfer.fromVesselCode(),
                transfer.speciesCode(), transfer.quotaYear()).orElse(null);
        BigDecimal remaining = remainingOf(fromQuota);
        if (transfer.amountKg().compareTo(remaining) > 0) {
            throw new BusinessRuleException("调剂量 " + transfer.amountKg().toPlainString()
                    + "kg 超出调出船 " + transfer.fromVesselCode() + " 当前剩余额度 "
                    + remaining.toPlainString() + "kg，整笔拒绝");
        }

        // 同一事务内完成双方额度变更：调出船核减、调入船核增（无配额行则初始化）。
        quotaRepo.adjustQuotaKgLocked(fromQuota, transfer.amountKg().negate());
        Optional<QuotaRow> toQuota = quotaRepo.findForUpdate(transfer.toVesselCode(),
                transfer.speciesCode(), transfer.quotaYear());
        if (toQuota.isPresent()) {
            quotaRepo.adjustQuotaKgLocked(toQuota.get(), transfer.amountKg());
        } else {
            quotaRepo.insert(transfer.toVesselCode(), transfer.speciesCode(),
                    transfer.quotaYear(), transfer.amountKg());
        }

        transferRepo.markEffective(transferId, now);
        transferRepo.releaseGuards(transferId);

        TransferRow saved = transferRepo.findById(transferId).orElseThrow();
        TransferResponse response = toResponse(saved, false);
        idempotencyRepo.saveResponse(request.requestId(), JsonSupport.toJson(objectMapper, response));
        return response;
    }

    // ------------------------------------------------------------------
    // 查询（读取时惰性失效）
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public TransferResponse get(long transferId) {
        expireIfOverdue(transferId, Instant.now(clock));
        TransferRow transfer = transferRepo.findById(transferId)
                .orElseThrow(() -> new NotFoundException("调剂单 " + transferId + " 不存在"));
        return toResponse(transfer, false);
    }

    // ------------------------------------------------------------------
    // 辅助方法
    // ------------------------------------------------------------------

    /** 独立事务提交超时失效：恰满 48 小时未确认自动失效且额度不变。 */
    private void expireIfOverdue(long transferId, Instant now) {
        requiresNewTx.executeWithoutResult(status -> {
            Optional<TransferRow> row = transferRepo.findByIdForUpdate(transferId);
            if (row.isPresent() && row.get().status() == TransferStatus.PENDING
                    && !row.get().expiresAt().isAfter(now)) {
                transferRepo.markExpired(transferId);
                transferRepo.releaseGuards(transferId);
            }
        });
    }

    /** 发起前失效双方涉及的超时在途单（同事务），失效单可重新发起。 */
    private void expireOverduePending(String vesselA, String vesselB, String speciesCode,
                                      int quotaYear, Instant now) {
        for (TransferRow overdue : transferRepo.findOverduePendingForVessels(
                vesselA, vesselB, speciesCode, quotaYear, now)) {
            transferRepo.markExpired(overdue.id());
            transferRepo.releaseGuards(overdue.id());
        }
    }

    private static BigDecimal remainingOf(QuotaRow quota) {
        return quota == null ? BigDecimal.ZERO : quota.quotaKg().subtract(quota.usedKg());
    }

    private void requireVessel(String vesselCode) {
        if (catalogRepo.findVessel(vesselCode) == null) {
            throw new NotFoundException("渔船 " + vesselCode + " 不存在");
        }
    }

    private TransferResponse toResponse(TransferRow row, boolean replayed) {
        return new TransferResponse(row.id(), row.fromVesselCode(), row.toVesselCode(),
                row.speciesCode(), row.quotaYear(), row.amountKg(), row.status(),
                row.createdAt(), row.expiresAt(), row.confirmedAt(), replayed);
    }
}
