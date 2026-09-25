package com.example.starter.cosmetic.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Collectors;

import com.example.starter.cosmetic.api.IngredientPayload;
import com.example.starter.cosmetic.api.ObjectionResponse;
import com.example.starter.cosmetic.api.ProductResponse;
import com.example.starter.cosmetic.api.RegistrationDetailResponse;
import com.example.starter.cosmetic.api.RegistrationResponse;
import com.example.starter.cosmetic.api.RegistrationSummaryResponse;
import com.example.starter.cosmetic.api.ReportPayload;
import com.example.starter.cosmetic.api.ReviewItemDto;
import com.example.starter.cosmetic.api.ReviewRecordDto;
import com.example.starter.cosmetic.api.SubmitChangeRequest;
import com.example.starter.cosmetic.api.SubmitRegistrationRequest;
import com.example.starter.cosmetic.domain.ChangeType;
import com.example.starter.cosmetic.domain.IdempotencyRow;
import com.example.starter.cosmetic.domain.ObjectionRow;
import com.example.starter.cosmetic.domain.ObjectionStatus;
import com.example.starter.cosmetic.domain.ProductRow;
import com.example.starter.cosmetic.domain.RegistrationRow;
import com.example.starter.cosmetic.domain.RegistrationStatus;
import com.example.starter.cosmetic.domain.ReportConclusion;
import com.example.starter.cosmetic.domain.ReviewItem;
import com.example.starter.cosmetic.repo.IdempotencyRepository;
import com.example.starter.cosmetic.repo.ProductRepository;
import com.example.starter.cosmetic.repo.RegistrationRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 化妆品备案核心编排：提交（幂等）→ 形式审查 → 公示 → 生效 → 变更/注销。
 * 所有状态流转通过带前置条件的 UPDATE 完成，公示与异议并发形成唯一终态；
 * 变更生效与旧备案失效在同一事务内完成。
 */
@Service
public class RegistrationService {

    /** 公示期自然日数；期满时刻为起始日第10日24:00（UTC）。 */
    static final int PUBLICITY_DAYS = 10;

    private final ProductRepository productRepository;
    private final RegistrationRepository registrationRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final FormalReviewService formalReviewService;
    private final ObjectMapper objectMapper;

    public RegistrationService(ProductRepository productRepository,
                               RegistrationRepository registrationRepository,
                               IdempotencyRepository idempotencyRepository,
                               FormalReviewService formalReviewService,
                               ObjectMapper objectMapper) {
        this.productRepository = productRepository;
        this.registrationRepository = registrationRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.formalReviewService = formalReviewService;
        this.objectMapper = objectMapper;
    }

    /**
     * 新建产品（合成数据）。
     */
    @Transactional
    public ProductResponse createProduct(String name, String applicant, Instant now) {
        long id = productRepository.insert(name, applicant, now);
        return new ProductResponse(id, name, applicant, "ACTIVE");
    }

    /**
     * 首次备案提交：requestId 幂等，同键同参返回首次结果，同键异参返回 409。
     */
    @Transactional
    public RegistrationResponse submitInitial(SubmitRegistrationRequest req, Instant now) {
        String fingerprint = fingerprint("SUBMIT", req.requestId(), String.valueOf(req.productId()),
                req.packagingText(), null, req.claims(), req.ingredients(), req.reports());
        IdempotencyRow existing = idempotencyRepository.find(req.requestId()).orElse(null);
        if (existing != null) {
            return replay(existing, fingerprint);
        }
        try {
            idempotencyRepository.insertMarker(req.requestId(), "SUBMIT", fingerprint, now);
        } catch (DuplicateKeyException e) {
            return replay(awaitRecord(req.requestId()), fingerprint);
        }
        RegistrationResponse response = doSubmitInitial(req, now);
        idempotencyRepository.updateResponse(req.requestId(), 200, toJson(response));
        return response;
    }

