package com.example.starter.service;

import com.example.starter.domain.BindingStatus;
import com.example.starter.domain.CustomsDeclaration;
import com.example.starter.domain.CustomsStatus;
import com.example.starter.domain.Review;
import com.example.starter.domain.ReviewEvent;
import com.example.starter.domain.SubbatchBinding;
import com.example.starter.dto.BindingView;
import com.example.starter.dto.CustomsAmendRequest;
import com.example.starter.dto.CustomsBindRequest;
import com.example.starter.dto.CustomsLedgerView;
import com.example.starter.dto.CustomsRegisterRequest;
import com.example.starter.dto.CustomsRevokeRequest;
import com.example.starter.dto.ErrorResponse;
import com.example.starter.dto.ServiceResult;
import com.example.starter.dto.SubbatchWithdrawRequest;
import com.example.starter.idempotency.Fingerprints;
import com.example.starter.json.JsonSupport;
import com.example.starter.repo.CustomsRepository;
import com.example.starter.repo.IdempotencyRepository;
import com.example.starter.repo.ReviewRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 进口通关单核销衔接服务：通关登记、子批绑定预占、撤销、余量修订、签发前撤回与台账查询。
 *
 * <p>并发统一由行锁按固定顺序 customs_declaration → subbatch_binding 序列化；
 * 新写操作以 requestId 幂等，业务、版本与幂等结果同事务，业务失败回滚不占键。
 * 国内批次不经本服务，保留原批签发门禁不变。
 */
@Service
public class CustomsService {

    private final CustomsRepository customsRepository;
    private final ReviewRepository reviewRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final JsonSupport jsonSupport;
    private final TransactionTemplate txTemplate;

    public CustomsService(CustomsRepository customsRepository,
                          ReviewRepository reviewRepository,
                          IdempotencyRepository idempotencyRepository,
                          JsonSupport jsonSupport,
                          TransactionTemplate txTemplate) {
        this.customsRepository = customsRepository;
        this.reviewRepository = reviewRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.jsonSupport = jsonSupport;
        this.txTemplate = txTemplate;
    }

    // ============================ 通关登记 ============================

    /** 登记合成通关单（requestId 幂等）。数量必须为正，版本从 1 开始；同号重复登记 409。 */
    public ServiceResult register(CustomsRegisterRequest request) {
        return idempotent(request.requestId(), Fingerprints.CUSTOMS_REGISTER,
                Fingerprints.customsRegister(request), () -> doRegister(request));
    }

    private ServiceResult doRegister(CustomsRegisterRequest request) {
        Instant now = request.at();
        CustomsDeclaration declaration = new CustomsDeclaration();
        declaration.setCustomsNo(request.customsNo().trim());
        declaration.setEnterpriseName(request.enterpriseName().trim());
        declaration.setProductName(request.productName().trim());
        declaration.setValidUntil(request.validUntil());
        declaration.setTotalQuantity(request.totalQuantity());
        declaration.setReservedQuantity(0);
        declaration.setWrittenOffQuantity(0);
        declaration.setStatus(CustomsStatus.ACTIVE);
        declaration.setVersion(1);
        declaration.setCreatedAt(now);
        declaration.setUpdatedAt(now);

        Long id = customsRepository.insert(declaration);
        if (id == null) {
            throw new BusinessException(409, "CUSTOMS_NO_TAKEN",
                    "通关单号已存在: " + request.customsNo());
        }
        declaration.setId(id);
        return ServiceResult.ok(toLedger(declaration, List.of()));
    }

    // ============================ 子批绑定（预占） ============================

    /**
     * 包装子批绑定通关单并预占申报支数（requestId 幂等）。
     * 任一资料不符（企业/品种不一致、超余量、重复绑定、单已撤销/失效）整笔拒绝。
     */
    public ServiceResult bind(CustomsBindRequest request) {
        return idempotent(request.requestId(), Fingerprints.CUSTOMS_BIND,
                Fingerprints.customsBind(request), () -> doBind(request));
    }

