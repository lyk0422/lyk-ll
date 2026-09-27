package com.example.starter.service;

import com.example.starter.catalog.DocumentCatalog;
import com.example.starter.catalog.InspectionCatalog;
import com.example.starter.catalog.InspectionEvaluator;
import com.example.starter.catalog.InspectionSpec;
import com.example.starter.domain.EnterpriseProductStatus;
import com.example.starter.domain.InspectionRecord;
import com.example.starter.domain.PublicityRecord;
import com.example.starter.domain.PublicityStatus;
import com.example.starter.domain.Review;
import com.example.starter.domain.ReviewEvent;
import com.example.starter.domain.ReviewStatus;
import com.example.starter.dto.CorrectRequest;
import com.example.starter.dto.DeclareRequest;
import com.example.starter.dto.EnterpriseStatusView;
import com.example.starter.dto.ErrorResponse;
import com.example.starter.dto.InspectionInput;
import com.example.starter.dto.LateDocumentRequest;
import com.example.starter.dto.LateDocumentResponse;
import com.example.starter.dto.ReviewView;
import com.example.starter.dto.ServiceResult;
import com.example.starter.idempotency.Fingerprints;
import com.example.starter.json.JsonSupport;
import com.example.starter.repo.EnterpriseProductStatusRepository;
import com.example.starter.repo.IdempotencyRepository;
import com.example.starter.repo.ReviewRepository;
import com.example.starter.time.BusinessClock;
import com.example.starter.time.WorkdayCalculator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 疫苗批签发资料审查与公示核心服务。
 *
 * <p>流转：申报 → 资料审查 → 检验核对 → 综合评定 → 公示 → 签发归档。
 * 申报、补正、签发以 requestId 幂等；批号唯一；公示期满生效与异议受理经行锁形成唯一终态。
 */
@Service
public class ReviewService {

    /** 补正工作日时限。 */
    static final int CORRECTION_WORKDAYS = 5;
    /** 公示自然日时长（UTC 日界）。 */
    static final int PUBLICITY_DAYS = 7;
    /** 恰连续 3 批不合格触发暂停受理。 */
    static final int SUSPEND_THRESHOLD = 3;

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final ReviewRepository reviewRepository;
    private final EnterpriseProductStatusRepository statusRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final ReviewViewAssembler assembler;
    private final JsonSupport jsonSupport;
    private final BusinessClock clock;
    private final TransactionTemplate txTemplate;
    private final CustomsService customsService;

    public ReviewService(ReviewRepository reviewRepository,
                         EnterpriseProductStatusRepository statusRepository,
                         IdempotencyRepository idempotencyRepository,
                         ReviewViewAssembler assembler,
                         JsonSupport jsonSupport,
                         BusinessClock clock,
                         TransactionTemplate txTemplate,
                         CustomsService customsService) {
        this.reviewRepository = reviewRepository;
        this.statusRepository = statusRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.assembler = assembler;
        this.jsonSupport = jsonSupport;
        this.clock = clock;
        this.txTemplate = txTemplate;
        this.customsService = customsService;
    }

    /** 事务内执行业务并返回结果（允许提交 4xx 预期错误结果）。 */
    @FunctionalInterface
    private interface TxAction {
        ServiceResult run();
    }

    // ============================ 申报 ============================

    /**
     * 申报受理（requestId 幂等）。资料缺项一次性告知补正；资料齐全则完成资料审查与检验核对。
     */
    public ServiceResult declare(DeclareRequest request) {
        String fingerprint = Fingerprints.declare(request);
        return idempotent(request.requestId(), Fingerprints.DECLARE, fingerprint,
                () -> doDeclare(request));
    }