    /**
     * 备案变更：SUBSTANTIAL 实质变更重新审查并生成新编号；SIMPLE 简易变更引用原编号并立即生效。
     * requestId 幂等口径同首次提交。
     */
    @Transactional
    public RegistrationResponse submitChange(long sourceRegistrationId, SubmitChangeRequest req, Instant now) {
        String fingerprint = fingerprint("CHANGE", req.requestId(), String.valueOf(sourceRegistrationId),
                req.packagingText(), req.changeType(), req.claims(), req.ingredients(), req.reports());
        IdempotencyRow existing = idempotencyRepository.find(req.requestId()).orElse(null);
        if (existing != null) {
            return replay(existing, fingerprint);
        }
        try {
            idempotencyRepository.insertMarker(req.requestId(), "CHANGE", fingerprint, now);
        } catch (DuplicateKeyException e) {
            return replay(awaitRecord(req.requestId()), fingerprint);
        }
        RegistrationResponse response = doSubmitChange(sourceRegistrationId, req, now);
        idempotencyRepository.updateResponse(req.requestId(), 200, toJson(response));
        return response;
    }

    /**
     * 公示期内提出异议：仅当备案仍为公示中且未到期满时刻才受理，与期满生效互斥。
     */
    @Transactional
    public ObjectionResponse submitObjection(long registrationId, String content, Instant now) {
        RegistrationRow row = findRegistration(registrationId);
        if (row.status() == RegistrationStatus.OBJECTION_REVIEW) {
            long objectionId = registrationRepository.insertObjection(registrationId, content, now);
            return new ObjectionResponse(objectionId, registrationId, row.status().name(), "备案已处于异议审查，异议已追加登记");
        }
        if (row.status() != RegistrationStatus.PUBLICITY) {
            throw BusinessException.conflict("当前状态不受理异议：" + row.status());
        }
        int updated = registrationRepository.markObjectionReview(registrationId, now);
        if (updated == 0) {
            RegistrationRow current = findRegistration(registrationId);
            if (current.status() == RegistrationStatus.PUBLICITY) {
                throw BusinessException.conflict("公示期已满（第10日24:00），异议不予受理");
            }
            throw BusinessException.conflict("异议未受理，备案已进入状态：" + current.status());
        }
        long objectionId = registrationRepository.insertObjection(registrationId, content, now);
        return new ObjectionResponse(objectionId, registrationId, RegistrationStatus.OBJECTION_REVIEW.name(), "异议已受理，备案转入异议审查");
    }

    /**
     * 异议审查结论：UPHELD 异议成立则备案不予生效；DISMISSED 异议不成立则恢复公示或（已过期满）直接生效。
     */
    @Transactional
    public RegistrationResponse resolveObjection(long registrationId, String outcome, Instant now) {
        RegistrationRow row = findRegistration(registrationId);
        if (row.status() != RegistrationStatus.OBJECTION_REVIEW) {
            throw BusinessException.conflict("备案不在异议审查状态：" + row.status());
        }
        ObjectionRow pending = registrationRepository.findPendingObjection(registrationId)
                .orElseThrow(() -> BusinessException.conflict("备案无待审查异议"));
        ObjectionStatus result;
        try {
            result = ObjectionStatus.valueOf(outcome);
        } catch (IllegalArgumentException e) {
            throw BusinessException.unprocessable("审查结论仅支持 DISMISSED/UPHELD：" + outcome);
        }
        if (result == ObjectionStatus.PENDING) {
            throw BusinessException.unprocessable("审查结论仅支持 DISMISSED/UPHELD：" + outcome);
        }
        registrationRepository.updateObjectionStatus(pending.id(), result);
        if (result == ObjectionStatus.UPHELD) {
            registrationRepository.markRejectedFromObjection(registrationId, "异议成立，备案不予生效");
            return toResponse(findRegistration(registrationId), List.of(), "异议成立，备案不予生效");
        }
        if (!row.publicityDeadline().isAfter(now)) {
            registrationRepository.markEffectiveFromObjection(registrationId, now);
            registrationRepository.supersedeOtherEffective(row.productId(), registrationId);
            return toResponse(findRegistration(registrationId), List.of(), "异议不成立，公示期已满，备案生效");
        }
        registrationRepository.backToPublicity(registrationId);
        return toResponse(findRegistration(registrationId), List.of(), "异议不成立，备案恢复公示");
    }

