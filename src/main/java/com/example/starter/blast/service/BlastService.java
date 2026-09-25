package com.example.starter.blast.service;

import com.example.starter.blast.api.ApplyRequest;
import com.example.starter.blast.api.ApprovalRequest;
import com.example.starter.blast.api.BlastBusinessException;
import com.example.starter.blast.api.EvaluationRequest;
import com.example.starter.blast.api.EvaluationResponse;
import com.example.starter.blast.api.FilingRequest;
import com.example.starter.blast.api.ApplicationResponse;
import com.example.starter.blast.api.MineResponse;
import com.example.starter.blast.api.ReadingResult;
import com.example.starter.blast.api.RejectionResponse;
import com.example.starter.blast.api.ServiceResult;
import com.example.starter.blast.api.UnfreezeReviewRequest;
import com.example.starter.blast.api.VibrationReadingRequest;
import com.example.starter.blast.domain.ApplicationStatus;
import com.example.starter.blast.domain.BlastApplication;
import com.example.starter.blast.domain.BlastZone;
import com.example.starter.blast.domain.BizType;
import com.example.starter.blast.domain.Mine;
import com.example.starter.blast.domain.MonitoringPoint;
import com.example.starter.blast.domain.VibrationReading;
import com.example.starter.blast.repo.ApplicationRepository;
import com.example.starter.blast.repo.MonitoringRepository;
import com.example.starter.blast.repo.MonitoringRepository.EvaluationRow;
import com.example.starter.blast.repo.MonitoringRepository.FreezeEventRow;
import com.example.starter.blast.repo.MonitoringRepository.UnfreezeReviewRow;
import com.example.starter.blast.repo.ReferenceDataRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

/**
 * 爆破作业审批与振动监测联动核心服务。
 * 流转：申请→审批→起爆备案→振动回传→效果评估；超限自动冻结、监管复核解冻。
 */
@Service
public class BlastService {

    /** 申请被冻结矿区拒收、冻结作业备案被拒等锁定语义状态码。 */
    private static final int LOCKED = 423;
    /** 时段/药量等业务规则直接拒绝的状态码。 */
    private static final int UNPROCESSABLE = 422;

    private static final LocalTime WINDOW_START = LocalTime.of(6, 0);
    private static final LocalTime WINDOW_END = LocalTime.of(22, 0);

    private final ApplicationRepository applicationRepository;
    private final ReferenceDataRepository referenceDataRepository;
    private final MonitoringRepository monitoringRepository;
    private final IdempotencyTemplate idempotencyTemplate;
    private final Clock clock;

    public BlastService(ApplicationRepository applicationRepository,
                        ReferenceDataRepository referenceDataRepository,
                        MonitoringRepository monitoringRepository,
                        IdempotencyTemplate idempotencyTemplate,
                        Clock clock) {
        this.applicationRepository = applicationRepository;
        this.referenceDataRepository = referenceDataRepository;
        this.monitoringRepository = monitoringRepository;
        this.idempotencyTemplate = idempotencyTemplate;
        this.clock = clock;
    }

    /**
     * 提交申请（requestId 幂等）。业务性拒绝（冻结、超药量、超时段）同样缓存首次结果供同键重放。
     */
    public ServiceResult<Object> apply(ApplyRequest request) {
        return idempotencyTemplate.perform(request.requestId(), BizType.APPLY, request, Object.class,
                () -> doApply(request));
    }

