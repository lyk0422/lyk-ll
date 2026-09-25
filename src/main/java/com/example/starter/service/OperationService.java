package com.example.starter.service;

import com.example.starter.domain.BrakeTestRow;
import com.example.starter.domain.CarRow;
import com.example.starter.domain.ConsistCheckRow;
import com.example.starter.domain.OperationRow;
import com.example.starter.domain.OperationStatus;
import com.example.starter.domain.PressureReadingRow;
import com.example.starter.domain.TestPhase;
import com.example.starter.domain.TestResult;
import com.example.starter.dto.BrakeCurveResponse;
import com.example.starter.dto.BrakeTestRequest;
import com.example.starter.dto.BrakeTestResponse;
import com.example.starter.dto.CarInput;
import com.example.starter.dto.CloseResponse;
import com.example.starter.dto.ConfirmResponse;
import com.example.starter.dto.ConsistCheckRequest;
import com.example.starter.dto.ConsistCheckResponse;
import com.example.starter.dto.ConsistVersionsResponse;
import com.example.starter.dto.CutoutCarsResponse;
import com.example.starter.dto.OperationDetailResponse;
import com.example.starter.dto.PressureRequest;
import com.example.starter.dto.PressureResponse;
import com.example.starter.dto.ReceiveRequest;
import com.example.starter.dto.ReceiveResponse;
import com.example.starter.dto.ShuntRequest;
import com.example.starter.dto.ShuntResponse;
import com.example.starter.repo.BrakeTestRepository;
import com.example.starter.repo.CarRepository;
import com.example.starter.repo.ConsistCheckRepository;
import com.example.starter.repo.OperationRepository;
import com.example.starter.repo.PressureRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 列车作业业务服务: 接入→摘挂→编组核对→制动试验→发车确认→销记。
 */
@Service
public class OperationService {

    /** 感度试验标准减压量(kPa), 恰 50 合格。 */
    static final int SENSITIVITY_REDUCTION_KPA = 50;
    /** 安定试验标准减压量(kPa), 恰 140 合格。 */
    static final int STABILITY_REDUCTION_KPA = 140;
    /** 关门车比例上限(%), 恰 6% 合法。 */
    static final int MAX_CUTOUT_PERCENT = 6;

    private final OperationRepository operationRepository;
    private final CarRepository carRepository;
    private final ConsistCheckRepository consistCheckRepository;
    private final BrakeTestRepository brakeTestRepository;
    private final PressureRepository pressureRepository;
    private final Clock clock;

    public OperationService(OperationRepository operationRepository, CarRepository carRepository,
                            ConsistCheckRepository consistCheckRepository,
                            BrakeTestRepository brakeTestRepository,
                            PressureRepository pressureRepository, Clock clock) {
        this.operationRepository = operationRepository;
        this.carRepository = carRepository;
        this.consistCheckRepository = consistCheckRepository;
        this.brakeTestRepository = brakeTestRepository;
        this.pressureRepository = pressureRepository;
        this.clock = clock;
    }

    /**
     * 接入: 建立作业并登记初始编组; 同股道已有作业中列车时唯一索引冲突返回 409。
     */
    @Transactional
    public ApiResult receive(ReceiveRequest request) {
        ensureNoDuplicateCarNo(request.cars());
        long operationId;
        try {
            operationId = operationRepository.insert(
                    request.trackNo(), request.trainNo(), request.setPressureKpa());
        } catch (DuplicateKeyException e) {
            throw new ApiException(409, "TRACK_OCCUPIED",
                    "股道 " + request.trackNo() + " 已存在作业中列车, 本次接入不予受理");
        }
        for (CarInput car : request.cars()) {
            carRepository.insert(operationId, 1, car);
        }
        return new ApiResult(201, new ReceiveResponse(
                operationId, OperationStatus.RECEIVED.name(), 1, request.cars().size()));
    }

