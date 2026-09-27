package com.example.starter.service;

import com.example.starter.domain.BindingStatus;
import com.example.starter.domain.CustomsBinding;
import com.example.starter.domain.CustomsDeclaration;
import com.example.starter.domain.CustomsDeclarationStatus;
import com.example.starter.domain.IdempotencyRecord;
import com.example.starter.domain.ReleaseReason;
import com.example.starter.domain.Review;
import com.example.starter.domain.ReviewEvent;
import com.example.starter.dto.CustomsBindRequest;
import com.example.starter.dto.CustomsBindingView;
import com.example.starter.dto.CustomsDeclarationView;
import com.example.starter.dto.CustomsRegisterRequest;
import com.example.starter.dto.CustomsReviseRequest;
import com.example.starter.dto.CustomsRevokeRequest;
import com.example.starter.dto.CustomsWithdrawRequest;
import com.example.starter.dto.ErrorResponse;
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

/**
 * 进口通关单登记、子批绑定与数量核销服务。
 *
 * <p>规则：绑定先预占申报支数，累计不超总数（恰占满合法）；签发原子把预占转已核销；
 * 签发前撤回释放预占且只释放一次；通关单撤销释放全部未签发预占并阻断签发，
 * 已签发证据保留并记风险事件；修订仅允许调增总支数。
 * 版本从 1 开始，每次成功变更加 1；写操作 requestId 幂等，业务失败不占键。
 */
@Service
public class CustomsService {

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

    /** 登记合成通关单（requestId 幂等，版本从 1 开始）。 */
    public ServiceResult register(CustomsRegisterRequest request) {
        String fingerprint = Fingerprints.customsRegister(request);
        return idempotent(request.requestId(), Fingerprints.CUSTOMS_REGISTER, fingerprint,
                () -> doRegister(request));
    }

    private ServiceResult doRegister(CustomsRegisterRequest request) {
        Instant now = clock.now();
        if (!request.validUntil().isAfter(now)) {
            return ServiceResult.status(422, new ErrorResponse("CUSTOMS_DECLARATION_EXPIRED",
                    "通关有效截止必须晚于当前业务时刻，恰截止视为已失效"));
        }
        CustomsDeclaration declaration = new CustomsDeclaration();
        declaration.setDeclarationNo(request.declarationNo().trim());
        declaration.setEnterpriseName(request.enterpriseName().trim());
        declaration.setProductName(request.productName().trim());
        declaration.setValidUntil(request.validUntil());
        declaration.setTotalDoses(request.totalDoses());
        declaration.setStatus(CustomsDeclarationStatus.ACTIVE);
        declaration.setVersion(1);
        declaration.setCreatedAt(now);
        declaration.setUpdatedAt(now);
        Long id = customsRepository.insertDeclaration(declaration);
        if (id == null) {
            return ServiceResult.status(409, new ErrorResponse("DECLARATION_ALREADY_REGISTERED",
                    "通关单号已登记: " + request.declarationNo()));
        }
        declaration.setId(id);
        return ServiceResult.ok(assemble(declaration, List.of()));
    }

    // ============================ 子批绑定（预占） ============================

    /**
     * 绑定包装子批（requestId 幂等）。企业品种必须一致，预占累计不超总数，恰占满合法；
     * 任一资料不符整笔拒绝。
     */
    public ServiceResult bind(String declarationNo, CustomsBindRequest request) {
        String fingerprint = Fingerprints.customsBind(declarationNo, request);
        return idempotent(request.requestId(), Fingerprints.CUSTOMS_BIND, fingerprint,
                () -> doBind(declarationNo, request));
    }

