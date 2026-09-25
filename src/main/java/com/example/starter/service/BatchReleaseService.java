package com.example.starter.service;

import com.example.starter.domain.Catalog;
import com.example.starter.domain.ProcessStatus;
import com.example.starter.model.DocumentItemRow;
import com.example.starter.model.InspectionItemRow;
import com.example.starter.model.PublicityRow;
import com.example.starter.model.ReviewProcessRow;
import com.example.starter.repository.BatchReleaseRepository;
import com.example.starter.repository.ChecklistRepository;
import com.example.starter.repository.IdempotencyRepository;
import com.example.starter.repository.IdempotencyRepository.IdempotencyRow;
import com.example.starter.repository.PublicityRepository;
import com.example.starter.web.ApiException;
import com.example.starter.web.dto.ApplicationRequest;
import com.example.starter.web.dto.CorrectionRequest;
import com.example.starter.web.dto.ObjectionRequest;
import com.example.starter.web.dto.SimpleRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 批签发业务服务：申报→资料审查→检验核对→综合评定→公示→签发→归档。
 * 申报、补正与签发均以 requestId 幂等；指纹覆盖 requestId 与全部业务字段。
 */
@Service
public class BatchReleaseService {

    private final BatchReleaseRepository batchRepository;
    private final ChecklistRepository checklistRepository;
    private final PublicityRepository publicityRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public BatchReleaseService(BatchReleaseRepository batchRepository,
                               ChecklistRepository checklistRepository,
                               PublicityRepository publicityRepository,
                               IdempotencyRepository idempotencyRepository,
                               Clock clock,
                               ObjectMapper objectMapper) {
        this.batchRepository = batchRepository;
        this.checklistRepository = checklistRepository;
        this.publicityRepository = publicityRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    /**
     * 申报：按合成目录核对资料，缺项一次性告知并给5个工作日补正时限。
     */
    @Transactional
    public ServiceResponse apply(ApplicationRequest request) {
        String fingerprint = fingerprint(
                request.requestId(), "APPLY", request.batchNo(), request.enterprise(), request.productName(),
                canonicalDocuments(request.documents()), canonicalInspections(request.inspections()));
        return idempotent(request.requestId(), fingerprint, () -> doApply(request));
    }

    /**
     * 补正：恰第5个工作日提交受理，逾期按撤回处理；归档后迟到资料只记事件。
     */
    @Transactional
    public ServiceResponse correct(String batchNo, CorrectionRequest request) {
        String fingerprint = fingerprint(request.requestId(), "CORRECT", batchNo, canonicalDocuments(request.documents()));
        return idempotent(request.requestId(), fingerprint, () -> doCorrect(batchNo, request));
    }

    /**
     * 综合评定：关键项目（效力、无菌、异常毒性）须全项合格，恰等限值判合格；
     * 同企业同品种恰连续3批不合格触发暂停受理并转核查。
     */
    @Transactional
    public ServiceResponse evaluate(String batchNo, SimpleRequest request) {
        String fingerprint = fingerprint(request.requestId(), "EVALUATE", batchNo);
        return idempotent(request.requestId(), fingerprint, () -> doEvaluate(batchNo));
    }

    /**
     * 公示异议：公示期内受理并转复核，与签发并发形成唯一终态。
     */
    @Transactional
    public ServiceResponse object(String batchNo, ObjectionRequest request) {
        String fingerprint = fingerprint(request.requestId(), "OBJECT", batchNo, request.reason());
        return idempotent(request.requestId(), fingerprint, () -> doObject(batchNo, request));
    }

    /**
     * 签发：公示满7个自然日（UTC日界）后期满生效，恰第7日可签发。
     */
    @Transactional
    public ServiceResponse issue(String batchNo, SimpleRequest request) {
        String fingerprint = fingerprint(request.requestId(), "ISSUE", batchNo);
        return idempotent(request.requestId(), fingerprint, () -> doIssue(batchNo));
    }

    /**
     * 签发归档：终态，重复归档返回幂等成功。
     */
    @Transactional
    public ServiceResponse archive(String batchNo, SimpleRequest request) {
        String fingerprint = fingerprint(request.requestId(), "ARCHIVE", batchNo);
        return idempotent(request.requestId(), fingerprint, () -> doArchive(batchNo));
    }

    /**
     * 资料核对表复算：按目录重算缺项。
     */
    @Transactional(readOnly = true)
    public ServiceResponse checklist(String batchNo) {
        ReviewProcessRow process = findProcessOr404(batchNo);
        List<DocumentItemRow> items = checklistRepository.findDocumentItems(process.processId());
        List<Map<String, Object>> itemViews = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (DocumentItemRow item : items) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("itemCode", item.itemCode());
            view.put("itemName", item.itemName());
            view.put("required", item.required());
            view.put("submitted", item.submitted());
            view.put("submittedAt", item.submittedAt());
            itemViews.add(view);
            if (item.required() && !item.submitted()) {
                missing.add(item.itemCode());
            }
        }
        Map<String, Object> body = baseBody(process);
        body.put("items", itemViews);
        body.put("missing", missing);
        body.put("correctionDeadline", process.correctionDeadline());
        return new ServiceResponse(200, json(body));
    }

