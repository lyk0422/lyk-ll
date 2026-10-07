package com.example.starter.service;

import com.example.starter.domain.DispositionStatus;
import com.example.starter.domain.JobStatus;
import com.example.starter.domain.TestResult;
import com.example.starter.domain.TestType;
import com.example.starter.repo.CarRow;
import com.example.starter.repo.DispositionRow;
import com.example.starter.repo.EnRouteConfirmationRepository;
import com.example.starter.repo.EnRouteConfirmationRow;
import com.example.starter.repo.EnRouteDispositionRepository;
import com.example.starter.repo.EnRouteTestRepository;
import com.example.starter.repo.EnRouteTestRow;
import com.example.starter.repo.EotBindingRepository;
import com.example.starter.repo.EotBindingRow;
import com.example.starter.repo.EotDeviceRepository;
import com.example.starter.repo.EotDeviceRow;
import com.example.starter.repo.JobRow;
import com.example.starter.repo.TrainCarRepository;
import com.example.starter.repo.TrainJobRepository;
import com.example.starter.web.dto.EnRouteAbnormalityRequest;
import com.example.starter.web.dto.EnRouteConfirmationResponse;
import com.example.starter.web.dto.EnRouteConfirmRequest;
import com.example.starter.web.dto.EnRouteDispositionEvidence;
import com.example.starter.web.dto.EnRouteDispositionResponse;
import com.example.starter.web.dto.EnRouteResumeRequest;
import com.example.starter.web.dto.EnRouteResumeResponse;
import com.example.starter.web.dto.EnRouteTestRequest;
import com.example.starter.web.dto.EnRouteTestResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 途中异常停车与续行许可核心服务（仅模拟软件状态，不控制列车）。
 * 流程：发车确认后登记途中异常（冻结编组/列尾绑定/原发车证据，进入续行阻断，同作业仅一条活动处置）
 * → 途中试验（绑定处置单与当前编组版本的新压力曲线，复用始发试验数值/关门车比例/有效时长规则）
 * → 两名不同人员确认新试验与列尾一致性 → 续行入口同事务重查编组、自检有效期与最新试验版本。
 * 阻断期间列尾继续占用：不得销记释放，不得经旧发车入口绕过；途中编组变更仅在阻断中允许，
 * 并使待批准试验与确认失效。续行成功仅结束本次阻断，保留始发与途中两条证据，之后可另报新异常。
 */
@Service
public class EnRouteService {

    /** 列车管定压，单位 kPa；复用始发制动试验规则，恰达定压允许下一项。 */
    public static final int RATED_PRESSURE_KPA = TrainJobService.RATED_PRESSURE_KPA;
    /** 感度试验减压量，单位 kPa；复用始发制动试验规则。 */
    public static final int SENSITIVITY_REDUCTION_KPA = TrainJobService.SENSITIVITY_REDUCTION_KPA;
    /** 安定试验减压量，单位 kPa；复用始发制动试验规则，恰 140kPa 合格。 */
    public static final int STABILITY_REDUCTION_KPA = TrainJobService.STABILITY_REDUCTION_KPA;
    /** 途中试验有效时长（合成规则）：自记录时刻起 2 小时内有效，续行时 now &lt; 有效截止，恰达失效。 */
    public static final Duration EN_ROUTE_TEST_VALIDITY = Duration.ofHours(2);

    private final EnRouteDispositionRepository dispositionRepository;
    private final EnRouteTestRepository testRepository;
    private final EnRouteConfirmationRepository confirmationRepository;
    private final TrainJobRepository jobRepository;
    private final TrainCarRepository carRepository;
    private final EotBindingRepository bindingRepository;
    private final EotDeviceRepository deviceRepository;
    private final IdempotencyService idempotencyService;
    private final Clock clock;

    public EnRouteService(EnRouteDispositionRepository dispositionRepository,
                          EnRouteTestRepository testRepository,
                          EnRouteConfirmationRepository confirmationRepository,
                          TrainJobRepository jobRepository, TrainCarRepository carRepository,
                          EotBindingRepository bindingRepository, EotDeviceRepository deviceRepository,
                          IdempotencyService idempotencyService, Clock clock) {
        this.dispositionRepository = dispositionRepository;
        this.testRepository = testRepository;
        this.confirmationRepository = confirmationRepository;
        this.jobRepository = jobRepository;
        this.carRepository = carRepository;
        this.bindingRepository = bindingRepository;
        this.deviceRepository = deviceRepository;
        this.idempotencyService = idempotencyService;
        this.clock = clock;
    }

