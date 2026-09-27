package com.example.starter.service;

import com.example.starter.domain.BindingStatus;
import com.example.starter.domain.CustomsDeclaration;
import com.example.starter.domain.CustomsEvent;
import com.example.starter.domain.IdempotencyRecord;
import com.example.starter.domain.Review;
import com.example.starter.domain.ReviewStatus;
import com.example.starter.domain.SubBatchBinding;
import com.example.starter.dto.BindSubBatchRequest;
import com.example.starter.dto.BindingView;
import com.example.starter.dto.CustomsDeclarationView;
import com.example.starter.dto.ErrorResponse;
import com.example.starter.dto.RegisterDeclarationRequest;
import com.example.starter.dto.ReviseDeclarationRequest;
import com.example.starter.dto.RevokeDeclarationRequest;
import com.example.starter.dto.ServiceResult;
import com.example.starter.idempotency.Fingerprints;
import com.example.starter.json.JsonSupport;
import com.example.starter.repo.CustomsRepository;
import com.example.starter.repo.IdempotencyRepository;
import com.example.starter.repo.ReviewRepository;
import com.example.starter.time.BusinessClock;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 进口通关单与签发数量核销服务（合成数据）。
 *
 * <p>流转：通关登记 → 子批绑定预占 → 签发原子核销 / 签发前撤回释放 / 通关单撤销释放。
 * 写操作以 requestId 幂等且失败不占键；通关单版本从 1 开始、每次成功变更加 1；
 * 行锁顺序统一为 batch_review → customs_declaration，绑定状态变更均在通关单行锁保护下
 * 进行（不单独锁绑定行），避免签发与撤销互相等待死锁。
 */
@Service
public class CustomsService {

    /** 释放原因：子批签发前撤回。 */
    static final String RELEASE_SUB_BATCH_WITHDRAWN = "SUB_BATCH_WITHDRAWN";
    /** 释放原因：通关单撤销。 */
    static final String RELEASE_DECLARATION_REVOKED = "DECLARATION_REVOKED";

    private final CustomsRepository customsRepository;
    private final ReviewRepository reviewRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final JsonSupport jsonSupport;
    private final BusinessClock clock;
    private final TransactionTemplate txTemplate;

    public CustomsService(CustomsRepository customsRepository,
                          ReviewRepository reviewRepository,
                          IdempotencyRepository idempotencyRepository,
                          JsonSupport jsonSupport,
                          BusinessClock clock,
                          TransactionTemplate txTemplate) {
        this.customsRepository = customsRepository;
        this.reviewRepository = reviewRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.jsonSupport = jsonSupport;
        this.clock = clock;
        this.txTemplate = txTemplate;
    }

    /** 事务内执行业务并返回结果（允许提交 4xx 预期错误结果）。 */
    @FunctionalInterface
    private interface TxAction {
        ServiceResult run();
    }

    // ============================ 通关登记 ============================

    /**
     * 通关登记（requestId 幂等，失败不占键）。版本从 1 开始，预占/核销均为 0。
     */
    public ServiceResult register(RegisterDeclarationRequest request) {
        String fingerprint = Fingerprints.structured(Fingerprints.CUSTOMS_REGISTER,
                request.requestId(),
                request.declarationNo().trim(),
                request.enterpriseName().trim(),
                request.productName().trim(),
                request.validUntil(),
                request.totalQuantity());
        return idempotent(request.requestId(), Fingerprints.CUSTOMS_REGISTER, fingerprint,
                () -> doRegister(request));
    }

    private ServiceResult doRegister(RegisterDeclarationRequest request) {
        Instant now = clock.now();
        CustomsDeclaration declaration = new CustomsDeclaration();
        declaration.setDeclarationNo(request.declarationNo().trim());
        declaration.setEnterpriseName(request.enterpriseName().trim());
        declaration.setProductName(request.productName().trim());
        declaration.setValidUntil(request.validUntil());
        declaration.setTotalQuantity(request.totalQuantity());
        declaration.setReservedQuantity(0);
        declaration.setConsumedQuantity(0);
        declaration.setRevoked(false);
        declaration.setVersion(1);
        declaration.setCreatedAt(now);
        declaration.setUpdatedAt(now);

        Long id = customsRepository.insertDeclaration(declaration);
        if (id == null) {
            return ServiceResult.status(422, new ErrorResponse("DECLARATION_ALREADY_EXISTS",
                    "通关单号已存在: " + request.declarationNo()));
        }
        declaration.setId(id);
        customsRepository.insertEvent(new CustomsEvent(id, "REGISTERED",
                jsonSupport.write(Map.of(
                        "declarationNo", declaration.getDeclarationNo(),
                        "totalQuantity", declaration.getTotalQuantity(),
                        "validUntil", declaration.getValidUntil().toString())), now));
        return ServiceResult.ok(assembleView(declaration));
    }