    private ServiceResult doBind(CustomsBindRequest request) {
        Instant now = request.at();
        int qty = request.declaredQuantity();

        // 固定加锁顺序 customs 在前：先锁并校验通关单，再读取子批（本流程不更新审查行，故无需锁 review）。
        CustomsDeclaration customs = customsRepository.lockByCustomsNo(request.customsNo().trim());
        if (customs == null) {
            throw new BusinessException(404, "CUSTOMS_NOT_FOUND",
                    "通关单不存在: " + request.customsNo());
        }
        Review review = reviewRepository.findByBatchNo(request.batchNo().trim());
        if (review == null) {
            throw new BusinessException(404, "REVIEW_NOT_FOUND",
                    "批号不存在: " + request.batchNo());
        }
        if (customs.getVersion() != request.expectedVersion()) {
            throw new BusinessException(409, "VERSION_CONFLICT",
                    "通关单版本已变化，期望 " + request.expectedVersion()
                            + "，当前 " + customs.getVersion());
        }
        if (customs.getStatus() == CustomsStatus.REVOKED) {
            throw new BusinessException(422, "CUSTOMS_REVOKED",
                    "通关单已撤销，不能再绑定子批: " + customs.getCustomsNo());
        }
        // 有效截止的时间门禁只在进口签发事务内重查（now<截止，恰截止拒绝），绑定阶段不据此拒绝。
        if (!customs.getEnterpriseName().equals(review.getEnterpriseName())) {
            throw new BusinessException(422, "BINDING_ENTERPRISE_MISMATCH",
                    "子批企业 " + review.getEnterpriseName()
                            + " 与通关单登记企业 " + customs.getEnterpriseName() + " 不一致");
        }
        if (!customs.getProductName().equals(review.getProductName())) {
            throw new BusinessException(422, "BINDING_PRODUCT_MISMATCH",
                    "子批品种 " + review.getProductName()
                            + " 与通关单登记品种 " + customs.getProductName() + " 不一致");
        }

        int alreadyUsed = customs.getReservedQuantity() + customs.getWrittenOffQuantity();
        if (alreadyUsed + qty > customs.getTotalQuantity()) {
            throw new BusinessException(422, "INSUFFICIENT_CUSTOMS_QUANTITY",
                    "预占支数超过通关单余量：已占/已核 " + alreadyUsed + " + 本次 " + qty
                            + " > 总数 " + customs.getTotalQuantity());
        }

        SubbatchBinding binding = new SubbatchBinding();
        binding.setReviewId(review.getId());
        binding.setCustomsDeclarationId(customs.getId());
        binding.setBatchNo(review.getBatchNo());
        binding.setDeclaredQuantity(qty);
        binding.setStatus(BindingStatus.RESERVED);
        binding.setReservedAt(now);
        binding.setCreatedAt(now);
        binding.setUpdatedAt(now);
        Long bindingId = customsRepository.insertBinding(binding);
        if (bindingId == null) {
            // 同一子批只能绑定一张通关单（uk_binding_review 落败）。
            throw new BusinessException(422, "SUBBATCH_ALREADY_BOUND",
                    "该子批已绑定通关单，每个子批只能绑定一张单: " + review.getBatchNo());
        }
        binding.setId(bindingId);

        customs.setReservedQuantity(customs.getReservedQuantity() + qty);
        customs.setVersion(customs.getVersion() + 1);
        customs.setUpdatedAt(now);
        customsRepository.update(customs);

        reviewRepository.insertEvent(new ReviewEvent(review.getId(), "CUSTOMS_RESERVED",
                jsonSupport.write(Map.of(
                        "customsNo", customs.getCustomsNo(),
                        "declaredQuantity", qty,
                        "reservedQuantity", customs.getReservedQuantity(),
                        "writtenOffQuantity", customs.getWrittenOffQuantity(),
                        "totalQuantity", customs.getTotalQuantity())), now));

        return ServiceResult.ok(toLedger(customs, customsRepository.listBindingsByCustomsId(customs.getId())));
    }

    // ============================ 通关单撤销 ============================

    /**
     * 撤销通关单（requestId 幂等）：释放全部未签发子批预占并阻断后续签发；
     * 已签发（已核销）证据保持不变，逐笔登记风险事件。
     */
    public ServiceResult revoke(CustomsRevokeRequest request) {
        return idempotent(request.requestId(), Fingerprints.CUSTOMS_REVOKE,
                Fingerprints.customsRevoke(request), () -> doRevoke(request));
    }

