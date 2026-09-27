package com.example.starter.service;

import com.example.starter.domain.JobStatus;
import com.example.starter.domain.TestResult;
import com.example.starter.domain.TestType;
import com.example.starter.repo.BrakeTestRepository;
import com.example.starter.repo.BrakeTestRow;
import com.example.starter.repo.ConsistCheckRepository;
import com.example.starter.repo.JobRow;
import com.example.starter.repo.PressureReadingRepository;
import com.example.starter.repo.CarRow;
import com.example.starter.repo.TrainCarRepository;
import com.example.starter.repo.TrainJobRepository;
import com.example.starter.web.dto.AcceptJobRequest;
import com.example.starter.web.dto.BrakeTestRequest;
import com.example.starter.web.dto.BrakeTestResponse;
import com.example.starter.web.dto.CarDto;
import com.example.starter.web.dto.ClosedCarListResponse;
import com.example.starter.web.dto.ClosureResponse;
import com.example.starter.web.dto.ConsistChangeRequest;
import com.example.starter.web.dto.ConsistChangeResponse;
import com.example.starter.web.dto.ConsistCheckRequest;
import com.example.starter.web.dto.ConsistCheckResponse;
import com.example.starter.web.dto.ConsistResponse;
import com.example.starter.web.dto.DepartureResponse;
import com.example.starter.web.dto.JobResponse;
import com.example.starter.web.dto.PressureReadingRequest;
import com.example.starter.web.dto.PressureReadingResponse;
import com.example.starter.web.dto.RequestIdOnly;
import com.example.starter.web.dto.TestCurveResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * 列车制动机试验与摘挂核对核心服务。
 * 流程：接入 → 摘挂 → 编组核对 → 制动试验（感度/安定） → 发车确认 → 销记终态。
 */
@Service
public class TrainJobService {

    /** 列车管定压，单位 kPa；两项试验之间须恢复至定压，恰达定压允许下一项。 */
    public static final int RATED_PRESSURE_KPA = 500;
    /** 感度试验减压量，单位 kPa。 */
    public static final int SENSITIVITY_REDUCTION_KPA = 50;
    /** 安定试验减压量，单位 kPa；恰 140kPa 合格。 */
    public static final int STABILITY_REDUCTION_KPA = 140;
    /** 关门车比例上限分子（6%），恰 6% 合法。 */
    public static final int CLOSED_CAR_LIMIT_PERCENT = 6;

    private final TrainJobRepository jobRepository;
    private final TrainCarRepository carRepository;
    private final BrakeTestRepository brakeTestRepository;
    private final ConsistCheckRepository consistCheckRepository;
    private final PressureReadingRepository pressureReadingRepository;
    private final IdempotencyService idempotencyService;
    private final EotService eotService;

    public TrainJobService(TrainJobRepository jobRepository, TrainCarRepository carRepository,
                           BrakeTestRepository brakeTestRepository,
                           ConsistCheckRepository consistCheckRepository,
                           PressureReadingRepository pressureReadingRepository,
                           IdempotencyService idempotencyService,
                           EotService eotService) {
        this.jobRepository = jobRepository;
        this.carRepository = carRepository;
        this.brakeTestRepository = brakeTestRepository;
        this.consistCheckRepository = consistCheckRepository;
        this.pressureReadingRepository = pressureReadingRepository;
        this.idempotencyService = idempotencyService;
        this.eotService = eotService;
    }

    /**
     * 接入列车作业；同一股道同时刻仅允许一列作业中列车，并发接入唯一受理。
     */
    public JobResponse accept(AcceptJobRequest request) {
        return idempotencyService.execute(request.requestId(), "ACCEPT", request,
                JobResponse.class, () -> doAccept(request));
    }

    /**
     * 摘挂变更；变更与试验结论作废在同一事务内完成，变更后须重新核对与重做试验。
     */
    public ConsistChangeResponse changeConsist(long jobId, ConsistChangeRequest request) {
        return idempotencyService.execute(request.requestId(), "CONSIST_CHANGE", request,
                ConsistChangeResponse.class, () -> doChangeConsist(jobId, request));
    }

    /**
     * 编组核对：辆数、换长、总重与合成运统1比对，不一致则拒绝后续发车。
     */
    public ConsistCheckResponse checkConsist(long jobId, ConsistCheckRequest request) {
        return idempotencyService.execute(request.requestId(), "CONSIST_CHECK", request,
                ConsistCheckResponse.class, () -> doCheckConsist(jobId, request));
    }