    // ============================ 子批绑定（预占） ============================

    /**
     * 子批绑定（requestId 幂等，失败不占键）。绑定即预占申报支数，
     * 累计预占+已核销不超通关单总数，恰占满合法；任一资料不符整笔拒绝。
     */
    public ServiceResult bind(String declarationNo, BindSubBatchRequest request) {
        String fingerprint = Fingerprints.structured(Fingerprints.CUSTOMS_BIND,
                request.requestId(),
                declarationNo.trim(),
                request.batchNo().trim(),
                request.declaredQuantity(),
                request.expectedVersion());
        return idempotent(request.requestId(), Fingerprints.CUSTOMS_BIND, fingerprint,
                () -> doBind(declarationNo, request));
    }

    private ServiceResult doBind(String declarationNo, BindSubBatchRequest request) {
        Instant now = clock.now();
        // 锁顺序与签发一致：先批次行，后通关单行。
        Review review = reviewRepository.lockByBatchNo(request.batchNo().trim());
        if (review == null) {
            return ServiceResult.status(404, new ErrorResponse("REVIEW_NOT_FOUND",
                    "批号不存在: " + request.batchNo()));
        }
        CustomsDeclaration declaration = customsRepository.lockDeclaration(declarationNo.trim());
        if (declaration == null) {
            return ServiceResult.status(404, new ErrorResponse("DECLARATION_NOT_FOUND",
                    "通关单号不存在: " + declarationNo));
        }
        ServiceResult versionError = checkVersion(declaration, request.expectedVersion());
        if (versionError != null) {
            return versionError;
        }
        if (declaration.isRevoked()) {
            return ServiceResult.status(422, new ErrorResponse("DECLARATION_REVOKED",
                    "通关单已撤销，不能再绑定子批"));
        }
        if (!declaration.getEnterpriseName().equals(review.getEnterpriseName())
                || !declaration.getProductName().equals(review.getProductName())) {
            return ServiceResult.status(422, new ErrorResponse("ENTERPRISE_PRODUCT_MISMATCH",
                    "子批企业品种与通关单不一致，整笔拒绝"));
        }
        if (review.getStatus() == ReviewStatus.ISSUED
                || review.getStatus() == ReviewStatus.REJECTED
                || review.getStatus() == ReviewStatus.WITHDRAWN) {
            return ServiceResult.status(422, new ErrorResponse("REVIEW_TERMINAL",
                    "子批审查流程已终态（" + review.getStatus() + "），不能绑定通关单"));
        }
        if (customsRepository.findBindingByReviewId(review.getId()) != null) {
            return ServiceResult.status(422, new ErrorResponse("SUB_BATCH_ALREADY_BOUND",
                    "该子批已绑定通关单，每个子批只能绑定一张单"));
        }
        int quantity = request.declaredQuantity();
        if (declaration.getReservedQuantity() + declaration.getConsumedQuantity() + quantity
                > declaration.getTotalQuantity()) {
            return ServiceResult.status(422, new ErrorResponse("QUANTITY_EXCEEDED",
                    "累计预占将超过通关单可核销总数 " + declaration.getTotalQuantity()));
        }

        SubBatchBinding binding = new SubBatchBinding();
        binding.setDeclarationId(declaration.getId());
        binding.setReviewId(review.getId());
        binding.setBatchNo(review.getBatchNo());
        binding.setDeclaredQuantity(quantity);
        binding.setStatus(BindingStatus.RESERVED);
        binding.setBoundAt(now);
        binding.setCreatedAt(now);
        binding.setUpdatedAt(now);
        Long bindingId = customsRepository.insertBinding(binding);
        if (bindingId == null) {
            return ServiceResult.status(422, new ErrorResponse("SUB_BATCH_ALREADY_BOUND",
                    "该子批已绑定通关单，每个子批只能绑定一张单"));
        }

        declaration.setReservedQuantity(declaration.getReservedQuantity() + quantity);
        declaration.setVersion(declaration.getVersion() + 1);
        declaration.setUpdatedAt(now);
        customsRepository.updateDeclaration(declaration);
        customsRepository.insertEvent(new CustomsEvent(declaration.getId(), "BOUND",
                jsonSupport.write(Map.of(
                        "batchNo", review.getBatchNo(),
                        "declaredQuantity", quantity)), now));
        return ServiceResult.ok(assembleView(declaration));
    }