    /**
     * 单个备案公示期满生效：恰第10日24:00期满自动生效；与异议受理互斥，形成唯一终态。
     *
     * @return 是否在本次调用中生效
     */
    @Transactional
    public boolean finalizeIfDue(long registrationId, Instant now) {
        RegistrationRow row = findRegistration(registrationId);
        if (row.status() != RegistrationStatus.PUBLICITY || row.publicityDeadline().isAfter(now)) {
            return false;
        }
        int updated = registrationRepository.markEffective(registrationId, now);
        if (updated == 0) {
            return false;
        }
        registrationRepository.supersedeOtherEffective(row.productId(), registrationId);
        return true;
    }

    /**
     * 批量处理全部到期公示备案（定时任务入口）。
     *
     * @return 本次生效的备案数量
     */
    @Transactional
    public int finalizeDue(Instant now) {
        int count = 0;
        for (Long id : registrationRepository.findDuePublicity(now)) {
            RegistrationRow row = findRegistration(id);
            int updated = registrationRepository.markEffective(id, now);
            if (updated == 1) {
                registrationRepository.supersedeOtherEffective(row.productId(), id);
                count++;
            }
        }
        return count;
    }

    /**
     * 注销生效备案（终态）：重复注销返回幂等成功；注销后产品新宣称审查一律拒绝，历史备案可查。
     */
    @Transactional
    public RegistrationResponse cancel(long registrationId, Instant now) {
        RegistrationRow row = findRegistration(registrationId);
        if (row.status() == RegistrationStatus.CANCELLED) {
            return toResponse(row, List.of(), "备案已注销（重复注销幂等成功）");
        }
        if (row.status() != RegistrationStatus.EFFECTIVE) {
            throw BusinessException.conflict("仅生效备案可注销，当前状态：" + row.status());
        }
        int updated = registrationRepository.markCancelled(registrationId, now);
        if (updated != 1) {
            throw BusinessException.conflict("注销失败，备案状态已变更");
        }
        productRepository.updateStatus(row.productId(), "CANCELLED");
        return toResponse(findRegistration(registrationId), List.of(), "备案已注销");
    }

    /**
     * 备案详情查询（历史备案可查）。
     */
    @Transactional(readOnly = true)
    public RegistrationDetailResponse getDetail(long registrationId) {
        RegistrationRow row = findRegistration(registrationId);
        List<String> claims = registrationRepository.findClaims(registrationId).stream()
                .map(claim -> claim.efficacyCode())
                .toList();
        List<IngredientPayload> ingredients = registrationRepository.findIngredients(registrationId).stream()
                .map(ingredient -> new IngredientPayload(ingredient.ingredientCode(), ingredient.concentration()))
                .toList();
        List<ReportPayload> reports = registrationRepository.findReports(registrationId).stream()
                .map(report -> new ReportPayload(report.efficacyCode(), report.conclusion().name(),
                        report.issuedDate(), report.lab()))
                .toList();
        return new RegistrationDetailResponse(
                row.id(), row.registrationNo(), row.productId(), row.version(), row.changeType().name(),
                row.previousId(), row.status().name(), row.packagingText(), claims, ingredients, reports,
                row.publicityStartDate(), row.publicityDeadline(), row.effectiveAt(), row.cancelledAt(),
                row.rejectReason(), row.requestId(), row.createdAt());
    }