    /**
     * 检验链复算：按限值与测定值重算合格判定。
     */
    @Transactional(readOnly = true)
    public ServiceResponse inspection(String batchNo) {
        ReviewProcessRow process = findProcessOr404(batchNo);
        List<InspectionItemRow> items = checklistRepository.findInspectionItems(process.processId());
        List<Map<String, Object>> itemViews = new ArrayList<>();
        boolean allCriticalQualified = true;
        for (InspectionItemRow item : items) {
            boolean qualified = isQualified(item.measuredValue(), item.limitValue());
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("itemCode", item.itemCode());
            view.put("itemName", item.itemName());
            view.put("critical", item.critical());
            view.put("limitValue", item.limitValue());
            view.put("measuredValue", item.measuredValue());
            view.put("qualified", qualified);
            itemViews.add(view);
            if (item.critical() && !qualified) {
                allCriticalQualified = false;
            }
        }
        Map<String, Object> body = baseBody(process);
        body.put("items", itemViews);
        body.put("allCriticalQualified", allCriticalQualified);
        return new ServiceResponse(200, json(body));
    }

    /**
     * 公示记录复算：按UTC日界重算是否期满。
     */
    @Transactional(readOnly = true)
    public ServiceResponse publicity(String batchNo) {
        ReviewProcessRow process = findProcessOr404(batchNo);
        PublicityRow row = publicityRepository.findByProcessId(process.processId())
                .orElseThrow(() -> ApiException.notFound("该批号尚未进入公示: " + batchNo));
        Map<String, Object> body = baseBody(process);
        body.put("publicityStatus", row.status());
        body.put("startAt", row.startAt());
        body.put("endAt", row.endAt());
        body.put("expired", !clock.instant().isBefore(row.endAt()));
        body.put("objectionRequestId", row.objectionRequestId());
        body.put("objectionReason", row.objectionReason());
        return new ServiceResponse(200, json(body));
    }