    private ServiceResult doDeclare(DeclareRequest request) {
        Instant now = clock.now();

        // 暂停受理校验：同企业同品种连续 3 批不合格已暂停的，拒绝受理并转核查。
        EnterpriseProductStatus epStatus = statusRepository.find(request.enterpriseName(), request.productName());
        if (epStatus == null) {
            statusRepository.initIfAbsent(request.enterpriseName(), request.productName(), now);
            epStatus = statusRepository.find(request.enterpriseName(), request.productName());
        }
        if (epStatus.isSuspended()) {
            return ServiceResult.status(403, new ErrorResponse("ENTERPRISE_SUSPENDED",
                    "该企业同品种已因连续 3 批不合格暂停受理并转核查"));
        }

        List<String> submitted = normalizeDocuments(request.documents());
        List<String> invalidDocs = submitted.stream()
                .filter(code -> !DocumentCatalog.requiredDocuments().contains(code))
                .toList();
        if (!invalidDocs.isEmpty()) {
            return ServiceResult.status(400, new ErrorResponse("UNKNOWN_DOCUMENT_CODE",
                    "存在合成资料目录之外的资料代码: " + invalidDocs));
        }

        List<InspectionRecord> inspections = buildInspectionRecords(request.inspections());
        if (inspections == null) {
            return ServiceResult.status(400, new ErrorResponse("INVALID_INSPECTION",
                    "检验项目必须且只能包含合成检验目录中的全部项目，代码不得重复"));
        }

        List<String> missing = DocumentCatalog.requiredDocuments().stream()
                .filter(code -> !submitted.contains(code))
                .toList();

        Review review = new Review();
        review.setEnterpriseName(request.enterpriseName().trim());
        review.setProductName(request.productName().trim());
        review.setBatchNo(request.batchNo().trim());
        review.setDocumentsJson(jsonSupport.write(deduplicate(request.documents())));
        review.setDeclaredAt(now);
        review.setCreatedAt(now);
        review.setUpdatedAt(now);

        LocalDate noticeDate = LocalDate.ofInstant(now, SHANGHAI);
        if (!missing.isEmpty()) {
            // 缺项：一次性告知补正，时限 5 个工作日。
            review.setStatus(ReviewStatus.PENDING_CORRECTION);
            review.setMissingJson(jsonSupport.write(missing));
            review.setCorrectionNoticeDate(noticeDate);
            review.setCorrectionDeadline(WorkdayCalculator.plusWorkdays(noticeDate, CORRECTION_WORKDAYS));
        } else {
            review.setStatus(ReviewStatus.INSPECTION_CHECKED);
            review.setMissingJson(null);
        }

        Long reviewId = reviewRepository.insert(review);
        if (reviewId == null) {
            // 批号唯一约束落败：同一批号同时刻只允许一个审查流程。
            return ServiceResult.status(409, new ErrorResponse("BATCH_ALREADY_ACCEPTED",
                    "该批号已存在审查流程，并发申报仅唯一受理"));
        }
        review.setId(reviewId);
        reviewRepository.insertInspections(reviewId, inspections);

        reviewRepository.insertEvent(new ReviewEvent(reviewId, "DECLARED",
                jsonSupport.write(java.util.Map.of("batchNo", review.getBatchNo())), now));
        if (!missing.isEmpty()) {
            reviewRepository.insertEvent(new ReviewEvent(reviewId, "CORRECTION_NOTICED",
                    jsonSupport.write(java.util.Map.of(
                            "missing", missing,
                            "deadline", review.getCorrectionDeadline().toString())), now));
        } else {
            reviewRepository.insertEvent(new ReviewEvent(reviewId, "DOCUMENT_REVIEWED", null, now));
            reviewRepository.insertEvent(new ReviewEvent(reviewId, "INSPECTION_CHECKED",
                    inspectionSummary(inspections), now));
        }
        return ServiceResult.ok(assembler.assemble(review, inspections, null));
    }

    // ============================ 补正 ============================

    /**
     * 补正受理（requestId 幂等）。恰第 5 个工作日提交仍受理，逾期按撤回处理。
     */
    public ServiceResult correct(String batchNo, CorrectRequest request) {
        String fingerprint = Fingerprints.correct(batchNo, request);
        return idempotent(request.requestId(), Fingerprints.CORRECT, fingerprint,
                () -> doCorrect(batchNo, request));
    }

