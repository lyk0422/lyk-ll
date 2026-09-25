package com.example.starter.trial.domain;

import com.example.starter.trial.api.dto.EnrollRequest;
import com.example.starter.trial.api.dto.EnrollResponse;
import com.example.starter.trial.domain.EnrollmentRepository.EnrollmentRow;
import com.example.starter.trial.domain.CenterAllocationRepository.CenterState;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 随机入组领域服务。
 *
 * <p>关键顺序（全部在同一入组事务内，发号除外）：
 * <ol>
 *   <li>按 requestId 幂等判定：同指纹返回首次结果，异指纹 409；</li>
 *   <li>SELECT ... FOR UPDATE 锁定中心状态行，使同中心并发串行；</li>
 *   <li>锁内复查筛选号唯一，重复入组在发号前直接 409（不发新号）；</li>
 *   <li>随机号由独立事务发放并立即提交，外层回滚也不重用；</li>
 *   <li>按区组排列落组并推进中心状态；插入受试者记录。</li>
 * </ol>
 */
@Service
public class EnrollmentService {

    private final EnrollmentRepository enrollmentRepository;
    private final CenterAllocationRepository centerAllocationRepository;
    private final RandomNumberLedger randomNumberLedger;
    private final TimeProvider timeProvider;

    public EnrollmentService(EnrollmentRepository enrollmentRepository,
                             CenterAllocationRepository centerAllocationRepository,
                             RandomNumberLedger randomNumberLedger,
                             TimeProvider timeProvider) {
        this.enrollmentRepository = enrollmentRepository;
        this.centerAllocationRepository = centerAllocationRepository;
        this.randomNumberLedger = randomNumberLedger;
        this.timeProvider = timeProvider;
    }

    @Transactional
    public EnrollResponse enroll(EnrollRequest request) {
        String trialCode = TrialConstants.DEFAULT_TRIAL;
        String fingerprint = Fingerprints.of(trialCode, request.requestId(), request.screeningNo(),
                request.centerCode(), request.consentTimeUtc());

        var existingByRequest = enrollmentRepository.findByRequestId(trialCode, request.requestId());
        if (existingByRequest.isPresent()) {
            EnrollmentRow first = existingByRequest.get();
            if (!first.fingerprint().equals(fingerprint)) {
                throw new ConflictException(
                        "requestId 已用于不同入组参数，拒绝且不产生新随机号: " + request.requestId());
            }
            return toBlindedResponse(first);
        }

        CenterState state = centerAllocationRepository.lockForUpdate(trialCode, request.centerCode())
                .orElseThrow(() -> new BusinessRuleException("中心不存在: " + request.centerCode()));

        var existingByScreening = enrollmentRepository.findByScreeningNo(trialCode, request.screeningNo());
        if (existingByScreening.isPresent()) {
            throw new ConflictException(
                    "筛选号已在本试验入组，同一筛选号只能入组一次: " + request.screeningNo());
        }

        long randomNo = randomNumberLedger.issue(trialCode, request.centerCode());

        int allocated = state.allocatedInBlock();
        long blockNo = state.currentBlockNo();
        String pattern = state.currentBlockPattern();
        String prevPattern = state.prevBlockPattern();
        if (allocated == 0) {
            pattern = BlockAllocator.choosePattern(state.randomSeed(), blockNo, prevPattern);
        }
        String groupCode = BlockAllocator.groupAt(pattern, allocated);
        String blindedCode = groupCode.equals(state.maskAGroup())
                ? TrialConstants.BLIND_A : TrialConstants.BLIND_B;

        Instant now = timeProvider.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        int newAllocated = allocated + 1;
        if (newAllocated == TrialConstants.BLOCK_SIZE) {
            centerAllocationRepository.advance(trialCode, request.centerCode(),
                    blockNo + 1, 0, null, pattern, now);
        } else {
            centerAllocationRepository.advance(trialCode, request.centerCode(),
                    blockNo, newAllocated, pattern, prevPattern, now);
        }

        EnrollmentRow row = new EnrollmentRow(
                0L, trialCode, request.centerCode(), request.screeningNo(), request.requestId(),
                request.consentTimeUtc(), randomNo, blockNo, allocated, groupCode, blindedCode,
                TrialConstants.STATUS_ENROLLED, fingerprint, now, null);
        try {
            enrollmentRepository.insert(row);
        } catch (DuplicateKeyException ex) {
            // 唯一约束兜底（极端并发）：事务回滚，随机号已独立提交、绝不重用。
            throw new ConflictException("入组冲突：筛选号或 requestId 已存在 " + request.screeningNo());
        }

        return new EnrollResponse(trialCode, request.centerCode(), request.screeningNo(),
                randomNo, blindedCode, TrialConstants.STATUS_ENROLLED);
    }

    private EnrollResponse toBlindedResponse(EnrollmentRow row) {
        return new EnrollResponse(row.trialCode(), row.centerCode(), row.screeningNo(),
                row.randomNo(), row.blindedCode(), row.status());
    }
}
