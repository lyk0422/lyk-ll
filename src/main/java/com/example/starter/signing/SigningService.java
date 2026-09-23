package com.example.starter.signing;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.signing.Contracts.CancelPackageRequest;
import com.example.starter.signing.Contracts.CreatePackageRequest;
import com.example.starter.signing.Contracts.EvidenceEvent;
import com.example.starter.signing.Contracts.EvidencePackage;
import com.example.starter.signing.Contracts.EvidenceSignature;
import com.example.starter.signing.Contracts.PackageState;
import com.example.starter.signing.Contracts.RevisePackageRequest;
import com.example.starter.signing.Contracts.SignatureResult;
import com.example.starter.signing.Contracts.SignerState;
import com.example.starter.signing.Contracts.StepState;
import com.example.starter.signing.Contracts.SubmitSignatureRequest;
import com.example.starter.signing.SigningRepository.EventRow;
import com.example.starter.signing.SigningRepository.PackageRow;
import com.example.starter.signing.SigningRepository.RequestLogRow;
import com.example.starter.signing.SigningRepository.SignatureRow;
import com.example.starter.signing.SigningRepository.StepRow;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 多方签署流程核心服务。
 * 并发一致性：所有变更操作先 {@code SELECT ... FOR UPDATE} 锁定签署包行，
 * 在同一事务内完成校验、签名写入、事件追加与状态推进；
 * 到期/取消/修订与签名竞争时按行锁串行化，形成唯一终态，迟到签名一律拒绝。
 */
@Service
public class SigningService {

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String STATUS_CANCELLED = "CANCELLED";
    private static final String STATUS_EXPIRED = "EXPIRED";