    private ServiceResult doCorrect(String batchNo, CorrectRequest request) {
        Instant now = clock.now();
        Review review = reviewRepository.lockByBatchNo(batchNo.trim());
        if (review == null) {
            return ServiceResult.status(404, new ErrorResponse("REVIEW_NOT_FOUND",
                    "批号不存在: " + batchNo));
        }
        if (review.getStatus() == ReviewStatus.ISSUED
                || review.getStatus() == ReviewStatus.REJECTED
                || review.getStatus() == ReviewStatus.WITHDRAWN
                || review.getStatus() == ReviewStatus.OBJECTION_REVIEW) {
            return ServiceResult.status(409, new ErrorResponse("REVIEW_FINAL_OR_LOCKED",
                    "当前状态 " + review.getStatus() + " 不允许补正"));
        }
        if (review.getStatus() != ReviewStatus.PENDING_CORRECTION) {
            return ServiceResult.status(409, new ErrorResponse("NOT_AWAITING_CORRECTION",
                    "该流程不处于待补正状态，当前状态: " + review.getStatus()));
        }

        LocalDate today = LocalDate.ofInstant(now, SHANGHAI);
        if (today.isAfter(review.getCorrectionDeadline())) {
            // 逾期未补正：按撤回处理（终态）。
            review.setStatus(ReviewStatus.WITHDRAWN);
            review.setUpdatedAt(now);
            reviewRepository.update(review);
            // 签发前撤回：释放该子批在通关单上的预占（只释放一次）。
            customsService.releaseOnWithdraw(review.getId(), now);
            reviewRepository.insertEvent(new ReviewEvent(review.getId(), "WITHDRAWN",
                    jsonSupport.write(java.util.Map.of(
                            "deadline", review.getCorrectionDeadline().toString(),
                            "submittedDate", today.toString())), now));
            return ServiceResult.status(400, new ErrorResponse("CORRECTION_OVERDUE_WITHDRAWN",
                    "补正已逾期（截止 " + review.getCorrectionDeadline() + "），按撤回处理"));
        }

        List<String> submitted = normalizeDocuments(request.documents());
        List<String> invalidDocs = submitted.stream()
                .filter(code -> !DocumentCatalog.requiredDocuments().contains(code))
                .toList();
        if (!invalidDocs.isEmpty()) {
            return ServiceResult.status(400, new ErrorResponse("UNKNOWN_DOCUMENT_CODE",
                    "存在合成资料目录之外的资料代码: " + invalidDocs));
        }

        Set<String> merged = new LinkedHashSet<>(jsonSupport.readStringList(review.getDocumentsJson()));
        merged.addAll(submitted);
        List<String> stillMissing = DocumentCatalog.requiredDocuments().stream()
                .filter(code -> !merged.contains(code))
                .toList();
        if (!stillMissing.isEmpty()) {
            // 一次性告知后补正仍缺项：拒绝本次补正，维持原待补正状态与截止日。
            return ServiceResult.status(400, new ErrorResponse("CORRECTION_STILL_INCOMPLETE",
                    "补正资料仍缺项: " + stillMissing));
        }

        review.setDocumentsJson(jsonSupport.write(List.copyOf(merged)));
        review.setMissingJson(null);
        review.setCorrectedAt(now);
        review.setStatus(ReviewStatus.INSPECTION_CHECKED);
        review.setUpdatedAt(now);
        reviewRepository.update(review);

        List<InspectionRecord> inspections = reviewRepository.listInspections(review.getId());
        reviewRepository.insertEvent(new ReviewEvent(review.getId(), "CORRECTION_ACCEPTED",
                jsonSupport.write(java.util.Map.of("submittedDate", today.toString())), now));
        reviewRepository.insertEvent(new ReviewEvent(review.getId(), "DOCUMENT_REVIEWED", null, now));
        reviewRepository.insertEvent(new ReviewEvent(review.getId(), "INSPECTION_CHECKED",
                inspectionSummary(inspections), now));
        return ServiceResult.ok(assembler.assemble(review, inspections,
                reviewRepository.findPublicity(review.getId())));
    }

    // ============================ 综合评定 + 启动公示 ============================