    /**
     * 登记途中异常：仅 DEPARTURE_CONFIRMED 且未销记作业；冻结当前编组、列尾绑定与原发车证据，
     * 进入续行阻断；同作业仅一条活动处置。
     */
    public EnRouteDispositionResponse reportAbnormality(long jobId, EnRouteAbnormalityRequest request) {
        return idempotencyService.execute(request.requestId(), "EN_ROUTE_ABNORMALITY",
                new AbnormalityParams(jobId, request), EnRouteDispositionResponse.class,
                () -> doReportAbnormality(jobId, request));
    }

    /**
     * 记录途中试验：绑定处置单与当前编组版本的新压力曲线；始发曲线不能冒充新试验。
     */
    public EnRouteTestResponse recordTest(long jobId, long dispositionId, EnRouteTestRequest request) {
        return idempotencyService.execute(request.requestId(), "EN_ROUTE_TEST",
                new TestParams(jobId, dispositionId, request), EnRouteTestResponse.class,
                () -> doRecordTest(jobId, dispositionId, request));
    }

    /**
     * 两名不同人员确认新试验与列尾一致性。
     */
    public EnRouteConfirmationResponse confirm(long jobId, long dispositionId, EnRouteConfirmRequest request) {
        return idempotencyService.execute(request.requestId(), "EN_ROUTE_CONFIRM",
                new ConfirmParams(jobId, dispositionId, request), EnRouteConfirmationResponse.class,
                () -> doConfirm(jobId, dispositionId, request));
    }

    /**
     * 续行许可：同事务重查编组、自检有效期及最新试验版本；恰到原试验失效点拒绝；
     * 任一失败仍停车、不释放装置；成功仅结束本次阻断。
     */
    public EnRouteResumeResponse resume(long jobId, long dispositionId, EnRouteResumeRequest request) {
        return idempotencyService.execute(request.requestId(), "EN_ROUTE_RESUME",
                new ResumeParams(jobId, dispositionId, request), EnRouteResumeResponse.class,
                () -> doResume(jobId, dispositionId, request));
    }

    private EnRouteDispositionResponse doReportAbnormality(long jobId, EnRouteAbnormalityRequest request) {
        JobRow job = requireJobForUpdate(jobId);
        if (job.status() == JobStatus.CLOSED) {
            throw new BusinessException("作业已销记，不得登记途中异常");
        }
        if (job.status() != JobStatus.DEPARTURE_CONFIRMED) {
            throw new BusinessException("仅发车确认后途中方可登记异常：" + job.status());
        }
        if (dispositionRepository.findActiveByJobId(jobId).isPresent()) {
            throw new ConflictException("同作业仅允许一条活动处置，当前仍阻断中");
        }
        List<CarRow> activeCars = carRepository.findActiveByJobId(jobId);
        if (activeCars.isEmpty()) {
            throw new BusinessException("编组为空，无法冻结尾车证据");
        }
        String tailCarNo = activeCars.get(activeCars.size() - 1).carNo();
        // 冻结当前编组版本、尾车与生效列尾绑定；原发车证据（始发试验/核对记录）原样保留不重开
        Long frozenBindingId = bindingRepository.findActiveByJobId(jobId).map(EotBindingRow::id).orElse(null);
        long dispositionId;
        try {
            dispositionId = dispositionRepository.insert(jobId, job.consistVersion(), tailCarNo,
                    frozenBindingId, request.reason(), request.requestId());
        } catch (DuplicateKeyException e) {
            // 并发登记同作业异常：活动键唯一约束兜底，仅一条成功
            throw new ConflictException("同作业仅允许一条活动处置，当前仍阻断中");
        }
        return toResponse(dispositionRepository.findById(dispositionId).orElseThrow());
    }

