package com.example.starter.enrollment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.starter.audit.AuditRepository;
import com.example.starter.common.ApiException;

/**
 * 随机入组服务：按中心分层、区组长度 4（2:2）分配；requestId 幂等；
 * 随机号由独立事务发放，失败或回滚不重发。
 */
@Service
public class EnrollmentService {

    static final int BLOCK_SIZE = 4;

    private final EnrollmentRepository repository;
    private final RandomNumberAllocator allocator;
    private final AuditRepository auditRepository;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    public EnrollmentService(EnrollmentRepository repository, RandomNumberAllocator allocator,
            AuditRepository auditRepository, TransactionTemplate transactionTemplate, Clock clock) {
        this.repository = repository;
        this.allocator = allocator;
        this.auditRepository = auditRepository;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    /**
     * 入组：同 requestId 同参数重放返回首次结果；同 requestId 异参数返回 409；
     * 同一筛选号仅可入组一次。并发唯一约束冲突时在事务外重读裁决。
     */
    public EnrollmentResponse enroll(String trialCode, EnrollmentRequest request) {
        try {
            return transactionTemplate.execute(status -> doEnroll(trialCode, request));
        } catch (DuplicateKeyException e) {
            return resolveAfterDuplicate(trialCode, request);
        }
    }

    /**
     * 盲态查询：仅返回盲态组别编码；已揭盲受试者附带实际组别。
     */
    public EnrollmentView findBlindedView(String trialCode, String screeningNo) {
        Trial trial = requireTrial(trialCode);
        Enrollment enrollment = repository.findByScreening(trial.id(), screeningNo);
        if (enrollment == null) {
            throw ApiException.notFound("SUBJECT_NOT_FOUND", "受试者未入组: " + screeningNo);
        }
        return new EnrollmentView(
                enrollment.screeningNo(),
                enrollment.randomNo(),
                String.valueOf(enrollment.blindedArm()),
                enrollment.status(),
                enrollment.isUnblinded(),
                enrollment.isUnblinded() ? String.valueOf(enrollment.actualArm()) : null);
    }

    private EnrollmentResponse doEnroll(String trialCode, EnrollmentRequest request) {
        Trial trial = requireTrial(trialCode);
        Center center = repository.findCenterByCode(request.centerCode());
        if (center == null) {
            throw ApiException.notFound("CENTER_NOT_FOUND", "中心不存在: " + request.centerCode());
        }
        String fingerprint = fingerprint(request);

        Enrollment existing = repository.findByRequestId(trial.id(), request.requestId());
        if (existing != null) {
            if (existing.fingerprint().equals(fingerprint)) {
                return toResponse(existing, trial, request.centerCode(), true);
            }
            throw ApiException.conflict("REQUEST_ID_CONFLICT",
                    "requestId 已使用且请求参数不一致: " + request.requestId());
        }
        if (repository.findByScreening(trial.id(), request.screeningNo()) != null) {
            throw ApiException.conflict("SUBJECT_ALREADY_ENROLLED",
                    "筛选号已入组: " + request.screeningNo());
        }

        long randomNo = allocator.allocate(trial.id(), center.id());
        repository.lockCounter(trial.id(), center.id());
        char actualArm = blockArm(trial.id(), center.id(), randomNo);

        Instant now = Instant.now(clock);
        Enrollment enrollment = new Enrollment(0L, trial.id(), request.requestId(), fingerprint,
                request.screeningNo(), center.id(), request.consentSignedAt(), randomNo,
                trial.blindedCodeOf(actualArm), actualArm, "ENROLLED", now);
        repository.insert(enrollment);
        auditRepository.append(trial.id(), request.screeningNo(), "ENROLLED", null,
                "randomNo=" + randomNo + ", armCode=" + enrollment.blindedArm(), now);
        return toResponse(enrollment, trial, request.centerCode(), false);
    }

    /**
     * 唯一约束冲突后的裁决（新事务只读）：同键同参重放、同键异参 409、同筛选号 409。
     */
    private EnrollmentResponse resolveAfterDuplicate(String trialCode, EnrollmentRequest request) {
        Trial trial = requireTrial(trialCode);
        String fingerprint = fingerprint(request);
        Enrollment byRequest = repository.findByRequestId(trial.id(), request.requestId());
        if (byRequest != null) {
            if (byRequest.fingerprint().equals(fingerprint)) {
                return toResponse(byRequest, trial, request.centerCode(), true);
            }
            throw ApiException.conflict("REQUEST_ID_CONFLICT",
                    "requestId 已使用且请求参数不一致: " + request.requestId());
        }
        if (repository.findByScreening(trial.id(), request.screeningNo()) != null) {
            throw ApiException.conflict("SUBJECT_ALREADY_ENROLLED",
                    "筛选号已入组: " + request.screeningNo());
        }
        throw ApiException.conflict("ENROLLMENT_CONFLICT", "入组冲突，请重试");
    }

    /**
     * 按随机号定位区组位置并返回实际组别；区组计划不存在时懒生成。
     */
    private char blockArm(long trialId, long centerId, long randomNo) {
        long blockNo = (randomNo - 1) / BLOCK_SIZE;
        int position = (int) ((randomNo - 1) % BLOCK_SIZE);
        String arm = repository.findBlockArm(trialId, centerId, blockNo, position);
        if (arm == null) {
            generateBlock(trialId, centerId, blockNo);
            arm = repository.findBlockArm(trialId, centerId, blockNo, position);
        }
        return arm.charAt(0);
    }

    /**
     * 生成一个区组的分配计划：2:2 随机排列；若与上一区组末尾两例连成 4 例同组则确定性修正。
     * 并发生成由主键冲突兜底，冲突后重读已有计划。
     */
    private void generateBlock(long trialId, long centerId, long blockNo) {
        List<Character> arms = new ArrayList<>(List.of('T', 'T', 'C', 'C'));
        Collections.shuffle(arms, secureRandom);
        List<String> previous = blockNo > 0
                ? repository.blockArms(trialId, centerId, blockNo - 1)
                : List.of();
        if (previous.size() == BLOCK_SIZE) {
            char tail = previous.get(BLOCK_SIZE - 1).charAt(0);
            boolean previousEndsWithPair = previous.get(BLOCK_SIZE - 2).charAt(0) == tail;
            if (previousEndsWithPair && arms.get(0) == tail && arms.get(1) == tail) {
                // 交换第 2 位与首个不同臂，避免跨区组连续 4 例同组
                arms.set(1, arms.get(2));
                arms.set(2, tail);
            }
        }
        try {
            repository.insertBlock(trialId, centerId, blockNo, arms);
        } catch (DuplicateKeyException e) {
            // 并发事务已生成该区组计划，重读即可
        }
    }

    private EnrollmentResponse toResponse(Enrollment e, Trial trial, String centerCode, boolean replayed) {
        return new EnrollmentResponse(e.requestId(), e.screeningNo(), centerCode, e.randomNo(),
                String.valueOf(e.blindedArm()), e.status(), e.createdAt(), replayed);
    }

    private Trial requireTrial(String trialCode) {
        Trial trial = repository.findTrialByCode(trialCode);
        if (trial == null) {
            throw ApiException.notFound("TRIAL_NOT_FOUND", "试验不存在: " + trialCode);
        }
        return trial;
    }

    /**
     * 幂等指纹：requestId、筛选号、中心与同意时刻（UTC）的 SHA-256。
     */
    static String fingerprint(EnrollmentRequest request) {
        String material = String.join("|", request.requestId(), request.screeningNo(),
                request.centerCode(), request.consentSignedAt().toString());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