    /**
     * 综合评定：复算检验链，关键项目（效力、无菌、异常毒性）须全项合格。
     * 评定通过随即启动 7 个自然日公示；不通过则驳回并累计企业连续不合格批次，恰连续 3 批暂停受理。
     */
    public ServiceResult evaluate(String batchNo) {
        return txTemplate.execute(status -> {
            Instant now = clock.now();
            Review review = reviewRepository.lockByBatchNo(batchNo.trim());
            if (review == null) {
                throw new BusinessException(404, "REVIEW_NOT_FOUND", "批号不存在: " + batchNo);
            }
            if (review.getStatus() != ReviewStatus.INSPECTION_CHECKED) {
                throw new BusinessException(409, "INVALID_EVALUATION_STATE",
                        "仅检验核对完成的批次可综合评定，当前状态: " + review.getStatus());
            }

            List<InspectionRecord> records = reviewRepository.listInspections(review.getId());
            // 复算：不信任存储的 qualified，按限值重新判定。
            boolean allQualified = true;
            boolean criticalQualified = true;
            for (InspectionRecord record : records) {
                InspectionSpec spec = InspectionCatalog.find(record.getItemCode());
                boolean qualified = spec != null
                        && InspectionEvaluator.isQualified(spec, record.getMeasuredValue());
                record.setQualified(qualified);
                allQualified &= qualified;
                if (record.isCritical()) {
                    criticalQualified &= qualified;
                }
            }
            boolean passed = allQualified && criticalQualified;

            review.setConclusion(passed ? "PASS" : "FAIL");
            review.setUpdatedAt(now);
            if (!passed) {
                review.setStatus(ReviewStatus.REJECTED);
                reviewRepository.update(review);
                EnterpriseProductStatus updated = statusRepository.incrementFailed(
                        review.getEnterpriseName(), review.getProductName(), now);
                String payload = jsonSupport.write(java.util.Map.of(
                        "consecutiveFailed", updated.getConsecutiveFailed(),
                        "criticalQualified", criticalQualified));
                reviewRepository.insertEvent(new ReviewEvent(review.getId(), "EVALUATION_FAILED", payload, now));
                if (updated.getConsecutiveFailed() == SUSPEND_THRESHOLD) {
                    reviewRepository.insertEvent(new ReviewEvent(review.getId(), "SUSPENSION_TRIGGERED",
                            jsonSupport.write(java.util.Map.of(
                                    "enterpriseName", review.getEnterpriseName(),
                                    "productName", review.getProductName(),
                                    "consecutiveFailed", updated.getConsecutiveFailed())), now));
                }
                return ServiceResult.ok(assembler.assemble(review, records, null));
            }

            // 评定通过：连续不合格计数清零并启动公示（UTC 日界，恰第 7 日期满）。
            statusRepository.resetFailed(review.getEnterpriseName(), review.getProductName(), now);
            LocalDate startUtc = LocalDate.ofInstant(now, ZoneOffset.UTC);
            LocalDate endUtc = startUtc.plusDays(PUBLICITY_DAYS);
            review.setStatus(ReviewStatus.PUBLICIZING);
            review.setPublicityStartUtc(startUtc);
            review.setPublicityEndUtc(endUtc);
            reviewRepository.update(review);

            PublicityRecord publicity = new PublicityRecord();
            publicity.setReviewId(review.getId());
            publicity.setStartUtcDate(startUtc);
            publicity.setEndUtcDate(endUtc);
            publicity.setStatus(PublicityStatus.PUBLICIZING);
            reviewRepository.insertPublicity(publicity);

            reviewRepository.insertEvent(new ReviewEvent(review.getId(), "EVALUATION_PASSED", null, now));
            reviewRepository.insertEvent(new ReviewEvent(review.getId(), "PUBLICITY_STARTED",
                    jsonSupport.write(java.util.Map.of("startUtc", startUtc.toString(),
                            "endUtc", endUtc.toString())), now));
            return ServiceResult.ok(assembler.assemble(review, records, publicity));
        });
    }

    // ============================ 公示：异议 / 期满生效竞态 ============================

