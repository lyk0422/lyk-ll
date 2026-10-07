package com.example.starter.service;

import com.example.starter.domain.IncidentStatus;
import com.example.starter.domain.JobStatus;
import com.example.starter.domain.TestResult;
import com.example.starter.domain.TestType;
import com.example.starter.repo.CarRow;
import com.example.starter.repo.EnRouteConfirmationRepository;
import com.example.starter.repo.EnRouteConfirmationRow;
import com.example.starter.repo.EnRouteTestRepository;
import com.example.starter.repo.EnRouteTestRow;
import com.example.starter.repo.EotBindingRepository;
import com.example.starter.repo.EotBindingRow;
import com.example.starter.repo.EotDeviceRepository;
import com.example.starter.repo.EotDeviceRow;
import com.example.starter.repo.IncidentRepository;
import com.example.starter.repo.IncidentRow;
import com.example.starter.repo.JobRow;
import com.example.starter.repo.TrainCarRepository;
import com.example.starter.repo.TrainJobRepository;
import com.example.starter.web.dto.EnRouteConfirmRequest;
import com.example.starter.web.dto.EnRouteConfirmationResponse;
import com.example.starter.web.dto.EnRouteTestRequest;
import com.example.starter.web.dto.EnRouteTestResponse;
import com.example.starter.web.dto.IncidentEvidence;
import com.example.starter.web.dto.IncidentReportRequest;
import com.example.starter.web.dto.IncidentResponse;
import com.example.starter.web.dto.ResumeRequest;
import com.example.starter.web.dto.ResumeResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 途中异常停车与续行许可核心服务（仅模拟软件状态，不控制列车）。
 * 登记：仅 DEPARTURE_CONFIRMED 且未销记作业，冻结当前编组、列尾绑定与原发车证据，进入续行阻断，
 * 同作业仅一条活动处置；处置期间列尾继续占用，不得销记释放或经旧发车入口绕过。
 * 续行：途中试验（复用始发数值/关门车比例/有效时长规则）+ 两名不同人员确认新试验与列尾一致性后，
 * 续行入口同事务重查编组、自检有效期及最新试验版本，恰到试验失效点拒绝；任一失败仍停车，不释放装置。
 */
@Service
public class IncidentService {

    /** 途中试验有效时长：自试验记录时刻起 6 小时；续行时 UTC 下 now 须早于失效点，恰到即失效。 */
    public static final Duration EN_ROUTE_TEST_VALID_DURATION = Duration.ofHours(6);

    private final IncidentRepository incidentRepository;
    private final EnRouteTestRepository enRouteTestRepository;
    private final EnRouteConfirmationRepository enRouteConfirmationRepository;
    private final TrainJobRepository jobRepository;
    private final TrainCarRepository carRepository;
    private final EotBindingRepository bindingRepository;
    private final EotDeviceRepository deviceRepository;
    private final IdempotencyService idempotencyService;
    private final Clock clock;

    public IncidentService(IncidentRepository incidentRepository,
                           EnRouteTestRepository enRouteTestRepository,
                           EnRouteConfirmationRepository enRouteConfirmationRepository,
                           TrainJobRepository jobRepository,
                           TrainCarRepository carRepository,
                           EotBindingRepository bindingRepository,
                           EotDeviceRepository deviceRepository,
                           IdempotencyService idempotencyService,
                           Clock clock) {
        this.incidentRepository = incidentRepository;
        this.enRouteTestRepository = enRouteTestRepository;
        this.enRouteConfirmationRepository = enRouteConfirmationRepository;
        this.jobRepository = jobRepository;
        this.carRepository = carRepository;
        this.bindingRepository = bindingRepository;
        this.deviceRepository = deviceRepository;
        this.idempotencyService = idempotencyService;
        this.clock = clock;
    }

