package com.example.starter.quarantine.service;

import com.example.starter.quarantine.api.dto.BlockadeRequest;
import com.example.starter.quarantine.api.dto.BlockadeView;
import com.example.starter.quarantine.api.dto.CaseView;
import com.example.starter.quarantine.api.dto.DeclareRequest;
import com.example.starter.quarantine.api.dto.LandingReportRequest;
import com.example.starter.quarantine.api.dto.LandingResultView;
import com.example.starter.quarantine.domain.BlockadePo;
import com.example.starter.quarantine.domain.BlockadeStatus;
import com.example.starter.quarantine.domain.CertificatePo;
import com.example.starter.quarantine.domain.CertificateStatus;
import com.example.starter.quarantine.domain.IdempotentCommandPo;
import com.example.starter.quarantine.domain.LandingReportPo;
import com.example.starter.quarantine.domain.QuarantineRequestPo;
import com.example.starter.quarantine.domain.RequestStatus;
import com.example.starter.quarantine.domain.StockPo;
import com.example.starter.quarantine.repository.BlockadeRepository;
import com.example.starter.quarantine.repository.CertificateRepository;
import com.example.starter.quarantine.repository.FarmRepository;
import com.example.starter.quarantine.repository.IdempotencyRepository;
import com.example.starter.quarantine.repository.LandingRepository;
import com.example.starter.quarantine.repository.RequestRepository;
import com.example.starter.quarantine.repository.StockRepository;
import com.example.starter.quarantine.support.Fingerprints;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 动物调运检疫出证与疫点封锁联动服务。
 *
 * <p>并发控制：出证/启运/落地/封锁均在单事务内先对养殖场行加 {@code SELECT ... FOR UPDATE}
 * 行锁并持有到提交，同一养殖场上的业务与封锁因此严格串行，保证“封锁发布与出证并发形成唯一终态”。
 * 同批动物有效证唯一由 {@code uq_cert_active_slot} 约束兜底；出证核减存栏为条件 UPDATE，
 * 与证写入、幂等记录处于同一事务，失败整体回滚。
 */
@Service
public class QuarantineService {

    private static final String VOID_REASON_BLOCKED = "产地划为疫点封锁区，已出证未启运，证自动作废";
    private static final String REJECT_REASON_BLOCKED = "产地已划为疫点封锁区，未出证申报一律驳回";

    private static final DateTimeFormatter CERT_NO_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
            .withZone(ZoneOffset.UTC);