    private IdempotencyTemplate.Outcome<Object> doApply(ApplyRequest request) {
        Instant now = clock.instant();
        Mine mine = referenceDataRepository.findMine(request.mineCode())
                .orElseThrow(() -> new BlastBusinessException(404, "矿区不存在: " + request.mineCode()));
        BlastZone zone = referenceDataRepository.findZone(request.zoneCode())
                .orElseThrow(() -> new BlastBusinessException(404, "爆区不存在: " + request.zoneCode()));
        if (!zone.mineCode().equals(mine.mineCode())) {
            throw new BlastBusinessException(400,
                    "爆区 " + zone.zoneCode() + " 不属于矿区 " + mine.mineCode());
        }
        if (mine.frozen()) {
            return reject(LOCKED, "矿区处于冻结状态，暂停受理新申请");
        }
        if (request.chargePerDelayKg().compareTo(zone.maxChargePerDelayKg()) > 0) {
            return reject(UNPROCESSABLE,
                    "单段药量 " + request.chargePerDelayKg() + "kg 超过爆区备案上限 "
                            + zone.maxChargePerDelayKg() + "kg，直接拒绝");
        }
        if (!withinAllowedWindow(request.plannedTime(), mine.timeZone())) {
            return reject(UNPROCESSABLE,
                    "计划起爆时刻 " + request.plannedTime()
                            + " 不在允许作业时段（当地 06:00 含至 22:00 不含）");
        }

        BlastApplication application = new BlastApplication(
                null, request.requestId(), request.mineCode(), request.zoneCode(), request.applicant(),
                request.plannedTime(), request.chargePerDelayKg(), ApplicationStatus.SUBMITTED,
                null, null, now, now);
        long id;
        try {
            id = applicationRepository.insert(application);
        } catch (DuplicateKeyException ex) {
            // 并发同 requestId：胜者已建单，重放其首次结果。
            BlastApplication winner = applicationRepository.findByRequestId(request.requestId())
                    .orElseThrow(() -> new BlastBusinessException(409, "并发幂等处理冲突，请重试"));
            return IdempotencyTemplate.Outcome.of(201, toResponse(winner));
        }
        return IdempotencyTemplate.Outcome.of(201, toResponse(
                applicationRepository.findById(id).orElseThrow()));
    }

    /**
     * 审批：仅 SUBMITTED 可审批；批准受“同爆区同时刻唯一已批准作业”约束，并发只有一个获批。
     * 审批不以 requestId 幂等，重复审批按当前状态返回 409，不改变流程状态。
     */
    @Transactional
    public ApplicationResponse approve(long applicationId, ApprovalRequest request) {
        Instant now = clock.instant();
        BlastApplication application = getApplicationOr404(applicationId);
        Mine mine = referenceDataRepository.findMine(application.mineCode())
                .orElseThrow(() -> new BlastBusinessException(404, "矿区不存在: " + application.mineCode()));

        if (Boolean.FALSE.equals(request.approved())) {
            String reason = request.reason() == null || request.reason().isBlank()
                    ? "审批驳回" : request.reason();
            if (!applicationRepository.compareAndSetStatus(applicationId, ApplicationStatus.SUBMITTED,
                    ApplicationStatus.REJECTED, reason, null, now)) {
                throw new BlastBusinessException(409,
                        "作业当前状态 " + application.status() + "，不可驳回");
            }
            return toResponse(getApplicationOr404(applicationId));
        }

        if (mine.frozen()) {
            throw new BlastBusinessException(LOCKED, "矿区处于冻结状态，不能批准作业");
        }
        if (!applicationRepository.compareAndSetStatus(applicationId, ApplicationStatus.SUBMITTED,
                ApplicationStatus.APPROVED, null, null, now)) {
            if (application.status() != ApplicationStatus.SUBMITTED) {
                throw new BlastBusinessException(409,
                        "作业当前状态 " + application.status() + "，不可批准");
            }
            throw new BlastBusinessException(409, "同一爆区同一计划起爆时刻只允许一个已批准作业");
        }
        // 复查：若审批期间矿区被超限读数冻结，回滚本次批准，作业留待冻结批量处置驳回。
        boolean frozenNow = referenceDataRepository.findMine(application.mineCode())
                .map(Mine::frozen).orElse(false);
        if (frozenNow) {
            throw new BlastBusinessException(LOCKED, "矿区已冻结，审批回滚，作业由冻结流程处置");
        }
        return toResponse(getApplicationOr404(applicationId));
    }