    // ============================ 通关单撤销 ============================

    /**
     * 通关单撤销（requestId 幂等，失败不占键）。全部未签发子批预占释放并阻断签发；
     * 已签发（已核销）子批证据保持不变，逐笔记风险事件。
     */
    public ServiceResult revoke(String declarationNo, RevokeDeclarationRequest request) {
        String fingerprint = Fingerprints.structured(Fingerprints.CUSTOMS_REVOKE,
                request.requestId(),
                declarationNo.trim(),
                request.expectedVersion());
        return idempotent(request.requestId(), Fingerprints.CUSTOMS_REVOKE, fingerprint,
                () -> doRevoke(declarationNo, request));
    }

    private ServiceResult doRevoke(String declarationNo, RevokeDeclarationRequest request) {
        Instant now = clock.now();
        CustomsDeclaration declaration = customsRepository.lockDeclaration(declarationNo.trim());
        if (declaration == null) {
            return ServiceResult.status(404, new ErrorResponse("DECLARATION_NOT_FOUND",
                    "通关单号不存在: " + declarationNo));
        }
        ServiceResult versionError = checkVersion(declaration, request.expectedVersion());
        if (versionError != null) {
            return versionError;
        }
        if (declaration.isRevoked()) {
            return ServiceResult.status(422, new ErrorResponse("DECLARATION_ALREADY_REVOKED",
                    "通关单已撤销，不能重复撤销"));
        }

        // 释放全部未签发子批预占（只释放 RESERVED，核销记录不动）。
        List<SubBatchBinding> reserved =
                customsRepository.listBindingsByStatus(declaration.getId(), BindingStatus.RESERVED);
        int releasedTotal = 0;
        for (SubBatchBinding binding : reserved) {
            binding.setStatus(BindingStatus.RELEASED);
            binding.setReleasedAt(now);
            binding.setReleaseReason(RELEASE_DECLARATION_REVOKED);
            binding.setUpdatedAt(now);
            customsRepository.updateBinding(binding);
            releasedTotal += binding.getDeclaredQuantity();
            customsRepository.insertEvent(new CustomsEvent(declaration.getId(), "RELEASED",
                    jsonSupport.write(Map.of(
                            "batchNo", binding.getBatchNo(),
                            "declaredQuantity", binding.getDeclaredQuantity(),
                            "reason", RELEASE_DECLARATION_REVOKED)), now));
        }

        declaration.setReservedQuantity(declaration.getReservedQuantity() - releasedTotal);
        declaration.setRevoked(true);
        declaration.setRevokedAt(now);
        declaration.setVersion(declaration.getVersion() + 1);
        declaration.setUpdatedAt(now);
        customsRepository.updateDeclaration(declaration);
        customsRepository.insertEvent(new CustomsEvent(declaration.getId(), "REVOKED",
                jsonSupport.write(Map.of("releasedReserved", releasedTotal)), now));

        // 已签发（已核销）子批证据不变，逐笔记风险事件。
        List<SubBatchBinding> consumed =
                customsRepository.listBindingsByStatus(declaration.getId(), BindingStatus.CONSUMED);
        for (SubBatchBinding binding : consumed) {
            customsRepository.insertEvent(new CustomsEvent(declaration.getId(),
                    "RISK_ISSUED_UNDER_REVOKED",
                    jsonSupport.write(Map.of(
                            "batchNo", binding.getBatchNo(),
                            "consumedQuantity", binding.getDeclaredQuantity(),
                            "consumedAt", binding.getConsumedAt().toString())), now));
        }
        return ServiceResult.ok(assembleView(declaration));
    }