    private ServiceResult doRevoke(CustomsRevokeRequest request) {
        Instant now = request.at();
        CustomsDeclaration customs = customsRepository.lockByCustomsNo(request.customsNo().trim());
        if (customs == null) {
            throw new BusinessException(404, "CUSTOMS_NOT_FOUND",
                    "通关单不存在: " + request.customsNo());
        }
        if (customs.getVersion() != request.expectedVersion()) {
            throw new BusinessException(409, "VERSION_CONFLICT",
                    "通关单版本已变化，期望 " + request.expectedVersion()
                            + "，当前 " + customs.getVersion());
        }
        if (customs.getStatus() == CustomsStatus.REVOKED) {
            // 新 requestId 重复撤销已撤销单：业务拒绝；同 requestId 的重复请求由幂等重放首次结果。
            throw new BusinessException(422, "CUSTOMS_ALREADY_REVOKED",
                    "通关单已撤销，不能重复撤销: " + customs.getCustomsNo());
        }

        // 加锁顺序保持 customs → binding：锁定全部未签发（RESERVED）绑定并释放。
        List<SubbatchBinding> reserved = customsRepository.lockReservedBindings(customs.getId());
        int releasedTotal = 0;
        for (SubbatchBinding binding : reserved) {
            binding.setStatus(BindingStatus.RELEASED);
            binding.setReleasedAt(now);
            binding.setVersion(binding.getVersion() + 1);
            binding.setUpdatedAt(now);
            customsRepository.updateBinding(binding);
            releasedTotal += binding.getDeclaredQuantity();
            reviewRepository.insertEvent(new ReviewEvent(binding.getReviewId(), "CUSTOMS_RESERVATION_RELEASED",
                    jsonSupport.write(Map.of(
                            "customsNo", customs.getCustomsNo(),
                            "reason", "CUSTOMS_REVOKED",
                            "declaredQuantity", binding.getDeclaredQuantity())), now));
        }

        // 已核销（已签发）子批：证据不变，逐笔记风险事件。
        List<SubbatchBinding> all = customsRepository.listBindingsByCustomsId(customs.getId());
        for (SubbatchBinding binding : all) {
            if (binding.getStatus() == BindingStatus.WRITTEN_OFF) {
                reviewRepository.insertEvent(new ReviewEvent(binding.getReviewId(),
                        "CUSTOMS_REVOKED_AFTER_ISSUE",
                        jsonSupport.write(Map.of(
                                "customsNo", customs.getCustomsNo(),
                                "risk", "通关单撤销时该子批已签发，核销证据保持不变",
                                "writtenOffQuantity", binding.getDeclaredQuantity())), now));
            }
        }

        customs.setReservedQuantity(customs.getReservedQuantity() - releasedTotal);
        customs.setStatus(CustomsStatus.REVOKED);
        customs.setRevokedAt(now);
        customs.setVersion(customs.getVersion() + 1);
        customs.setUpdatedAt(now);
        customsRepository.update(customs);

        return ServiceResult.ok(toLedger(customs, customsRepository.listBindingsByCustomsId(customs.getId())));
    }

    // ============================ 通关单修订（仅增余量） ============================

    /** 修订通关单（requestId 幂等）：仅允许增加总支数，不改历史核销企业品种；撤销单不可修订。 */
    public ServiceResult amend(CustomsAmendRequest request) {
        return idempotent(request.requestId(), Fingerprints.CUSTOMS_AMEND,
                Fingerprints.customsAmend(request), () -> doAmend(request));
    }