    /**
     * 摘挂: 编组变更与试验结论作废在同一事务内完成, 变更后原结论一律不得沿用。
     */
    @Transactional
    public ApiResult shunt(long operationId, ShuntRequest request) {
        OperationRow operation = requireOperation(operationId);
        requireStatus(operation, OperationStatus.RECEIVED, "仅作业中列车允许摘挂");
        List<CarInput> attachCars = request.attachCars() == null ? List.of() : request.attachCars();
        List<String> detachCarNos = request.detachCarNos() == null ? List.of() : request.detachCarNos();
        if (attachCars.isEmpty() && detachCarNos.isEmpty()) {
            throw new ApiException(422, "EMPTY_SHUNT", "摘挂请求须至少包含一辆挂车或摘车");
        }
        List<CarRow> current = carRepository.findByOperationAndVersion(
                operationId, operation.consistVersion());
        Map<String, CarRow> currentByNo = new LinkedHashMap<>();
        for (CarRow car : current) {
            currentByNo.put(car.carNo(), car);
        }
        Set<String> detachSet = new LinkedHashSet<>(detachCarNos);
        for (String carNo : detachSet) {
            if (!currentByNo.containsKey(carNo)) {
                throw new ApiException(422, "CAR_NOT_FOUND", "车辆 " + carNo + " 不在当前编组中, 无法摘下");
            }
        }
        List<CarInput> nextConsist = new ArrayList<>();
        for (CarRow car : current) {
            if (!detachSet.contains(car.carNo())) {
                nextConsist.add(new CarInput(car.carNo(), car.lengthCoeff(), car.weightTons(),
                        car.brakeCutout()));
            }
        }
        nextConsist.addAll(attachCars);
        if (nextConsist.isEmpty()) {
            throw new ApiException(422, "EMPTY_CONSIST", "摘挂后编组不能为空");
        }
        ensureNoDuplicateCarNo(nextConsist);

        int newVersion = operation.consistVersion() + 1;
        for (CarInput car : nextConsist) {
            carRepository.insert(operationId, newVersion, car);
        }
        operationRepository.bumpConsistVersion(operationId, newVersion);
        brakeTestRepository.invalidateByOperation(operationId);

        return new ApiResult(200, new ShuntResponse(operationId, newVersion,
                nextConsist.size(), totalLength(nextConsist), totalWeight(nextConsist)));
    }

    /**
     * 编组核对: 与合成运统1 核对辆数/换长/总重, 不一致时发车确认将被拒绝。
     */
    @Transactional
    public ApiResult consistCheck(long operationId, ConsistCheckRequest request) {
        OperationRow operation = requireOperation(operationId);
        requireStatus(operation, OperationStatus.RECEIVED, "仅作业中列车允许编组核对");
        List<CarRow> cars = carRepository.findByOperationAndVersion(
                operationId, operation.consistVersion());
        int actualCars = cars.size();
        BigDecimal actualLength = totalLengthOfRows(cars);
        BigDecimal actualWeight = totalWeightOfRows(cars);
        boolean matched = actualCars == request.expectedCars()
                && actualLength.compareTo(request.expectedLength()) == 0
                && actualWeight.compareTo(request.expectedWeight()) == 0;
        consistCheckRepository.insert(operationId, operation.consistVersion(),
                request.expectedCars(), request.expectedLength(), request.expectedWeight(),
                actualCars, actualLength, actualWeight, matched);
        return new ApiResult(200, new ConsistCheckResponse(operationId, operation.consistVersion(),
                matched, request.expectedCars(), request.expectedLength(), request.expectedWeight(),
                actualCars, actualLength, actualWeight));
    }

    /**
     * 风压上报: 销记后迟到数据仅记迟到事件; 作业中恰达定压视为恢复, 允许下一项试验。
     */
    @Transactional
    public ApiResult reportPressure(long operationId, PressureRequest request) {
        OperationRow operation = requireOperation(operationId);
        boolean late = operation.status() == OperationStatus.CLOSED;
        LocalDateTime occurredAt = request.occurredAt() != null
                ? request.occurredAt() : LocalDateTime.now(clock);
        pressureRepository.insert(operationId, request.pressureKpa(), occurredAt, late);
        if (!late && operation.recoveryPending()
                && request.pressureKpa() >= operation.setPressureKpa()) {
            operationRepository.markRecovered(operationId);
        }
        return new ApiResult(200, new PressureResponse(operationId, true, late));
    }