    private ServiceResult doBind(String declarationNo, CustomsBindRequest request) {
        Instant now = clock.now();
        CustomsDeclaration declaration = customsRepository.lockDeclaration(declarationNo.trim());
        if (declaration == null) {
            return ServiceResult.status(404, new ErrorResponse("DECLARATION_NOT_FOUND",
                    "通关单不存在: " + declarationNo));
        }
        ServiceResult versionConflict = checkVersion(declaration, request.expectedVersion());
        if (versionConflict != null) {
            return versionConflict;
        }
        ServiceResult usable = checkDeclarationUsable(declaration, now);
        if (usable != null) {
            return usable;
        }
        Review review = reviewRepository.findByBatchNo(request.batchNo().trim());
        if (review == null) {
            return ServiceResult.status(404, new ErrorResponse("REVIEW_NOT_FOUND",
                    "批号不存在: " + request.batchNo()));
        }
        if (!declaration.getEnterpriseName().equals(review.getEnterpriseName())
                || !declaration.getProductName().equals(review.getProductName())) {
            return ServiceResult.status(422, new ErrorResponse("ENTERPRISE_PRODUCT_MISMATCH",
                    "子批企业品种与通关单不一致，整笔拒绝"));
        }
        if (request.declaredDoses() > declaration.availableDoses()) {
            return ServiceResult.status(422, new ErrorResponse("CUSTOMS_QUOTA_EXCEEDED",
                    "申报支数超出通关单未占余量 " + declaration.availableDoses()));
        }

        CustomsBinding existing = customsRepository.lockBindingByReviewId(review.getId());
        if (existing != null && existing.getStatus() != BindingStatus.RELEASED) {
            return ServiceResult.status(422, new ErrorResponse("BATCH_ALREADY_BOUND",
                    "该子批已绑定通关单，一个子批只能绑定一张单"));
        }
        if (existing != null && existing.getReleaseReason() == ReleaseReason.DECLARATION_REVOKED) {
            return ServiceResult.status(422, new ErrorResponse("BATCH_BLOCKED_BY_REVOCATION",
                    "该子批原通关单已撤销，签发被阻断，不能重新绑定"));
        }

        CustomsBinding binding;
        if (existing == null) {
            binding = new CustomsBinding();
            binding.setDeclarationId(declaration.getId());
            binding.setReviewId(review.getId());
            binding.setDeclaredDoses(request.declaredDoses());
            binding.setStatus(BindingStatus.RESERVED);
            binding.setCreatedAt(now);
            binding.setUpdatedAt(now);
            Long bindingId = customsRepository.insertBinding(binding);
            if (bindingId == null) {
                // 并发绑定同一子批：唯一约束落败，整笔拒绝（通关单尚未变更）。
                return ServiceResult.status(422, new ErrorResponse("BATCH_ALREADY_BOUND",
                        "该子批已绑定通关单，一个子批只能绑定一张单"));
            }
            binding.setId(bindingId);
        } else {
            // 撤回释放后重新绑定：复用唯一行，重置核销/释放痕迹。
            binding = existing;
            binding.setDeclarationId(declaration.getId());
            binding.setDeclaredDoses(request.declaredDoses());
            binding.setStatus(BindingStatus.RESERVED);
            binding.setReleaseReason(null);
            binding.setConsumedAt(null);
            binding.setReleasedAt(null);
            binding.setUpdatedAt(now);
            customsRepository.updateBinding(binding);
        }

        declaration.setReservedDoses(declaration.getReservedDoses() + request.declaredDoses());
        declaration.setVersion(declaration.getVersion() + 1);
        declaration.setUpdatedAt(now);
        customsRepository.updateDeclaration(declaration);

        reviewRepository.insertEvent(new ReviewEvent(review.getId(), "CUSTOMS_BOUND",
                jsonSupport.write(java.util.Map.of(
                        "declarationNo", declaration.getDeclarationNo(),
                        "declaredDoses", request.declaredDoses())), now));
        return ServiceResult.ok(assemble(declaration, customsRepository.listBindings(declaration.getId())));
    }

    // ============================ 签发前撤回子批 ============================

    /** 撤回子批（requestId 幂等）：释放预占且只释放一次。 */
    public ServiceResult withdraw(String declarationNo, String batchNo, CustomsWithdrawRequest request) {
        String fingerprint = Fingerprints.customsWithdraw(declarationNo, batchNo, request);
        return idempotent(request.requestId(), Fingerprints.CUSTOMS_WITHDRAW, fingerprint,
                () -> doWithdraw(declarationNo, batchNo, request));
    }