    /**
     * 公示期内异议受理，转复核。公示期满（UTC 日期到达 endUtc，恰第 7 日）后不再受理。
     */
    public ServiceResult acceptObjection(String batchNo) {
        return txTemplate.execute(status -> {
            Instant now = clock.now();
            Review review = reviewRepository.lockByBatchNo(batchNo.trim());
            if (review == null) {
                throw new BusinessException(404, "REVIEW_NOT_FOUND", "批号不存在: " + batchNo);
            }
            PublicityRecord publicity = reviewRepository.lockPublicity(review.getId());
            if (publicity == null) {
                throw new BusinessException(409, "NO_PUBLICITY", "该批次尚未进入公示，不能受理异议");
            }
            if (publicity.getStatus() == PublicityStatus.OBJECTION_REVIEW) {
                throw new BusinessException(409, "OBJECTION_ALREADY_ACCEPTED", "异议已受理，正在复核中");
            }
            if (publicity.getStatus() == PublicityStatus.EFFECTIVE) {
                throw new BusinessException(409, "PUBLICITY_ALREADY_EFFECTIVE",
                        "公示已期满生效，异议不再受理");
            }
            LocalDate todayUtc = LocalDate.ofInstant(now, ZoneOffset.UTC);
            if (!todayUtc.isBefore(publicity.getEndUtcDate())) {
                // 恰第 7 个自然日已届日界：期满，异议不予受理。
                throw new BusinessException(409, "PUBLICITY_PERIOD_ENDED",
                        "公示已于 " + publicity.getEndUtcDate() + "（UTC）期满，异议不再受理");
            }
            publicity.setStatus(PublicityStatus.OBJECTION_REVIEW);
            publicity.setObjectionAt(now);
            reviewRepository.updatePublicity(publicity);
            review.setStatus(ReviewStatus.OBJECTION_REVIEW);
            review.setUpdatedAt(now);
            reviewRepository.update(review);
            reviewRepository.insertEvent(new ReviewEvent(review.getId(), "OBJECTION_ACCEPTED", null, now));
            return ServiceResult.ok(assembler.assemble(review,
                    reviewRepository.listInspections(review.getId()), publicity));
        });
    }

    /**
     * 公示期满生效：仅当 UTC 日期恰到达或超过期满日，且无异议受理时生效，形成唯一终态。
     * 已生效重复调用幂等返回；异议受理中的不生效。
     */
    public ServiceResult settlePublicity(String batchNo) {
        return txTemplate.execute(status -> {
            Instant now = clock.now();
            Review review = reviewRepository.lockByBatchNo(batchNo.trim());
            if (review == null) {
                throw new BusinessException(404, "REVIEW_NOT_FOUND", "批号不存在: " + batchNo);
            }
            PublicityRecord publicity = reviewRepository.lockPublicity(review.getId());
            if (publicity == null) {
                throw new BusinessException(409, "NO_PUBLICITY", "该批次尚未进入公示");
            }
            if (publicity.getStatus() == PublicityStatus.EFFECTIVE) {
                return ServiceResult.ok(assembler.assemble(review,
                        reviewRepository.listInspections(review.getId()), publicity));
            }
            if (publicity.getStatus() == PublicityStatus.OBJECTION_REVIEW) {
                throw new BusinessException(409, "UNDER_OBJECTION_REVIEW",
                        "公示期内异议已受理并转复核，不能期满生效");
            }
            LocalDate todayUtc = LocalDate.ofInstant(now, ZoneOffset.UTC);
            if (todayUtc.isBefore(publicity.getEndUtcDate())) {
                throw new BusinessException(409, "PUBLICITY_NOT_EXPIRED",
                        "公示尚未期满，期满日（UTC）为 " + publicity.getEndUtcDate());
            }
            // 恰第 7 日期满：生效，与异议受理互斥，行锁保证唯一终态。
            publicity.setStatus(PublicityStatus.EFFECTIVE);
            publicity.setEffectiveAt(now);
            reviewRepository.updatePublicity(publicity);
            review.setStatus(ReviewStatus.PUBLICITY_EFFECTIVE);
            review.setUpdatedAt(now);
            reviewRepository.update(review);
            reviewRepository.insertEvent(new ReviewEvent(review.getId(), "PUBLICITY_EFFECTIVE",
                    jsonSupport.write(java.util.Map.of("endUtc", publicity.getEndUtcDate().toString())), now));
            return ServiceResult.ok(assembler.assemble(review,
                    reviewRepository.listInspections(review.getId()), publicity));
        });
    }

    // ============================ 签发归档（终态） ============================

    /**
     * 签发归档（requestId 幂等）。仅公示期满生效可签发；重复归档幂等成功。
     */
    public ServiceResult issue(String batchNo, com.example.starter.dto.IssueRequest request) {
        String fingerprint = Fingerprints.issue(batchNo, request);
        return idempotent(request.requestId(), Fingerprints.ISSUE, fingerprint,
                () -> doIssue(batchNo));
    }