    /**
     * 制动试验: 感度减压恰 50kPa 且全列制动为合格; 安定减压恰 140kPa 且无紧急制动为合格;
     * 安定试验前须有感度合格结论且风压已恢复至定压。
     */
    @Transactional
    public ApiResult brakeTest(long operationId, BrakeTestRequest request) {
        OperationRow operation = requireOperation(operationId);
        requireStatus(operation, OperationStatus.RECEIVED, "仅作业中列车允许制动试验");
        int version = operation.consistVersion();
        if (request.phase() == TestPhase.SENSITIVITY) {
            if (request.allCarsBraked() == null) {
                throw new ApiException(422, "MISSING_FIELD", "感度试验须提供 allCarsBraked(全列是否制动)");
            }
            TestResult result = request.reductionKpa() == SENSITIVITY_REDUCTION_KPA
                    && request.allCarsBraked() ? TestResult.PASS : TestResult.FAIL;
            brakeTestRepository.insert(operationId, version, TestPhase.SENSITIVITY,
                    request.reductionKpa(), request.allCarsBraked(), null, result);
            if (result == TestResult.PASS) {
                operationRepository.markRecoveryPending(operationId);
            }
            return new ApiResult(200, new BrakeTestResponse(operationId, version,
                    TestPhase.SENSITIVITY, request.reductionKpa(), result));
        }
        if (request.emergencyOccurred() == null) {
            throw new ApiException(422, "MISSING_FIELD", "安定试验须提供 emergencyOccurred(是否发生紧急制动)");
        }
        if (!brakeTestRepository.existsValidPass(operationId, version, TestPhase.SENSITIVITY)) {
            throw new ApiException(409, "SENSITIVITY_REQUIRED",
                    "安定试验前须在当前编组版本上完成合格的感度试验");
        }
        if (!operation.recoveryOk()) {
            throw new ApiException(409, "PRESSURE_NOT_RECOVERED",
                    "两项试验之间风压须恢复至定压 " + operation.setPressureKpa() + "kPa(恰达定压允许)");
        }
        TestResult result = request.reductionKpa() == STABILITY_REDUCTION_KPA
                && !request.emergencyOccurred() ? TestResult.PASS : TestResult.FAIL;
        brakeTestRepository.insert(operationId, version, TestPhase.STABILITY,
                request.reductionKpa(), null, request.emergencyOccurred(), result);
        return new ApiResult(200, new BrakeTestResponse(operationId, version,
                TestPhase.STABILITY, request.reductionKpa(), result));
    }

    /**
     * 发车确认: 运统1 核对一致、关门车比例不超过 6%(恰 6% 合法)、当前编组版本上感度与安定均合格;
     * 任一不满足则拒绝发车(HTTP 422, 不改变作业状态)。
     */
    @Transactional
    public ApiResult confirmDeparture(long operationId) {
        OperationRow operation = requireOperation(operationId);
        requireStatus(operation, OperationStatus.RECEIVED, "仅作业中列车允许发车确认");
        int version = operation.consistVersion();
        List<String> reasons = new ArrayList<>();

        var latestCheck = consistCheckRepository.findLatest(operationId, version);
        if (latestCheck.isEmpty()) {
            reasons.add("当前编组版本未完成运统1核对");
        } else if (!latestCheck.get().matched()) {
            reasons.add("编组与运统1不一致(辆数/换长/总重)");
        }

        List<CarRow> cars = carRepository.findByOperationAndVersion(operationId, version);
        long cutoutCount = cars.stream().filter(CarRow::brakeCutout).count();
        if (cutoutCount * 100 > (long) cars.size() * MAX_CUTOUT_PERCENT) {
            reasons.add("关门车比例超过6%: " + cutoutCount + "/" + cars.size());
        }

        if (!brakeTestRepository.existsValidPass(operationId, version, TestPhase.SENSITIVITY)) {
            reasons.add("感度试验未通过或未在当前编组版本上完成");
        }
        if (!brakeTestRepository.existsValidPass(operationId, version, TestPhase.STABILITY)) {
            reasons.add("安定试验未通过或未在当前编组版本上完成");
        }

        if (!reasons.isEmpty()) {
            return new ApiResult(422, new ConfirmResponse(operationId, false, reasons));
        }
        operationRepository.confirmDeparture(operationId);
        return new ApiResult(200, new ConfirmResponse(operationId, true, List.of()));
    }

    /**
     * 销记(终态): 重复销记返回幂等成功; 销记后股道释放, 迟到风压数据仅记迟到事件。
     */
    @Transactional
    public ApiResult close(long operationId) {
        OperationRow operation = requireOperation(operationId);
        if (operation.status() == OperationStatus.CLOSED) {
            return new ApiResult(200, new CloseResponse(operationId, true, true));
        }
        requireStatus(operation, OperationStatus.DEPARTURE_CONFIRMED, "须先完成发车确认方可销记");
        operationRepository.close(operationId);
        return new ApiResult(200, new CloseResponse(operationId, true, false));
    }