    // ============================ 通关单修订 ============================

    /**
     * 通关单修订（requestId 幂等，失败不占键）。仅允许调增可核销总数（增加未占余量），
     * 不改历史核销记录与企业品种。
     */
    public ServiceResult revise(String declarationNo, ReviseDeclarationRequest request) {
        String fingerprint = Fingerprints.structured(Fingerprints.CUSTOMS_REVISE,
                request.requestId(),
                declarationNo.trim(),
                request.expectedVersion(),
                request.newTotalQuantity());
        return idempotent(request.requestId(), Fingerprints.CUSTOMS_REVISE, fingerprint,
                () -> doRevise(declarationNo, request));
    }

    private ServiceResult doRevise(String declarationNo, ReviseDeclarationRequest request) {
        Instant now = clock.now();
        CustomsDeclaration declaration = customsRepository.lockDeclaration(declarationNo.trim());
        if (declaration == null) {
            return ServiceResult.status(404, new ErrorResponse("DECLARATION_NOT_FOUND",
                    "通关单号不存在: " + declarationNo));
        }
        ServiceResult versionError = checkVersion(declaration, request.expectedVersion());
        if (versionError != null) {
            return versionError;
        }
        if (declaration.isRevoked()) {
            return ServiceResult.status(422, new ErrorResponse("DECLARATION_REVOKED",
                    "通关单已撤销，不能修订"));
        }
        if (request.newTotalQuantity() <= declaration.getTotalQuantity()) {
            return ServiceResult.status(422, new ErrorResponse("REVISION_NOT_INCREASING",
                    "通关修订仅能增加未占余量，新总数必须严格大于当前总数 "
                            + declaration.getTotalQuantity()));
        }
        int oldTotal = declaration.getTotalQuantity();
        declaration.setTotalQuantity(request.newTotalQuantity());
        declaration.setVersion(declaration.getVersion() + 1);
        declaration.setUpdatedAt(now);
        customsRepository.updateDeclaration(declaration);
        customsRepository.insertEvent(new CustomsEvent(declaration.getId(), "REVISED",
                jsonSupport.write(Map.of(
                        "oldTotalQuantity", oldTotal,
                        "newTotalQuantity", request.newTotalQuantity())), now));
        return ServiceResult.ok(assembleView(declaration));
    }

    // ============================ 签发/撤回衔接（参与调用方事务） ============================

    /**
     * 签发衔接：批次已锁、状态已校验可签发后调用。
     * 无绑定（国内批次）返回 null 不加门禁；有绑定则在事务内重查通关单有效且未撤销，
     * 并原子把本子批预占转已核销。返回非 null 即拒绝签发的错误结果。
     *
     * <p>锁顺序：调用方已持有 batch_review 行锁，此处只再锁 customs_declaration 行；
     * 绑定状态变更一律在通关单行锁保护下进行，不再单独锁绑定行，避免与撤销死锁。
     */
    public ServiceResult consumeOnIssue(Review review, Instant now) {
        SubBatchBinding binding = customsRepository.findBindingByReviewId(review.getId());
        if (binding == null) {
            // 国内批次：不新增通关门禁。并发绑定被调用方持有的批次行锁阻塞，不会漏判。
            return null;
        }
        CustomsDeclaration declaration = customsRepository.lockDeclarationById(binding.getDeclarationId());
        if (declaration == null) {
            return ServiceResult.status(422, new ErrorResponse("DECLARATION_NOT_FOUND",
                    "子批绑定的通关单不存在"));
        }
        // 通关单行锁保护下重读绑定状态（锁内状态稳定）。
        binding = customsRepository.findBindingByReviewId(review.getId());
        if (binding.getStatus() == BindingStatus.CONSUMED) {
            // 已核销（理论上签发幂等重放不会走到这里），不加额外门禁。
            return null;
        }
        if (binding.getStatus() == BindingStatus.RELEASED) {
            return ServiceResult.status(422, new ErrorResponse("CUSTOMS_BINDING_RELEASED",
                    "子批预占已释放（通关单撤销或子批撤回），签发被阻断"));
        }
        if (declaration.isRevoked()) {
            return ServiceResult.status(422, new ErrorResponse("CUSTOMS_DECLARATION_REVOKED",
                    "通关单已撤销，签发被阻断（不以先前公示通过绕过新失效）"));
        }
        if (!now.isBefore(declaration.getValidUntil())) {
            // 恰截止拒绝：now 必须严格早于有效截止。
            return ServiceResult.status(422, new ErrorResponse("CUSTOMS_DECLARATION_EXPIRED",
                    "通关单已过有效截止 " + declaration.getValidUntil() + "，签发被阻断"));
        }
        // 原子核销：预占转已核销，与签发同事务提交。
        binding.setStatus(BindingStatus.CONSUMED);
        binding.setConsumedAt(now);
        binding.setUpdatedAt(now);
        customsRepository.updateBinding(binding);
        declaration.setReservedQuantity(declaration.getReservedQuantity() - binding.getDeclaredQuantity());
        declaration.setConsumedQuantity(declaration.getConsumedQuantity() + binding.getDeclaredQuantity());
        declaration.setVersion(declaration.getVersion() + 1);
        declaration.setUpdatedAt(now);
        customsRepository.updateDeclaration(declaration);
        customsRepository.insertEvent(new CustomsEvent(declaration.getId(), "CONSUMED",
                jsonSupport.write(Map.of(
                        "batchNo", binding.getBatchNo(),
                        "consumedQuantity", binding.getDeclaredQuantity())), now));
        return null;
    }