    /**
     * 审查明细查询（可复算）。
     */
    @Transactional(readOnly = true)
    public List<ReviewRecordDto> getReviewRecords(long registrationId) {
        findRegistration(registrationId);
        return registrationRepository.findReviewRecords(registrationId).stream()
                .map(record -> new ReviewRecordDto(record.item(), record.result(), record.detail(), record.createdAt()))
                .toList();
    }

    /**
     * 产品变更链查询（全部版本，含被替代与已注销）。
     */
    @Transactional(readOnly = true)
    public List<RegistrationSummaryResponse> getChangeChain(long productId) {
        productRepository.findById(productId)
                .orElseThrow(() -> BusinessException.notFound("产品不存在：" + productId));
        return registrationRepository.findByProductId(productId).stream()
                .map(row -> new RegistrationSummaryResponse(row.id(), row.registrationNo(), row.version(),
                        row.changeType().name(), row.previousId(), row.status().name(),
                        row.effectiveAt(), row.cancelledAt(), row.createdAt()))
                .toList();
    }

    private RegistrationResponse doSubmitInitial(SubmitRegistrationRequest req, Instant now) {
        ProductRow product = productRepository.findById(req.productId())
                .orElseThrow(() -> BusinessException.notFound("产品不存在：" + req.productId()));
        if ("CANCELLED".equals(product.status())) {
            throw BusinessException.unprocessable("产品已注销，注销后新宣称审查一律拒绝");
        }
        if (registrationRepository.hasActiveRegistration(product.id())) {
            throw BusinessException.conflict("产品已存在公示中或生效的备案，请通过变更接口办理");
        }
        LocalDate submitDate = LocalDate.ofInstant(now, ZoneOffset.UTC);
        List<ReviewItem> items = formalReviewService.review(
                submitDate, req.packagingText(), req.claims(), req.ingredients(), req.reports());
        boolean passed = items.stream().allMatch(ReviewItem::passed);
        String registrationNo = registrationNo(product.id(), 1);
        RegistrationRow row = new RegistrationRow(
                0L, registrationNo, product.id(), 1, ChangeType.INITIAL, null,
                passed ? RegistrationStatus.PUBLICITY : RegistrationStatus.REJECTED,
                req.packagingText(),
                passed ? submitDate : null,
                passed ? publicityDeadline(submitDate) : null,
                null, null,
                passed ? null : rejectReason(items),
                req.requestId(), now);
        long id = registrationRepository.insert(row);
        persistDetails(id, req.claims(), req.ingredients(), req.reports(), items, now);
        return toResponse(findRegistration(id), items,
                passed ? "形式审查通过，进入公示" : "形式审查不通过");
    }

    private RegistrationResponse doSubmitChange(long sourceRegistrationId, SubmitChangeRequest req, Instant now) {
        RegistrationRow source = findRegistration(sourceRegistrationId);
        ProductRow product = productRepository.findById(source.productId()).orElseThrow();
        if ("CANCELLED".equals(product.status())) {
            throw BusinessException.unprocessable("产品已注销，注销后新宣称审查一律拒绝");
        }
        if (source.status() != RegistrationStatus.EFFECTIVE) {
            throw BusinessException.conflict("仅生效备案可发起变更，当前状态：" + source.status());
        }
        ChangeType changeType;
        try {
            changeType = ChangeType.valueOf(req.changeType());
        } catch (IllegalArgumentException e) {
            throw BusinessException.unprocessable("变更类型仅支持 SUBSTANTIAL/SIMPLE：" + req.changeType());
        }
        if (changeType == ChangeType.INITIAL) {
            throw BusinessException.unprocessable("首次备案请使用提交接口");
        }
        if (changeType == ChangeType.SIMPLE) {
            return doSimpleChange(source, req, now);
        }
        boolean inFlight = registrationRepository.findByProductId(source.productId()).stream()
                .anyMatch(row -> row.status() == RegistrationStatus.PUBLICITY
                        || row.status() == RegistrationStatus.OBJECTION_REVIEW);
        if (inFlight) {
            throw BusinessException.conflict("产品存在进行中的备案流程，暂不能发起实质变更");
        }
        LocalDate submitDate = LocalDate.ofInstant(now, ZoneOffset.UTC);
        List<ReviewItem> items = formalReviewService.review(
                submitDate, req.packagingText(), req.claims(), req.ingredients(), req.reports());
        boolean passed = items.stream().allMatch(ReviewItem::passed);
        int version = source.version() + 1;
        RegistrationRow row = new RegistrationRow(
                0L, registrationNo(source.productId(), version), source.productId(), version,
                ChangeType.SUBSTANTIAL, source.id(),
                passed ? RegistrationStatus.PUBLICITY : RegistrationStatus.REJECTED,
                req.packagingText(),
                passed ? submitDate : null,
                passed ? publicityDeadline(submitDate) : null,
                null, null,
                passed ? null : rejectReason(items),
                req.requestId(), now);
        long id = registrationRepository.insert(row);
        persistDetails(id, req.claims(), req.ingredients(), req.reports(), items, now);
        return toResponse(findRegistration(id), items,
                passed ? "实质变更重新审查通过，生成新备案编号并进入公示" : "实质变更形式审查不通过");
    }

