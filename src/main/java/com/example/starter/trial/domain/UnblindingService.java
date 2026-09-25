package com.example.starter.trial.domain;

import com.example.starter.trial.api.dto.UnblindRequest;
import com.example.starter.trial.api.dto.UnblindResponse;
import com.example.starter.trial.domain.EnrollmentRepository.EnrollmentRow;
import com.example.starter.trial.domain.UnblindingRepository.UnblindEventRow;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * 应急揭盲领域服务。
 *
 * <p>规则：
 * <ul>
 *   <li>两名确认人必须都是授权人且互不相同，否则整笔事务回滚、单人确认不生效；</li>
 *   <li>两次确认在同一事务内先后发生，各自写入审计事件后揭盲才生效；</li>
 *   <li>生效时受试者置 UNBLINDED 终态，UTC 日界不早于当日的后续访视全部排除出主要分析集；</li>
 *   <li>揭盲为终态：同 requestId 或同受试者重复请求返回首次结果，不新增事件；</li>
 *   <li>已揭盲受试者不得再参与分配相关修改。</li>
 * </ul>
 */
@Service
public class UnblindingService {

    private final EnrollmentRepository enrollmentRepository;
    private final UnblindingRepository unblindingRepository;
    private final VisitRepository visitRepository;
    private final TimeProvider timeProvider;

    public UnblindingService(EnrollmentRepository enrollmentRepository,
                             UnblindingRepository unblindingRepository,
                             VisitRepository visitRepository,
                             TimeProvider timeProvider) {
        this.enrollmentRepository = enrollmentRepository;
        this.unblindingRepository = unblindingRepository;
        this.visitRepository = visitRepository;
        this.timeProvider = timeProvider;
    }

    @Transactional
    public UnblindResponse unblind(String screeningNo, UnblindRequest request) {
        String trialCode = TrialConstants.DEFAULT_TRIAL;

        // 终态幂等：同 requestId 重放直接返回首次结果，不新增事件。
        var byRequest = unblindingRepository.findByRequestId(trialCode, request.requestId());
        if (byRequest.isPresent()) {
            UnblindEventRow event = byRequest.get();
            if (!event.screeningNo().equals(screeningNo)) {
                throw new ConflictException("requestId 已用于其他受试者的揭盲: " + request.requestId());
            }
            return responseOf(event, enrollmentRepository
                    .findByScreeningNo(trialCode, screeningNo)
                    .orElseThrow(() -> new NotFoundException("受试者不存在: " + screeningNo)));
        }
        var byScreening = unblindingRepository.findByScreeningNo(trialCode, screeningNo);
        if (byScreening.isPresent()) {
            UnblindEventRow event = byScreening.get();
            return responseOf(event, enrollmentRepository
                    .findByScreeningNo(trialCode, screeningNo)
                    .orElseThrow(() -> new NotFoundException("受试者不存在: " + screeningNo)));
        }

        validateConfirmers(request);

        // 锁定受试者行，串行化同一受试者的并发揭盲。
        EnrollmentRow subject = enrollmentRepository.lockByScreeningNo(trialCode, screeningNo)
                .orElseThrow(() -> new NotFoundException("受试者不存在: " + screeningNo));
        if (TrialConstants.STATUS_UNBLINDED.equals(subject.status())) {
            // 锁内复查：并发下另一事务已完成揭盲，返回其首次结果，不新增事件。
            UnblindEventRow event = unblindingRepository.findByScreeningNo(trialCode, screeningNo)
                    .orElseThrow(() -> new IllegalStateException("终态受试者缺少揭盲事件"));
            return responseOf(event, subject);
        }

        Instant now = timeProvider.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);

        // 两名授权人在同一事务内先后确认；任一前置校验失败则此前所有写入随事务回滚。
        unblindingRepository.insertAudit(trialCode, screeningNo,
                TrialConstants.EVENT_FIRST_CONFIRMED, now, request.requestId(),
                request.firstConfirmer(), "应急揭盲第一名授权人确认");
        Instant secondTime = timeProvider.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        unblindingRepository.insertAudit(trialCode, screeningNo,
                TrialConstants.EVENT_SECOND_CONFIRMED, secondTime, request.requestId(),
                request.secondConfirmer(), "应急揭盲第二名授权人确认");

        Instant effectiveAt = timeProvider.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        int marked = enrollmentRepository.markUnblinded(trialCode, screeningNo, effectiveAt);
        if (marked == 0) {
            throw new ConflictException("受试者状态已变化，揭盲被拒绝: " + screeningNo);
        }

        LocalDate unblindUtcDay = LocalDate.ofInstant(effectiveAt, ZoneOffset.UTC);
        int excludedVisits = visitRepository.markExcludedOnOrAfterUtcDay(
                trialCode, screeningNo, unblindUtcDay);

        try {
            unblindingRepository.insertEvent(new UnblindEventRow(
                    0L, trialCode, screeningNo, request.requestId(), "EFFECTIVE",
                    request.firstConfirmer(), request.secondConfirmer(), effectiveAt, effectiveAt));
        } catch (DuplicateKeyException ex) {
            throw new ConflictException("揭盲请求冲突（requestId 或受试者已揭盲）: " + screeningNo);
        }

        unblindingRepository.insertAudit(trialCode, screeningNo,
                TrialConstants.EVENT_EFFECTIVE, effectiveAt, request.requestId(),
                request.secondConfirmer(),
                "揭盲生效，标记后续访视 " + excludedVisits + " 条排除出主要分析集");

        return new UnblindResponse(request.requestId(), trialCode, screeningNo,
                subject.groupCode(), subject.blindedCode(), TrialConstants.STATUS_UNBLINDED,
                request.firstConfirmer(), request.secondConfirmer(), effectiveAt);
    }

    private void validateConfirmers(UnblindRequest request) {
        if (!unblindingRepository.isAuthorized(request.firstConfirmer())) {
            throw new BusinessRuleException("第一名确认人不是授权揭盲人员: " + request.firstConfirmer());
        }
        if (!unblindingRepository.isAuthorized(request.secondConfirmer())) {
            throw new BusinessRuleException("第二名确认人不是授权揭盲人员: " + request.secondConfirmer());
        }
        if (request.firstConfirmer().equals(request.secondConfirmer())) {
            throw new BusinessRuleException("应急揭盲须两名不同授权人确认，不能为同一人");
        }
    }

    private UnblindResponse responseOf(UnblindEventRow event, EnrollmentRow subject) {
        return new UnblindResponse(event.requestId(), event.trialCode(), event.screeningNo(),
                subject.groupCode(), subject.blindedCode(), TrialConstants.STATUS_UNBLINDED,
                event.firstConfirmer(), event.secondConfirmer(), event.effectiveAtUtc());
    }
}