    /**
     * 制动试验：感度减压 50kPa 全列须制动；安定减压 140kPa 不得紧急制动；
     * 两项之间风压须恢复至定压 500kPa（恰达允许）。
     */
    public BrakeTestResponse brakeTest(long jobId, BrakeTestRequest request) {
        return idempotencyService.execute(request.requestId(), "BRAKE_TEST", request,
                BrakeTestResponse.class, () -> doBrakeTest(jobId, request));
    }

    /**
     * 发车确认：运统1核对一致、感度与安定均合格、关门车比例不超限方可发车。
     */
    public DepartureResponse confirmDeparture(long jobId, RequestIdOnly request) {
        return idempotencyService.execute(request.requestId(), "DEPARTURE", request,
                DepartureResponse.class, () -> doConfirmDeparture(jobId));
    }

    /**
     * 销记：终态；重复销记返回幂等成功。
     */
    public ClosureResponse close(long jobId, RequestIdOnly request) {
        return idempotencyService.execute(request.requestId(), "CLOSURE", request,
                ClosureResponse.class, () -> doClose(jobId));
    }

    /**
     * 风压读数上报；销记后迟到数据只记迟到事件。
     */
    public PressureReadingResponse reportPressure(long jobId, PressureReadingRequest request) {
        return idempotencyService.execute(request.requestId(), "PRESSURE", request,
                PressureReadingResponse.class, () -> doReportPressure(jobId, request));
    }

    private JobResponse doAccept(AcceptJobRequest request) {
        List<CarDto> cars = request.cars();
        checkClosedCarRatio(cars);
        BigDecimal totalLength = cars.stream().map(CarDto::length).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalWeight = cars.stream().map(CarDto::weightTons).reduce(BigDecimal.ZERO, BigDecimal::add);
        long jobId;
        try {
            jobId = jobRepository.insert(request.trainNo(), request.track(),
                    cars.size(), totalLength, totalWeight);
        } catch (DuplicateKeyException e) {
            throw new ConflictException("同一股道同时刻仅允许一列作业中列车：" + request.track());
        }
        for (int i = 0; i < cars.size(); i++) {
            carRepository.insert(jobId, i + 1, cars.get(i));
        }
        return toJobResponse(jobRepository.findById(jobId).orElseThrow());
    }

    private ConsistChangeResponse doChangeConsist(long jobId, ConsistChangeRequest request) {
        JobRow job = requireJobForUpdate(jobId);
        if (job.status() != JobStatus.ACCEPTED && job.status() != JobStatus.CHECKED) {
            throw new BusinessException("当前状态不允许摘挂变更：" + job.status());
        }
        for (String carNo : request.detachCarNos()) {
            if (!carRepository.detach(jobId, carNo)) {
                throw new BusinessException("摘车失败，车辆不存在或已摘下：" + carNo);
            }
        }
        int nextSeq = carRepository.findActiveByJobId(jobId).size();
        for (CarDto car : request.attachCars()) {
            List<CarRow> existing = carRepository.findByJobIdAndCarNo(jobId, car.carNo());
            if (!existing.isEmpty()) {
                if (existing.get(0).detached()) {
                    nextSeq++;
                    carRepository.reattach(jobId, car.carNo(), nextSeq);
                } else {
                    throw new BusinessException("挂车失败，车辆已在列：" + car.carNo());
                }
            } else {
                nextSeq++;
                carRepository.insert(jobId, nextSeq, car);
            }
        }
        List<CarRow> active = carRepository.findActiveByJobId(jobId);
        if (active.isEmpty()) {
            throw new BusinessException("摘挂后在列辆数不得为空");
        }
        checkClosedCarRatio(active.stream()
                .map(c -> new CarDto(c.carNo(), c.weightTons(), c.length(), c.valveClosed()))
                .toList());
        int voidedTests = brakeTestRepository.voidAllByJobId(jobId);
        BigDecimal totalLength = active.stream().map(CarRow::length).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalWeight = active.stream().map(CarRow::weightTons).reduce(BigDecimal.ZERO, BigDecimal::add);
        int newVersion = job.consistVersion() + 1;
        jobRepository.updateConsist(jobId, newVersion, active.size(), totalLength, totalWeight,
                false, JobStatus.ACCEPTED);
        // 编组变化（含尾车/版本变化）：列尾绑定保留，但一致性确认立即失效，须重新双人确认
        eotService.invalidateConfirmationsOnConsistChange(jobId);
        return new ConsistChangeResponse(jobId, newVersion, active.size(), totalLength, totalWeight, voidedTests);
    }