    private ServiceResult doWithdraw(String declarationNo, String batchNo, CustomsWithdrawRequest request) {
        Instant now = clock.now();
        CustomsDeclaration declaration = customsRepository.lockDeclaration(declarationNo.trim());
        if (declaration == null) {
            return ServiceResult.status(404, new ErrorResponse("DECLARATION_NOT_FOUND",
                    "通关单不存在: " + declarationNo));
        }
        ServiceResult versionConflict = checkVersion(declaration, request.expectedVersion());
        if (versionConflict != null) {
            return versionConflict;
        }
        Review review = reviewRepository.findByBatchNo(batchNo.trim());
        if (review == null) {
            return ServiceResult.status(404, new ErrorResponse("REVIEW_NOT_FOUND",
                    "批号不存在: " + batchNo));
        }
        CustomsBinding binding = customsRepository.lockBindingByReviewId(review.getId());
        if (binding == null || !binding.getDeclarationId().equals(declaration.getId())) {
            return ServiceResult.status(404, new ErrorResponse("BINDING_NOT_FOUND",
                    "该子批未绑定此通关单"));
        }
        if (binding.getStatus() != BindingStatus.RESERVED) {
            return ServiceResult.status(422, new ErrorResponse("BINDING_NOT_RESERVED",
                    "仅预占中的绑定可撤回释放，当前状态: " + binding.getStatus() + "，预占只释放一次"));
        }

        binding.setStatus(BindingStatus.RELEASED);
        binding.setReleaseReason(ReleaseReason.WITHDRAWN);
        binding.setReleasedAt(now);
        binding.setUpdatedAt(now);
        customsRepository.updateBinding(binding);

        declaration.setReservedDoses(declaration.getReservedDoses() - binding.getDeclaredDoses());
        declaration.setVersion(declaration.getVersion() + 1);
        declaration.setUpdatedAt(now);
        customsRepository.updateDeclaration(declaration);

        reviewRepository.insertEvent(new ReviewEvent(review.getId(), "CUSTOMS_RELEASED",
                jsonSupport.write(java.util.Map.of(
                        "declarationNo", declaration.getDeclarationNo(),
                        "releasedDoses", binding.getDeclaredDoses(),
                        "reason", ReleaseReason.WITHDRAWN.name())), now));
        return ServiceResult.ok(assemble(declaration, customsRepository.listBindings(declaration.getId())));
    }

    // ============================ 通关单撤销 ============================

    /**
     * 撤销通关单（requestId 幂等）：全部未签发子批预占释放并阻断签发；
     * 已签发证据保持不变并逐批记风险事件。
     */
    public ServiceResult revoke(String declarationNo, CustomsRevokeRequest request) {
        String fingerprint = Fingerprints.customsRevoke(declarationNo, request);
        return idempotent(request.requestId(), Fingerprints.CUSTOMS_REVOKE, fingerprint,
                () -> doRevoke(declarationNo, request));
    }

    private ServiceResult doRevoke(String declarationNo, CustomsRevokeRequest request) {
        Instant now = clock.now();
        CustomsDeclaration declaration = customsRepository.lockDeclaration(declarationNo.trim());
        if (declaration == null) {
            return ServiceResult.status(404, new ErrorResponse("DECLARATION_NOT_FOUND",
                    "通关单不存在: " + declarationNo));
        }
        ServiceResult versionConflict = checkVersion(declaration, request.expectedVersion());
        if (versionConflict != null) {
            return versionConflict;
        }
        if (declaration.getStatus() == CustomsDeclarationStatus.REVOKED) {
            return ServiceResult.status(422, new ErrorResponse("DECLARATION_ALREADY_REVOKED",
                    "通关单已撤销，不能重复撤销"));
        }

        List<CustomsBinding> bindings = customsRepository.listBindings(declaration.getId());
        int released = 0;
        for (CustomsBinding binding : bindings) {
            if (binding.getStatus() == BindingStatus.RESERVED) {
                binding.setStatus(BindingStatus.RELEASED);
                binding.setReleaseReason(ReleaseReason.DECLARATION_REVOKED);
                binding.setReleasedAt(now);
                binding.setUpdatedAt(now);
                customsRepository.updateBinding(binding);
                released += binding.getDeclaredDoses();
                reviewRepository.insertEvent(new ReviewEvent(binding.getReviewId(), "CUSTOMS_RELEASED",
                        jsonSupport.write(java.util.Map.of(
                                "declarationNo", declaration.getDeclarationNo(),
                                "releasedDoses", binding.getDeclaredDoses(),
                                "reason", ReleaseReason.DECLARATION_REVOKED.name())), now));
            } else if (binding.getStatus() == BindingStatus.CONSUMED) {
                // 已签发证据保持不变，记风险事件。
                reviewRepository.insertEvent(new ReviewEvent(binding.getReviewId(),
                        "CUSTOMS_RISK_ISSUED_UNDER_REVOKED",
                        jsonSupport.write(java.util.Map.of(
                                "declarationNo", declaration.getDeclarationNo(),
                                "consumedDoses", binding.getDeclaredDoses())), now));
            }
        }

        declaration.setStatus(CustomsDeclarationStatus.REVOKED);
        declaration.setReservedDoses(declaration.getReservedDoses() - released);
        declaration.setVersion(declaration.getVersion() + 1);
        declaration.setUpdatedAt(now);
        customsRepository.updateDeclaration(declaration);
        return ServiceResult.ok(assemble(declaration, customsRepository.listBindings(declaration.getId())));
    }

