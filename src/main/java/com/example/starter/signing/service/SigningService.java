package com.example.starter.signing.service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.signing.domain.SignEvent;
import com.example.starter.signing.domain.SignatureRecord;
import com.example.starter.signing.domain.SigningPackage;
import com.example.starter.signing.domain.SigningStep;
import com.example.starter.signing.model.EventType;
import com.example.starter.signing.model.PackageStatus;
import com.example.starter.signing.model.SignatureStatus;
import com.example.starter.signing.repo.SigningRepository;
import com.example.starter.signing.repo.SigningRepository.IdempotentRequest;
import com.example.starter.signing.web.ActionResponse;
import com.example.starter.signing.web.CancelRequest;
import com.example.starter.signing.web.CreatePackageRequest;
import com.example.starter.signing.web.EvidenceResponse;
import com.example.starter.signing.web.PackageStatusResponse;
import com.example.starter.signing.web.RevisionRequest;
import com.example.starter.signing.web.SignatureSubmitRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 多方签署流程核心服务。
 *
 * <p>所有写操作在单个事务内先对签署包行加悲观行锁（{@code SELECT ... FOR UPDATE}），
 * 因此签名、修订、取消、到期互斥，终态一致；迟到签名在终态下被拒绝且不会复活流程。</p>
 */
@Service
public class SigningService {