    /**
     * 登记途中异常：冻结当前编组、列尾绑定与原发车证据，进入续行阻断；同作业仅一条活动处置。
     */
    public IncidentResponse report(long jobId, IncidentReportRequest request) {
        return idempotencyService.execute(request.requestId(), "INCIDENT_REPORT",
                new IncidentParams(jobId, 0L, request), IncidentResponse.class,
                () -> doReport(jobId, request));
    }

    /**
     * 途中制动试验：绑定处置单、当前编组版本与新压力曲线，复用始发试验数值规则；
     * 始发曲线不能冒充新试验。
     */
    public EnRouteTestResponse enRouteTest(long jobId, long incidentId, EnRouteTestRequest request) {
        return idempotencyService.execute(request.requestId(), "EN_ROUTE_TEST",
                new IncidentParams(jobId, incidentId, request), EnRouteTestResponse.class,
                () -> doEnRouteTest(jobId, incidentId, request));
    }

    /**
     * 两名不同人员共同确认途中新试验与列尾一致性；成功后处置版本加 1。
     */
    public EnRouteConfirmationResponse confirmEnRoute(long jobId, long incidentId,
                                                      EnRouteConfirmRequest request) {
        return idempotencyService.execute(request.requestId(), "EN_ROUTE_CONFIRM",
                new IncidentParams(jobId, incidentId, request), EnRouteConfirmationResponse.class,
                () -> doConfirmEnRoute(jobId, incidentId, request));
    }

    /**
     * 续行许可：同事务重查编组、自检有效期及最新试验版本；仅结束本次阻断，证据保留，装置不释放。
     */
    public ResumeResponse resume(long jobId, long incidentId, ResumeRequest request) {
        return idempotencyService.execute(request.requestId(), "RESUME",
                new IncidentParams(jobId, incidentId, request), ResumeResponse.class,
                () -> doResume(jobId, incidentId, request));
    }

    private IncidentResponse doReport(long jobId, IncidentReportRequest request) {
        JobRow job = requireJobForUpdate(jobId);
        if (job.status() != JobStatus.DEPARTURE_CONFIRMED) {
            throw new BusinessException("仅发车确认且未销记的作业可登记途中异常：" + job.status());
        }
        if (incidentRepository.findActiveByJobId(jobId).isPresent()) {
            throw new ConflictException("同作业仅一条活动处置，当前处置尚未结束");
        }
        Long bindingId = bindingRepository.findActiveByJobId(jobId)
                .map(EotBindingRow::id).orElse(null);
        long incidentId;
        try {
            incidentId = incidentRepository.insertOpen(jobId, job.consistVersion(), bindingId,
                    request.reason(), request.requestId(), clock.instant());
        } catch (DuplicateKeyException e) {
            // 并发登记：active_job_id 唯一约束兜底，唯一受理
            throw new ConflictException("同作业仅一条活动处置，并发登记唯一受理");
        }
        IncidentRow incident = incidentRepository.findById(incidentId).orElseThrow();
        return toResponse(incident, "已登记途中异常，进入续行阻断");
    }