    private ServiceResult doAmend(CustomsAmendRequest request) {
        Instant now = request.at();
        CustomsDeclaration customs = customsRepository.lockByCustomsNo(request.customsNo().trim());
        if (customs == null) {
            throw new BusinessException(404, "CUSTOMS_NOT_FOUND",
                    "通关单不存在: " + request.customsNo());
        }
        if (customs.getVersion() != request.expectedVersion()) {
            throw new BusinessException(409, "VERSION_CONFLICT",
                    "通关单版本已变化，期望 " + request.expectedVersion()
                            + "，当前 " + customs.getVersion());
        }
        if (customs.getStatus() == CustomsStatus.REVOKED) {
            throw new BusinessException(422, "CUSTOMS_REVOKED",
                    "通关单已撤销，不能修订: " + customs.getCustomsNo());
        }
        if (request.newTotalQuantity() <= customs.getTotalQuantity()) {
            throw new BusinessException(422, "AMEND_MUST_INCREASE",
                    "修订只能增加总支数，当前总数 " + customs.getTotalQuantity()
                            + "，提交 " + request.newTotalQuantity());
        }
        customs.setTotalQuantity(request.newTotalQuantity());
        customs.setVersion(customs.getVersion() + 1);
        customs.setUpdatedAt(now);
        customsRepository.update(customs);
        return ServiceResult.ok(toLedger(customs, customsRepository.listBindingsByCustomsId(customs.getId())));
    }

    // ============================ 签发前撤回子批 ============================

    /** 签发前撤回子批（requestId 幂等）：释放本子批预占且只释放一次；已核销（已签发）拒绝。 */
    public ServiceResult withdraw(SubbatchWithdrawRequest request) {
        return idempotent(request.requestId(), Fingerprints.SUBBATCH_WITHDRAW,
                Fingerprints.subbatchWithdraw(request), () -> doWithdraw(request));
    }

    private ServiceResult doWithdraw(SubbatchWithdrawRequest request) {
        Instant now = request.at();
        Review review = reviewRepository.findByBatchNo(request.batchNo().trim());
        if (review == null) {
            throw new BusinessException(404, "REVIEW_NOT_FOUND",
                    "批号不存在: " + request.batchNo());
        }
        SubbatchBinding existing = customsRepository.findBindingByReviewId(review.getId());
        if (existing == null) {
            throw new BusinessException(404, "BINDING_NOT_FOUND",
                    "该子批未绑定通关单，无预占可释放: " + review.getBatchNo());
        }

        // 先锁通关单再锁绑定，保持全局 customs → binding 加锁顺序。
        CustomsDeclaration customs = customsRepository.lockById(existing.getCustomsDeclarationId());
        SubbatchBinding binding = customsRepository.lockBindingByReviewId(review.getId());
        if (binding.getVersion() != request.expectedVersion()) {
            throw new BusinessException(409, "VERSION_CONFLICT",
                    "子批绑定版本已变化，期望 " + request.expectedVersion()
                            + "，当前 " + binding.getVersion());
        }
        if (binding.getStatus() == BindingStatus.WRITTEN_OFF) {
            throw new BusinessException(422, "SUBBATCH_ALREADY_ISSUED",
                    "子批已签发核销，不能撤回: " + review.getBatchNo());
        }
        if (binding.getStatus() == BindingStatus.RELEASED) {
            throw new BusinessException(422, "SUBBATCH_ALREADY_RELEASED",
                    "子批预占已释放，释放只允许一次: " + review.getBatchNo());
        }

        binding.setStatus(BindingStatus.RELEASED);
        binding.setReleasedAt(now);
        binding.setVersion(binding.getVersion() + 1);
        binding.setUpdatedAt(now);
        customsRepository.updateBinding(binding);

        customs.setReservedQuantity(customs.getReservedQuantity() - binding.getDeclaredQuantity());
        customs.setVersion(customs.getVersion() + 1);
        customs.setUpdatedAt(now);
        customsRepository.update(customs);

        reviewRepository.insertEvent(new ReviewEvent(review.getId(), "SUBBATCH_WITHDRAWN",
                jsonSupport.write(Map.of(
                        "customsNo", customs.getCustomsNo(),
                        "releasedQuantity", binding.getDeclaredQuantity())), now));

        return ServiceResult.ok(toLedger(customs, customsRepository.listBindingsByCustomsId(customs.getId())));
    }

    // ============================ 台账查询 ============================

    /** 查询通关单余量台账及全部子批绑定明细；通关单不存在 404。 */
    public CustomsLedgerView getLedger(String customsNo) {
        CustomsDeclaration customs = customsRepository.findByCustomsNo(customsNo.trim());
        if (customs == null) {
            throw new BusinessException(404, "CUSTOMS_NOT_FOUND",
                    "通关单不存在: " + customsNo);
        }
        return toLedger(customs, customsRepository.listBindingsByCustomsId(customs.getId()));
    }