    private RegistrationResponse doSimpleChange(RegistrationRow source, SubmitChangeRequest req, Instant now) {
        if (req.packagingText() == null || req.packagingText().isBlank()) {
            throw BusinessException.unprocessable("简易变更须提供新的包装文字");
        }
        int version = source.version() + 1;
        RegistrationRow row = new RegistrationRow(
                0L, source.registrationNo(), source.productId(), version,
                ChangeType.SIMPLE, source.id(), RegistrationStatus.EFFECTIVE,
                req.packagingText(), source.publicityStartDate(), source.publicityDeadline(),
                now, null, null, req.requestId(), now);
        long id = registrationRepository.insert(row);
        for (var claim : registrationRepository.findClaims(source.id())) {
            registrationRepository.insertClaim(id, claim.efficacyCode());
        }
        for (var ingredient : registrationRepository.findIngredients(source.id())) {
            registrationRepository.insertIngredient(id, ingredient.ingredientCode(), ingredient.concentration());
        }
        for (var report : registrationRepository.findReports(source.id())) {
            registrationRepository.insertReport(id, report.efficacyCode(), report.conclusion(),
                    report.issuedDate(), report.lab());
        }
        List<ReviewItem> items = List.of(new ReviewItem("简易变更", true,
                "包装文字类变更，引用原备案编号 " + source.registrationNo() + "，无需重新审查"));
        persistReviewRecords(id, items, now);
        registrationRepository.supersedeOtherEffective(source.productId(), id);
        return toResponse(findRegistration(id), items, "简易变更已生效，引用原备案编号");
    }

    private RegistrationRow findRegistration(long registrationId) {
        return registrationRepository.findById(registrationId)
                .orElseThrow(() -> BusinessException.notFound("备案不存在：" + registrationId));
    }

    private void persistDetails(long registrationId, List<String> claims, List<IngredientPayload> ingredients,
                                List<ReportPayload> reports, List<ReviewItem> items, Instant now) {
        for (String code : claims == null ? List.<String>of() : claims.stream().distinct().sorted().toList()) {
            registrationRepository.insertClaim(registrationId, code);
        }
        for (IngredientPayload ingredient : ingredients == null ? List.<IngredientPayload>of() : ingredients) {
            registrationRepository.insertIngredient(registrationId, ingredient.ingredientCode(), ingredient.concentration());
        }
        for (ReportPayload report : reports == null ? List.<ReportPayload>of() : reports) {
            ReportConclusion conclusion;
            try {
                conclusion = ReportConclusion.valueOf(report.conclusion());
            } catch (IllegalArgumentException e) {
                throw BusinessException.unprocessable("报告结论仅支持 SUPPORT/NOT_SUPPORT：" + report.conclusion());
            }
            registrationRepository.insertReport(registrationId, report.efficacyCode(), conclusion,
                    report.issuedDate(), report.lab());
        }
        persistReviewRecords(registrationId, items, now);
    }