    private final RequestRepository requestRepository;
    private final CertificateRepository certificateRepository;
    private final LandingRepository landingRepository;
    private final BlockadeRepository blockadeRepository;
    private final StockRepository stockRepository;
    private final FarmRepository farmRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public QuarantineService(RequestRepository requestRepository,
                             CertificateRepository certificateRepository,
                             LandingRepository landingRepository,
                             BlockadeRepository blockadeRepository,
                             StockRepository stockRepository,
                             FarmRepository farmRepository,
                             IdempotencyRepository idempotencyRepository,
                             TransactionTemplate transactionTemplate,
                             ObjectMapper objectMapper,
                             Clock clock) {
        this.requestRepository = requestRepository;
        this.certificateRepository = certificateRepository;
        this.landingRepository = landingRepository;
        this.blockadeRepository = blockadeRepository;
        this.stockRepository = stockRepository;
        this.farmRepository = farmRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // ============================ 申报 ============================

    public CaseView declare(DeclareRequest request) {
        String key = "DECLARE:" + request.requestId();
        String fingerprint = Fingerprints.sha256(
                "DECLARE", request.requestId(), request.originFarm(), request.destination(),
                request.animalType(), request.earTagRange(), request.quantity());
        return executeIdempotent(key, fingerprint, CaseView.class, () -> doDeclare(request, fingerprint));
    }

    private CaseView doDeclare(DeclareRequest request, String fingerprint) {
        // 与封锁发布串行：持农场行锁后判定封锁状态，避免封锁生效同刻仍落 SUBMITTED。
        farmRepository.lockFarm(request.originFarm());
        if (stockRepository.findSlot(request.originFarm(), request.animalType(),
                request.earTagRange()) == null) {
            throw new BusinessRuleException("养殖场不存在该动物种类/耳标段存栏批次："
                    + request.originFarm() + " / " + request.animalType() + " / " + request.earTagRange());
        }
        Instant now = Instant.now(clock);
        QuarantineRequestPo po = new QuarantineRequestPo();
        po.setRequestId(request.requestId());
        po.setOriginFarm(request.originFarm());
        po.setDestination(request.destination());
        po.setAnimalType(request.animalType());
        po.setEarTagRange(request.earTagRange());
        po.setQuantity(request.quantity());
        // 封锁即刻生效：封锁生效期间的新申报同样一律驳回。
        boolean blocked = blockadeRepository.isFarmBlocked(request.originFarm());
        po.setStatus(blocked ? RequestStatus.REJECTED : RequestStatus.SUBMITTED);
        po.setFingerprint(fingerprint);
        po.setRejectReason(blocked ? REJECT_REASON_BLOCKED : null);
        po.setCreatedAt(now);
        po.setUpdatedAt(now);
        requestRepository.insert(po);
        return CaseViews.of(po, null, 0, false);
    }

    // ============================ 出证 ============================

    public CaseView issue(String requestId) {
        String key = "ISSUE:" + requestId;
        String fingerprint = Fingerprints.sha256("ISSUE", requestId);
        return executeIdempotent(key, fingerprint, CaseView.class,
                () -> doIssue(requestId, fingerprint, true));
    }

    private CaseView doIssue(String requestId, String fingerprint, boolean lockFarm) {
        if (lockFarm) {
            String farmId = requireRequest(requestId).getOriginFarm();
            farmRepository.lockFarm(farmId);
        }
        QuarantineRequestPo request = requireRequest(requestId);

        // 并发封锁可能已将本单驳回：出证请求观察到驳回终态，直接返回驳回视图。
        if (request.getStatus() == RequestStatus.REJECTED) {
            return CaseViews.of(request, certificateRepository.findByRequestId(requestId), 0, false);
        }
        if (request.getStatus() != RequestStatus.SUBMITTED) {
            throw new BusinessRuleException("当前单据状态不允许出证：" + request.getStatus());
        }

        // 封锁即刻生效：未出证申报一律驳回。
        if (blockadeRepository.isFarmBlocked(request.getOriginFarm())) {
            Instant now = Instant.now(clock);
            requestRepository.updateStatus(requestId, RequestStatus.REJECTED, REJECT_REASON_BLOCKED, now);
            request.setStatus(RequestStatus.REJECTED);
            request.setRejectReason(REJECT_REASON_BLOCKED);
            return CaseViews.of(request, null, 0, false);
        }

        StockPo stock = stockRepository.findSlot(
                request.getOriginFarm(), request.getAnimalType(), request.getEarTagRange());
        if (stock == null) {
            throw new BusinessRuleException("存栏批次不存在");
        }
        if (stock.getAvailableCount() < request.getQuantity()) {
            throw new BusinessRuleException("可售存栏不足：可用 " + stock.getAvailableCount()
                    + "，申报 " + request.getQuantity());
        }

        Instant now = Instant.now(clock);
        // 1) 核减存栏（条件 UPDATE，与证写入同事务，失败整体回滚）。
        if (!stockRepository.deductAvailable(request.getOriginFarm(), request.getAnimalType(),
                request.getEarTagRange(), request.getQuantity())) {
            throw new BusinessRuleException("可售存栏核减失败，余额已被并发占用");
        }

        // 2) 生成证号并写证；同养殖场同耳标段有效证唯一冲突即回滚核减。
        long seq = farmRepository.nextCertificateSequence();
        CertificatePo cert = new CertificatePo();
        cert.setCertificateNo("QC-" + CERT_NO_TIME.format(now) + "-" + String.format("%08d", seq));
        cert.setRequestId(requestId);
        cert.setFarmId(request.getOriginFarm());
        cert.setAnimalType(request.getAnimalType());
        cert.setEarTagRange(request.getEarTagRange());
        cert.setQuantity(request.getQuantity());
        cert.setIssuedAt(now);
        cert.setExpiresAt(now.plus(request.getAnimalType().certificateValidity()));
        cert.setStatus(CertificateStatus.VALID);
        cert.setQuarantineFlag(false);
        cert.setActiveSlot(CertificateRepository.activeSlot(
                request.getOriginFarm(), request.getEarTagRange()));
        cert.setCreatedAt(now);
        cert.setUpdatedAt(now);
        try {
            certificateRepository.insert(cert);
        } catch (DuplicateKeyException ex) {
            // 同批动物并发出证唯一成功：本事务回滚，存栏核减随之撤销。
            throw new ResourceConflictException("同一养殖场同批动物（耳标段）只允许一张有效证："
                    + cert.getActiveSlot());
        }

        // 3) 单据进入已出证。
        requestRepository.updateStatus(requestId, RequestStatus.CERTIFIED, null, now);
        request.setStatus(RequestStatus.CERTIFIED);
        return CaseViews.of(request, cert, 0, false);
    }

    // ============================ 启运 ============================

    public CaseView depart(String requestId) {
        String key = "DEPART:" + requestId;
        String fingerprint = Fingerprints.sha256("DEPART", requestId);
        return executeIdempotent(key, fingerprint, CaseView.class, () -> {
            QuarantineRequestPo request = requireRequest(requestId);
            farmRepository.lockFarm(request.getOriginFarm());
            CertificatePo cert = certificateRepository.findByRequestId(requestId);
            if (cert != null && cert.getStatus() == CertificateStatus.VOID) {
                throw new BusinessRuleException("证已因疫点封锁作废，禁止启运");
            }
            Instant now = Instant.now(clock);
            boolean moved = requestRepository.updateStatusIfCurrent(
                    requestId, RequestStatus.CERTIFIED, RequestStatus.IN_TRANSIT, null, now);
            if (!moved) {
                throw new BusinessRuleException("当前单据状态不允许启运：" + request.getStatus());
            }
            request.setStatus(RequestStatus.IN_TRANSIT);
            return CaseViews.of(request, cert, 0, false);
        });
    }

    // ============================ 落地报告 ============================

    public LandingResultView landing(LandingReportRequest request) {
        String key = "LANDING:" + request.requestId() + ":" + request.reportId();
        String fingerprint = Fingerprints.sha256(
                "LANDING", request.requestId(), request.reportId(), request.quantity());
        return executeIdempotent(key, fingerprint, LandingResultView.class, () -> doLanding(request));
    }

    private LandingResultView doLanding(LandingReportRequest request) {
        QuarantineRequestPo casePo = requireRequest(request.requestId());
        farmRepository.lockFarm(casePo.getOriginFarm());
        // 重读，拿锁后取最新状态。
        casePo = requireRequest(request.requestId());
        CertificatePo cert = certificateRepository.findByRequestId(request.requestId());
        Instant now = Instant.now(clock);

        // 终态/无证分支：记录事件但不改状态（结案后迟到事件同理）。
        if (casePo.getStatus() == RequestStatus.CLOSED) {
            return recordLanding(request, cert, false, landingRepository.sumAccepted(request.requestId()),
                    RequestStatus.CLOSED, CertificateStatus.CLOSED, true,
                    "结案后迟到事件，只记录不改状态", now);
        }
        if (cert != null && cert.getStatus() == CertificateStatus.VOID) {
            return recordLanding(request, cert, false, landingRepository.sumAccepted(request.requestId()),
                    RequestStatus.VOID, CertificateStatus.VOID, false, "证已作废，落地报告拒绝", now);
        }
        if (cert == null || casePo.getStatus() == RequestStatus.SUBMITTED
                || casePo.getStatus() == RequestStatus.REJECTED) {
            throw new BusinessRuleException("单据尚未出证，不允许落地报告：" + casePo.getStatus());
        }
        if (casePo.getStatus() == RequestStatus.CERTIFIED) {
            throw new BusinessRuleException("货物尚未启运，不允许落地报告");
        }

        // 有效期：报告时刻不晚于有效期终点（恰在终点前）即有效；超时证失效。
        if (now.isAfter(cert.getExpiresAt())) {
            certificateRepository.deactivateIfActive(cert.getId(), CertificateStatus.EXPIRED, null,
                    cert.isQuarantineFlag(), now);
            requestRepository.updateStatus(request.requestId(), RequestStatus.EXPIRED,
                    "落地报告超过证有效期，证失效", now);
            cert.setStatus(CertificateStatus.EXPIRED);
            return recordLanding(request, cert, false, landingRepository.sumAccepted(request.requestId()),
                    RequestStatus.EXPIRED, CertificateStatus.EXPIRED, false,
                    "落地报告超过证有效期，证失效", now);
        }

        int already = landingRepository.sumAccepted(request.requestId());
        if (request.quantity() > cert.getQuantity()) {
            return recordLanding(request, cert, false, already, RequestStatus.IN_TRANSIT,
                    CertificateStatus.VALID, false, "本批数量超过证载数量，整批拒绝", now);
        }
        if ((long) already + request.quantity() > cert.getQuantity()) {
            return recordLanding(request, cert, false, already, RequestStatus.IN_TRANSIT,
                    CertificateStatus.VALID, false, "累计落地数量将超证载数量，整批拒绝", now);
        }

        // 接受本批。
        LandingReportPo event = new LandingReportPo();
        event.setRequestId(request.requestId());
        event.setReportId(request.reportId());
        event.setQuantity(request.quantity());
        event.setReportedAt(now);
        event.setQuarantineFlag(cert.isQuarantineFlag());
        event.setAccepted(true);
        event.setNote(cert.isQuarantineFlag() ? "落地后强制隔离观察" : null);
        event.setCreatedAt(now);
        landingRepository.insert(event);

        int cumulative = already + request.quantity();
        boolean closed = cumulative == cert.getQuantity();
        if (closed) {
            certificateRepository.closeIfValid(cert.getId(), now);
            requestRepository.updateStatus(request.requestId(), RequestStatus.CLOSED, null, now);
            cert.setStatus(CertificateStatus.CLOSED);
        }
        return new LandingResultView(request.requestId(), request.reportId(), request.quantity(),
                true, cumulative, closed ? RequestStatus.CLOSED : RequestStatus.IN_TRANSIT,
                cert.getStatus(), cert.isQuarantineFlag(), closed, false,
                closed ? "已按证载数量全部落地，结案" : "部分落地已接受", false);
    }

    private LandingResultView recordLanding(LandingReportRequest request, CertificatePo cert,
                                            boolean accepted, int cumulative, RequestStatus requestStatus,
                                            CertificateStatus certificateStatus, boolean lateEvent,
                                            String note, Instant now) {
        LandingReportPo event = new LandingReportPo();
        event.setRequestId(request.requestId());
        event.setReportId(request.reportId());
        event.setQuantity(request.quantity());
        event.setReportedAt(now);
        event.setQuarantineFlag(cert != null && cert.isQuarantineFlag());
        event.setAccepted(accepted);
        event.setNote(note);
        event.setCreatedAt(now);
        landingRepository.insert(event);
        return new LandingResultView(request.requestId(), request.reportId(), request.quantity(),
                accepted, cumulative, requestStatus, certificateStatus,
                cert != null && cert.isQuarantineFlag(), false, lateEvent, note, false);
    }

    // ============================ 疫点封锁发布 ============================

    public BlockadeView publishBlockade(BlockadeRequest request) {
        String key = "BLOCKADE:" + request.blockadeId();
        String fingerprint = Fingerprints.sha256(
                "BLOCKADE", request.blockadeId(), request.farmId(),
                request.reason() == null ? "" : request.reason());
        return executeIdempotent(key, fingerprint, BlockadeView.class, () -> doPublishBlockade(request));
    }

    private BlockadeView doPublishBlockade(BlockadeRequest request) {
        farmRepository.lockFarm(request.farmId());
        if (blockadeRepository.findActiveByFarm(request.farmId()) != null) {
            throw new ResourceConflictException("养殖场已存在生效中的疫点封锁：" + request.farmId());
        }
        Instant now = Instant.now(clock);

        BlockadePo blockade = new BlockadePo();
        blockade.setBlockadeId(request.blockadeId());
        blockade.setFarmId(request.farmId());
        blockade.setReason(request.reason());
        blockade.setStatus(BlockadeStatus.ACTIVE);
        blockade.setEffectiveAt(now);
        blockade.setCreatedAt(now);
        blockade.setUpdatedAt(now);
        blockadeRepository.insert(blockade);

        // 一级联动：未出证申报一律驳回。
        List<QuarantineRequestPo> pending = requestRepository.findByFarmAndStatus(
                request.farmId(), RequestStatus.SUBMITTED);
        for (QuarantineRequestPo pendingRequest : pending) {
            requestRepository.updateStatus(pendingRequest.getRequestId(), RequestStatus.REJECTED,
                    REJECT_REASON_BLOCKED, now);
        }

        // 二级联动：已出证未启运的证自动作废并返还存栏。
        List<CertificatePo> certified = certificateRepository.findValidCertifiedOnFarm(request.farmId());
        for (CertificatePo cert : certified) {
            boolean deactivated = certificateRepository.deactivateIfActive(
                    cert.getId(), CertificateStatus.VOID, VOID_REASON_BLOCKED, false, now);
            if (deactivated) {
                requestRepository.updateStatus(cert.getRequestId(), RequestStatus.VOID,
                        VOID_REASON_BLOCKED, now);
                stockRepository.restoreAvailable(cert.getFarmId(), cert.getAnimalType(),
                        cert.getEarTagRange(), cert.getQuantity());
            }
        }

        // 三级联动：已在途允许完成落地，但强制隔离观察标记。
        List<CertificatePo> inTransit = certificateRepository.findValidInTransitOnFarm(request.farmId());
        for (CertificatePo cert : inTransit) {
            certificateRepository.markQuarantineIfActive(cert.getId(), now);
        }

        return new BlockadeView(request.blockadeId(), request.farmId(), BlockadeStatus.ACTIVE, now,
                request.reason(), pending.size(), certified.size(), inTransit.size(), false);
    }

    // ============================ 幂等与事务基础设施 ============================

    private QuarantineRequestPo requireRequest(String requestId) {
        QuarantineRequestPo po = requestRepository.findById(requestId);
        if (po == null) {
            throw new BusinessRuleException("检疫申报不存在：" + requestId);
        }
        return po;
    }

    /**
     * 幂等执行：同键同参返回首次结果；同键异参 409 且不改状态。
     * 业务与幂等记录在同一事务；若并发同键插入冲突，新开只读事务重读裁决。
     */
    private <T> T executeIdempotent(String commandKey, String fingerprint, Class<T> resultType,
                                    Supplier<T> action) {
        try {
            return transactionTemplate.execute(status -> doExecuteIdempotent(
                    commandKey, fingerprint, resultType, action));
        } catch (DuplicateKeyException concurrentInsert) {
            IdempotentCommandPo existing = transactionTemplate.execute(
                    status -> idempotencyRepository.find(commandKey));
            if (existing == null) {
                // 唯一冲突来自其他业务唯一键（如耳标段有效证），按资源冲突上抛。
                throw new ResourceConflictException("并发唯一约束冲突，请重试或查询单据状态");
            }
            if (!fingerprint.equals(existing.getFingerprint())) {
                throw new IdempotencyConflictException(
                        "幂等键 " + commandKey + " 已存在但业务参数不一致（409），单据状态保持不变");
            }
            return markReplay(deserialize(existing.getResultJson(), resultType));
        }
    }

    private <T> T doExecuteIdempotent(String commandKey, String fingerprint, Class<T> resultType,
                                      Supplier<T> action) {
        IdempotentCommandPo existing = idempotencyRepository.find(commandKey);
        if (existing != null) {
            if (!fingerprint.equals(existing.getFingerprint())) {
                throw new IdempotencyConflictException(
                        "幂等键 " + commandKey + " 已存在但业务参数不一致（409），单据状态保持不变");
            }
            return markReplay(deserialize(existing.getResultJson(), resultType));
        }
        T result = action.get();
        IdempotentCommandPo command = new IdempotentCommandPo();
        command.setCommandKey(commandKey);
        command.setFingerprint(fingerprint);
        command.setResultJson(serialize(result));
        command.setCreatedAt(Instant.now(clock));
        idempotencyRepository.insert(command);
        return result;
    }

    /** 同键同参重放：返回首次结果并置重放标记（record 不可变，重建视图）。 */
    @SuppressWarnings("unchecked")
    private <T> T markReplay(T result) {
        if (result instanceof CaseView v) {
            return (T) new CaseView(v.requestId(), v.originFarm(), v.destination(), v.animalType(),
                    v.earTagRange(), v.quantity(), v.requestStatus(), v.certificate(),
                    v.landedQuantity(), v.quarantineFlag(), v.rejectReason(), true);
        }
        if (result instanceof LandingResultView v) {
            return (T) new LandingResultView(v.requestId(), v.reportId(), v.quantity(), v.accepted(),
                    v.cumulativeLanded(), v.requestStatus(), v.certificateStatus(), v.quarantineFlag(),
                    v.closed(), v.lateEvent(), v.note(), true);
        }
        if (result instanceof BlockadeView v) {
            return (T) new BlockadeView(v.blockadeId(), v.farmId(), v.status(), v.effectiveAt(),
                    v.reason(), v.rejectedPendingCount(), v.voidedCertifiedCount(),
                    v.flaggedInTransitCount(), true);
        }
        return result;
    }

    private String serialize(Object result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception ex) {
            throw new IllegalStateException("幂等结果序列化失败", ex);
        }
    }

    private <T> T deserialize(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception ex) {
            throw new IllegalStateException("幂等结果反序列化失败", ex);
        }
    }
}