    private final SigningRepository repository;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public SigningService(SigningRepository repository, Clock clock, ObjectMapper objectMapper) {
        this.repository = repository;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    /**
     * 建包：创建签署包、有序步骤与 PACKAGE_CREATED 事件。
     */
    @Transactional
    public PackageState createPackage(CreatePackageRequest request) {
        Instant now = clock.instant();
        if (!request.expiresAt().isAfter(now)) {
            throw ApiException.badRequest("EXPIRES_AT_NOT_FUTURE", "expiresAt must be in the future");
        }
        String packageId = request.packageId() == null || request.packageId().isBlank()
                ? UUID.randomUUID().toString() : request.packageId();
        if (repository.findPackage(packageId).isPresent()) {
            throw ApiException.conflict("PACKAGE_EXISTS", "package already exists: " + packageId);
        }
        for (int i = 0; i < request.steps().size(); i++) {
            List<String> signers = request.steps().get(i).signers();
            if (signers.stream().distinct().count() != signers.size()) {
                throw ApiException.badRequest("DUPLICATE_SIGNER_IN_STEP", "duplicate signer in step " + i);
            }
        }
        repository.insertPackage(packageId, STATUS_ACTIVE, 1, request.docSha256(), request.expiresAt(), now);
        List<List<String>> steps = new ArrayList<>();
        for (int i = 0; i < request.steps().size(); i++) {
            List<String> signers = List.copyOf(request.steps().get(i).signers());
            steps.add(signers);
            for (String signer : signers) {
                repository.insertStep(packageId, i, signer);
            }
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("docSha256", request.docSha256());
        payload.put("expiresAt", request.expiresAt().toString());
        payload.put("revision", 1);
        payload.put("steps", steps);
        appendEvent(packageId, "PACKAGE_CREATED", toJson(payload), now);
        return buildState(repository.findPackage(packageId).orElseThrow());
    }

    /**
     * 提交签名：幂等键 + 完整参数指纹；同键同参返回首次结果，同键异参返回 409 且不新增事件。
     */
    @Transactional
    public SignatureResult submitSignature(String packageId, SubmitSignatureRequest request) {
        String fingerprint = Hashing.sha256Hex(String.join("|",
                packageId, request.signerId(), String.valueOf(request.revision()),
                request.docSha256(), String.valueOf(request.expectedVersion()), request.signature()));
        var existing = repository.findRequest(request.requestId());
        if (existing.isPresent()) {
            return replay(existing.get(), fingerprint);
        }
        return doSubmitSignature(packageId, request, fingerprint);
    }

    private SignatureResult replay(RequestLogRow logged, String fingerprint) {
        if (!logged.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("REQUEST_FINGERPRINT_MISMATCH",
                    "requestId already used with different parameters");
        }
        try {
            return objectMapper.readValue(logged.responseJson(), SignatureResult.class);
        } catch (Exception e) {
            throw new IllegalStateException("corrupt request log for " + logged.requestId(), e);
        }
    }

    private SignatureResult doSubmitSignature(String packageId, SubmitSignatureRequest request, String fingerprint) {
        Instant now = clock.instant();
        PackageRow pkg = repository.findPackageForUpdate(packageId)
                .orElseThrow(() -> ApiException.notFound("package not found: " + packageId));
        // 持有行锁后复查幂等日志：并发同键请求在此串行，后到者直接返回首次结果。
        var logged = repository.findRequest(request.requestId());
        if (logged.isPresent()) {
            return replay(logged.get(), fingerprint);
        }
        pkg = expireIfDue(pkg, now);
        requireActive(pkg, "submit signature");
        if (request.expectedVersion() != pkg.version()) {
            throw ApiException.conflict("VERSION_CONFLICT",
                    "expectedVersion " + request.expectedVersion() + " != current " + pkg.version());
        }
        if (request.revision() != pkg.currentRevision()) {
            throw ApiException.conflict("REVISION_MISMATCH",
                    "revision " + request.revision() + " != current " + pkg.currentRevision());
        }
        if (!request.docSha256().equals(pkg.docSha256())) {
            throw ApiException.unprocessable("DIGEST_MISMATCH", "document digest does not match current revision");
        }
        List<StepRow> steps = repository.findSteps(packageId);
        int signerStep = -1;
        for (StepRow step : steps) {
            if (step.signerId().equals(request.signerId())) {
                signerStep = step.stepIndex();
                break;
            }
        }
        if (signerStep < 0) {
            throw ApiException.unprocessable("UNKNOWN_SIGNER", "signer is not part of this package");
        }
        if (signerStep != pkg.currentStep()) {
            throw ApiException.conflict("STEP_NOT_OPEN",
                    "step " + signerStep + " is not open; current step is " + pkg.currentStep());
        }
        if (!SyntheticSignatures.verify(packageId, request.revision(), request.signerId(),
                request.docSha256(), request.signature())) {
            throw ApiException.unprocessable("SIGNATURE_INVALID", "synthetic signature verification failed");
        }
        if (!repository.tryInsertSignature(packageId, request.revision(), signerStep, request.signerId(),
                request.docSha256(), request.signature(), now)) {
            throw ApiException.conflict("DUPLICATE_SIGNER", "signer already signed this revision");
        }
        Map<String, Object> signed = new LinkedHashMap<>();
        signed.put("docSha256", request.docSha256());
        signed.put("revision", request.revision());
        signed.put("signerId", request.signerId());
        signed.put("stepIndex", signerStep);
        appendEvent(packageId, "SIGNATURE_SUBMITTED", toJson(signed), now);

        PackageRow current = pkg;
        if (stepCompleted(packageId, pkg.currentRevision(), pkg.currentStep(), steps)) {
            Map<String, Object> stepDone = new LinkedHashMap<>();
            stepDone.put("stepIndex", pkg.currentStep());
            appendEvent(packageId, "STEP_COMPLETED", toJson(stepDone), now);
            int totalSteps = (int) steps.stream().map(StepRow::stepIndex).distinct().count();
            if (pkg.currentStep() + 1 >= totalSteps) {
                current = completeAndFreeze(pkg, now);
            } else {
                repository.updatePackage(packageId, STATUS_ACTIVE, pkg.currentRevision(),
                        pkg.docSha256(), pkg.currentStep() + 1, now);
                current = repository.findPackage(packageId).orElseThrow();
            }
        } else {
            // 步骤未完成：同样推进版本号，保证每次签名提交都产生可观测的新版本。
            repository.updatePackage(packageId, STATUS_ACTIVE, pkg.currentRevision(),
                    pkg.docSha256(), pkg.currentStep(), now);
            current = repository.findPackage(packageId).orElseThrow();
        }
        SignatureResult result = new SignatureResult(request.requestId(), false, buildState(current));
        String body = toJson(result);
        if (!repository.tryInsertRequest(request.requestId(), packageId, fingerprint, 200, body, now)) {
            // 跨包同键等极端竞争下另一事务已登记：以其首次结果为准。
            return replay(repository.findRequest(request.requestId()).orElseThrow(), fingerprint);
        }
        return result;
    }

    private boolean stepCompleted(String packageId, int revision, int stepIndex, List<StepRow> steps) {
        List<String> required = steps.stream()
                .filter(s -> s.stepIndex() == stepIndex)
                .map(StepRow::signerId)
                .toList();
        List<SignatureRow> valid = repository.findSignatures(packageId).stream()
                .filter(s -> s.revision() == revision && s.stepIndex() == stepIndex
                        && "VALID".equals(s.status()))
                .toList();
        return valid.size() == required.size()
                && valid.stream().allMatch(s -> required.contains(s.signerId()));
    }

    /**
     * 最后一个有效签名落库后原子完成流程并冻结证据包（同事务）。
     */
    private PackageRow completeAndFreeze(PackageRow pkg, Instant now) {
        String packageId = pkg.id();
        Map<String, Object> completed = new LinkedHashMap<>();
        completed.put("revision", pkg.currentRevision());
        appendEvent(packageId, "PACKAGE_COMPLETED", toJson(completed), now);
        appendEvent(packageId, "EVIDENCE_FROZEN", "{}", now);
        repository.updatePackage(packageId, STATUS_COMPLETED, pkg.currentRevision(),
                pkg.docSha256(), pkg.currentStep() + 1, now);
        EvidencePackage evidence = buildEvidence(packageId);
        repository.freezeEvidence(packageId, toJson(evidence), evidence.evidenceHash(), now);
        return repository.findPackage(packageId).orElseThrow();
    }

    /**
     * 修订文档：同事务内产生新修订号、旧修订全部签名失效（保留历史）、流程重置到第一步。
     * 最终完成/取消/到期后禁止修订。
     */
    @Transactional
    public PackageState revise(String packageId, RevisePackageRequest request) {
        Instant now = clock.instant();
        PackageRow pkg = repository.findPackageForUpdate(packageId)
                .orElseThrow(() -> ApiException.notFound("package not found: " + packageId));
        pkg = expireIfDue(pkg, now);
        requireActive(pkg, "revise document");
        if (request.expectedVersion() != pkg.version()) {
            throw ApiException.conflict("VERSION_CONFLICT",
                    "expectedVersion " + request.expectedVersion() + " != current " + pkg.version());
        }
        int invalidated = repository.invalidateValidSignatures(packageId);
        if (invalidated > 0) {
            Map<String, Object> invalid = new LinkedHashMap<>();
            invalid.put("count", invalidated);
            invalid.put("revision", pkg.currentRevision());
            appendEvent(packageId, "SIGNATURES_INVALIDATED", toJson(invalid), now);
        }
        int newRevision = pkg.currentRevision() + 1;
        Map<String, Object> revised = new LinkedHashMap<>();
        revised.put("docSha256", request.docSha256());
        revised.put("revision", newRevision);
        appendEvent(packageId, "REVISION_CREATED", toJson(revised), now);
        repository.updatePackage(packageId, STATUS_ACTIVE, newRevision, request.docSha256(), 0, now);
        return buildState(repository.findPackage(packageId).orElseThrow());
    }

    /**
     * 取消流程：终态，之后任何签名均被拒绝。
     */
    @Transactional
    public PackageState cancel(String packageId, CancelPackageRequest request) {
        Instant now = clock.instant();
        PackageRow pkg = repository.findPackageForUpdate(packageId)
                .orElseThrow(() -> ApiException.notFound("package not found: " + packageId));
        pkg = expireIfDue(pkg, now);
        requireActive(pkg, "cancel package");
        if (request.expectedVersion() != pkg.version()) {
            throw ApiException.conflict("VERSION_CONFLICT",
                    "expectedVersion " + request.expectedVersion() + " != current " + pkg.version());
        }
        appendEvent(packageId, "PACKAGE_CANCELLED", "{}", now);
        repository.updatePackage(packageId, STATUS_CANCELLED, pkg.currentRevision(),
                pkg.docSha256(), pkg.currentStep(), now);
        return buildState(repository.findPackage(packageId).orElseThrow());
    }

    /**
     * 查询流程状态；到期包在此懒推进为 EXPIRED 终态。
     */
    @Transactional
    public PackageState getState(String packageId) {
        PackageRow pkg = repository.findPackageForUpdate(packageId)
                .orElseThrow(() -> ApiException.notFound("package not found: " + packageId));
        pkg = expireIfDue(pkg, clock.instant());
        return buildState(pkg);
    }

    /**
     * 查询证据包：仅最终完成冻结后可用；直接返回冻结时存储的内容，重复查询摘要稳定。
     */
    @Transactional(readOnly = true)
    public EvidencePackage getEvidence(String packageId) {
        PackageRow pkg = repository.findPackage(packageId)
                .orElseThrow(() -> ApiException.notFound("package not found: " + packageId));
        if (pkg.evidenceJson() == null) {
            throw ApiException.conflict("EVIDENCE_NOT_FROZEN", "evidence package is not frozen yet");
        }
        try {
            return objectMapper.readValue(pkg.evidenceJson(), EvidencePackage.class);
        } catch (Exception e) {
            throw new IllegalStateException("corrupt evidence for " + packageId, e);
        }
    }

    /**
     * 懒到期：ACTIVE 且当前时间已到达期时间时，同事务推进为 EXPIRED 并记录事件。
     */
    private PackageRow expireIfDue(PackageRow pkg, Instant now) {
        if (STATUS_ACTIVE.equals(pkg.status()) && !now.isBefore(pkg.expiresAt())) {
            appendEvent(pkg.id(), "PACKAGE_EXPIRED", "{}", now);
            repository.updatePackage(pkg.id(), STATUS_EXPIRED, pkg.currentRevision(),
                    pkg.docSha256(), pkg.currentStep(), now);
            return repository.findPackage(pkg.id()).orElseThrow();
        }
        return pkg;
    }

    private void requireActive(PackageRow pkg, String action) {
        if (!STATUS_ACTIVE.equals(pkg.status())) {
            throw ApiException.conflict("PACKAGE_NOT_ACTIVE",
                    "cannot " + action + " while package is " + pkg.status());
        }
    }

    private void appendEvent(String packageId, String type, String payload, Instant now) {
        int seq = repository.nextEventSeq(packageId);
        String prevHash = repository.lastEventHash(packageId);
        String hash = EventChain.hash(prevHash, packageId, seq, type, payload);
        repository.insertEvent(packageId, seq, type, payload, prevHash, hash, now);
    }

    private PackageState buildState(PackageRow pkg) {
        List<StepRow> stepRows = repository.findSteps(pkg.id());
        List<SignatureRow> signatures = repository.findSignatures(pkg.id());
        Map<Integer, List<SignerState>> byStep = new LinkedHashMap<>();
        for (StepRow step : stepRows) {
            boolean signed = signatures.stream().anyMatch(s -> "VALID".equals(s.status())
                    && s.revision() == pkg.currentRevision()
                    && s.stepIndex() == step.stepIndex()
                    && s.signerId().equals(step.signerId()));
            byStep.computeIfAbsent(step.stepIndex(), k -> new ArrayList<>())
                    .add(new SignerState(step.signerId(), signed));
        }
        List<StepState> steps = byStep.entrySet().stream()
                .map(e -> new StepState(e.getKey(), e.getValue()))
                .toList();
        return new PackageState(pkg.id(), pkg.status(), pkg.currentRevision(), pkg.docSha256(),
                pkg.currentStep(), steps.size(), pkg.version(), pkg.expiresAt(), steps,
                pkg.evidenceJson() != null, pkg.evidenceHash());
    }

    private EvidencePackage buildEvidence(String packageId) {
        PackageRow pkg = repository.findPackage(packageId).orElseThrow();
        List<StepState> steps = buildState(pkg).steps();
        List<SignatureRow> signatures = repository.findSignatures(packageId);
        List<EvidenceSignature> valid = new ArrayList<>();
        List<EvidenceSignature> invalidated = new ArrayList<>();
        for (SignatureRow s : signatures) {
            EvidenceSignature entry = new EvidenceSignature(s.revision(), s.stepIndex(), s.signerId(),
                    s.docSha256(), s.signatureValue(), s.status(), s.createdAt());
            if ("VALID".equals(s.status())) {
                valid.add(entry);
            } else {
                invalidated.add(entry);
            }
        }
        List<EvidenceEvent> events = repository.findEvents(packageId).stream()
                .map(e -> new EvidenceEvent(e.seq(), e.eventType(), e.payload(), e.prevHash(), e.eventHash()))
                .toList();
        Instant completedAt = clock.instant();
        String contentHash = Hashing.sha256Hex(String.join("|",
                packageId, String.valueOf(pkg.currentRevision()), pkg.docSha256(),
                String.valueOf(events.isEmpty() ? EventChain.GENESIS_HASH : events.get(events.size() - 1).eventHash()),
                String.valueOf(valid.size()), String.valueOf(invalidated.size())));
        return new EvidencePackage(packageId, pkg.currentRevision(), pkg.docSha256(), completedAt,
                steps, valid, invalidated, events, contentHash);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("json serialization failed", e);
        }
    }
}
