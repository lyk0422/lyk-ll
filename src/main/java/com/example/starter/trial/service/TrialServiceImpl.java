package com.example.starter.trial.service;

import com.example.starter.trial.api.AuditEventResponse;
import com.example.starter.trial.api.BlindedSubjectResponse;
import com.example.starter.trial.api.EnrollmentRequest;
import com.example.starter.trial.api.EnrollmentResponse;
import com.example.starter.trial.api.UnblindingConfirmRequest;
import com.example.starter.trial.api.UnblindingResponse;
import com.example.starter.trial.api.VisitRequest;
import com.example.starter.trial.api.VisitResponse;
import com.example.starter.trial.domain.Assignment;
import com.example.starter.trial.domain.SubjectStatus;
import com.example.starter.trial.domain.UnblindingStatus;
import com.example.starter.trial.repository.AuditEventRow;
import com.example.starter.trial.repository.EnrollmentRequestRow;
import com.example.starter.trial.repository.SubjectRow;
import com.example.starter.trial.repository.TrialRepository;
import com.example.starter.trial.repository.UnblindingRequestRow;
import com.example.starter.trial.repository.VisitRow;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.function.Supplier;

/**
 * 临床试验随机入组与应急揭盲服务实现。
 *
 * <p>关键并发与一致性策略：</p>
 * <ul>
 *   <li>随机号由 {@link RandomNumberSequencer} 在独立事务中发出，业务回滚不回收号；</li>
 *   <li>入组事务对“中心序列行”加行锁（SELECT ... FOR UPDATE），同中心入组串行，
 *       保证区组位置（按已入组人数计）唯一、每个 4 例区组严格 2:2；不同中心互不阻塞；</li>
 *   <li>同筛选号唯一性由数据库唯一约束兜底，并发只成功一次；</li>
 *   <li>揭盲两步确认在同一事务内完成终态转换、排除标记与审计落库；条件更新
 *       {@code status='PENDING'} 使并发的第二步确认只有一个生效，其余返回首次结果。</li>
 * </ul>
 */
@Service
public class TrialServiceImpl implements TrialService {

    private static final String BLINDED_TREATMENT = "A";
    private static final String BLINDED_CONTROL = "B";
    private static final String EXCLUSION_REASON = "SUBJECT_UNBLINDED";

    private final TrialRepository repository;
    private final RandomNumberSequencer sequencer;
    private final BlockRandomizer randomizer;
    private final Supplier<Instant> clock;
    private final Random seedRandom;

    public TrialServiceImpl(TrialRepository repository,
                            RandomNumberSequencer sequencer,
                            BlockRandomizer randomizer,
                            Supplier<Instant> clock) {
        this.repository = repository;
        this.sequencer = sequencer;
        this.randomizer = randomizer;
        this.clock = clock;
        this.seedRandom = new Random();
    }