    // ============================ 通关单修订 ============================

    /** 修订（requestId 幂等）：仅允许调增总支数以增加未占余量，不改企业品种。 */
    public ServiceResult revise(String declarationNo, CustomsReviseRequest request) {
        String fingerprint = Fingerprints.customsRevise(declarationNo, request);
        return idempotent(request.requestId(), Fingerprints.CUSTOMS_REVISE, fingerprint,
                () -> doRevise(declarationNo, request));
    }

    private ServiceResult doRevise(String declarationNo, CustomsReviseRequest request) {
        Instant now = clock.now();
        CustomsDeclaration declaration = customsRepository.lockDeclaration(declarationNo.trim());
        if (declaration == null) {
            return ServiceResult.status(404, new ErrorResponse("DECLARATION_NOT_FOUND",
                    "通关单不存在: " + declarationNo));
        }
        ServiceResult versionConflict = checkVersion(declaration, request.expectedVersion());
        if (versionConflict != null) {
            return versionConflict;
        }
        if (declaration.getStatus() == CustomsDeclarationStatus.REVOKED) {
            return ServiceResult.status(422, new ErrorResponse("DECLARATION_ALREADY_REVOKED",
                    "通关单已撤销，不能修订"));
        }
        if (request.newTotalDoses() <= declaration.getTotalDoses()) {
            return ServiceResult.status(422, new ErrorResponse("REVISION_NOT_INCREASING",
                    "通关修订仅允许调增总支数（当前 " + declaration.getTotalDoses() + "），不改历史核销企业品种"));
        }
        declaration.setTotalDoses(request.newTotalDoses());
        declaration.setVersion(declaration.getVersion() + 1);
        declaration.setUpdatedAt(now);
        customsRepository.updateDeclaration(declaration);
        return ServiceResult.ok(assemble(declaration, customsRepository.listBindings(declaration.getId())));
    }

    // ============================ 余量台账查询 ============================

    /** 查询通关单余量台账（总数、预占、已核销、未占余量及子批绑定明细）。 */
    public CustomsDeclarationView getLedger(String declarationNo) {
        CustomsDeclaration declaration = customsRepository.findDeclaration(declarationNo.trim());
        if (declaration == null) {
            throw new BusinessException(404, "DECLARATION_NOT_FOUND", "通关单不存在: " + declarationNo);
        }
        return assemble(declaration, customsRepository.listBindings(declaration.getId()));
    }

    // ============================ 内部辅助 ============================

    /** 版本校验：期望版本与当前版本不一致时返回 409。 */
    private ServiceResult checkVersion(CustomsDeclaration declaration, Integer expectedVersion) {
        if (expectedVersion == null || expectedVersion != declaration.getVersion()) {
            return ServiceResult.status(409, new ErrorResponse("VERSION_CONFLICT",
                    "期望版本 " + expectedVersion + " 与当前版本 " + declaration.getVersion() + " 不一致"));
        }
        return null;
    }