    private ServiceResult doIssue(String batchNo) {
        Instant now = clock.now();
        Review review = reviewRepository.lockByBatchNo(batchNo.trim());
        if (review == null) {
            return ServiceResult.status(404, new ErrorResponse("REVIEW_NOT_FOUND", "批号不存在: " + batchNo));
        }
        if (review.getStatus() == ReviewStatus.ISSUED) {
            // 终态重复归档：幂等成功。
            return ServiceResult.ok(assembler.assemble(review));
        }
        if (review.getStatus() != ReviewStatus.PUBLICITY_EFFECTIVE) {
            return ServiceResult.status(409, new ErrorResponse("NOT_ISSUEABLE",
                    "仅公示期满生效的批次可签发归档，当前状态: " + review.getStatus()));
        }
        // 进口批次衔接：事务内重查通关单有效且未撤销，原子把本子批预占转已核销；
        // 国内批次（无绑定）不新增门禁。拒绝时整个签发不生效，不能已发证而余量未扣。
        ServiceResult customsError = customsService.consumeOnIssue(review, now);
        if (customsError != null) {
            return customsError;
        }
        review.setStatus(ReviewStatus.ISSUED);
        review.setIssuedAt(now);
        review.setUpdatedAt(now);
        reviewRepository.update(review);
        reviewRepository.insertEvent(new ReviewEvent(review.getId(), "ISSUED_ARCHIVED", null, now));
        return ServiceResult.ok(assembler.assemble(review));
    }

    /**
     * 归档后迟到资料：仅登记迟到事件，不改变终态；非终态上报拒绝。
     */
    public ServiceResult reportLateDocument(String batchNo, LateDocumentRequest request) {
        return txTemplate.execute(status -> {
            Instant now = clock.now();
            Review review = reviewRepository.lockByBatchNo(batchNo.trim());
            if (review == null) {
                throw new BusinessException(404, "REVIEW_NOT_FOUND", "批号不存在: " + batchNo);
            }
            if (review.getStatus() != ReviewStatus.ISSUED) {
                throw new BusinessException(409, "REVIEW_NOT_ARCHIVED",
                        "仅签发归档后的迟到资料按迟到事件登记，当前状态: " + review.getStatus());
            }
            List<String> documents = normalizeDocuments(request.documents());
            reviewRepository.insertEvent(new ReviewEvent(review.getId(), "LATE_DOCUMENT",
                    jsonSupport.write(java.util.Map.of("documents", documents)), now));
            return ServiceResult.ok(new LateDocumentResponse(review.getBatchNo(),
                    review.getStatus().name(), documents.size(),
                    "批次已签发归档，迟到资料仅登记迟到事件，不改变终态"));
        });
    }

    // ============================ 查询（可复算） ============================

    /** 查询审查全量视图（资料核对表、检验链、公示记录）。 */
    public ReviewView getReview(String batchNo) {
        Review review = reviewRepository.findByBatchNo(batchNo.trim());
        if (review == null) {
            throw new BusinessException(404, "REVIEW_NOT_FOUND", "批号不存在: " + batchNo);
        }
        return assembler.assemble(review);
    }

    /** 查询企业-品种受理状态（连续不合格计数、暂停标记）。 */
    public EnterpriseStatusView getEnterpriseStatus(String enterprise, String product) {
        EnterpriseProductStatus status = statusRepository.find(enterprise.trim(), product.trim());
        if (status == null) {
            return new EnterpriseStatusView(enterprise.trim(), product.trim(), 0, false, null);
        }
        return new EnterpriseStatusView(status.getEnterpriseName(), status.getProductName(),
                status.getConsecutiveFailed(), status.isSuspended(), status.getSuspendedAt());
    }

    /** 读取事件流水（资料核对表、检验链、公示链均可随事件复算）。 */
    public List<java.util.Map<String, Object>> listEvents(String batchNo) {
        Review review = reviewRepository.findByBatchNo(batchNo.trim());
        if (review == null) {
            throw new BusinessException(404, "REVIEW_NOT_FOUND", "批号不存在: " + batchNo);
        }
        return reviewRepository.listEvents(review.getId()).stream()
                .map(event -> java.util.Map.<String, Object>of(
                        "seq", event.getId(),
                        "eventType", event.getEventType(),
                        "payload", event.getPayload() == null ? "" : event.getPayload(),
                        "eventAt", event.getEventAt()))
                .toList();
    }