    private ConsistCheckResponse doCheckConsist(long jobId, ConsistCheckRequest request) {
        JobRow job = requireJobForUpdate(jobId);
        if (job.status() == JobStatus.DEPARTURE_CONFIRMED || job.status() == JobStatus.CLOSED) {
            throw new BusinessException("当前状态不允许编组核对：" + job.status());
        }
        boolean matched = request.expectedCarCount() == job.carCount()
                && request.expectedTotalLength().compareTo(job.totalLength()) == 0
                && request.expectedTotalWeight().compareTo(job.totalWeight()) == 0;
        long checkId = consistCheckRepository.insert(jobId, job.consistVersion(),
                request.expectedCarCount(), request.expectedTotalLength(), request.expectedTotalWeight(),
                job.carCount(), job.totalLength(), job.totalWeight(), matched, request.requestId());
        jobRepository.updateStatusAndChecked(jobId,
                matched ? JobStatus.CHECKED : JobStatus.ACCEPTED, matched);
        String message = matched ? "编组与运统1一致" : "编组与运统1不一致，拒绝发车";
        return new ConsistCheckResponse(checkId, matched, job.consistVersion(),
                request.expectedCarCount(), job.carCount(),
                request.expectedTotalLength(), job.totalLength(),
                request.expectedTotalWeight(), job.totalWeight(), message);
    }