    private ServiceResponse doApply(ApplicationRequest request) {
        Instant now = clock.instant();
        if (batchRepository.suspensionExists(request.enterprise(), request.productName())) {
            throw ApiException.unprocessable("同企业同品种连续3批不合格，已暂停受理并转核查");
        }
        Map<String, Catalog.CatalogItem> catalogByCode = Catalog.DOCUMENT_CATALOG.stream()
                .collect(Collectors.toMap(Catalog.CatalogItem::code, item -> item));
        Set<String> submittedCodes = new HashSet<>();
        for (ApplicationRequest.DocumentItem doc : request.documents()) {
            if (!catalogByCode.containsKey(doc.itemCode())) {
                throw ApiException.unprocessable("未知资料目录项: " + doc.itemCode());
            }
            submittedCodes.add(doc.itemCode());
        }
        Map<String, Catalog.CatalogItem> criticalByCode = Catalog.CRITICAL_INSPECTION_ITEMS.stream()
                .collect(Collectors.toMap(Catalog.CatalogItem::code, item -> item));
        Set<String> inspectionCodes = new HashSet<>();
        for (ApplicationRequest.InspectionItem item : request.inspections()) {
            if (!inspectionCodes.add(item.itemCode())) {
                throw ApiException.unprocessable("检验项目重复: " + item.itemCode());
            }
        }
        List<String> missingCritical = criticalByCode.keySet().stream()
                .filter(code -> !inspectionCodes.contains(code)).sorted().toList();
        if (!missingCritical.isEmpty()) {
            throw ApiException.unprocessable("缺少关键检验项目: " + String.join(",", missingCritical));
        }

        long batchId;
        try {
            batchId = batchRepository.insertBatch(request.batchNo(), request.enterprise(), request.productName(), now);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("同一批号已存在审查流程，申报唯一受理");
        }

        List<Map<String, String>> missing = Catalog.DOCUMENT_CATALOG.stream()
                .filter(item -> item.required() && !submittedCodes.contains(item.code()))
                .map(item -> Map.of("itemCode", item.code(), "itemName", item.name()))
                .toList();
        ProcessStatus status = missing.isEmpty() ? ProcessStatus.UNDER_REVIEW : ProcessStatus.PENDING_CORRECTION;
        LocalDate deadline = missing.isEmpty() ? null : correctionDeadline(LocalDate.now(clock));
        long processId = batchRepository.insertProcess(batchId, request.requestId(),
                fingerprint(request.requestId(), "APPLY", request.batchNo(), request.enterprise(), request.productName(),
                        canonicalDocuments(request.documents()), canonicalInspections(request.inspections())),
                status, missing.isEmpty() ? null : json(Map.of("missing", missing)), deadline, now);

        for (Catalog.CatalogItem item : Catalog.DOCUMENT_CATALOG) {
            boolean submitted = submittedCodes.contains(item.code());
            checklistRepository.insertDocumentItems(processId, item.code(), item.name(), item.required(),
                    submitted, submitted ? now : null);
        }
        for (ApplicationRequest.InspectionItem item : request.inspections()) {
            Catalog.CatalogItem critical = criticalByCode.get(item.itemCode());
            String name = critical != null ? critical.name()
                    : (item.itemName() == null || item.itemName().isBlank() ? item.itemCode() : item.itemName());
            checklistRepository.insertInspectionItem(processId, item.itemCode(), name, critical != null,
                    item.limitValue(), item.measuredValue(), isQualified(item.measuredValue(), item.limitValue()));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("processId", processId);
        body.put("batchNo", request.batchNo());
        body.put("status", status.name());
        body.put("missing", missing);
        body.put("correctionDeadline", deadline);
        return new ServiceResponse(201, json(body));
    }

    private ServiceResponse doCorrect(String batchNo, CorrectionRequest request) {
        ReviewProcessRow process = findProcessOr404(batchNo);
        Instant now = clock.instant();
        if (process.status() == ProcessStatus.ARCHIVED) {
            batchRepository.insertLateEvent(process.batchId(), request.requestId(),
                    "归档后迟到资料: " + canonicalDocuments(request.documents()), now);
            Map<String, Object> body = baseBody(process);
            body.put("lateEventRecorded", true);
            return new ServiceResponse(200, json(body));
        }
        if (process.status() != ProcessStatus.PENDING_CORRECTION) {
            throw ApiException.conflict("当前状态不接受补正: " + process.status());
        }
        LocalDate today = LocalDate.now(clock);
        if (today.isAfter(process.correctionDeadline())) {
            // 逾期按撤回处理：撤回是业务结果，随事务提交并作为幂等结果返回409
            batchRepository.transitionStatus(process.processId(),
                    ProcessStatus.PENDING_CORRECTION, ProcessStatus.WITHDRAWN, now);
            Map<String, Object> body = baseBody(process);
            body.put("status", ProcessStatus.WITHDRAWN.name());
            body.put("error", "补正超过5个工作日时限，已按撤回处理");
            return new ServiceResponse(409, json(body));
        }
        List<DocumentItemRow> items = checklistRepository.findDocumentItems(process.processId());
        Set<String> catalogCodes = items.stream().map(DocumentItemRow::itemCode).collect(Collectors.toSet());
        Set<String> submitting = new HashSet<>();
        for (ApplicationRequest.DocumentItem doc : request.documents()) {
            if (!catalogCodes.contains(doc.itemCode())) {
                throw ApiException.unprocessable("未知资料目录项: " + doc.itemCode());
            }
            submitting.add(doc.itemCode());
        }
        List<String> stillMissing = items.stream()
                .filter(item -> item.required() && !item.submitted() && !submitting.contains(item.itemCode()))
                .map(DocumentItemRow::itemCode).sorted().toList();
        if (!stillMissing.isEmpty()) {
            throw ApiException.unprocessable("补正未覆盖全部缺项: " + String.join(",", stillMissing));
        }
        for (String code : submitting) {
            checklistRepository.markDocumentSubmitted(process.processId(), code, now);
        }
        if (!batchRepository.completeCorrection(process.processId(),
                ProcessStatus.PENDING_CORRECTION, ProcessStatus.UNDER_REVIEW, now)) {
            throw ApiException.conflict("补正状态并发冲突");
        }
        Map<String, Object> body = baseBody(process);
        body.put("status", ProcessStatus.UNDER_REVIEW.name());
        return new ServiceResponse(200, json(body));
    }

    private ServiceResponse doEvaluate(String batchNo) {
        ReviewProcessRow process = findProcessOr404(batchNo);
        if (process.status() != ProcessStatus.UNDER_REVIEW) {
            throw ApiException.conflict("当前状态不可综合评定: " + process.status());
        }
        Instant now = clock.instant();
        List<InspectionItemRow> items = checklistRepository.findInspectionItems(process.processId());
        List<String> failedCritical = items.stream()
                .filter(item -> item.critical() && !isQualified(item.measuredValue(), item.limitValue()))
                .map(item -> item.itemCode() + "(" + item.itemName() + ")").sorted().toList();
        Map<String, Object> body = baseBody(process);
        if (!failedCritical.isEmpty()) {
            String reason = "关键检验项目未全项合格: " + String.join(",", failedCritical);
            if (!batchRepository.completeEvaluation(process.processId(), "FAIL", reason,
                    ProcessStatus.UNDER_REVIEW, ProcessStatus.EVALUATED_FAIL, now)) {
                throw ApiException.conflict("综合评定状态并发冲突");
            }
            boolean suspended = checkAndSuspend(process, now);
            body.put("status", ProcessStatus.EVALUATED_FAIL.name());
            body.put("evaluationResult", "FAIL");
            body.put("failReason", reason);
            body.put("suspended", suspended);
            return new ServiceResponse(200, json(body));
        }
        if (!batchRepository.completeEvaluation(process.processId(), "PASS", null,
                ProcessStatus.UNDER_REVIEW, ProcessStatus.IN_PUBLICITY, now)) {
            throw ApiException.conflict("综合评定状态并发冲突");
        }
        Instant startAt = now;
        Instant endAt = now.plus(java.time.Duration.ofDays(Catalog.PUBLICITY_DAYS));
        publicityRepository.insert(process.processId(), startAt, endAt, now);
        body.put("status", ProcessStatus.IN_PUBLICITY.name());
        body.put("evaluationResult", "PASS");
        body.put("publicityStartAt", startAt);
        body.put("publicityEndAt", endAt);
        return new ServiceResponse(200, json(body));
    }

    private ServiceResponse doObject(String batchNo, ObjectionRequest request) {
        ReviewProcessRow process = findProcessOr404(batchNo);
        if (process.status() != ProcessStatus.IN_PUBLICITY) {
            throw ApiException.conflict("当前状态不受理异议: " + process.status());
        }
        PublicityRow row = publicityRepository.findByProcessId(process.processId())
                .orElseThrow(() -> ApiException.notFound("该批号尚未进入公示: " + batchNo));
        Instant now = clock.instant();
        if (!now.isBefore(row.endAt())) {
            throw ApiException.conflict("公示期已满，异议不予受理");
        }
        if (!batchRepository.transitionStatus(process.processId(),
                ProcessStatus.IN_PUBLICITY, ProcessStatus.OBJECTION_REVIEW, now)) {
            throw ApiException.conflict("异议受理与签发并发，流程已进入唯一终态");
        }
        if (!publicityRepository.transitionStatus(process.processId(), "IN_PROGRESS", "OBJECTION",
                request.requestId(), request.reason())) {
            throw ApiException.conflict("公示状态并发冲突");
        }
        Map<String, Object> body = baseBody(process);
        body.put("status", ProcessStatus.OBJECTION_REVIEW.name());
        return new ServiceResponse(200, json(body));
    }

    private ServiceResponse doIssue(String batchNo) {
        ReviewProcessRow process = findProcessOr404(batchNo);
        if (process.status() != ProcessStatus.IN_PUBLICITY) {
            throw ApiException.conflict("当前状态不可签发: " + process.status());
        }
        PublicityRow row = publicityRepository.findByProcessId(process.processId())
                .orElseThrow(() -> ApiException.notFound("该批号尚未进入公示: " + batchNo));
        Instant now = clock.instant();
        if (now.isBefore(row.endAt())) {
            throw ApiException.conflict("公示期未满7个自然日，不得签发");
        }
        if (!batchRepository.transitionStatus(process.processId(),
                ProcessStatus.IN_PUBLICITY, ProcessStatus.ISSUED, now)) {
            throw ApiException.conflict("签发与异议受理并发，流程已进入唯一终态");
        }
        if (!publicityRepository.transitionStatus(process.processId(), "IN_PROGRESS", "EFFECTIVE", null, null)) {
            throw ApiException.conflict("公示状态并发冲突");
        }
        Map<String, Object> body = baseBody(process);
        body.put("status", ProcessStatus.ISSUED.name());
        return new ServiceResponse(200, json(body));
    }

    private ServiceResponse doArchive(String batchNo) {
        ReviewProcessRow process = findProcessOr404(batchNo);
        Instant now = clock.instant();
        if (process.status() == ProcessStatus.ARCHIVED) {
            Map<String, Object> body = baseBody(process);
            body.put("idempotent", true);
            return new ServiceResponse(200, json(body));
        }
        if (process.status() != ProcessStatus.ISSUED) {
            throw ApiException.conflict("仅已签发批次可归档: " + process.status());
        }
        if (!batchRepository.transitionStatus(process.processId(), ProcessStatus.ISSUED, ProcessStatus.ARCHIVED, now)) {
            ReviewProcessRow again = findProcessOr404(batchNo);
            if (again.status() == ProcessStatus.ARCHIVED) {
                Map<String, Object> body = baseBody(again);
                body.put("idempotent", true);
                return new ServiceResponse(200, json(body));
            }
            throw ApiException.conflict("归档状态并发冲突");
        }
        Map<String, Object> body = baseBody(process);
        body.put("status", ProcessStatus.ARCHIVED.name());
        return new ServiceResponse(200, json(body));
    }

    /**
     * 同企业同品种恰连续3批不合格时写入暂停受理记录（转核查）。
     *
     * @return true 本次触发暂停
     */
    private boolean checkAndSuspend(ReviewProcessRow process, Instant now) {
        List<String> recent = batchRepository.recentEvaluationResults(
                process.enterprise(), process.productName(), Catalog.CONSECUTIVE_FAIL_THRESHOLD);
        boolean trigger = recent.size() >= Catalog.CONSECUTIVE_FAIL_THRESHOLD
                && recent.stream().limit(Catalog.CONSECUTIVE_FAIL_THRESHOLD).allMatch("FAIL"::equals);
        if (!trigger || batchRepository.suspensionExists(process.enterprise(), process.productName())) {
            return false;
        }
        batchRepository.insertSuspension(process.enterprise(), process.productName(),
                "同企业同品种连续3批不合格，暂停受理并转核查", now);
        return true;
    }

    /**
     * 幂等骨架：同键同参返回首次结果，同键异参返回409且不改变审查状态。
     */
    private ServiceResponse idempotent(String requestId, String fingerprint, Supplier<ServiceResponse> action) {
        var existing = idempotencyRepository.find(requestId);
        if (existing.isPresent()) {
            return replay(existing.get(), fingerprint);
        }
        ServiceResponse response = action.get();
        if (!idempotencyRepository.tryInsert(requestId, fingerprint, response.httpStatus(), response.jsonBody(),
                clock.instant())) {
            IdempotencyRow row = idempotencyRepository.find(requestId)
                    .orElseThrow(() -> ApiException.conflict("幂等记录并发冲突"));
            return replay(row, fingerprint);
        }
        return response;
    }

    private ServiceResponse replay(IdempotencyRow row, String fingerprint) {
        if (!row.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("同键异参：requestId 已登记且业务字段不一致");
        }
        return new ServiceResponse(row.httpStatus(), row.responseBody());
    }

    private ReviewProcessRow findProcessOr404(String batchNo) {
        return batchRepository.findProcessByBatchNo(batchNo)
                .orElseThrow(() -> ApiException.notFound("批号不存在: " + batchNo));
    }

    /**
     * 合格判定：测定值小于等于限值判合格（恰等限值判合格）。
     */
    private boolean isQualified(BigDecimal measured, BigDecimal limit) {
        return measured.compareTo(limit) <= 0;
    }

    /**
     * 补正截止日：自申报日（UTC）起第5个工作日，恰第5日提交受理。
     */
    private LocalDate correctionDeadline(LocalDate from) {
        LocalDate day = from;
        int count = 0;
        while (count < Catalog.CORRECTION_WORKING_DAYS) {
            day = day.plusDays(1);
            DayOfWeek week = day.getDayOfWeek();
            if (week != DayOfWeek.SATURDAY && week != DayOfWeek.SUNDAY) {
                count++;
            }
        }
        return day;
    }

    private Map<String, Object> baseBody(ReviewProcessRow process) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("processId", process.processId());
        body.put("batchNo", process.batchNo());
        body.put("status", process.status().name());
        return body;
    }

    private String canonicalDocuments(List<ApplicationRequest.DocumentItem> documents) {
        return documents.stream().map(ApplicationRequest.DocumentItem::itemCode)
                .sorted().collect(Collectors.joining(","));
    }

    private String canonicalInspections(List<ApplicationRequest.InspectionItem> inspections) {
        return inspections.stream()
                .sorted(Comparator.comparing(ApplicationRequest.InspectionItem::itemCode))
                .map(item -> item.itemCode() + "|" + (item.itemName() == null ? "" : item.itemName())
                        + "|" + item.limitValue().stripTrailingZeros().toPlainString()
                        + "|" + item.measuredValue().stripTrailingZeros().toPlainString())
                .collect(Collectors.joining(","));
    }

    /**
     * 请求指纹：requestId 与全部业务字段按 SOH（U+0001）分隔后计算 SHA-256。
     */
    private String fingerprint(String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(String.join("", parts).getBytes(StandardCharsets.UTF_8));
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

    private String json(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }
}