    private EnRouteTestResponse doEnRouteTest(long jobId, long incidentId, EnRouteTestRequest request) {
        JobRow job = requireJobForUpdate(jobId);
        IncidentRow incident = requireOpenIncident(jobId, incidentId, job);
        requireIncidentVersion(incident, request.expectedVersion());
        if (request.pipePressureBefore() < TrainJobService.RATED_PRESSURE_KPA) {
            throw new BusinessException("风压未恢复至定压 " + TrainJobService.RATED_PRESSURE_KPA
                    + "kPa，不得进行试验");
        }
        TestResult result;
        String message;
        if (request.testType() == TestType.SENSITIVITY) {
            if (request.pressureReduction() != TrainJobService.SENSITIVITY_REDUCTION_KPA) {
                throw new BusinessException("感度试验减压量须为 "
                        + TrainJobService.SENSITIVITY_REDUCTION_KPA + "kPa");
            }
            if (request.allCarsBraked() == null) {
                throw new BusinessException("感度试验须上报全列制动情况");
            }
            result = request.allCarsBraked() ? TestResult.PASS : TestResult.FAIL;
            message = result == TestResult.PASS ? "途中感度试验合格" : "途中感度试验不合格：存在未制动车辆";
        } else {
            requireEnRoutePassed(incident.id(), job.consistVersion(), TestType.SENSITIVITY);
            if (request.pressureReduction() != TrainJobService.STABILITY_REDUCTION_KPA) {
                throw new BusinessException("安定试验减压量须为 "
                        + TrainJobService.STABILITY_REDUCTION_KPA + "kPa");
            }
            if (request.emergencyOccurred() == null) {
                throw new BusinessException("安定试验须上报紧急制动情况");
            }
            result = request.emergencyOccurred() ? TestResult.FAIL : TestResult.PASS;
            message = result == TestResult.PASS ? "途中安定试验合格" : "途中安定试验不合格：发生紧急制动";
        }
        Instant now = clock.instant();
        Instant validUntil = now.plus(EN_ROUTE_TEST_VALID_DURATION);
        long testId = enRouteTestRepository.insert(incident.id(), jobId, job.consistVersion(),
                request.testType(), request.pressureReduction(), request.pipePressureBefore(),
                request.allCarsBraked(), request.emergencyOccurred(), result, validUntil,
                request.requestId(), now);
        bumpIncidentVersion(incident, request.expectedVersion());
        return new EnRouteTestResponse(testId, incident.id(), request.testType().name(), result.name(),
                job.consistVersion(), validUntil, request.expectedVersion() + 1, message);
    }

    private EnRouteConfirmationResponse doConfirmEnRoute(long jobId, long incidentId,
                                                         EnRouteConfirmRequest request) {
        JobRow job = requireJobForUpdate(jobId);
        IncidentRow incident = requireOpenIncident(jobId, incidentId, job);
        requireIncidentVersion(incident, request.expectedVersion());
        bindingRepository.findActiveByJobId(jobId)
                .orElseThrow(() -> new BusinessException("作业未绑定列尾装置，无法确认一致性"));
        if (request.confirmer1().equals(request.confirmer2())) {
            throw new BusinessException("一致性确认须由两名不同人员共同完成");
        }
        if (request.consistVersion() != job.consistVersion()) {
            throw new BusinessException("确认的编组版本与最新编组不一致：期望 "
                    + job.consistVersion() + "，提交 " + request.consistVersion());
        }
        String currentTailCarNo = currentTailCarNo(jobId);
        if (!request.tailCarNo().equals(currentTailCarNo)) {
            throw new BusinessException("确认的尾车与当前编组尾车不一致：当前尾车 " + currentTailCarNo);
        }
        // 确认对象是途中新试验：两项试验须已在当前编组版本下合格
        requireEnRoutePassed(incident.id(), job.consistVersion(), TestType.SENSITIVITY);
        requireEnRoutePassed(incident.id(), job.consistVersion(), TestType.STABILITY);
        enRouteConfirmationRepository.invalidateByIncidentId(incident.id());
        long confirmationId = enRouteConfirmationRepository.insert(incident.id(), jobId,
                currentTailCarNo, job.consistVersion(), request.confirmer1(), request.confirmer2(),
                request.requestId(), clock.instant());
        bumpIncidentVersion(incident, request.expectedVersion());
        return new EnRouteConfirmationResponse(confirmationId, incident.id(), jobId, currentTailCarNo,
                job.consistVersion(), request.confirmer1(), request.confirmer2(), true,
                request.expectedVersion() + 1);
    }