    private void persistReviewRecords(long registrationId, List<ReviewItem> items, Instant now) {
        for (ReviewItem item : items) {
            registrationRepository.insertReviewRecord(registrationId, item.item(), item.passed(), item.detail(), now);
        }
    }

    private RegistrationResponse replay(IdempotencyRow record, String fingerprint) {
        if (!record.fingerprint().equals(fingerprint)) {
            throw BusinessException.conflict("幂等键已使用且业务参数不一致（同键异参）：" + record.requestId());
        }
        if (record.responseBody() == null) {
            throw BusinessException.conflict("幂等键对应的请求正在处理中：" + record.requestId());
        }
        try {
            return objectMapper.readValue(record.responseBody(), RegistrationResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("幂等响应反序列化失败", e);
        }
    }

    /**
     * 并发同键插入冲突后等待首次处理事务提交并读取其记录（短暂重试）。
     */
    private IdempotencyRow awaitRecord(String requestId) {
        for (int attempt = 0; attempt < 40; attempt++) {
            var record = idempotencyRepository.find(requestId);
            if (record.isPresent() && record.get().responseBody() != null) {
                return record.get();
            }
            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return idempotencyRepository.find(requestId)
                .orElseThrow(() -> BusinessException.conflict("幂等键对应的请求处理超时：" + requestId));
    }

    private RegistrationResponse toResponse(RegistrationRow row, List<ReviewItem> items, String message) {
        List<ReviewItemDto> reviewItems = items.stream()
                .map(item -> new ReviewItemDto(item.item(), item.passed() ? "PASS" : "FAIL", item.detail()))
                .toList();
        boolean reviewPassed = items.stream().allMatch(ReviewItem::passed);
        return new RegistrationResponse(
                row.id(), row.registrationNo(), row.productId(), row.version(), row.changeType().name(),
                row.status().name(), reviewPassed, reviewItems,
                row.publicityStartDate(), row.publicityDeadline(), row.effectiveAt(), row.cancelledAt(), message);
    }

    private String toJson(RegistrationResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    /**
     * 公示期满时刻：公示起始日第10日24:00（UTC），即起始日+10日 00:00。
     */
    static Instant publicityDeadline(LocalDate publicityStartDate) {
        return publicityStartDate.plusDays(PUBLICITY_DAYS).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static String registrationNo(long productId, int version) {
        return "REG-%06d-%03d".formatted(productId, version);
    }

    private static String rejectReason(List<ReviewItem> items) {
        return items.stream()
                .filter(item -> !item.passed())
                .map(ReviewItem::detail)
                .collect(Collectors.joining("；"));
    }

    private static String fingerprint(String endpoint, String requestId, String targetId, String packagingText,
                                      String changeType, List<String> claims, List<IngredientPayload> ingredients,
                                      List<ReportPayload> reports) {
        String claimsPart = claims == null ? "" : claims.stream().sorted().collect(Collectors.joining(","));
        String ingredientsPart = ingredients == null ? "" : ingredients.stream()
                .map(item -> item.ingredientCode() + "=" + item.concentration().stripTrailingZeros().toPlainString())
                .sorted()
                .collect(Collectors.joining(","));
        String reportsPart = reports == null ? "" : reports.stream()
                .map(item -> item.efficacyCode() + "|" + item.conclusion() + "|" + item.issuedDate() + "|" + item.lab())
                .sorted()
                .collect(Collectors.joining(","));
        String canonical = String.join("#", endpoint, requestId, targetId,
                packagingText == null ? "" : packagingText,
                changeType == null ? "" : changeType,
                claimsPart, ingredientsPart, reportsPart);
        return sha256(canonical);
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