    private EnRouteTestResponse doRecordTest(long jobId, long dispositionId, EnRouteTestRequest request) {
        JobRow job = requireJobForUpdate(jobId);
        DispositionRow disposition = requireDisposition(dispositionId, jobId);
        requireDeparted(job);
        requireActiveVersion(disposition, request.expectedVersion());
        if (request.pipePressureBefore() < RATED_PRESSURE_KPA) {
            throw new BusinessException("风压未恢复至定压 " + RATED_PRESSURE_KPA + "kPa，不得进行试验");
        }
        TestResult result;
        String message;
        if (request.testType() == TestType.SENSITIVITY) {
            if (request.pressureReduction() != SENSITIVITY_REDUCTION_KPA) {
                throw new BusinessException("感度试验减压量须为 " + SENSITIVITY_REDUCTION_KPA + "kPa");
            }
            if (request.allCarsBraked() == null) {
                throw new BusinessException("感度试验须上报全列制动情况");
            }
            result = request.allCarsBraked() ? TestResult.PASS : TestResult.FAIL;
            message = result == TestResult.PASS ? "途中感度试验合格" : "途中感度试验不合格：存在未制动车辆";
        } else {
            requireEnRoutePassed(disposition, job.consistVersion(), TestType.SENSITIVITY);
            if (request.pressureReduction() != STABILITY_REDUCTION_KPA) {
                throw new BusinessException("安定试验减压量须为 " + STABILITY_REDUCTION_KPA + "kPa");
            }
            if (request.emergencyOccurred() == null) {
                throw new BusinessException("安定试验须上报紧急制动情况");
            }
            result = request.emergencyOccurred() ? TestResult.FAIL : TestResult.PASS;
            message = result == TestResult.PASS ? "途中安定试验合格" : "途中安定试验不合格：发生紧急制动";
        }
        Instant validUntil = clock.instant().plus(EN_ROUTE_TEST_VALIDITY);
        long testId = testRepository.insert(dispositionId, jobId, job.consistVersion(), request.testType(),
                request.pressureReduction(), request.pipePressureBefore(),
                request.allCarsBraked(), request.emergencyOccurred(), result, validUntil, request.requestId());
        bumpDisposition(dispositionId, request.expectedVersion());
        return new EnRouteTestResponse(testId, dispositionId, request.testType().name(), result.name(),
                job.consistVersion(), validUntil, disposition.version() + 1, message);
    }

    private EnRouteConfirmationResponse doConfirm(long jobId, long dispositionId, EnRouteConfirmRequest request) {
        JobRow job = requireJobForUpdate(jobId);
        DispositionRow disposition = requireDisposition(dispositionId, jobId);
        requireDeparted(job);
        requireActiveVersion(disposition, request.expectedVersion());
        if (request.confirmer1().equals(request.confirmer2())) {
            throw new BusinessException("途中试验与列尾一致性须由两名不同人员共同确认");
        }
        // 新试验：当前编组版本下感度与安定途中试验均合格（有效时长在续行入口重查）
        requireEnRoutePassed(disposition, job.consistVersion(), TestType.SENSITIVITY);
        requireEnRoutePassed(disposition, job.consistVersion(), TestType.STABILITY);
        List<CarRow> activeCars = carRepository.findActiveByJobId(jobId);
        if (activeCars.isEmpty()) {
            throw new BusinessException("编组为空，无法核对列尾一致性");
        }
        String tailCarNo = activeCars.get(activeCars.size() - 1).carNo();
        // 列尾一致性：阻断期间装置继续占用，自检须仍在有效期（UTC，恰达失效）
        Optional<EotBindingRow> binding = bindingRepository.findActiveByJobId(jobId);
        if (binding.isPresent()) {
            EotDeviceRow device = deviceRepository.findById(binding.get().deviceId())
                    .orElseThrow(() -> new BusinessException("列尾装置档案缺失"));
            if (!clock.instant().isBefore(device.selfCheckValidUntil())) {
                throw new BusinessException("列尾装置自检已过有效截止时刻，不得确认续行一致性："
                        + device.selfCheckValidUntil());
            }
        }
        confirmationRepository.invalidateByDispositionId(dispositionId);
        long confirmationId = confirmationRepository.insert(dispositionId, jobId, job.consistVersion(),
                tailCarNo, request.confirmer1(), request.confirmer2(), request.requestId());
        bumpDisposition(dispositionId, request.expectedVersion());
        return new EnRouteConfirmationResponse(confirmationId, dispositionId, job.consistVersion(), tailCarNo,
                request.confirmer1(), request.confirmer2(), true, disposition.version() + 1);
    }