    private ResumeResponse doResume(long jobId, long incidentId, ResumeRequest request) {
        JobRow job = requireJobForUpdate(jobId);
        if (job.status() == JobStatus.CLOSED) {
            throw new BusinessException("作业已销记，不得续行");
        }
        IncidentRow incident = requireOpenIncident(jobId, incidentId, job);
        requireIncidentVersion(incident, request.expectedVersion());
        Instant now = clock.instant();
        // 同事务重查：最新试验版本匹配当前编组、试验仍在有效时长内（恰到失效点拒绝）
        EnRouteTestRow sensitivity = requireEnRoutePassed(incident.id(), job.consistVersion(),
                TestType.SENSITIVITY);
        EnRouteTestRow stability = requireEnRoutePassed(incident.id(), job.consistVersion(),
                TestType.STABILITY);
        if (!now.isBefore(sensitivity.validUntil())) {
            throw new BusinessException("途中感度试验已过有效截止时刻，仍停车：" + sensitivity.validUntil());
        }
        if (!now.isBefore(stability.validUntil())) {
            throw new BusinessException("途中安定试验已过有效截止时刻，仍停车：" + stability.validUntil());
        }
        // 关门车比例复用始发规则（恰 6% 合法）
        List<CarRow> activeCars = carRepository.findActiveByJobId(jobId);
        long closed = activeCars.stream().filter(CarRow::valveClosed).count();
        if (activeCars.isEmpty()
                || closed * 100 > (long) activeCars.size() * TrainJobService.CLOSED_CAR_LIMIT_PERCENT) {
            throw new BusinessException("关门车比例超过 6%，仍停车");
        }
        // 列尾联动：装置仍被本作业占用、自检未过期（恰到截止失效）、双人确认匹配最新编组与尾车
        bindingRepository.findActiveByJobId(jobId).ifPresent(binding -> {
            EotDeviceRow device = deviceRepository.findById(binding.deviceId())
                    .orElseThrow(() -> new BusinessException("列尾装置档案缺失：" + binding.deviceNo()));
            if (device.boundJobId() == null || device.boundJobId() != jobId) {
                throw new BusinessException("列尾装置占用与绑定不一致，仍停车");
            }
            if (!now.isBefore(device.selfCheckValidUntil())) {
                throw new BusinessException("列尾装置自检已过有效截止时刻，仍停车："
                        + device.selfCheckValidUntil());
            }
            EnRouteConfirmationRow confirmation = enRouteConfirmationRepository
                    .findValidByIncidentId(incident.id())
                    .orElseThrow(() -> new BusinessException("途中新试验与列尾一致性未共同确认，仍停车"));
            if (confirmation.consistVersion() != job.consistVersion()) {
                throw new BusinessException("途中确认的编组版本已过期，仍停车：确认版本 "
                        + confirmation.consistVersion() + "，最新版本 " + job.consistVersion());
            }
            if (!confirmation.tailCarNo().equals(currentTailCarNo(jobId))) {
                throw new BusinessException("途中确认的尾车与最新编组尾车不一致，仍停车");
            }
        });
        // 原子续行：仅结束本次阻断；作业仍发车确认态，列尾装置继续占用，证据保留
        if (!incidentRepository.resumeIfOpen(incident.id(), request.expectedVersion(), now)) {
            throw new ConflictException("处置版本已变化或已续行，续行失败");
        }
        return new ResumeResponse(incident.id(), jobId, true, request.expectedVersion() + 1,
                "准许续行，本次阻断结束，可另报新异常");
    }

    /**
     * 是否存在活动处置（续行阻断中）；发车确认/销记入口据此拒绝绕过。
     */
    public boolean hasActiveIncident(long jobId) {
        return incidentRepository.findActiveByJobId(jobId).isPresent();
    }

    /**
     * 阻断中编组变更同事务联动：待批准途中试验与双人确认立即失效，处置版本加 1，重做后才能续行。
     */
    public void onConsistChangeDuringBlock(long jobId) {
        incidentRepository.findActiveByJobId(jobId).ifPresent(incident -> {
            enRouteTestRepository.voidAllByIncidentId(incident.id());
            enRouteConfirmationRepository.invalidateByIncidentId(incident.id());
            incidentRepository.bumpVersionIfOpen(incident.id(), incident.version());
        });
    }