    /**
     * 子批签发前撤回衔接：批次已锁并转入撤回终态时调用。
     * 释放该子批预占且只释放一次（仅 RESERVED 状态可释放）。
     */
    public void releaseOnWithdraw(Long reviewId, Instant now) {
        SubBatchBinding binding = customsRepository.findBindingByReviewId(reviewId);
        if (binding == null) {
            return;
        }
        CustomsDeclaration declaration = customsRepository.lockDeclarationById(binding.getDeclarationId());
        if (declaration == null) {
            return;
        }
        // 通关单行锁保护下重读：仅 RESERVED 可释放，保证只释放一次。
        binding = customsRepository.findBindingByReviewId(reviewId);
        if (binding.getStatus() != BindingStatus.RESERVED) {
            return;
        }
        binding.setStatus(BindingStatus.RELEASED);
        binding.setReleasedAt(now);
        binding.setReleaseReason(RELEASE_SUB_BATCH_WITHDRAWN);
        binding.setUpdatedAt(now);
        customsRepository.updateBinding(binding);
        declaration.setReservedQuantity(declaration.getReservedQuantity() - binding.getDeclaredQuantity());
        declaration.setVersion(declaration.getVersion() + 1);
        declaration.setUpdatedAt(now);
        customsRepository.updateDeclaration(declaration);
        customsRepository.insertEvent(new CustomsEvent(declaration.getId(), "RELEASED",
                jsonSupport.write(Map.of(
                        "batchNo", binding.getBatchNo(),
                        "declaredQuantity", binding.getDeclaredQuantity(),
                        "reason", RELEASE_SUB_BATCH_WITHDRAWN)), now));
    }

    // ============================ 余量台账查询 ============================

    /** 查询通关单余量台账（总数 = 已预占 + 已核销 + 未占余量，含全部子批绑定）。 */
    public CustomsDeclarationView getDeclaration(String declarationNo) {
        CustomsDeclaration declaration = customsRepository.findDeclaration(declarationNo.trim());
        if (declaration == null) {
            throw new BusinessException(404, "DECLARATION_NOT_FOUND", "通关单号不存在: " + declarationNo);
        }
        return assembleView(declaration);
    }

    /** 读取通关单事件流水（登记、绑定、核销、释放、撤销、修订、风险事件）。 */
    public List<Map<String, Object>> listEvents(String declarationNo) {
        CustomsDeclaration declaration = customsRepository.findDeclaration(declarationNo.trim());
        if (declaration == null) {
            throw new BusinessException(404, "DECLARATION_NOT_FOUND", "通关单号不存在: " + declarationNo);
        }
        return customsRepository.listEvents(declaration.getId()).stream()
                .map(event -> Map.<String, Object>of(
                        "seq", event.getId(),
                        "eventType", event.getEventType(),
                        "payload", event.getPayload() == null ? "" : event.getPayload(),
                        "eventAt", event.getEventAt()))
                .toList();
    }