    /**
     * 起爆备案（requestId 幂等）：仅已批准未起爆作业可备案，未审批/冻结作废一律拒绝并缓存首次结果。
     */
    public ServiceResult<Object> filing(FilingRequest request) {
        return idempotencyTemplate.perform(request.requestId(), BizType.FILING,
                List.of(request.applicationId(), request.detonateTime()), Object.class,
                () -> doFiling(request));
    }

    private IdempotencyTemplate.Outcome<Object> doFiling(FilingRequest request) {
        Instant now = clock.instant();
        BlastApplication application = getApplicationOr404(request.applicationId());
        if (application.status() == ApplicationStatus.APPROVED) {
            Mine mine = referenceDataRepository.findMine(application.mineCode())
                    .orElseThrow(() -> new BlastBusinessException(404, "矿区不存在: " + application.mineCode()));
            if (mine.frozen()) {
                return reject(LOCKED, "矿区已冻结，已批准未起爆作业已作废，不能起爆备案");
            }
        }
        if (application.status() != ApplicationStatus.APPROVED) {
            return reject(409,
                    "作业当前状态 " + application.status() + "，未审批通过不得起爆备案");
        }
        if (!applicationRepository.compareAndSetStatus(application.id(), ApplicationStatus.APPROVED,
                ApplicationStatus.FILED, null, request.detonateTime(), now)) {
            throw new BlastBusinessException(409, "作业备案状态变更失败，请刷新后重试");
        }
        return IdempotencyTemplate.Outcome.of(200, toResponse(getApplicationOr404(application.id())));
    }

    /**
     * 振动读数回传：爆区＋测点＋采样时刻去重；任一测点超限自动冻结矿区，
     * 同时驳回在途申请、作废已批准未起爆作业，FILED/ARCHIVED 等冻结前历史不受影响。
     */
    @Transactional
    public ReadingResult submitReading(VibrationReadingRequest request) {
        Instant now = clock.instant();
        BlastApplication application = getApplicationOr404(request.applicationId());
        if (!application.zoneCode().equals(request.zoneCode())) {
            throw new BlastBusinessException(400,
                    "读数爆区与作业备案爆区不一致: " + request.zoneCode() + " != " + application.zoneCode());
        }
        MonitoringPoint point = referenceDataRepository.findPoint(request.zoneCode(), request.pointCode())
                .orElseThrow(() -> new BlastBusinessException(404,
                        "测点未备案: " + request.zoneCode() + "/" + request.pointCode()));
        boolean overLimit = request.velocityMmS().compareTo(point.velocityLimitMmS()) > 0;

        if (application.status() == ApplicationStatus.ARCHIVED) {
            // 封档后的迟到读数：只记迟到事件，不触发冻结、不改变评估结论。
            VibrationReading late = new VibrationReading(
                    null, application.id(), request.zoneCode(), request.pointCode(),
                    request.sampleTime(), request.velocityMmS(), overLimit, now);
            boolean recorded = monitoringRepository.insertLateEvent(late, now);
            return new ReadingResult(recorded, !recorded, true, overLimit, false, request.sampleTime());
        }
        if (application.status() != ApplicationStatus.FILED) {
            throw new BlastBusinessException(409,
                    "仅已起爆备案的作业可回传振动读数，当前状态 " + application.status());
        }

        VibrationReading reading = new VibrationReading(
                null, application.id(), request.zoneCode(), request.pointCode(),
                request.sampleTime(), request.velocityMmS(), overLimit, now);
        var inserted = monitoringRepository.insertReading(reading);
        if (inserted.isEmpty()) {
            boolean frozenNow = referenceDataRepository.findMine(application.mineCode())
                    .map(Mine::frozen).orElse(false);
            return new ReadingResult(false, true, false, overLimit, frozenNow, request.sampleTime());
        }

        boolean frozenAfter = false;
        if (overLimit) {
            String reason = "测点 " + point.pointCode() + " 振速 " + request.velocityMmS()
                    + "mm/s 超过阈值 " + point.velocityLimitMmS() + "mm/s，自动冻结";
            frozenAfter = triggerFreeze(application, point, request, reason, now);
        }
        if (!frozenAfter) {
            frozenAfter = referenceDataRepository.findMine(application.mineCode())
                    .map(Mine::frozen).orElse(false);
        }
        return new ReadingResult(true, false, false, overLimit, frozenAfter, request.sampleTime());
    }