    // ============================ 幂等模板（失败不占键） ============================

    /**
     * requestId 幂等：同键同参重放首次冻结结果；同键异参 409；
     * 业务、版本与幂等结果同事务。业务失败统一抛 {@link BusinessException}（404/409/422），
     * 触发整事务回滚，占位记录一并回滚，因此失败不占用 requestId。
     * 并发同键落败方在占位插入处撞唯一键，等待首事务定稿后重放首次结果。
     */
    private ServiceResult idempotent(String requestId, String operation, String fingerprint,
                                     TxAction action) {
        while (true) {
            var existing = idempotencyRepository.find(requestId);
            if (existing != null) {
                return replayOrConflict(existing, fingerprint);
            }
            try {
                return txTemplate.execute(status -> {
                    idempotencyRepository.insertPlaceholder(requestId, operation, fingerprint,
                            java.time.Instant.now());
                    ServiceResult result = action.run();
                    idempotencyRepository.finalize(requestId, result.httpStatus(),
                            jsonSupport.write(result.body()));
                    return result;
                });
            } catch (DataAccessException ex) {
                // 并发同键：等待首事务定稿；若首事务失败回滚导致记录消失，则本请求自行重试。
                var winner = awaitFinalizedOrGone(requestId);
                if (winner == null) {
                    continue;
                }
                return replayOrConflict(winner, fingerprint);
            }
        }
    }

    @FunctionalInterface
    private interface TxAction {
        ServiceResult run();
    }

    private ServiceResult replayOrConflict(com.example.starter.domain.IdempotencyRecord record,
                                           String requestFingerprint) {
        if (!record.getFingerprint().equals(requestFingerprint)) {
            return ServiceResult.status(409, new ErrorResponse("IDEMPOTENCY_FINGERPRINT_MISMATCH",
                    "同一 requestId 携带了不同业务参数（含版本或精确时刻），请求被拒绝且业务状态不变"));
        }
        return ServiceResult.status(record.getHttpStatus(), jsonSupport.readObject(record.getResponseJson()));
    }

    /**
     * 等待并发首事务结果：定稿（http_status 非 0）则返回记录；记录缺失意味着首事务失败回滚、
     * 键已释放，返回 null 由调用方自行重试。落败方的重复 INSERT 只会在首事务提交或回滚后才被
     * 解除阻塞，因此此处不会观察到“占位中（http_status=0）”的稳态。
     */
    private com.example.starter.domain.IdempotencyRecord awaitFinalizedOrGone(String requestId) {
        for (int i = 0; i < 300; i++) {
            var record = idempotencyRepository.find(requestId);
            if (record == null) {
                return null;
            }
            if (record.getHttpStatus() != 0) {
                return record;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new BusinessException(500, "IDEMPOTENCY_WAIT_INTERRUPTED", "等待首次请求结果时被中断");
            }
        }
        throw new BusinessException(500, "IDEMPOTENCY_RECORD_NOT_FINALIZED",
                "并发首请求长时间未定稿: " + requestId);
    }

    // ============================ 视图装配 ============================

    private CustomsLedgerView toLedger(CustomsDeclaration customs, List<SubbatchBinding> bindings) {
        int available = customs.getTotalQuantity()
                - customs.getReservedQuantity() - customs.getWrittenOffQuantity();
        List<BindingView> bindingViews = bindings.stream()
                .map(b -> new BindingView(
                        b.getBatchNo(),
                        b.getDeclaredQuantity(),
                        b.getStatus().name(),
                        b.getVersion(),
                        b.getReservedAt(),
                        b.getWrittenOffAt(),
                        b.getReleasedAt()))
                .toList();
        return new CustomsLedgerView(
                customs.getCustomsNo(),
                customs.getEnterpriseName(),
                customs.getProductName(),
                customs.getValidUntil(),
                customs.getTotalQuantity(),
                customs.getReservedQuantity(),
                customs.getWrittenOffQuantity(),
                available,
                customs.getStatus().name(),
                customs.getVersion(),
                customs.getRevokedAt(),
                bindingViews);
    }
}