    /**
     * 作业详情查询。
     */
    @Transactional(readOnly = true)
    public OperationDetailResponse getOperation(long operationId) {
        OperationRow operation = requireOperation(operationId);
        List<CarRow> cars = carRepository.findByOperationAndVersion(
                operationId, operation.consistVersion());
        long cutoutCount = cars.stream().filter(CarRow::brakeCutout).count();
        return new OperationDetailResponse(operation.id(), operation.trackNo(), operation.trainNo(),
                operation.setPressureKpa(), operation.status(), operation.consistVersion(),
                cars.size(), cutoutCount);
    }

    /**
     * 关门车清单复算(默认当前编组版本)。
     */
    @Transactional(readOnly = true)
    public CutoutCarsResponse cutoutCars(long operationId, Integer version) {
        OperationRow operation = requireOperation(operationId);
        int consistVersion = version != null ? version : operation.consistVersion();
        List<CarRow> cars = carRepository.findByOperationAndVersion(operationId, consistVersion);
        if (cars.isEmpty()) {
            throw new ApiException(404, "VERSION_NOT_FOUND",
                    "编组版本 " + consistVersion + " 不存在");
        }
        List<String> cutoutCarNos = cars.stream()
                .filter(CarRow::brakeCutout).map(CarRow::carNo).toList();
        BigDecimal ratio = BigDecimal.valueOf(cutoutCarNos.size() * 100.0 / cars.size())
                .setScale(2, RoundingMode.HALF_UP);
        return new CutoutCarsResponse(operationId, consistVersion, cars.size(),
                cutoutCarNos.size(), ratio, cutoutCarNos);
    }

    /**
     * 编组版本复算: 每个版本的辆数/换长/总重。
     */
    @Transactional(readOnly = true)
    public ConsistVersionsResponse consistVersions(long operationId) {
        requireOperation(operationId);
        Map<Integer, List<CarRow>> byVersion = new LinkedHashMap<>();
        for (CarRow car : carRepository.findByOperation(operationId)) {
            byVersion.computeIfAbsent(car.consistVersion(), k -> new ArrayList<>()).add(car);
        }
        List<ConsistVersionsResponse.VersionSummary> versions = new ArrayList<>();
        byVersion.forEach((version, cars) -> versions.add(new ConsistVersionsResponse.VersionSummary(
                version, cars.size(), totalLengthOfRows(cars), totalWeightOfRows(cars))));
        return new ConsistVersionsResponse(operationId, versions);
    }

    /**
     * 制动试验曲线复算: 风压读数与试验事件按时间排列。
     */
    @Transactional(readOnly = true)
    public BrakeCurveResponse brakeCurve(long operationId) {
        requireOperation(operationId);
        List<BrakeCurveResponse.PressurePoint> pressures = new ArrayList<>();
        for (PressureReadingRow reading : pressureRepository.findByOperation(operationId)) {
            pressures.add(new BrakeCurveResponse.PressurePoint(
                    reading.occurredAt(), reading.pressureKpa(), reading.late()));
        }
        List<BrakeCurveResponse.TestPoint> tests = new ArrayList<>();
        for (BrakeTestRow test : brakeTestRepository.findByOperation(operationId)) {
            tests.add(new BrakeCurveResponse.TestPoint(test.createdAt(), test.phase(),
                    test.reductionKpa(), test.result(), test.valid(), test.consistVersion()));
        }
        return new BrakeCurveResponse(operationId, pressures, tests);
    }

    private OperationRow requireOperation(long operationId) {
        return operationRepository.findById(operationId)
                .orElseThrow(() -> new ApiException(404, "OPERATION_NOT_FOUND",
                        "作业 " + operationId + " 不存在"));
    }

    private void requireStatus(OperationRow operation, OperationStatus expected, String hint) {
        if (operation.status() != expected) {
            throw new ApiException(409, "INVALID_STATE",
                    hint + "(当前状态: " + operation.status() + ")");
        }
    }

    private void ensureNoDuplicateCarNo(List<CarInput> cars) {
        Set<String> seen = new LinkedHashSet<>();
        for (CarInput car : cars) {
            if (!seen.add(car.carNo())) {
                throw new ApiException(422, "DUPLICATE_CAR", "车辆号重复: " + car.carNo());
            }
        }
    }

    private BigDecimal totalLength(List<CarInput> cars) {
        return cars.stream().map(CarInput::lengthCoeff).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal totalWeight(List<CarInput> cars) {
        return cars.stream().map(CarInput::weightTons).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal totalLengthOfRows(List<CarRow> cars) {
        return cars.stream().map(CarRow::lengthCoeff).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal totalWeightOfRows(List<CarRow> cars) {
        return cars.stream().map(CarRow::weightTons).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