    /**
     * 条件翻转冻结并落冻结事件、批量处置在途/已批准作业；返回是否由本次调用完成翻转。
     */
    private boolean triggerFreeze(BlastApplication application, MonitoringPoint point,
                                  VibrationReadingRequest request, String reason, Instant now) {
        boolean flipped = referenceDataRepository.freezeIfActive(application.mineCode(), reason, now);
        if (!flipped) {
            return false;
        }
        applicationRepository.bulkStatusChange(application.mineCode(),
                ApplicationStatus.SUBMITTED, ApplicationStatus.REJECTED,
                "矿区冻结，在途申请一律驳回: " + reason, now);
        applicationRepository.bulkStatusChange(application.mineCode(),
                ApplicationStatus.APPROVED, ApplicationStatus.VOIDED,
                "矿区冻结，已批准未起爆作业作废: " + reason, now);
        monitoringRepository.insertFreezeEvent(new FreezeEventRow(
                application.mineCode(), application.id(), application.zoneCode(), point.pointCode(),
                request.sampleTime(), request.velocityMmS(), point.velocityLimitMmS(), now));
        return true;
    }

    /**
     * 监管复核解冻（requestId 幂等）：confirmed=true 才受理；未冻结的首次结果为 409 并缓存。
     */
    public ServiceResult<Object> unfreeze(String mineCode, UnfreezeReviewRequest request) {
        return idempotencyTemplate.perform(request.requestId(), BizType.REVIEW,
                List.of(mineCode, request.reviewer(), request.confirmed()), Object.class,
                () -> doUnfreeze(mineCode, request));
    }

    private IdempotencyTemplate.Outcome<Object> doUnfreeze(String mineCode, UnfreezeReviewRequest request) {
        Instant now = clock.instant();
        Mine mine = referenceDataRepository.findMine(mineCode)
                .orElseThrow(() -> new BlastBusinessException(404, "矿区不存在: " + mineCode));
        if (!mine.frozen()) {
            return reject(409, "矿区未处于冻结状态，无需解冻");
        }
        boolean reviewLogged = monitoringRepository.insertUnfreezeReview(new UnfreezeReviewRow(
                mineCode, request.requestId(), request.reviewer(),
                Boolean.TRUE.equals(request.confirmed()), now));
        if (!reviewLogged) {
            throw new BlastBusinessException(409, "复核请求并发冲突，请按首次结果重放");
        }
        if (!referenceDataRepository.unfreezeIfFrozen(mineCode, request.reviewer(), now)) {
            throw new BlastBusinessException(409, "矿区解冻状态变更失败，请刷新后重试");
        }
        Mine refreshed = referenceDataRepository.findMine(mineCode).orElseThrow();
        return IdempotencyTemplate.Outcome.of(200, toMineResponse(refreshed));
    }

    /**
     * 效果评估封档（requestId 幂等）：振动读数覆盖全部备案测点方可提交；
     * 未齐全的首次结果为 409 并缓存；终态重复封档幂等成功，不改变结论。
     */
    public ServiceResult<Object> evaluate(EvaluationRequest request) {
        return idempotencyTemplate.perform(request.requestId(), BizType.EVALUATION,
                List.of(request.applicationId(), request.evaluator(), request.conclusion()), Object.class,
                () -> doEvaluate(request));
    }