    /**
     * 查询作业全部处置证据（处置单、途中试验与双人确认历史；始发与途中两条证据均保留）。
     */
    @Transactional(readOnly = true)
    public List<IncidentEvidence> getEvidence(long jobId) {
        jobRepository.findById(jobId)
                .orElseThrow(() -> new NotFoundException("作业不存在：" + jobId));
        return incidentRepository.findAllByJobId(jobId).stream()
                .map(incident -> new IncidentEvidence(incident.id(), incident.jobId(),
                        incident.status().name(), incident.consistVersion(), incident.eotBindingId(),
                        incident.version(), incident.reason(), incident.resumedAt(), incident.createdAt(),
                        enRouteTestRepository.findByIncidentId(incident.id()).stream()
                                .map(t -> new IncidentEvidence.TestPoint(t.id(), t.testType().name(),
                                        t.pressureReduction(), t.result().name(), t.consistVersion(),
                                        t.voided(), t.validUntil(), t.createdAt()))
                                .toList(),
                        enRouteConfirmationRepository.findAllByIncidentId(incident.id()).stream()
                                .map(c -> new IncidentEvidence.ConfirmationPoint(c.id(), c.tailCarNo(),
                                        c.consistVersion(), c.confirmer1(), c.confirmer2(), c.valid(),
                                        c.createdAt()))
                                .toList()))
                .toList();
    }

    private IncidentRow requireOpenIncident(long jobId, long incidentId, JobRow job) {
        if (job.status() != JobStatus.DEPARTURE_CONFIRMED) {
            throw new BusinessException("当前作业状态不允许途中处置操作：" + job.status());
        }
        IncidentRow incident = incidentRepository.findById(incidentId)
                .orElseThrow(() -> new NotFoundException("处置单不存在：" + incidentId));
        if (incident.jobId() != jobId) {
            throw new BusinessException("跨作业引用处置单拒绝：处置单 " + incidentId + " 不属于作业 " + jobId);
        }
        if (incident.status() != IncidentStatus.OPEN) {
            throw new BusinessException("处置已续行结束，不得再操作：" + incidentId);
        }
        return incident;
    }

    private void requireIncidentVersion(IncidentRow incident, int expectedVersion) {
        if (expectedVersion != incident.version()) {
            throw new ConflictException("处置版本冲突：期望 " + expectedVersion
                    + "，当前 " + incident.version());
        }
    }

    private void bumpIncidentVersion(IncidentRow incident, int expectedVersion) {
        if (!incidentRepository.bumpVersionIfOpen(incident.id(), expectedVersion)) {
            throw new ConflictException("处置版本已变化或已续行，操作失败");
        }
    }

    private EnRouteTestRow requireEnRoutePassed(long incidentId, int consistVersion, TestType testType) {
        EnRouteTestRow latest = enRouteTestRepository
                .findLatestActive(incidentId, consistVersion, testType)
                .orElseThrow(() -> new BusinessException("当前编组版本未完成途中" + testType + "试验"));
        if (latest.result() != TestResult.PASS) {
            throw new BusinessException("当前编组版本途中" + testType + "试验不合格");
        }
        return latest;
    }

    private String currentTailCarNo(long jobId) {
        List<CarRow> activeCars = carRepository.findActiveByJobId(jobId);
        if (activeCars.isEmpty()) {
            throw new BusinessException("编组为空，无法确定尾车");
        }
        return activeCars.get(activeCars.size() - 1).carNo();
    }

    private JobRow requireJobForUpdate(long jobId) {
        return jobRepository.findByIdForUpdate(jobId)
                .orElseThrow(() -> new NotFoundException("作业不存在：" + jobId));
    }

    private IncidentResponse toResponse(IncidentRow incident, String message) {
        return new IncidentResponse(incident.id(), incident.jobId(), incident.status().name(),
                incident.consistVersion(), incident.eotBindingId(), incident.version(),
                incident.reason(), incident.resumedAt(), incident.createdAt(), message);
    }

    /** 途中处置幂等指纹参数：路径作业ID、处置单ID与请求体共同结构化参与指纹。 */
    private record IncidentParams(long jobId, long incidentId, Object request) {
    }
}