    // ============================ 幂等模板 ============================

    /**
     * requestId 幂等执行：同键同参返回首次结果；同键异参 409 且不触碰审查状态。
     * 占位记录与业务在同一事务内提交，并发同键由主键竞争唯一序列化。
     */
    private ServiceResult idempotent(String requestId, String operation, String fingerprint, TxAction action) {
        var existing = idempotencyRepository.find(requestId);
        if (existing != null) {
            return replayOrConflict(existing, fingerprint);
        }
        try {
            return txTemplate.execute(status -> {
                idempotencyRepository.insertPlaceholder(requestId, operation, fingerprint, clock.now());
                ServiceResult result = action.run();
                idempotencyRepository.finalize(requestId, result.httpStatus(),
                        jsonSupport.write(result.body()));
                return result;
            });
        } catch (org.springframework.dao.DataAccessException ex) {
            // 并发同键：首个事务持锁，落败方可能收到唯一键冲突或锁等待超时；
            // 等待首事务提交后读取首次结果重放。
            var winner = idempotencyRepository.find(requestId);
            if (winner == null) {
                winner = awaitIdempotencyRecord(requestId);
            }
            return replayOrConflict(winner, fingerprint);
        }
    }

    private ServiceResult replayOrConflict(com.example.starter.domain.IdempotencyRecord record,
                                           String requestFingerprint) {
        if (!record.getFingerprint().equals(requestFingerprint)) {
            return ServiceResult.status(409, new ErrorResponse("IDEMPOTENCY_FINGERPRINT_MISMATCH",
                    "同一 requestId 携带了不同业务参数，请求被拒绝且审查状态不变"));
        }
        return ServiceResult.status(record.getHttpStatus(), jsonSupport.readObject(record.getResponseJson()));
    }

    private com.example.starter.domain.IdempotencyRecord awaitIdempotencyRecord(String requestId) {
        for (int i = 0; i < 200; i++) {
            var record = idempotencyRepository.find(requestId);
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

    // ============================ 内部辅助 ============================

    /**
     * 按合成检验目录构建检验链并逐项复算；项目缺失、未知或重复时返回 null。
     */
    private List<InspectionRecord> buildInspectionRecords(List<InspectionInput> inputs) {
        Set<String> codes = new LinkedHashSet<>();
        for (InspectionInput input : inputs) {
            if (!codes.add(input.itemCode().trim())) {
                return null;
            }
        }
        if (codes.size() != InspectionCatalog.all().size()) {
            return null;
        }
        List<InspectionRecord> records = new ArrayList<>();
        for (InspectionSpec spec : InspectionCatalog.all()) {
            InspectionInput input = inputs.stream()
                    .filter(i -> i.itemCode().trim().equals(spec.itemCode()))
                    .findFirst()
                    .orElse(null);
            if (input == null) {
                return null;
            }
            InspectionRecord record = new InspectionRecord();
            record.setItemCode(spec.itemCode());
            record.setItemName(spec.itemName());
            record.setCritical(spec.critical());
            record.setMeasuredValue(input.measuredValue().trim());
            record.setComparison(spec.comparison().name());
            String primaryLimit = spec.limitMin() != null
                    ? spec.limitMin().toPlainString()
                    : spec.limitMax() != null ? spec.limitMax().toPlainString() : spec.passText();
            record.setLimitValue(primaryLimit);
            record.setLimitMax(spec.limitMax() == null ? null : spec.limitMax().toPlainString());
            record.setQualified(InspectionEvaluator.isQualified(spec, input.measuredValue()));
            records.add(record);
        }
        return records;
    }

    private List<String> normalizeDocuments(List<String> documents) {
        return documents.stream().map(String::trim).filter(code -> !code.isEmpty()).sorted().distinct().toList();
    }

    private List<String> deduplicate(List<String> documents) {
        return new ArrayList<>(new LinkedHashSet<>(documents.stream().map(String::trim).toList()));
    }

    private String inspectionSummary(List<InspectionRecord> records) {
        return jsonSupport.write(records.stream()
                .map(r -> java.util.Map.of("itemCode", r.getItemCode(),
                        "critical", r.isCritical(),
                        "qualified", r.isQualified()))
                .toList());
    }
}
