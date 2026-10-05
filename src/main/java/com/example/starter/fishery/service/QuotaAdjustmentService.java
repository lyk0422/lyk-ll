package com.example.starter.fishery.service;

import com.example.starter.fishery.api.BusinessRuleException;
import com.example.starter.fishery.api.ConflictException;
import com.example.starter.fishery.api.NotFoundException;
import com.example.starter.fishery.api.dto.CarryoverRequest;
import com.example.starter.fishery.api.dto.CarryoverResponse;
import com.example.starter.fishery.api.dto.TransferConfirmRequest;
import com.example.starter.fishery.api.dto.TransferCreateRequest;
import com.example.starter.fishery.api.dto.TransferResponse;
import com.example.starter.fishery.db.CarryoverRepository;
import com.example.starter.fishery.db.CatalogRepository;
import com.example.starter.fishery.db.IdempotencyRepository;
import com.example.starter.fishery.db.IdempotencyRepository.AcquireResult;
import com.example.starter.fishery.db.QuotaRepository;
import com.example.starter.fishery.db.QuotaRow;
import com.example.starter.fishery.db.TransferRepository;
import com.example.starter.fishery.db.TransferRow;
import com.example.starter.fishery.domain.TransferStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

/**
 * 配额调剂与年度结转应用服务。
 *
 * <p>调剂：同鱼种同年度跨船调剂，双方确认后生效，生效与双方额度变更在同一事务内完成；
 * 调剂量恰等调出船剩余额度合法，超出整笔拒绝；同船同鱼种同时刻只允许一张进行中调剂单
 * （数据库护栏唯一受理）；确认时限按 UTC 计 48 小时，恰满未确认自动失效且额度不变，失效单可重新发起。
 *
 * <p>结转：年度末未用配额按不超过年度核定配额 20% 结转至次年（恰 20% 合法），
 * 结转与次年额度初始化原子完成；已触发产地预警的额度不得结转；结转额度次年台账单列并优先核销。
 */
@Service
public class QuotaAdjustmentService {

    /** 双方确认时限（UTC 48 小时，恰满未确认自动失效）。 */
    static final Duration CONFIRM_WINDOW = Duration.ofHours(48);
    /** 年度结转上限比例（不超过年度核定配额的 20%，恰 20% 合法）。 */
    static final BigDecimal CARRYOVER_LIMIT_RATIO = new BigDecimal("0.2");

    private static final String OP_TRANSFER_CREATE = "TRANSFER_CREATE";
    private static final String OP_TRANSFER_CONFIRM = "TRANSFER_CONFIRM";
    private static final String OP_CARRYOVER = "CARRYOVER";