    private BrakeTestResponse doBrakeTest(long jobId, BrakeTestRequest request) {
        JobRow job = requireJobForUpdate(jobId);
        if (job.status() != JobStatus.CHECKED) {
            throw new BusinessException("编组未通过运统1核对或状态不允许试验：" + job.status());
        }
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
            message = result == TestResult.PASS ? "感度试验合格" : "感度试验不合格：存在未制动车辆";
        } else {
            requireSensitivityPassed(job);
            if (request.pressureReduction() != STABILITY_REDUCTION_KPA) {
                throw new BusinessException("安定试验减压量须为 " + STABILITY_REDUCTION_KPA + "kPa");
            }
            if (request.emergencyOccurred() == null) {
                throw new BusinessException("安定试验须上报紧急制动情况");
            }
            result = request.emergencyOccurred() ? TestResult.FAIL : TestResult.PASS;
            message = result == TestResult.PASS ? "安定试验合格" : "安定试验不合格：发生紧急制动";
        }
        long testId = brakeTestRepository.insert(jobId, job.consistVersion(), request.testType(),
                request.pressureReduction(), request.pipePressureBefore(),
                request.allCarsBraked(), request.emergencyOccurred(), result, request.requestId());
        return new BrakeTestResponse(testId, request.testType().name(), result.name(),
                job.consistVersion(), message);
    }

    private DepartureResponse doConfirmDeparture(long jobId) {
        JobRow job = requireJobForUpdate(jobId);
        if (job.status() == JobStatus.CLOSED) {
            throw new BusinessException("作业已销记，不得重复发车确认");
        }
        if (job.status() == JobStatus.DEPARTURE_CONFIRMED) {
            return new DepartureResponse(jobId, true, "已确认发车");
        }
        if (!job.consistChecked()) {
            throw new BusinessException("编组与运统1不一致或未核对，拒绝发车");
        }
        requirePassed(job, TestType.SENSITIVITY);
        requirePassed(job, TestType.STABILITY);
        List<CarRow> active = carRepository.findActiveByJobId(jobId);
        long closed = active.stream().filter(CarRow::valveClosed).count();
        if (!withinClosedCarLimit(closed, active.size())) {
            throw new BusinessException("关门车比例超过 6%，拒绝发车");
        }
        // 原制动试验仍有效的前提下，事务内同时核对列尾装置自检未过期、双人一致性确认匹配最新编组；
        // 未绑定列尾装置的历史作业不增加新门禁
        eotService.assertDepartureReady(job);
        jobRepository.updateStatus(jobId, JobStatus.DEPARTURE_CONFIRMED);
        return new DepartureResponse(jobId, true, "准许发车");
    }

    private ClosureResponse doClose(long jobId) {
        JobRow job = requireJobForUpdate(jobId);
        if (job.status() == JobStatus.CLOSED) {
            return new ClosureResponse(jobId, true, true, "重复销记，幂等成功");
        }
        if (job.status() != JobStatus.DEPARTURE_CONFIRMED) {
            throw new BusinessException("未发车确认，不得销记：" + job.status());
        }
        jobRepository.close(jobId);
        // 销记原子解除列尾装置占用、解除绑定并失效确认，绑定历史保留
        eotService.releaseOnClosure(jobId);
        return new ClosureResponse(jobId, true, false, "销记完成，作业终态");
    }

    private PressureReadingResponse doReportPressure(long jobId, PressureReadingRequest request) {
        JobRow job = requireJob(jobId);
        if (job.status() == JobStatus.CLOSED) {
            pressureReadingRepository.insertLateEvent(jobId, "LATE_PRESSURE",
                    "销记后迟到风压数据：" + request.pressure() + "kPa，采集时间 " + request.recordedAt());
            return new PressureReadingResponse(null, false, true, "作业已销记，迟到数据仅记迟到事件");
        }
        long readingId = pressureReadingRepository.insert(jobId, request.pressure(),
                request.recordedAt(), false, request.requestId());
        return new PressureReadingResponse(readingId, true, false, "风压读数已记录");
    }

    /**
     * 查询作业视图。
     */
    @Transactional(readOnly = true)
    public JobResponse getJob(long jobId) {
        return toJobResponse(requireJob(jobId));
    }

    /**
     * 复算试验曲线：风压读数（不含迟到数据）与试验事件。
     */
    @Transactional(readOnly = true)
    public TestCurveResponse getTestCurve(long jobId) {
        requireJob(jobId);
        List<TestCurveResponse.TestPoint> tests = brakeTestRepository.findByJobId(jobId).stream()
                .map(t -> new TestCurveResponse.TestPoint(t.testType().name(), t.pressureReduction(),
                        t.result().name(), t.consistVersion(), t.voided(), t.createdAt()))
                .toList();
        return new TestCurveResponse(jobId, pressureReadingRepository.findCurvePoints(jobId), tests);
    }

    /**
     * 复算关门车清单与比例。
     */
    @Transactional(readOnly = true)
    public ClosedCarListResponse getClosedCars(long jobId) {
        requireJob(jobId);
        List<CarRow> active = carRepository.findActiveByJobId(jobId);
        List<String> closedNos = active.stream().filter(CarRow::valveClosed).map(CarRow::carNo).toList();
        double ratio = active.isEmpty() ? 0.0 : (double) closedNos.size() / active.size();
        return new ClosedCarListResponse(jobId, closedNos, closedNos.size(), active.size(), ratio,
                withinClosedCarLimit(closedNos.size(), active.size()));
    }

    /**
     * 复算当前编组版本与在列车辆。
     */
    @Transactional(readOnly = true)
    public ConsistResponse getConsist(long jobId) {
        JobRow job = requireJob(jobId);
        List<ConsistResponse.CarView> cars = carRepository.findActiveByJobId(jobId).stream()
                .map(c -> new ConsistResponse.CarView(c.carNo(), c.seqNo(), c.weightTons(),
                        c.length(), c.valveClosed()))
                .toList();
        return new ConsistResponse(jobId, job.consistVersion(), job.carCount(),
                job.totalLength(), job.totalWeight(), cars);
    }

    private JobRow requireJob(long jobId) {
        return jobRepository.findById(jobId)
                .orElseThrow(() -> new NotFoundException("作业不存在：" + jobId));
    }

    private JobRow requireJobForUpdate(long jobId) {
        return jobRepository.findByIdForUpdate(jobId)
                .orElseThrow(() -> new NotFoundException("作业不存在：" + jobId));
    }

    private void requireSensitivityPassed(JobRow job) {
        requirePassed(job, TestType.SENSITIVITY);
    }

    private void requirePassed(JobRow job, TestType testType) {
        BrakeTestRow latest = brakeTestRepository
                .findLatestActive(job.id(), job.consistVersion(), testType)
                .orElseThrow(() -> new BusinessException("当前编组版本未完成" + testType + "试验"));
        if (latest.result() != TestResult.PASS) {
            throw new BusinessException("当前编组版本" + testType + "试验不合格");
        }
    }

    private void checkClosedCarRatio(List<CarDto> cars) {
        long closed = cars.stream().filter(CarDto::valveClosed).count();
        if (!withinClosedCarLimit(closed, cars.size())) {
            throw new BusinessException("关门车比例超过 6%：" + closed + "/" + cars.size());
        }
    }

    private boolean withinClosedCarLimit(long closed, long total) {
        return total > 0 && closed * 100 <= total * CLOSED_CAR_LIMIT_PERCENT;
    }

    private JobResponse toJobResponse(JobRow job) {
        List<CarRow> active = carRepository.findActiveByJobId(job.id());
        long closed = active.stream().filter(CarRow::valveClosed).count();
        double ratio = active.isEmpty() ? 0.0 : (double) closed / active.size();
        return new JobResponse(job.id(), job.trainNo(), job.track(), job.status().name(),
                job.consistVersion(), job.carCount(), job.totalLength(), job.totalWeight(),
                (int) closed, ratio);
    }
}