    private IdempotencyTemplate.Outcome<Object> doEvaluate(EvaluationRequest request) {
        Instant now = clock.instant();
        BlastApplication application = getApplicationOr404(request.applicationId());

        var existing = monitoringRepository.findEvaluationByApplication(application.id());
        if (existing.isPresent()) {
            // 已封档终态：任何重复封档请求幂等成功返回既有结论，不改变结论。
            return IdempotencyTemplate.Outcome.of(200, toEvaluationResponse(existing.get()));
        }
        if (application.status() != ApplicationStatus.FILED) {
            return reject(409,
                    "仅已完成起爆备案的作业可提交效果评估，当前状态 " + application.status());
        }
        List<MonitoringPoint> registeredPoints = referenceDataRepository.findPointsOfZone(application.zoneCode());
        int readPoints = monitoringRepository.countDistinctPointsRead(application.id());
        if (registeredPoints.isEmpty() || readPoints < registeredPoints.size()) {
            return reject(409,
                    "振动读数未回传齐全：备案测点 " + registeredPoints.size()
                            + " 个，已回传测点 " + readPoints + " 个");
        }
        if (!monitoringRepository.insertEvaluation(new EvaluationRow(
                null, application.id(), request.requestId(), request.evaluator(),
                request.conclusion(), now))) {
            var winner = monitoringRepository.findEvaluationByApplication(application.id()).orElseThrow();
            return IdempotencyTemplate.Outcome.of(200, toEvaluationResponse(winner));
        }
        if (!applicationRepository.compareAndSetStatus(application.id(), ApplicationStatus.FILED,
                ApplicationStatus.ARCHIVED, null, application.detonateTime(), now)) {
            throw new BlastBusinessException(409, "作业封档状态变更失败，请刷新后重试");
        }
        var saved = monitoringRepository.findEvaluationByApplication(application.id()).orElseThrow();
        return IdempotencyTemplate.Outcome.of(201, toEvaluationResponse(saved));
    }

    public ApplicationResponse getApplication(long applicationId) {
        return toResponse(getApplicationOr404(applicationId));
    }

    public MineResponse getMine(String mineCode) {
        return toMineResponse(referenceDataRepository.findMine(mineCode)
                .orElseThrow(() -> new BlastBusinessException(404, "矿区不存在: " + mineCode)));
    }

    private BlastApplication getApplicationOr404(long applicationId) {
        return applicationRepository.findById(applicationId)
                .orElseThrow(() -> new BlastBusinessException(404, "作业申请不存在: " + applicationId));
    }

    /**
     * 允许作业时段：矿区当地时间 06:00（含）至 22:00（不含）。
     */
    private boolean withinAllowedWindow(Instant plannedTime, String timeZone) {
        ZoneId zoneId = ZoneId.of(timeZone);
        LocalTime localTime = LocalDateTime.ofInstant(plannedTime, zoneId).toLocalTime();
        return !localTime.isBefore(WINDOW_START) && localTime.isBefore(WINDOW_END);
    }

    private IdempotencyTemplate.Outcome<Object> reject(int status, String message) {
        return IdempotencyTemplate.Outcome.of(status, RejectionResponse.of(status, message));
    }

    private ApplicationResponse toResponse(BlastApplication application) {
        return new ApplicationResponse(
                application.id(),
                application.mineCode(),
                application.zoneCode(),
                application.applicant(),
                application.plannedTime(),
                application.chargePerDelayKg(),
                application.status());
    }

    private MineResponse toMineResponse(Mine mine) {
        return new MineResponse(
                mine.mineCode(), mine.mineName(), mine.timeZone(), mine.frozen(),
                mine.frozenAt(), mine.frozenReason(), mine.unfrozenAt(), mine.unfreezeReviewer());
    }

    private EvaluationResponse toEvaluationResponse(EvaluationRow row) {
        return new EvaluationResponse(row.id(), row.applicationId(), row.requestId(),
                row.evaluator(), row.conclusion(), row.archivedAt());
    }
}