    // ============================ 幂等模板（失败不占键） ============================

    /**
     * requestId 幂等执行：同键同参重放首次冻结结果；同键异参 409；
     * 失败结果（4xx）不占键——占位记录与业务变更一并回滚，同键可重新发起。
     * 成功结果与业务变更在同一事务内提交。
     */
    private ServiceResult idempotent(String requestId, String operation, String fingerprint, TxAction action) {
        for (int attempt = 0; attempt < 3; attempt++) {
            IdempotencyRecord existing = idempotencyRepository.find(requestId);
            if (existing != null) {
                return replayOrConflict(existing, fingerprint);
            }
            try {
                return txTemplate.execute(status -> {
                    idempotencyRepository.insertPlaceholder(requestId, operation, fingerprint, clock.now());
                    ServiceResult result = action.run();
                    if (result.httpStatus() >= 400) {
                        // 失败不占键：回滚占位记录与业务变更。
                        status.setRollbackOnly();
                        return result;
                    }
                    idempotencyRepository.finalize(requestId, result.httpStatus(),
                            jsonSupport.write(result.body()));
                    return result;
                });
            } catch (DataAccessException ex) {
                // 并发同键：首事务已提交则重放其结果；首事务失败回滚（不占键）则重试执行。
                IdempotencyRecord winner = awaitIdempotencyRecord(requestId);
                if (winner != null) {
                    return replayOrConflict(winner, fingerprint);
                }
            }
        }
        throw new BusinessException(500, "IDEMPOTENCY_RECORD_MISSING",
                "并发幂等记录缺失: " + requestId);
    }

    private ServiceResult replayOrConflict(IdempotencyRecord record, String requestFingerprint) {
        if (!record.getFingerprint().equals(requestFingerprint)) {
            return ServiceResult.status(409, new ErrorResponse("IDEMPOTENCY_FINGERPRINT_MISMATCH",
                    "同一 requestId 携带了不同业务参数，请求被拒绝且通关台账不变"));
        }
        return ServiceResult.status(record.getHttpStatus(), jsonSupport.readObject(record.getResponseJson()));
    }

    /** 等待并发首事务的幂等记录；首事务失败回滚（不占键）时返回 null。 */
    private IdempotencyRecord awaitIdempotencyRecord(String requestId) {
        for (int i = 0; i < 100; i++) {
            IdempotencyRecord record = idempotencyRepository.find(requestId);
            if (record != null) {
                return record;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    // ============================ 内部辅助 ============================

    /** 乐观锁版本校验：不匹配返回 409，匹配返回 null。 */
    private ServiceResult checkVersion(CustomsDeclaration declaration, Integer expectedVersion) {
        if (expectedVersion == null || expectedVersion != declaration.getVersion()) {
            return ServiceResult.status(409, new ErrorResponse("VERSION_CONFLICT",
                    "通关单版本冲突：期望 " + expectedVersion + "，当前 " + declaration.getVersion()));
        }
        return null;
    }

    private CustomsDeclarationView assembleView(CustomsDeclaration declaration) {
        List<BindingView> bindings = customsRepository.listBindings(declaration.getId()).stream()
                .map(binding -> new BindingView(
                        binding.getBatchNo(),
                        binding.getDeclaredQuantity(),
                        binding.getStatus().name(),
                        binding.getBoundAt(),
                        binding.getConsumedAt(),
                        binding.getReleasedAt(),
                        binding.getReleaseReason()))
                .toList();
        return new CustomsDeclarationView(
                declaration.getDeclarationNo(),
                declaration.getEnterpriseName(),
                declaration.getProductName(),
                declaration.getValidUntil(),
                declaration.getTotalQuantity(),
                declaration.getReservedQuantity(),
                declaration.getConsumedQuantity(),
                declaration.getTotalQuantity()
                        - declaration.getReservedQuantity()
                        - declaration.getConsumedQuantity(),
                declaration.isRevoked(),
                declaration.getRevokedAt(),
                declaration.getVersion(),
                bindings);
    }
}