    @Override
    @Transactional
    public EnrollmentResponse enroll(EnrollmentRequest request) {
        String fingerprint = fingerprint(request);

        // 1) 幂等快查：同 requestId 先查；同参回放首次结果，异参 409。此处尚未加锁/发号。
        Optional<EnrollmentRequestRow> existing = repository.findEnrollmentRequest(request.requestId());
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), fingerprint);
        }

        // 2) 获取中心入组串行锁（独立锁表，不与序列行争用），同中心入组在锁内串行。
        repository.lockSiteEnrollment(request.trialId(), request.siteCode(), clock.get());

        // 3) 锁内复查幂等键：并发同 requestId 的落败方直接返回首次结果，不发新号。
        Optional<EnrollmentRequestRow> raced = repository.findEnrollmentRequest(request.requestId());
        if (raced.isPresent()) {
            return replayOrConflict(raced.get(), fingerprint);
        }

        // 4) 锁内复查受试者唯一性：同一试验筛选号只能入组一次，落败方 409 且不发号。
        if (repository.findSubject(request.trialId(), request.subjectScreenNo()).isPresent()) {
            throw new ConflictException("subject already enrolled in this trial: "
                    + request.trialId() + "/" + request.subjectScreenNo());
        }

        // 5) 区组位置按“中心内已成功入组人数”计，保证每个实际入组区组 2:2；先定位再发号。
        long position = repository.countSubjectsAtSite(request.trialId(), request.siteCode()) + 1;

        // 6) 发号（独立事务立即提交）。锁表与序列表分离，故此调用不会与步骤 2 的行锁死锁；
        //    即便本事务随后回滚（如跨中心同筛选号唯一约束兜底），该号也绝不重用。
        long randomizationNo = sequencer.nextRandomNumber(request.trialId(), request.siteCode());

        // 7) 中心种子（首次使用时合成生成）+ 区组随机分配。
        long seed = repository.getOrCreateSiteSeed(request.trialId(), request.siteCode(),
                seedRandom.nextLong(), clock.get());
        Assignment assignment = randomizer.assign(seed, position);
        String blindedCode = assignment == Assignment.TREATMENT ? BLINDED_TREATMENT : BLINDED_CONTROL;
        Instant now = clock.get();

        SubjectRow subject = new SubjectRow(0L, request.trialId(), request.siteCode(),
                request.subjectScreenNo(), randomizationNo, blindedCode, assignment.name(),
                request.consentAtUtc(), now, null, SubjectStatus.ENROLLED.name());
        try {
            repository.insertSubject(subject);
        } catch (DuplicateKeyException dupSubject) {
            // 跨中心并发同一筛选号的兜底：唯一约束保证只成功一例；已发号码不重用。
            throw new ConflictException("concurrent enrollment lost for subject: "
                    + request.trialId() + "/" + request.subjectScreenNo());
        }

        try {
            repository.insertEnrollmentRequest(new EnrollmentRequestRow(0L, request.requestId(),
                    request.trialId(), request.subjectScreenNo(), request.siteCode(),
                    request.consentAtUtc(), fingerprint, randomizationNo, blindedCode, now));
        } catch (DuplicateKeyException dupRequest) {
            // 理论兜底（锁内已复查）：并发同 requestId 时重读首次记录回放或判冲突。
            EnrollmentRequestRow winner = repository.findEnrollmentRequest(request.requestId()).orElseThrow();
            return replayOrConflict(winner, fingerprint);
        }

        return new EnrollmentResponse(randomizationNo, blindedCode, false);
    }

    private EnrollmentResponse replayOrConflict(EnrollmentRequestRow row, String fingerprint) {
        if (!row.fingerprint().equals(fingerprint)) {
            throw new ConflictException("requestId already used with different parameters: " + row.requestId());
        }
        return new EnrollmentResponse(row.randomizationNo(), row.blindedGroupCode(), true);
    }

    @Override
    public BlindedSubjectResponse getBlindedSubject(String trialId, String subjectScreenNo) {
        SubjectRow subject = repository.findSubject(trialId, subjectScreenNo)
                .orElseThrow(() -> new NotFoundException("subject not found: "
                        + trialId + "/" + subjectScreenNo));
        return new BlindedSubjectResponse(subject.trialId(), subject.subjectScreenNo(),
                subject.siteCode(), subject.randomizationNo(), subject.blindedGroupCode(),
                subject.status());
    }

    @Override
    @Transactional
    public UnblindingResponse confirmUnblinding(UnblindingConfirmRequest request) {
        Instant now = clock.get();

        // 同 requestId 重放：完成则返回首次结果且不新增事件；待确认则继续第二步逻辑。
        Optional<UnblindingRequestRow> byRequest =
                repository.findUnblindingByRequestId(request.requestId());
        if (byRequest.isPresent()) {
            UnblindingRequestRow row = byRequest.get();
            if (row.status().equals(UnblindingStatus.COMPLETED.name())) {
                return toCompletedResponse(row, true);
            }
            return handleSecondConfirmation(request, row, now);
        }

        // 新 requestId：受试者必须存在。
        SubjectRow subject = repository.findSubject(request.trialId(), request.subjectScreenNo())
                .orElseThrow(() -> new NotFoundException("subject not found: "
                        + request.trialId() + "/" + request.subjectScreenNo()));

        // 该受试者已有揭盲申请：已完成→终态回放；待确认→409（应使用原 requestId 完成第二步）。
        Optional<UnblindingRequestRow> prior =
                repository.findUnblindingForSubject(request.trialId(), request.subjectScreenNo());
        if (prior.isPresent()) {
            if (prior.get().status().equals(UnblindingStatus.COMPLETED.name())) {
                return toCompletedResponse(prior.get(), true);
            }
            throw new ConflictException("an unblinding request is already pending for this subject; "
                    + "continue with requestId " + prior.get().requestId());
        }

        // 已揭盲终态（理论上上面已覆盖）：不再产生任何变更。
        if (subject.status().equals(SubjectStatus.UNBLINDED.name())) {
            throw new ConflictException("subject already unblinded: "
                    + request.trialId() + "/" + request.subjectScreenNo());
        }

        // 第一步确认：授权人校验后落 PENDING 与审计。
        assertAuthorized(request.confirmerUserId());
        repository.insertUnblindingRequest(new UnblindingRequestRow(0L, request.requestId(),
                request.trialId(), request.subjectScreenNo(), UnblindingStatus.PENDING.name(),
                request.confirmerUserId(), now, null, null, null, now));
        repository.insertAuditEvent(request.trialId(), request.subjectScreenNo(), request.requestId(),
                "FIRST_CONFIRMED", request.confirmerUserId(),
                "第一名授权人确认应急揭盲，申请待第二名授权人确认", now);
        return new UnblindingResponse(request.requestId(), request.trialId(),
                request.subjectScreenNo(), subject.randomizationNo(),
                UnblindingStatus.PENDING.name(), request.confirmerUserId(), null, null, false);
    }

    private UnblindingResponse handleSecondConfirmation(UnblindingConfirmRequest request,
                                                        UnblindingRequestRow pending, Instant now) {
        // 申请归属必须与请求参数一致。
        if (!pending.trialId().equals(request.trialId())
                || !pending.subjectScreenNo().equals(request.subjectScreenNo())) {
            throw new ConflictException("requestId bound to a different trial/subject: " + request.requestId());
        }
        assertAuthorized(request.confirmerUserId());
        if (request.confirmerUserId().equals(pending.confirmerA())) {
            throw new BusinessRuleException(
                    "second confirmer must be a different authorized user from the first confirmer");
        }

        // 条件推进 PENDING→COMPLETED：并发下仅一个第二步生效。
        int updated = repository.completeUnblinding(request.requestId(),
                request.confirmerUserId(), now, now);
        if (updated == 0) {
            UnblindingRequestRow winner =
                    repository.findUnblindingByRequestId(request.requestId()).orElseThrow();
            return toCompletedResponse(winner, true);
        }

        SubjectRow subject = repository.findSubject(request.trialId(), request.subjectScreenNo()).orElseThrow();
        repository.markSubjectUnblinded(request.trialId(), request.subjectScreenNo(), now);

        // 后续访视：以揭盲当日 UTC 0 点为日界，当日及之后访视排除出主要分析集。
        Instant utcDayBoundary = ZonedDateTime.ofInstant(now, ZoneOffset.UTC)
                .toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant();
        repository.markVisitsExcludedSince(request.trialId(), request.subjectScreenNo(),
                utcDayBoundary, EXCLUSION_REASON);

        repository.insertAuditEvent(request.trialId(), request.subjectScreenNo(), request.requestId(),
                "SECOND_CONFIRMED", request.confirmerUserId(),
                "第二名授权人确认应急揭盲，两名不同授权人确认齐备", now);
        repository.insertAuditEvent(request.trialId(), request.subjectScreenNo(), request.requestId(),
                "UNBLINDED", request.confirmerUserId(),
                "揭盲生效：受试者标记已揭盲，UTC 日界 " + utcDayBoundary
                        + " 及之后访视排除出主要分析集", now);

        UnblindingRequestRow completed = new UnblindingRequestRow(pending.id(), pending.requestId(),
                pending.trialId(), pending.subjectScreenNo(), UnblindingStatus.COMPLETED.name(),
                pending.confirmerA(), pending.confirmedAAtUtc(), request.confirmerUserId(), now, now,
                pending.createdAtUtc());
        return toCompletedResponse(completed, false);
    }

    @Override
    public UnblindingResponse getUnblindingStatus(String trialId, String subjectScreenNo) {
        SubjectRow subject = repository.findSubject(trialId, subjectScreenNo)
                .orElseThrow(() -> new NotFoundException("subject not found: "
                        + trialId + "/" + subjectScreenNo));
        UnblindingRequestRow row = repository.findUnblindingForSubject(trialId, subjectScreenNo)
                .orElseThrow(() -> new NotFoundException("no unblinding request for subject: "
                        + trialId + "/" + subjectScreenNo));
        if (row.status().equals(UnblindingStatus.COMPLETED.name())) {
            return toCompletedResponse(row, false);
        }
        return new UnblindingResponse(row.requestId(), row.trialId(), row.subjectScreenNo(),
                subject.randomizationNo(), row.status(), row.confirmerA(), null, null, false);
    }

    @Override
    public List<AuditEventResponse> listAuditEvents(String trialId, String subjectScreenNo) {
        return repository.listAuditEvents(trialId, subjectScreenNo).stream()
                .map(TrialServiceImpl::toAuditResponse)
                .toList();
    }

    @Override
    @Transactional
    public VisitResponse registerVisit(String trialId, String subjectScreenNo, VisitRequest request) {
        SubjectRow subject = repository.findSubject(trialId, subjectScreenNo)
                .orElseThrow(() -> new NotFoundException("subject not found: "
                        + trialId + "/" + subjectScreenNo));

        // 已揭盲受试者：访视时刻落在揭盲当日 UTC 0 点及之后 → 排除出主要分析集。
        boolean excluded = false;
        String reason = null;
        if (subject.status().equals(SubjectStatus.UNBLINDED.name()) && subject.unblindedAtUtc() != null) {
            Instant boundary = ZonedDateTime.ofInstant(subject.unblindedAtUtc(), ZoneOffset.UTC)
                    .toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant();
            if (!request.visitAtUtc().isBefore(boundary)) {
                excluded = true;
                reason = EXCLUSION_REASON;
            }
        }

        Instant now = clock.get();
        repository.insertVisit(new VisitRow(0L, trialId, subjectScreenNo, request.visitAtUtc(),
                request.visitType(), request.measureValue(), excluded, reason, now));
        return new VisitResponse(request.visitAtUtc(), request.visitType(),
                request.measureValue(), excluded, reason);
    }

    @Override
    public List<VisitResponse> listVisits(String trialId, String subjectScreenNo) {
        return repository.listVisits(trialId, subjectScreenNo).stream()
                .map(v -> new VisitResponse(v.visitAtUtc(), v.visitType(), v.measureValue(),
                        v.excludedFromMainAnalysis(), v.exclusionReason()))
                .toList();
    }

    private void assertAuthorized(String userId) {
        if (!repository.isAuthorizedUserEnabled(userId)) {
            throw new ForbiddenException("user is not an authorized confirmer: " + userId);
        }
    }

    private UnblindingResponse toCompletedResponse(UnblindingRequestRow row, boolean replayed) {
        SubjectRow subject = repository.findSubject(row.trialId(), row.subjectScreenNo()).orElseThrow();
        return new UnblindingResponse(row.requestId(), row.trialId(), row.subjectScreenNo(),
                subject.randomizationNo(), UnblindingStatus.COMPLETED.name(),
                row.confirmerA(), row.confirmerB(), subject.assignedGroup(), replayed);
    }

    private static AuditEventResponse toAuditResponse(AuditEventRow row) {
        return new AuditEventResponse(row.id(), row.trialId(), row.subjectScreenNo(), row.requestId(),
                row.eventType(), row.actorUserId(), row.detail(), row.createdAtUtc());
    }

    /**
     * 幂等指纹：覆盖 requestId、筛选号、中心与同意签署时刻（UTC 瞬时值）。
     */
    private static String fingerprint(EnrollmentRequest r) {
        String raw = String.join("|",
                r.requestId(), r.trialId(), r.subjectScreenNo(), r.siteCode(),
                r.consentAtUtc().toString());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