    private final SigningRepository repository;
    private final SyntheticSignatureVerifier verifier;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public SigningService(SigningRepository repository,
                          SyntheticSignatureVerifier verifier,
                          ObjectMapper objectMapper,
                          Clock clock) {
        this.repository = repository;
        this.verifier = verifier;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 建包：写入包、有序步骤与 PACKAGE_CREATED 创世事件。
     */
    @Transactional
    public ActionResponse createPackage(CreatePackageRequest request) {
        validateSteps(request);
        Instant now = now();
        String packageId = UUID.randomUUID().toString();
        String documentHash = Hashes.normalize(request.documentHash());

        SigningPackage pkg = new SigningPackage();
        pkg.setId(packageId);
        pkg.setCurrentRevision(1);
        pkg.setDocumentHash(documentHash);
        pkg.setExpiresAt(request.expiresAt());
        pkg.setStatus(PackageStatus.PENDING);
        pkg.setCreatedAt(now);
        repository.insertPackage(pkg);

        for (CreatePackageRequest.StepSpec step : request.steps()) {
            for (String signer : step.signers()) {
                repository.insertStep(new SigningStep(packageId, step.stepOrder(), signer.trim()));
            }
        }

        List<Map<String, Object>> stepDetails = new ArrayList<>();
        for (CreatePackageRequest.StepSpec step : request.steps()) {
            stepDetails.add(orderedMap(
                    "signers", List.copyOf(step.signers().stream().map(String::trim).sorted().toList()),
                    "stepOrder", step.stepOrder()));
        }
        appendEvent(packageId, 1, EventType.PACKAGE_CREATED, orderedMap(
                "documentHash", documentHash,
                "expiresAt", request.expiresAt(),
                "revision", 1,
                "steps", stepDetails), now);

        return new ActionResponse(packageId, null, true, 1, false, null, now);
    }

    /**
     * 提交签名：携带幂等键，包级行锁串行化全部提交。
     *
     * @return 包含 HTTP 状态与响应体的结果；失败结果同样按 requestId 记录用于重放
     */
    @Transactional
    public SubmitOutcome submitSignature(String packageId, SignatureSubmitRequest request) {
        Instant now = now();
        SigningPackage pkg = repository.findPackageForUpdate(packageId);
        if (pkg == null) {
            throw new BusinessRuleException(HttpStatus.NOT_FOUND, "PACKAGE_NOT_FOUND",
                    "签署包不存在: " + packageId);
        }

        String fingerprint = fingerprint(packageId, request);
        Optional<IdempotentRequest> existing = repository.findRequest(request.requestId());
        if (existing.isPresent()) {
            IdempotentRequest row = existing.get();
            if (!row.fingerprint().equals(fingerprint)) {
                // 同键异参：409 且不新增任何事件
                throw new BusinessRuleException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
                        "requestId 已用于不同参数的请求: " + request.requestId());
            }
            return replayOutcome(row);
        }

        SubmitOutcome outcome = doSubmit(pkg, request, now);
        try {
            repository.insertRequest(new IdempotentRequest(
                    request.requestId(), packageId, fingerprint,
                    outcome.status().value(), writeJson(outcome.body()), now));
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 跨包或极端并发下同 requestId 唯一键冲突：回滚事务并按幂等冲突拒绝
            throw new BusinessRuleException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
                    "requestId 已用于不同参数的请求: " + request.requestId());
        }
        return outcome;
    }

    /**
     * 修订文档：单事务内产生新版本、失效旧签名（保留历史）并重置进度到第一步。
     */
    @Transactional
    public ActionResponse revise(String packageId, RevisionRequest request) {
        Instant now = now();
        SigningPackage pkg = lockPackage(packageId);
        rejectIfTerminalOrExpired(pkg, now);

        String newHash = Hashes.normalize(request.newDocumentHash());
        if (newHash.equals(pkg.getDocumentHash())) {
            throw new BusinessRuleException(HttpStatus.CONFLICT, "REVISION_UNCHANGED",
                    "新文档摘要与当前修订相同，无需修订");
        }

        int oldRevision = pkg.getCurrentRevision();
        int updated = repository.compareAndUpdateRevision(packageId, oldRevision, newHash);
        if (updated == 0) {
            throw new BusinessRuleException(HttpStatus.CONFLICT, "REVISION_CONFLICT",
                    "修订并发冲突，请重试");
        }
        int invalidated = repository.invalidateSignatures(packageId, oldRevision, oldRevision + 1, now);
        appendEvent(packageId, oldRevision + 1, EventType.REVISION_CREATED, orderedMap(
                "invalidatedCount", invalidated,
                "newDocumentHash", newHash,
                "newRevision", oldRevision + 1,
                "previousDocumentHash", pkg.getDocumentHash(),
                "previousRevision", oldRevision), now);

        return new ActionResponse(packageId, null, true, oldRevision + 1, false, null, now);
    }

    /**
     * 取消流程：终态，迟到签名不再生效。
     */
    @Transactional
    public ActionResponse cancel(String packageId, CancelRequest request) {
        Instant now = now();
        SigningPackage pkg = lockPackage(packageId);
        rejectIfTerminalOrExpired(pkg, now);

        int revision = pkg.getCurrentRevision();
        int updated = repository.compareAndCancel(packageId, revision);
        if (updated == 0) {
            throw new BusinessRuleException(HttpStatus.CONFLICT, "CANCEL_CONFLICT",
                    "取消并发冲突，请刷新状态");
        }
        appendEvent(packageId, revision, EventType.CANCELLED, orderedMap(
                "reason", request == null || request.reason() == null ? "" : request.reason()), now);

        return new ActionResponse(packageId, null, true, revision, false, null, now);
    }

    /**
     * 查询流程状态；若已到 UTC 到期时间则在事务内惰性转入 EXPIRED 终态。
     */
    @Transactional
    public PackageStatusResponse getStatus(String packageId) {
        Instant now = now();
        SigningPackage pkg = repository.findPackageForUpdate(packageId);
        if (pkg == null) {
            throw new BusinessRuleException(HttpStatus.NOT_FOUND, "PACKAGE_NOT_FOUND",
                    "签署包不存在: " + packageId);
        }
        expireIfDue(pkg, now);
        return buildStatus(pkg);
    }

    /**
     * 查询冻结证据包；未完成冻结时返回 409。
     */
    public EvidenceResponse getEvidence(String packageId) {
        SigningRepository.EvidenceRow row = repository.findEvidence(packageId)
                .orElseThrow(() -> new BusinessRuleException(HttpStatus.CONFLICT,
                        "EVIDENCE_NOT_FROZEN", "证据包尚未冻结：流程未最终完成"));
        try {
            // 校验内容与摘要一致后再返回，确保快照未被篡改
            String recomputed = Hashes.sha256(row.content());
            if (!recomputed.equals(row.digest())) {
                throw new BusinessRuleException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "EVIDENCE_CORRUPTED", "证据包内容与摘要不一致");
            }
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(row.content());
            EvidenceResponse body = objectMapper.treeToValue(node, EvidenceResponse.class);
            return new EvidenceResponse(
                    body.packageId(), row.digest(), row.createdAt(),
                    body.documentHash(), body.revision(), body.steps(),
                    body.validSignatures(), body.invalidatedHistory(), body.events());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("证据包内容解析失败", e);
        }
    }

    // ---------------- 签名提交主流程（包行锁已持有） ----------------

    private SubmitOutcome doSubmit(SigningPackage pkg, SignatureSubmitRequest request,
                                   Instant now) {
        String packageId = pkg.getId();

        // 任何终态（含已落库的 EXPIRED）都拒绝签名，迟到签名不得复活流程
        if (pkg.getStatus() == PackageStatus.CANCELLED) {
            return rejected(HttpStatus.CONFLICT,
                    "PACKAGE_CANCELLED", "流程已取消，签名被拒绝");
        }
        if (pkg.getStatus() == PackageStatus.COMPLETED) {
            return rejected(HttpStatus.CONFLICT,
                    "FLOW_COMPLETED", "流程已完成冻结，迟到签名被拒绝");
        }
        if (pkg.getStatus() == PackageStatus.EXPIRED) {
            return rejected(HttpStatus.GONE,
                    "PACKAGE_EXPIRED", "流程已过期，签名被拒绝");
        }
        // PENDING 状态下惰性到期转换
        if (expireIfDue(pkg, now)) {
            return rejected(HttpStatus.GONE,
                    "PACKAGE_EXPIRED", "流程已过期，签名被拒绝");
        }

        int revision = pkg.getCurrentRevision();
        if (request.revision() != revision || request.expectedVersion() != revision) {
            return rejected(HttpStatus.CONFLICT,
                    "STALE_VERSION", "修订号或期望版本与当前版本不一致: current=" + revision);
        }

        String documentHash = Hashes.normalize(request.documentHash());
        if (!documentHash.equals(pkg.getDocumentHash())) {
            return rejected(HttpStatus.UNPROCESSABLE_ENTITY,
                    "DIGEST_MISMATCH", "文档摘要与当前修订不一致");
        }

        List<SigningStep> steps = repository.findSteps(packageId);
        SigningStep ownStep = steps.stream()
                .filter(s -> s.getSigner().equals(request.signer()))
                .findFirst()
                .orElse(null);
        if (ownStep == null) {
            return rejected(HttpStatus.UNPROCESSABLE_ENTITY,
                    "SIGNER_NOT_FOUND", "签署人不在签署步骤中: " + request.signer());
        }

        List<SignatureRecord> currentSignatures =
                repository.findSignaturesByRevision(packageId, revision);
        if (!isStepOpen(ownStep.getStepOrder(), steps, currentSignatures)) {
            return rejected(HttpStatus.CONFLICT,
                    "STEP_NOT_OPEN", "步骤尚未开放: " + ownStep.getStepOrder());
        }
        boolean duplicated = currentSignatures.stream()
                .anyMatch(s -> s.getStatus() == SignatureStatus.VALID
                        && s.getStepOrder() == ownStep.getStepOrder()
                        && s.getSigner().equals(request.signer()));
        if (duplicated) {
            return rejected(HttpStatus.CONFLICT,
                    "ALREADY_SIGNED", "签署人在当前修订已完成签名: " + request.signer());
        }

        if (!verifier.verify(request.signer(), revision, documentHash, request.signature())) {
            return rejected(HttpStatus.UNPROCESSABLE_ENTITY,
                    "INVALID_SIGNATURE", "合成签名验证失败");
        }

        SignatureRecord record = new SignatureRecord();
        record.setId(UUID.randomUUID().toString());
        record.setPackageId(packageId);
        record.setRevision(revision);
        record.setStepOrder(ownStep.getStepOrder());
        record.setSigner(request.signer());
        record.setDocumentHash(documentHash);
        record.setSignatureValue(request.signature().trim().toLowerCase());
        record.setStatus(SignatureStatus.VALID);
        record.setRequestId(request.requestId());
        record.setCreatedAt(now);
        repository.insertSignature(record);

        appendEvent(packageId, revision, EventType.SIGNED, orderedMap(
                "documentHash", documentHash,
                "requestId", request.requestId(),
                "revision", revision,
                "signature", record.getSignatureValue(),
                "signer", request.signer(),
                "stepOrder", ownStep.getStepOrder()), now);

        boolean completed = false;
        String evidenceDigest = null;
        List<SignatureRecord> afterSign = repository.findSignaturesByRevision(packageId, revision);
        if (allStepsSigned(steps, afterSign)) {
            completed = true;
            evidenceDigest = freezeEvidence(pkg, steps, revision, now);
        }

        ActionResponse body = new ActionResponse(
                packageId, request.requestId(), true, revision, completed, evidenceDigest, now);
        return new SubmitOutcome(HttpStatus.OK, body);
    }

    private SubmitOutcome rejected(HttpStatus status, String code, String message) {
        // 拒绝不产生签署事件；错误结果仍按 requestId 落库以支持同参重放
        return new SubmitOutcome(status, new ErrorBody(code, message));
    }

    // ---------------- 证据包冻结 ----------------

    private String freezeEvidence(SigningPackage pkg, List<SigningStep> steps,
                                  int revision, Instant now) {
        // COMPLETED 事件为链尾；证据摘要覆盖包含该事件在内的完整事件链。
        appendEvent(pkg.getId(), revision, EventType.COMPLETED,
                orderedMap("revision", revision), now);

        List<SignatureRecord> all = repository.findAllSignatures(pkg.getId());
        List<SignEvent> events = repository.findEvents(pkg.getId());

        Map<String, Object> content = new TreeMap<>();
        content.put("documentHash", pkg.getDocumentHash());
        content.put("events", events.stream().map(SigningService::eventEntry).toList());
        content.put("invalidatedHistory", all.stream()
                .filter(s -> s.getStatus() == SignatureStatus.INVALIDATED)
                .map(SigningService::signatureEntry)
                .toList());
        content.put("packageId", pkg.getId());
        content.put("revision", revision);
        content.put("steps", stepEntries(steps));
        content.put("validSignatures", all.stream()
                .filter(s -> s.getStatus() == SignatureStatus.VALID)
                .map(SigningService::signatureEntry)
                .toList());

        String contentJson = CanonicalJson.object(content);
        String digest = Hashes.sha256(contentJson);

        repository.insertEvidence(pkg.getId(), contentJson, digest, now);
        int completed = repository.compareAndComplete(pkg.getId(), revision, now, digest);
        if (completed == 0) {
            throw new BusinessRuleException(HttpStatus.CONFLICT, "FREEZE_CONFLICT",
                    "完成冻结并发冲突，事务回滚");
        }
        return digest;
    }

    // ---------------- 事件链 ----------------

    private SignEvent appendEvent(String packageId, int revision, EventType type,
                                  LinkedHashMap<String, Object> detail, Instant now) {
        int seq = repository.countEvents(packageId) + 1;
        String previousHash = repository.findLastEvent(packageId)
                .map(SignEvent::getEventHash)
                .orElse(Hashes.GENESIS_PREVIOUS);
        String detailJson = CanonicalJson.orderedObject(detail);
        String canonical = canonicalEvent(seq, type, revision, now, parseForCanonical(detailJson));
        String eventHash = Hashes.sha256(previousHash + canonical);

        SignEvent event = new SignEvent();
        event.setPackageId(packageId);
        event.setSeq(seq);
        event.setEventType(type);
        event.setRevision(revision);
        event.setDetail(detailJson);
        event.setPreviousHash(previousHash);
        event.setEventHash(eventHash);
        event.setCreatedAt(now);
        repository.insertEvent(event);
        return event;
    }

    /**
     * 当前时间截断到微秒：H2 TIMESTAMP(6) 精度为微秒，保证事件哈希用的时间值
     * 与落库读回值完全一致，真实时钟下链哈希也可稳定复算。
     */
    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    private String canonicalEvent(int seq, EventType type, int revision,
                                  Instant createdAt, Object detailNode) {
        Map<String, Object> event = new TreeMap<>();
        event.put("createdAt", createdAt);
        event.put("detail", detailNode);
        event.put("eventType", type);
        event.put("revision", revision);
        event.put("seq", seq);
        return CanonicalJson.object(event);
    }

    @SuppressWarnings("unchecked")
    private Object parseForCanonical(String detailJson) {
        try {
            return objectMapper.readValue(detailJson, TreeMap.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("事件详情序列化失败", e);
        }
    }

    private static Map<String, Object> eventEntry(SignEvent e) {
        // 键名与 EvidenceResponse.EventView 的 record 组件一致，便于快照反序列化
        Map<String, Object> entry = new TreeMap<>();
        entry.put("at", e.getCreatedAt());
        entry.put("detail", e.getDetail());
        entry.put("eventHash", e.getEventHash());
        entry.put("previousHash", e.getPreviousHash());
        entry.put("revision", e.getRevision());
        entry.put("seq", e.getSeq());
        entry.put("type", e.getEventType());
        return entry;
    }

    private static Map<String, Object> signatureEntry(SignatureRecord s) {
        Map<String, Object> entry = new TreeMap<>();
        entry.put("documentHash", s.getDocumentHash());
        entry.put("invalidatedByRevision", s.getInvalidatedByRevision());
        entry.put("revision", s.getRevision());
        entry.put("signature", s.getSignatureValue());
        entry.put("signedAt", s.getCreatedAt());
        entry.put("signer", s.getSigner());
        entry.put("stepOrder", s.getStepOrder());
        return entry;
    }

    private static List<Map<String, Object>> stepEntries(List<SigningStep> steps) {
        int maxOrder = steps.stream().mapToInt(SigningStep::getStepOrder).max().orElse(0);
        List<Map<String, Object>> result = new ArrayList<>();
        for (int order = 1; order <= maxOrder; order++) {
            int current = order;
            List<String> signers = steps.stream()
                    .filter(s -> s.getStepOrder() == current)
                    .map(SigningStep::getSigner)
                    .sorted()
                    .toList();
            result.add(orderedMap("signers", signers, "stepOrder", current));
        }
        return result;
    }

    // ---------------- 状态与规则辅助 ----------------

    private PackageStatusResponse buildStatus(SigningPackage pkg) {
        List<SigningStep> steps = repository.findSteps(pkg.getId());
        List<SignatureRecord> signatures =
                repository.findSignaturesByRevision(pkg.getId(), pkg.getCurrentRevision());
        int maxOrder = steps.stream().mapToInt(SigningStep::getStepOrder).max().orElse(0);

        List<PackageStatusResponse.StepStatus> stepStatuses = new ArrayList<>();
        for (int order = 1; order <= maxOrder; order++) {
            int current = order;
            List<SigningStep> stepRows = steps.stream()
                    .filter(s -> s.getStepOrder() == current)
                    .toList();
            boolean open = isStepOpen(current, steps, signatures);
            List<PackageStatusResponse.SignerStatus> signerStatuses = stepRows.stream()
                    .map(row -> new PackageStatusResponse.SignerStatus(
                            row.getSigner(),
                            signatures.stream().anyMatch(s -> s.getStatus() == SignatureStatus.VALID
                                    && s.getSigner().equals(row.getSigner())
                                    && s.getStepOrder() == current)))
                    .toList();
            stepStatuses.add(new PackageStatusResponse.StepStatus(current, open, signerStatuses));
        }

        return new PackageStatusResponse(
                pkg.getId(),
                pkg.getCurrentRevision(),
                pkg.getDocumentHash(),
                pkg.getExpiresAt(),
                pkg.getStatus().name(),
                pkg.getFrozenAt(),
                pkg.getEvidenceDigest(),
                stepStatuses);
    }

    /**
     * 步骤开放条件：所有更前步骤的全部签署人均已在当前修订完成有效签名。
     */
    private boolean isStepOpen(int stepOrder, List<SigningStep> steps,
                               List<SignatureRecord> signatures) {
        for (SigningStep step : steps) {
            if (step.getStepOrder() >= stepOrder) {
                continue;
            }
            boolean signed = signatures.stream()
                    .anyMatch(s -> s.getStatus() == SignatureStatus.VALID
                            && s.getStepOrder() == step.getStepOrder()
                            && s.getSigner().equals(step.getSigner()));
            if (!signed) {
                return false;
            }
        }
        return true;
    }

    private boolean allStepsSigned(List<SigningStep> steps, List<SignatureRecord> signatures) {
        return steps.stream().allMatch(step -> signatures.stream()
                .anyMatch(s -> s.getStatus() == SignatureStatus.VALID
                        && s.getStepOrder() == step.getStepOrder()
                        && s.getSigner().equals(step.getSigner())));
    }

    /**
     * 惰性到期：PENDING 且当前时间不早于到期时间时，单事务内转入 EXPIRED 并补事件。
     *
     * @return true 表示本次调用发生了到期转换
     */
    private boolean expireIfDue(SigningPackage pkg, Instant now) {
        if (pkg.getStatus() != PackageStatus.PENDING || now.isBefore(pkg.getExpiresAt())) {
            return false;
        }
        int updated = repository.compareAndExpire(pkg.getId(), pkg.getCurrentRevision());
        if (updated == 0) {
            return false;
        }
        appendEvent(pkg.getId(), pkg.getCurrentRevision(), EventType.EXPIRED, orderedMap(
                "expiresAt", pkg.getExpiresAt()), now);
        pkg.setStatus(PackageStatus.EXPIRED);
        return true;
    }

    private SigningPackage lockPackage(String packageId) {
        SigningPackage pkg = repository.findPackageForUpdate(packageId);
        if (pkg == null) {
            throw new BusinessRuleException(HttpStatus.NOT_FOUND, "PACKAGE_NOT_FOUND",
                    "签署包不存在: " + packageId);
        }
        return pkg;
    }

    private void rejectIfTerminalOrExpired(SigningPackage pkg, Instant now) {
        expireIfDue(pkg, now);
        if (pkg.getStatus() == PackageStatus.COMPLETED) {
            throw new BusinessRuleException(HttpStatus.CONFLICT, "REVISION_AFTER_COMPLETION",
                    "最终完成后禁止修订或取消");
        }
        if (pkg.getStatus() == PackageStatus.CANCELLED) {
            throw new BusinessRuleException(HttpStatus.CONFLICT, "PACKAGE_CANCELLED",
                    "流程已取消");
        }
        if (pkg.getStatus() == PackageStatus.EXPIRED) {
            throw new BusinessRuleException(HttpStatus.GONE, "PACKAGE_EXPIRED", "流程已过期");
        }
    }

    private void validateSteps(CreatePackageRequest request) {
        List<CreatePackageRequest.StepSpec> steps = request.steps();
        int expected = 1;
        List<String> allSigners = new ArrayList<>();
        for (CreatePackageRequest.StepSpec step : steps) {
            if (step.stepOrder() == null || step.stepOrder() != expected) {
                throw new BusinessRuleException(HttpStatus.BAD_REQUEST, "INVALID_STEP_DEFINITION",
                        "步骤序号必须从 1 开始连续递增");
            }
            List<String> trimmed = step.signers().stream().map(String::trim).toList();
            if (trimmed.stream().distinct().count() != trimmed.size()) {
                throw new BusinessRuleException(HttpStatus.BAD_REQUEST, "INVALID_STEP_DEFINITION",
                        "同一步骤内签署人重复: step=" + expected);
            }
            for (String signer : trimmed) {
                if (allSigners.contains(signer)) {
                    throw new BusinessRuleException(HttpStatus.BAD_REQUEST, "INVALID_STEP_DEFINITION",
                            "签署人在多个步骤重复出现: " + signer);
                }
                allSigners.add(signer);
            }
            expected++;
        }
    }

    private String fingerprint(String packageId, SignatureSubmitRequest request) {        String canonical = String.join("|",
                packageId,
                request.requestId(),
                request.signer(),
                String.valueOf(request.revision()),
                Hashes.normalize(request.documentHash()),
                String.valueOf(request.expectedVersion()),
                request.signature().trim().toLowerCase());
        return Hashes.sha256(canonical);
    }

    private SubmitOutcome replayOutcome(IdempotentRequest row) {
        try {
            Object body;
            if (row.httpStatus() == HttpStatus.OK.value()) {
                body = objectMapper.readValue(row.response(), ActionResponse.class);
            } else {
                body = objectMapper.readValue(row.response(), ErrorBody.class);
            }
            return new SubmitOutcome(HttpStatus.valueOf(row.httpStatus()), body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("幂等响应快照解析失败", e);
        }
    }

    private String writeJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    private static LinkedHashMap<String, Object> orderedMap(Object... kv) {        if (kv.length % 2 != 0) {
            throw new IllegalArgumentException("键值必须成对");
        }
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }

    /**
     * 签名提交结果（含首次 HTTP 状态与响应体）。
     */
    public record SubmitOutcome(HttpStatus status, Object body) {
    }

    /**
     * 错误响应体。
     */
    public record ErrorBody(String error, String message) {
    }
}