    private final TransferRepository transferRepo;
    private final CarryoverRepository carryoverRepo;
    private final QuotaRepository quotaRepo;
    private final CatalogRepository catalogRepo;
    private final IdempotencyRepository idempotencyRepo;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public QuotaAdjustmentService(TransferRepository transferRepo, CarryoverRepository carryoverRepo,
                                  QuotaRepository quotaRepo, CatalogRepository catalogRepo,
                                  IdempotencyRepository idempotencyRepo, ObjectMapper objectMapper, Clock clock) {
        this.transferRepo = transferRepo;
        this.carryoverRepo = carryoverRepo;
        this.quotaRepo = quotaRepo;
        this.catalogRepo = catalogRepo;
        this.idempotencyRepo = idempotencyRepo;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // ------------------------------------------------------------------
    // 跨船调剂：发起
    // ------------------------------------------------------------------

    @Transactional
    public TransferResponse createTransfer(TransferCreateRequest request) {
        String fingerprint = fingerprint(Map.of(
                "op", OP_TRANSFER_CREATE,
                "fromVessel", request.fromVessel(),
                "toVessel", request.toVessel(),
                "speciesCode", request.speciesCode(),
                "quotaYear", request.quotaYear().toString(),
                "amountKg", request.amountKg().toPlainString()));
        AcquireResult acquired = idempotencyRepo.acquire(
                request.requestId(), OP_TRANSFER_CREATE, fingerprint, Instant.now(clock));
        switch (acquired.outcome()) {
            case REPLAY -> {
                return replay(acquired.responseJson(), TransferResponse.class);
            }
            case MISMATCH -> throw new ConflictException("相同 requestId 已用于不同业务参数");
            case IN_PROGRESS -> throw new ConflictException("相同 requestId 请求正在处理中");
            default -> { }
        }

        if (request.fromVessel().equals(request.toVessel())) {
            throw new BusinessRuleException("调出方与调入方不能为同一渔船");
        }
        requireVessel(request.fromVessel());
        requireVessel(request.toVessel());
        requireSpecies(request.speciesCode());

        // 调剂量恰等调出船剩余额度合法，超出整笔拒绝（持行锁校验，并发核销不会放行超量调剂）。
        QuotaRow source = quotaRepo.findForUpdate(
                request.fromVessel(), request.speciesCode(), request.quotaYear()).orElse(null);
        BigDecimal remaining = source == null
                ? BigDecimal.ZERO : source.quotaKg().subtract(source.usedKg());
        if (request.amountKg().compareTo(remaining) > 0) {
            throw new BusinessRuleException("调剂量 " + request.amountKg().toPlainString()
                    + "kg 超过调出船 " + request.fromVessel() + " 鱼种 " + request.speciesCode() + " "
                    + request.quotaYear() + " 年度剩余额度 " + remaining.toPlainString() + "kg，整笔拒绝");
        }

        Instant now = Instant.now(clock);
        long transferId = transferRepo.insert(request.fromVessel(), request.toVessel(),
                request.speciesCode(), request.quotaYear(), request.amountKg(),
                now, now.plus(CONFIRM_WINDOW));
        // 同船同鱼种同时刻只允许一张进行中调剂单（调出方与调入方均占用护栏，失败整体回滚）。
        if (!transferRepo.tryOccupyGuard(request.fromVessel(), request.speciesCode(), transferId)
                || !transferRepo.tryOccupyGuard(request.toVessel(), request.speciesCode(), transferId)) {
            throw new ConflictException("渔船 " + request.fromVessel() + " 或 " + request.toVessel()
                    + " 在鱼种 " + request.speciesCode() + " 上已存在进行中调剂单，同时刻唯一受理");
        }

        TransferRow saved = transferRepo.findById(transferId).orElseThrow();
        TransferResponse response = toResponse(saved, false);
        idempotencyRepo.saveResponse(request.requestId(), toJson(response));
        return response;
    }

    // ------------------------------------------------------------------
    // 跨船调剂：双方确认
    // ------------------------------------------------------------------

    @Transactional
    public TransferResponse confirmTransfer(Long transferId, TransferConfirmRequest request) {
        String fingerprint = fingerprint(Map.of(
                "op", OP_TRANSFER_CONFIRM,
                "transferId", transferId.toString(),
                "vesselCode", request.vesselCode()));
        AcquireResult acquired = idempotencyRepo.acquire(
                request.requestId(), OP_TRANSFER_CONFIRM, fingerprint, Instant.now(clock));
        switch (acquired.outcome()) {
            case REPLAY -> {
                return replay(acquired.responseJson(), TransferResponse.class);
            }
            case MISMATCH -> throw new ConflictException("相同 requestId 已用于不同业务参数");
            case IN_PROGRESS -> throw new ConflictException("相同 requestId 请求正在处理中");
            default -> { }
        }

        TransferRow row = transferRepo.findByIdForUpdate(transferId)
                .orElseThrow(() -> new NotFoundException("调剂单 " + transferId + " 不存在"));
        Instant now = Instant.now(clock);
        expireIfDue(row, now);
        if (row.status() == TransferStatus.EXPIRED) {
            throw new ConflictException("调剂单 " + transferId + " 确认时限已满 48 小时，自动失效且额度不变");
        }
        if (row.status() != TransferStatus.PENDING) {
            throw new ConflictException("调剂单 " + transferId + " 非进行中状态，不能确认");
        }

        boolean fromSide = request.vesselCode().equals(row.fromVessel());
        boolean toSide = request.vesselCode().equals(row.toVessel());
        if (!fromSide && !toSide) {
            throw new BusinessRuleException("确认方 " + request.vesselCode() + " 不是调剂单 " + transferId
                    + " 的调出方或调入方");
        }
        if ((fromSide && row.fromConfirmed()) || (toSide && row.toConfirmed())) {
            throw new ConflictException("渔船 " + request.vesselCode() + " 已确认调剂单 " + transferId);
        }

        transferRepo.markConfirmed(transferId, fromSide, row.version());
        boolean otherSideConfirmed = fromSide ? row.toConfirmed() : row.fromConfirmed();
        if (otherSideConfirmed) {
            // 双方确认生效：双方额度变更与状态翻转在同一事务内完成，不出现单方变动。
            TransferRow confirmed = transferRepo.findById(transferId).orElseThrow();
            effectTransfer(confirmed, now);
        }

        TransferRow saved = transferRepo.findById(transferId).orElseThrow();
        TransferResponse response = toResponse(saved, false);
        idempotencyRepo.saveResponse(request.requestId(), toJson(response));
        return response;
    }

    /** 调剂单查询；确认时限恰满仍进行中的单在读取时自动失效（额度不变）。 */
    @Transactional
    public TransferResponse getTransfer(Long transferId) {
        TransferRow row = transferRepo.findByIdForUpdate(transferId)
                .orElseThrow(() -> new NotFoundException("调剂单 " + transferId + " 不存在"));
        expireIfDue(row, Instant.now(clock));
        return toResponse(transferRepo.findById(transferId).orElseThrow(), false);
    }

    /** 双方额度变更：调出方核减、调入方核增，与调剂单生效同一事务；行锁按船序获取避免死锁。 */
    private void effectTransfer(TransferRow row, Instant now) {
        String firstVessel = row.fromVessel().compareTo(row.toVessel()) <= 0 ? row.fromVessel() : row.toVessel();
        String secondVessel = firstVessel.equals(row.fromVessel()) ? row.toVessel() : row.fromVessel();
        QuotaRow first = quotaRepo.findForUpdate(firstVessel, row.speciesCode(), row.quotaYear()).orElse(null);
        QuotaRow second = quotaRepo.findForUpdate(secondVessel, row.speciesCode(), row.quotaYear()).orElse(null);
        QuotaRow source = row.fromVessel().equals(firstVessel) ? first : second;
        QuotaRow target = row.toVessel().equals(firstVessel) ? first : second;

        // 生效时点再次校验调出方剩余额度（发起后可能已发生核销），不足则整笔拒绝。
        BigDecimal remaining = source == null
                ? BigDecimal.ZERO : source.quotaKg().subtract(source.usedKg());
        if (row.amountKg().compareTo(remaining) > 0) {
            throw new BusinessRuleException("生效时调出船 " + row.fromVessel() + " 剩余额度 "
                    + remaining.toPlainString() + "kg 不足调剂量 " + row.amountKg().toPlainString()
                    + "kg，整笔拒绝");
        }
        quotaRepo.adjustQuotaLocked(source, row.amountKg().negate());
        if (target == null) {
            quotaRepo.insert(row.toVessel(), row.speciesCode(), row.quotaYear(), row.amountKg());
        } else {
            quotaRepo.adjustQuotaLocked(target, row.amountKg());
        }
        transferRepo.markEffective(row.id(), now, row.version());
        transferRepo.releaseGuards(row.id());
    }

    /** 确认时限按 UTC 计 48 小时，恰满（now &gt;= expiresAt）未确认自动失效并释放在途护栏。 */
    private void expireIfDue(TransferRow row, Instant now) {
        if (row.status() == TransferStatus.PENDING && !now.isBefore(row.expiresAt())) {
            transferRepo.markExpired(row.id(), row.version());
            transferRepo.releaseGuards(row.id());
        }
    }

    // ------------------------------------------------------------------
    // 年度结转
    // ------------------------------------------------------------------

    @Transactional
    public CarryoverResponse carryover(CarryoverRequest request) {
        String fingerprint = fingerprint(Map.of(
                "op", OP_CARRYOVER,
                "vesselCode", request.vesselCode(),
                "speciesCode", request.speciesCode(),
                "fromYear", request.fromYear().toString()));
        AcquireResult acquired = idempotencyRepo.acquire(
                request.requestId(), OP_CARRYOVER, fingerprint, Instant.now(clock));
        switch (acquired.outcome()) {
            case REPLAY -> {
                return replay(acquired.responseJson(), CarryoverResponse.class);
            }
            case MISMATCH -> throw new ConflictException("相同 requestId 已用于不同业务参数");
            case IN_PROGRESS -> throw new ConflictException("相同 requestId 请求正在处理中");
            default -> { }
        }

        requireVessel(request.vesselCode());
        requireSpecies(request.speciesCode());
        int fromYear = request.fromYear();
        int toYear = fromYear + 1;
        Instant now = Instant.now(clock);
        int currentYear = now.atZone(ZoneOffset.UTC).getYear();
        if (currentYear <= fromYear) {
            throw new BusinessRuleException("年度 " + fromYear + " 尚未结束，不能结转（当前 UTC 年度 "
                    + currentYear + "）");
        }

        QuotaRow source = quotaRepo.findForUpdate(request.vesselCode(), request.speciesCode(), fromYear)
                .orElseThrow(() -> new NotFoundException("渔船 " + request.vesselCode() + " 鱼种 "
                        + request.speciesCode() + " " + fromYear + " 年度无核定配额"));
        if (source.alertTriggered()) {
            throw new BusinessRuleException("渔船 " + request.vesselCode() + " 鱼种 " + request.speciesCode()
                    + " " + fromYear + " 年度额度已触发产地预警，不得结转");
        }
        BigDecimal remaining = source.quotaKg().subtract(source.usedKg());
        if (remaining.signum() <= 0) {
            throw new BusinessRuleException("渔船 " + request.vesselCode() + " 鱼种 " + request.speciesCode()
                    + " " + fromYear + " 年度无未用配额可结转");
        }
        // 结转额度 = min(未用配额, 年度核定配额 × 20%)；恰 20% 合法。
        BigDecimal cap = source.quotaKg().multiply(CARRYOVER_LIMIT_RATIO)
                .setScale(3, RoundingMode.HALF_UP);
        BigDecimal amount = remaining.min(cap);

        // 结转与次年额度初始化原子完成；唯一约束保证同船同鱼种同年度只结转一次。
        if (!carryoverRepo.insert(request.vesselCode(), request.speciesCode(), fromYear, toYear,
                amount, now)) {
            throw new ConflictException("渔船 " + request.vesselCode() + " 鱼种 " + request.speciesCode()
                    + " " + fromYear + " 年度额度已结转，不能重复结转");
        }
        quotaRepo.insertIfAbsent(request.vesselCode(), request.speciesCode(), toYear, BigDecimal.ZERO);

        CarryoverResponse response = new CarryoverResponse(request.vesselCode(), request.speciesCode(),
                fromYear, toYear, amount.setScale(3, RoundingMode.HALF_UP), false);
        idempotencyRepo.saveResponse(request.requestId(), toJson(response));
        return response;
    }

    // ------------------------------------------------------------------
    // 辅助方法
    // ------------------------------------------------------------------

    private TransferResponse toResponse(TransferRow row, boolean replayed) {
        return new TransferResponse(row.id(), row.fromVessel(), row.toVessel(), row.speciesCode(),
                row.quotaYear(), row.amountKg(), row.status(), row.fromConfirmed(), row.toConfirmed(),
                row.createdAt(), row.expiresAt(), row.effectiveAt(), replayed);
    }

    private void requireVessel(String vesselCode) {
        if (catalogRepo.findVessel(vesselCode) == null) {
            throw new NotFoundException("渔船 " + vesselCode + " 不存在");
        }
    }

    private void requireSpecies(String speciesCode) {
        if (catalogRepo.findSpecies(speciesCode) == null) {
            throw new NotFoundException("鱼种 " + speciesCode + " 不存在");
        }
    }

    private String fingerprint(Object canonicalPayload) {
        try {
            String json = objectMapper.writer()
                    .with(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsString(canonicalPayload);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(json.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (JsonProcessingException | NoSuchAlgorithmException ex) {
            throw new IllegalStateException("生成幂等指纹失败", ex);
        }
    }

    private String toJson(Object response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("序列化响应快照失败", ex);
        }
    }

    private <T> T replay(String json, Class<T> type) {
        if (json == null) {
            throw new ConflictException("首次请求尚未处理完成");
        }
        try {
            T value = objectMapper.readValue(json, type);
            if (value instanceof com.example.starter.fishery.api.dto.ReplayAware replayAware) {
                @SuppressWarnings("unchecked")
                T marked = (T) replayAware.withReplayed(true);
                return marked;
            }
            return value;
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("读取首次响应快照失败", ex);
        }
    }
}