    private EnRouteResumeResponse doResume(long jobId, long dispositionId, EnRouteResumeRequest request) {
        // 与销记/异常补录同一作业行锁串行化：不得已销记又续行，不得尚阻断便释放列尾
        JobRow job = requireJobForUpdate(jobId);
        DispositionRow disposition = requireDisposition(dispositionId, jobId);
        if (job.status() == JobStatus.CLOSED) {
            throw new BusinessException("作业已销记，不得续行");
        }
        requireDeparted(job);
        requireActiveVersion(disposition, request.expectedVersion());
        Instant now = clock.instant();
        // 同事务重查：双人确认匹配最新编组版本
        EnRouteConfirmationRow confirmation = confirmationRepository.findValidByDispositionId(dispositionId)
                .orElseThrow(() -> new BusinessException("途中试验与列尾一致性未经双人确认，拒绝续行"));
        if (confirmation.consistVersion() != job.consistVersion()) {
            throw new BusinessException("编组已变更，途中确认失效，拒绝续行");
        }
        // 同事务重查：最新试验版本合格且仍在有效时长内，恰到原试验失效点拒绝
        requireEnRouteTestValid(disposition, job.consistVersion(), TestType.SENSITIVITY, now);
        requireEnRouteTestValid(disposition, job.consistVersion(), TestType.STABILITY, now);
        // 同事务重查：关门车比例不超限（复用始发 6% 规则）
        List<CarRow> activeCars = carRepository.findActiveByJobId(jobId);
        long closed = activeCars.stream().filter(CarRow::valveClosed).count();
        if (!withinClosedCarLimit(closed, activeCars.size())) {
            throw new BusinessException("关门车比例超过 6%，拒绝续行");
        }
        // 同事务重查：列尾继续占用且自检在有效期；任一失败仍停车，不释放装置
        Optional<EotBindingRow> binding = bindingRepository.findActiveByJobId(jobId);
        if (binding.isPresent()) {
            EotDeviceRow device = deviceRepository.findById(binding.get().deviceId())
                    .orElseThrow(() -> new BusinessException("列尾装置档案缺失"));
            if (device.boundJobId() == null || device.boundJobId() != jobId) {
                throw new BusinessException("列尾装置占用与绑定不一致，拒绝续行");
            }
            if (!now.isBefore(device.selfCheckValidUntil())) {
                throw new BusinessException("列尾装置自检已过有效截止时刻，拒绝续行："
                        + device.selfCheckValidUntil());
            }
        }
        if (!dispositionRepository.resumeIfActive(dispositionId, request.expectedVersion(), now)) {
            throw new ConflictException("处置版本冲突或处置已结束：期望 " + request.expectedVersion());
        }
        return new EnRouteResumeResponse(dispositionId, jobId, true, disposition.version() + 1,
                "准许续行，本次阻断结束，始发与途中证据保留");
    }

    /**
     * 查询处置单完整证据（含途中试验与双人确认历史）。
     */
    @Transactional(readOnly = true)
    public EnRouteDispositionEvidence getDispositionEvidence(long jobId, long dispositionId) {
        return toEvidence(requireDisposition(dispositionId, jobId));
    }

    /**
     * 查询作业当前活动处置单证据；无活动处置返回 404。
     */
    @Transactional(readOnly = true)
    public EnRouteDispositionEvidence getActiveDispositionEvidence(long jobId) {
        requireJob(jobId);
        DispositionRow disposition = dispositionRepository.findActiveByJobId(jobId)
                .orElseThrow(() -> new NotFoundException("作业无活动处置单：" + jobId));
        return toEvidence(disposition);
    }

    /**
     * 查询作业当前活动处置单（阻断中）；供销记/发车/摘挂事务内门禁使用。
     */
    public Optional<DispositionRow> findActiveDisposition(long jobId) {
        return dispositionRepository.findActiveByJobId(jobId);
    }

    /**
     * 途中编组变更同事务联动：作废待批准途中试验与双人确认并递增处置版本。
     *
     * @return 失效的途中试验条数
     */
    public int invalidateOnConsistChange(long jobId) {
        DispositionRow disposition = dispositionRepository.findActiveByJobId(jobId)
                .orElseThrow(() -> new IllegalStateException("无活动处置单，不得联动失效：" + jobId));
        int voided = testRepository.voidAllByDispositionId(disposition.id());
        confirmationRepository.invalidateByDispositionId(disposition.id());
        bumpDisposition(disposition.id(), disposition.version());
        return voided;
    }

    private void requireEnRoutePassed(DispositionRow disposition, int consistVersion, TestType testType) {
        EnRouteTestRow latest = testRepository
                .findLatestActive(disposition.id(), consistVersion, testType)
                .orElseThrow(() -> new BusinessException("当前编组版本未完成途中" + testType + "试验"));
        if (latest.result() != TestResult.PASS) {
            throw new BusinessException("当前编组版本途中" + testType + "试验不合格");
        }
    }

    private void requireEnRouteTestValid(DispositionRow disposition, int consistVersion,
                                         TestType testType, Instant now) {
        EnRouteTestRow latest = testRepository
                .findLatestActive(disposition.id(), consistVersion, testType)
                .orElseThrow(() -> new BusinessException("当前编组版本未完成途中" + testType + "试验，拒绝续行"));
        if (latest.result() != TestResult.PASS) {
            throw new BusinessException("当前编组版本途中" + testType + "试验不合格，拒绝续行");
        }
        if (!now.isBefore(latest.validUntil())) {
            throw new BusinessException("途中" + testType + "试验已过有效截止时刻，拒绝续行："
                    + latest.validUntil());
        }
    }