    /** 通关单可用性：未撤销且 now<截止（恰截止拒绝）。 */
    private ServiceResult checkDeclarationUsable(CustomsDeclaration declaration, Instant now) {
        if (declaration.getStatus() == CustomsDeclarationStatus.REVOKED) {
            return ServiceResult.status(422, new ErrorResponse("DECLARATION_ALREADY_REVOKED",
                    "通关单已撤销，不能绑定子批"));
        }
        if (!now.isBefore(declaration.getValidUntil())) {
            return ServiceResult.status(422, new ErrorResponse("CUSTOMS_DECLARATION_EXPIRED",
                    "通关单已于 " + declaration.getValidUntil() + " 截止，恰截止拒绝"));
        }
        return null;
    }

    /** 装配余量台账视图。 */
    private CustomsDeclarationView assemble(CustomsDeclaration declaration, List<CustomsBinding> bindings) {
        List<CustomsBindingView> bindingViews = bindings.stream()
                .map(binding -> {
                    Review review = reviewRepository.findById(binding.getReviewId());
                    return new CustomsBindingView(
                            review == null ? null : review.getBatchNo(),
                            binding.getDeclaredDoses(),
                            binding.getStatus().name(),
                            binding.getReleaseReason() == null ? null : binding.getReleaseReason().name(),
                            binding.getCreatedAt(),
                            binding.getConsumedAt(),
                            binding.getReleasedAt());
                })
                .toList();
        return new CustomsDeclarationView(
                declaration.getDeclarationNo(),
                declaration.getEnterpriseName(),
                declaration.getProductName(),
                declaration.getValidUntil(),
                declaration.getTotalDoses(),
                declaration.getReservedDoses(),
                declaration.getConsumedDoses(),
                declaration.availableDoses(),
                declaration.getStatus().name(),
                declaration.getVersion(),
                bindingViews);
    }

    // ============================ 幂等模板（失败不占键） ============================

    /**
     * requestId 幂等执行：同键同参重放首次冻结结果；同键异参 409；
     * 业务失败（4xx）删除占位不占键，后续同键请求可重新执行。
     * 占位记录与业务在同一事务内提交，并发同键由主键竞争唯一序列化。
     */
    private ServiceResult idempotent(String requestId, String operation, String fingerprint, TxAction action) {
        IdempotencyRecord existing = idempotencyRepository.find(requestId);
        if (existing != null) {
            return replayOrConflict(existing, fingerprint);
        }
        try {
            return txTemplate.execute(status -> {
                idempotencyRepository.insertPlaceholder(requestId, operation, fingerprint, clock.now());
                ServiceResult result = action.run();
                if (result.httpStatus() < 400) {
                    idempotencyRepository.finalize(requestId, result.httpStatus(),
                            jsonSupport.write(result.body()));
                } else {
                    // 失败不占键：同事务移除占位。
                    idempotencyRepository.delete(requestId);
                }
                return result;
            });
        } catch (DataAccessException ex) {
            // 并发同键：等待首事务提交后读取首次结果重放。
            IdempotencyRecord winner = idempotencyRepository.find(requestId);
            if (winner == null) {
                winner = awaitIdempotencyRecord(requestId);
            }
            return replayOrConflict(winner, fingerprint);
        }
    }

    private ServiceResult replayOrConflict(IdempotencyRecord record, String requestFingerprint) {
        if (!record.getFingerprint().equals(requestFingerprint)) {
            return ServiceResult.status(409, new ErrorResponse("IDEMPOTENCY_FINGERPRINT_MISMATCH",
                    "同一 requestId 携带了不同业务参数，请求被拒绝且通关状态不变"));
        }
        return ServiceResult.status(record.getHttpStatus(), jsonSupport.readObject(record.getResponseJson()));
    }

    private IdempotencyRecord awaitIdempotencyRecord(String requestId) {
        for (int i = 0; i < 200; i++) {
            IdempotencyRecord record = idempotencyRepository.find(requestId);
            if (record != null) {
                return record;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new BusinessException(500, "IDEMPOTENCY_WAIT_INTERRUPTED", "等待首次请求结果时被中断");
            }
        }
        throw new BusinessException(500, "IDEMPOTENCY_RECORD_MISSING",
                "并发幂等记录缺失: " + requestId);
    }
}