    private void requireActiveVersion(DispositionRow disposition, int expectedVersion) {
        if (disposition.status() != DispositionStatus.ACTIVE) {
            throw new BusinessException("处置单已结束，不得继续操作：" + disposition.status());
        }
        if (disposition.version() != expectedVersion) {
            throw new ConflictException("处置版本冲突：期望 " + expectedVersion
                    + "，当前 " + disposition.version());
        }
    }

    private void bumpDisposition(long dispositionId, int expectedVersion) {
        if (!dispositionRepository.bumpVersionIfActive(dispositionId, expectedVersion)) {
            throw new ConflictException("处置版本冲突或处置已结束：期望 " + expectedVersion);
        }
    }

    private void requireDeparted(JobRow job) {
        if (job.status() != JobStatus.DEPARTURE_CONFIRMED) {
            throw new BusinessException("作业状态不允许途中处置操作：" + job.status());
        }
    }

    private DispositionRow requireDisposition(long dispositionId, long jobId) {
        DispositionRow disposition = dispositionRepository.findById(dispositionId)
                .orElseThrow(() -> new NotFoundException("处置单不存在：" + dispositionId));
        if (disposition.jobId() != jobId) {
            throw new NotFoundException("处置单不属于作业 " + jobId + "：" + dispositionId);
        }
        return disposition;
    }

    private JobRow requireJob(long jobId) {
        return jobRepository.findById(jobId)
                .orElseThrow(() -> new NotFoundException("作业不存在：" + jobId));
    }

    private JobRow requireJobForUpdate(long jobId) {
        return jobRepository.findByIdForUpdate(jobId)
                .orElseThrow(() -> new NotFoundException("作业不存在：" + jobId));
    }

    private boolean withinClosedCarLimit(long closed, long total) {
        return total > 0 && closed * 100 <= total * TrainJobService.CLOSED_CAR_LIMIT_PERCENT;
    }

    private EnRouteDispositionResponse toResponse(DispositionRow disposition) {
        return new EnRouteDispositionResponse(disposition.id(), disposition.jobId(),
                disposition.status().name(), disposition.version(), disposition.consistVersion(),
                disposition.frozenTailCarNo(), disposition.frozenEotBindingId(), disposition.reason(),
                disposition.resumedAt(), disposition.createdAt());
    }

    private EnRouteDispositionEvidence toEvidence(DispositionRow disposition) {
        List<EnRouteDispositionEvidence.TestEvidence> tests = testRepository
                .findByDispositionId(disposition.id()).stream()
                .map(t -> new EnRouteDispositionEvidence.TestEvidence(t.id(), t.testType().name(),
                        t.result().name(), t.consistVersion(), t.voided(), t.validUntil(), t.createdAt()))
                .toList();
        List<EnRouteDispositionEvidence.ConfirmationEvidence> confirmations = confirmationRepository
                .findByDispositionId(disposition.id()).stream()
                .map(c -> new EnRouteDispositionEvidence.ConfirmationEvidence(c.id(), c.consistVersion(),
                        c.tailCarNo(), c.confirmer1(), c.confirmer2(), c.valid(), c.createdAt()))
                .toList();
        return new EnRouteDispositionEvidence(disposition.id(), disposition.jobId(),
                disposition.status().name(), disposition.version(), disposition.consistVersion(),
                disposition.frozenTailCarNo(), disposition.frozenEotBindingId(), disposition.reason(),
                disposition.resumedAt(), disposition.createdAt(), tests, confirmations);
    }

    /** 异常登记幂等指纹参数：路径作业ID与请求体共同结构化参与指纹。 */
    private record AbnormalityParams(long jobId, EnRouteAbnormalityRequest request) {
    }

    /** 途中试验幂等指纹参数：路径作业ID、处置单ID与请求体共同结构化参与指纹。 */
    private record TestParams(long jobId, long dispositionId, EnRouteTestRequest request) {
    }

    /** 途中确认幂等指纹参数：路径作业ID、处置单ID与请求体共同结构化参与指纹。 */
    private record ConfirmParams(long jobId, long dispositionId, EnRouteConfirmRequest request) {
    }

    /** 续行许可幂等指纹参数：路径作业ID、处置单ID与请求体共同结构化参与指纹。 */
    private record ResumeParams(long jobId, long dispositionId, EnRouteResumeRequest request) {
    }
}
