package com.example.starter.rt.service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.rt.domain.ComplexityLevel;
import com.example.starter.rt.domain.OperationType;
import com.example.starter.rt.domain.PlanStatus;
import com.example.starter.rt.domain.ScheduleBatchStatus;
import com.example.starter.rt.domain.ScheduleItemStatus;
import com.example.starter.rt.dto.DeviceCapabilityRequest;
import com.example.starter.rt.dto.DeviceRegisterRequest;
import com.example.starter.rt.dto.DeviceResponse;
import com.example.starter.rt.dto.OccupancyItemView;
import com.example.starter.rt.dto.RecordedResponse;
import com.example.starter.rt.dto.RejectionBody;
import com.example.starter.rt.dto.ScheduleCancelResponse;
import com.example.starter.rt.dto.ScheduleFractionItem;
import com.example.starter.rt.dto.ScheduleItemView;
import com.example.starter.rt.dto.SchedulePublishRequest;
import com.example.starter.rt.dto.SchedulePublishResponse;
import com.example.starter.rt.dto.TreatmentRequest;
import com.example.starter.rt.dto.TreatmentResponse;
import com.example.starter.rt.repository.PlanRepository;
import com.example.starter.rt.repository.PlanRepository.ActivePlanRow;
import com.example.starter.rt.repository.PlanRepository.PlanVersionRow;
import com.example.starter.rt.repository.SchedulingRepository;
import com.example.starter.rt.repository.SchedulingRepository.DeviceRow;
import com.example.starter.rt.repository.SchedulingRepository.ScheduleBatchRow;
import com.example.starter.rt.repository.SchedulingRepository.ScheduleItemRow;
import com.example.starter.rt.repository.WorkflowRepository;
import com.example.starter.rt.web.ApiException;

/**
 * 设备与连续分次批量排程服务（合成规则，不依据真实临床规则）。
 *
 * <p>核心规则：设备可执行复杂度等级 1 至 3，每分次所需分钟数固定 10/20/30；
 * UTC 半开窗口 [start, end) 长度须等于等级分钟数，端点相接可共存；
 * 同设备窗口重叠或同分次双占拒绝；单批最多 10 个连续分次，任一冲突整批零占用；
 * 发布冻结计划版本/等级/设备/窗口并原子占用；完成原子释放占用并冻结实绩；
 * 计划改版取消未开始排程并释放资源；设备能力下调只阻断尚未开始项。</p>
 *
 * <p>并发控制：全局锁顺序为患者 → 设备 → 批次/占用项；能力调整仅锁设备（不反向要患者锁），
 * 不构成锁环。新增写操作仅冻结成功结果（失败不占键），业务、版本与幂等结果同事务。</p>
 */
@Service
public class SchedulingService {

    private static final String CANCEL_MANUAL = "MANUAL";

    private final PlanRepository planRepository;
    private final SchedulingRepository schedulingRepository;
    private final WorkflowRepository workflowRepository;
    private final IdempotencyTemplate idempotency;
    private final Clock clock;

    public SchedulingService(PlanRepository planRepository, SchedulingRepository schedulingRepository,
                             WorkflowRepository workflowRepository, IdempotencyTemplate idempotency,
                             Clock clock) {
        this.planRepository = planRepository;
        this.schedulingRepository = schedulingRepository;
        this.workflowRepository = workflowRepository;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    // ============================ 设备登记与能力调整 ============================

    /** 设备登记：版本从 1 开始；同 ID 重复登记 409。 */
    @Transactional
    public RecordedResponse registerDevice(DeviceRegisterRequest request) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("deviceId", request.deviceId());
        params.put("deviceName", request.deviceName());
        params.put("maxComplexityLevel", request.maxComplexityLevel());
        return idempotency.executeSuccessOnly(OperationType.DEVICE_REGISTER, request.requestId(),
                params, () -> doRegisterDevice(request));
    }

    private RecordedResponse doRegisterDevice(DeviceRegisterRequest request) {
        LocalDateTime now = LocalDateTime.now(clock);
        try {
            schedulingRepository.insertDevice(request.deviceId(), request.deviceName(),
                    request.maxComplexityLevel(), now);
        } catch (DuplicateKeyException e) {
            return reject(409, "DEVICE_EXISTS", "设备已存在：" + request.deviceId());
        }
        return new RecordedResponse(201, new DeviceResponse(request.deviceId(),
                request.deviceName(), request.maxComplexityLevel(), 1, request.requestId(), false));
    }

    /** 设备能力调整：携带 expectedVersion 乐观并发；下调只阻断尚未开始项，不改写已完成实绩。 */
    @Transactional
    public RecordedResponse updateCapability(String deviceId, DeviceCapabilityRequest request) {
        DeviceRow device = schedulingRepository.lockDevice(deviceId)
                .orElseThrow(() -> ApiException.notFound("DEVICE_NOT_FOUND", "设备不存在：" + deviceId));
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("deviceId", deviceId);
        params.put("maxComplexityLevel", request.maxComplexityLevel());
        params.put("expectedVersion", request.expectedVersion());
        return idempotency.executeSuccessOnly(OperationType.DEVICE_CAPABILITY, request.requestId(),
                params, () -> doUpdateCapability(device, request));
    }

    private RecordedResponse doUpdateCapability(DeviceRow device, DeviceCapabilityRequest request) {
        if (device.version() != request.expectedVersion()) {
            return reject(409, "VERSION_CONFLICT",
                    "设备当前版本 " + device.version() + " 与期望版本 " + request.expectedVersion()
                            + " 不一致，请刷新后重试");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        int updated = schedulingRepository.updateCapability(device.deviceId(),
                request.maxComplexityLevel(), request.expectedVersion(), now);
        if (updated == 0) {
            return reject(409, "VERSION_CONFLICT", "设备版本并发冲突，仅一个变更生效");
        }
        return new RecordedResponse(200, new DeviceResponse(device.deviceId(), device.deviceName(),
                request.maxComplexityLevel(), device.version() + 1, request.requestId(), false));
    }

    // ============================ 连续分次批量排程发布 ============================

    /**
     * 批量排程发布：只引用已批准且首次验证合格的当前计划及尚未执行分次；
     * 任一冲突整批零占用（校验全部通过后同事务一次性写入）。
     */
    @Transactional
    public RecordedResponse publish(SchedulePublishRequest request) {
        List<ScheduleFractionItem> sorted = validateAndSortFractions(request);
        ensurePatientLock(request.patientId());
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("patientId", request.patientId());
        params.put("planCode", request.planCode());
        params.put("level", request.level());
        params.put("deviceId", request.deviceId());
        params.put("fractions", fractionParams(sorted));
        return idempotency.executeSuccessOnly(OperationType.SCHEDULE_PUBLISH, request.requestId(),
                params, () -> doPublish(request, sorted));
    }

    private RecordedResponse doPublish(SchedulePublishRequest request,
                                       List<ScheduleFractionItem> sorted) {
        ActivePlanRow active = planRepository.findActivePlan(request.patientId()).orElse(null);
        if (active == null) {
            return reject(422, "NO_ACTIVE_PLAN", "患者不存在执行中计划，不能发布排程");
        }
        if ("CLOSED".equals(active.status())) {
            return reject(409, "PATIENT_COURSE_CLOSED", "患者疗程已结案（终态），不能发布排程");
        }
        if (!active.planCode().equals(request.planCode())) {
            return reject(409, "ANOTHER_PLAN_ACTIVE",
                    "患者当前执行中计划为 " + active.planCode() + "，与请求计划 " + request.planCode() + " 不一致");
        }
        PlanVersionRow version = planRepository.lockVersionById(active.planVersionId())
                .orElseThrow(() -> ApiException.notFound("PLAN_VERSION_NOT_FOUND", "执行中计划版本不存在"));
        if (!PlanStatus.VERIFIED.name().equals(version.status())
                && !PlanStatus.EXECUTING.name().equals(version.status())) {
            return reject(422, "PLAN_NOT_VERIFIED",
                    "排程只引用已批准且首次验证合格的当前计划（当前状态 " + version.status() + "）");
        }
        DeviceRow device = schedulingRepository.lockDevice(request.deviceId())
                .orElseThrow(() -> ApiException.notFound("DEVICE_NOT_FOUND",
                        "设备不存在：" + request.deviceId()));
        if (request.level() > device.maxComplexityLevel()) {
            return reject(422, "DEVICE_CAPABILITY_INSUFFICIENT",
                    "设备 " + device.deviceId() + " 当前可执行等级上限 " + device.maxComplexityLevel()
                            + "，低于排程等级 " + request.level());
        }

        for (ScheduleFractionItem item : sorted) {
            if (item.fractionNo() > version.fractionCount()) {
                return reject(422, "FRACTION_OUT_OF_PRESCRIPTION",
                        "分次 " + item.fractionNo() + " 超出处方分次数 " + version.fractionCount());
            }
            if (workflowRepository.findFraction(request.patientId(), request.planCode(),
                    item.fractionNo()).isPresent()) {
                return reject(422, "FRACTION_ALREADY_EXECUTED",
                        "分次 " + item.fractionNo() + " 已执行，排程只引用尚未执行分次");
            }
            if (schedulingRepository.countActiveFractionOccupancy(request.patientId(),
                    request.planCode(), item.fractionNo()) > 0) {
                return reject(409, "FRACTION_ALREADY_SCHEDULED",
                        "分次 " + item.fractionNo() + " 已存在未开始排程，同分次不允许双占");
            }
            LocalDateTime startUtc = toUtc(item.windowStartUtc());
            LocalDateTime endUtc = toUtc(item.windowEndUtc());
            if (schedulingRepository.countDeviceOverlap(device.deviceId(), startUtc, endUtc) > 0) {
                return reject(409, "DEVICE_WINDOW_OVERLAP",
                        "设备 " + device.deviceId() + " 在窗口 [" + item.windowStartUtc().toInstant()
                                + ", " + item.windowEndUtc().toInstant() + ") 已被占用，同设备重叠拒绝");
            }
        }

        LocalDateTime now = LocalDateTime.now(clock);
        long batchId = schedulingRepository.insertBatch(request.patientId(), request.planCode(),
                version.id(), request.level(), device.deviceId(), request.requestId(), now);
        List<ScheduleItemView> items = new ArrayList<>();
        for (ScheduleFractionItem item : sorted) {
            schedulingRepository.insertItem(batchId, request.patientId(), request.planCode(),
                    version.id(), device.deviceId(), request.level(), item.fractionNo(),
                    toUtc(item.windowStartUtc()), toUtc(item.windowEndUtc()));
            items.add(new ScheduleItemView(null, item.fractionNo(),
                    item.windowStartUtc().toInstant().atOffset(ZoneOffset.UTC),
                    item.windowEndUtc().toInstant().atOffset(ZoneOffset.UTC),
                    ScheduleItemStatus.SCHEDULED.name(), null, null, null));
        }
        SchedulePublishResponse response = new SchedulePublishResponse(batchId, request.patientId(),
                request.planCode(), version.id(), version.versionNo(), request.level(),
                device.deviceId(), items, 1, request.requestId(), false);
        return new RecordedResponse(201, response);
    }

    // ============================ 排程批次取消 ============================

    /** 人工取消批次：释放全部未开始项占用；携带 expectedVersion 乐观并发。 */
    @Transactional
    public RecordedResponse cancel(long scheduleId, Integer expectedVersion, String requestId) {
        ScheduleBatchRow batch = schedulingRepository.findBatch(scheduleId)
                .orElseThrow(() -> ApiException.notFound("SCHEDULE_NOT_FOUND",
                        "排程批次不存在：" + scheduleId));
        ensurePatientLock(batch.patientId());
        ScheduleBatchRow locked = schedulingRepository.lockBatch(scheduleId)
                .orElseThrow(() -> ApiException.notFound("SCHEDULE_NOT_FOUND",
                        "排程批次不存在：" + scheduleId));
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("scheduleId", scheduleId);
        params.put("expectedVersion", expectedVersion);
        return idempotency.executeSuccessOnly(OperationType.SCHEDULE_CANCEL, requestId, params,
                () -> doCancel(locked, expectedVersion, requestId));
    }

    private RecordedResponse doCancel(ScheduleBatchRow batch, int expectedVersion, String requestId) {
        if (!ScheduleBatchStatus.ACTIVE.name().equals(batch.status())) {
            return reject(422, "SCHEDULE_NOT_ACTIVE",
                    "排程批次当前状态 " + batch.status() + "，仅 ACTIVE 可取消");
        }
        if (batch.version() != expectedVersion) {
            return reject(409, "VERSION_CONFLICT",
                    "排程批次当前版本 " + batch.version() + " 与期望版本 " + expectedVersion + " 不一致");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        int cancelled = schedulingRepository.cancelScheduledItems(batch.id(), CANCEL_MANUAL);
        int updated = schedulingRepository.cancelBatch(batch.id(), expectedVersion, now);
        if (updated == 0) {
            return reject(409, "VERSION_CONFLICT", "排程批次版本并发冲突，仅一个变更生效");
        }
        return new RecordedResponse(200, new ScheduleCancelResponse(batch.id(),
                ScheduleBatchStatus.CANCELLED.name(), cancelled, batch.version() + 1,
                requestId, false));
    }

    // ============================ 排程受控治疗记录 ============================

    /**
     * 受控治疗入口：须在排程窗口内、匹配排程/计划版本、遵守原顺序分次规则；
     * 完成原子释放占用并冻结实绩（与分次台账同事务）。
     */
    @Transactional
    public RecordedResponse treat(TreatmentRequest request) {
        ensurePatientLock(request.patientId());
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("patientId", request.patientId());
        params.put("planCode", request.planCode());
        params.put("scheduleId", request.scheduleId());
        params.put("fractionNo", request.fractionNo());
        params.put("deliveredDoseCgy", request.deliveredDoseCgy());
        return idempotency.executeSuccessOnly(OperationType.TREATMENT, request.requestId(), params,
                () -> doTreat(request));
    }

    private RecordedResponse doTreat(TreatmentRequest request) {
        ScheduleItemRow item = schedulingRepository.lockItem(request.scheduleId(),
                        request.fractionNo())
                .orElseThrow(() -> ApiException.notFound("SCHEDULE_ITEM_NOT_FOUND",
                        "排程项不存在：批次 " + request.scheduleId() + " 分次 " + request.fractionNo()));
        if (!item.patientId().equals(request.patientId()) || !item.planCode().equals(request.planCode())) {
            throw ApiException.notFound("SCHEDULE_ITEM_NOT_FOUND",
                    "排程项不属于该患者/计划：批次 " + request.scheduleId() + " 分次 " + request.fractionNo());
        }
        if (ScheduleItemStatus.CANCELLED.name().equals(item.status())) {
            return reject(422, "SCHEDULE_ITEM_CANCELLED",
                    "排程项已取消（原因 " + item.cancelReason() + "），不能用于治疗");
        }
        if (ScheduleItemStatus.COMPLETED.name().equals(item.status())) {
            return reject(409, "FRACTION_ALREADY_RECORDED",
                    "分次 " + request.fractionNo() + " 已完成治疗，拒绝重复登记");
        }

        LocalDateTime nowUtc = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        if (nowUtc.isBefore(item.windowStartUtc()) || !nowUtc.isBefore(item.windowEndUtc())) {
            return reject(422, "OUTSIDE_SCHEDULE_WINDOW",
                    "当前时刻不在排程窗口 [" + item.windowStartUtc() + ", " + item.windowEndUtc()
                            + ")（UTC）内，不能执行治疗");
        }

        ActivePlanRow active = planRepository.findActivePlan(request.patientId()).orElse(null);
        if (active == null) {
            return reject(422, "NO_ACTIVE_PLAN", "患者不存在执行中计划，不能登记治疗");
        }
        if ("CLOSED".equals(active.status())) {
            return reject(409, "PATIENT_COURSE_CLOSED", "患者疗程已结案（终态），不能登记治疗");
        }
        if (!active.planCode().equals(request.planCode())) {
            return reject(409, "ANOTHER_PLAN_ACTIVE",
                    "患者当前执行中计划为 " + active.planCode() + "，与请求计划 " + request.planCode() + " 不一致");
        }
        if (active.planVersionId() != item.planVersionId()) {
            return reject(422, "PLAN_VERSION_MISMATCH",
                    "排程冻结的计划版本已非当前可执行版本，不能用失效版本治疗");
        }
        PlanVersionRow version = planRepository.lockVersionById(active.planVersionId())
                .orElseThrow(() -> ApiException.notFound("PLAN_VERSION_NOT_FOUND", "执行中计划版本不存在"));
        if (!PlanStatus.VERIFIED.name().equals(version.status())
                && !PlanStatus.EXECUTING.name().equals(version.status())) {
            return reject(422, "PLAN_VERSION_NOT_EXECUTABLE",
                    "计划版本当前状态 " + version.status() + "，不能登记治疗");
        }
        DeviceRow device = schedulingRepository.lockDevice(item.deviceId())
                .orElseThrow(() -> ApiException.notFound("DEVICE_NOT_FOUND",
                        "设备不存在：" + item.deviceId()));
        if (device.maxComplexityLevel() < item.complexityLevel()) {
            return reject(422, "DEVICE_CAPABILITY_INSUFFICIENT",
                    "设备 " + device.deviceId() + " 能力已下调至等级 " + device.maxComplexityLevel()
                            + "，低于排程冻结等级 " + item.complexityLevel() + "，该未开始项被阻断");
        }

        int recorded = workflowRepository.countFractions(request.patientId(), request.planCode());
        int expected = recorded + 1;
        if (request.fractionNo() < expected) {
            return reject(409, "FRACTION_ALREADY_RECORDED",
                    "分次 " + request.fractionNo() + " 已存在，按患者+计划+分次号去重，拒绝重复登记");
        }
        if (request.fractionNo() > expected) {
            return new RecordedResponse(409, RejectionBody.alert("FRACTION_SKIP_DETECTED",
                    "跳次执行：下一分次应为第 " + expected + " 次，请求第 " + request.fractionNo()
                            + " 次，拒绝并告警"));
        }

        LocalDateTime now = LocalDateTime.now(clock);
        try {
            workflowRepository.insertFraction(request.patientId(), request.planCode(), version.id(),
                    request.fractionNo(), request.deliveredDoseCgy(), request.requestId(), now);
        } catch (DuplicateKeyException e) {
            return reject(409, "FRACTION_ALREADY_RECORDED",
                    "分次 " + request.fractionNo() + " 已存在（并发去重），拒绝重复登记");
        }
        int completed = schedulingRepository.completeItem(item.id(), nowUtc,
                request.deliveredDoseCgy(), request.requestId());
        if (completed == 0) {
            return reject(409, "SCHEDULE_ITEM_RACE", "排程项状态并发冲突，仅一个治疗生效");
        }
        schedulingRepository.completeBatchIfDone(item.batchId());
        planRepository.markExecutingIfFirst(version.id(), now);

        TreatmentResponse response = new TreatmentResponse(item.id(), item.batchId(),
                request.patientId(), request.planCode(), version.id(), version.versionNo(),
                request.fractionNo(), request.deliveredDoseCgy(), request.requestId(), false,
                nowUtc.atOffset(ZoneOffset.UTC));
        return new RecordedResponse(200, response);
    }

    // ============================ 占用查询 ============================

    /** 设备占用查询：返回与指定 UTC 范围重叠的全部排程项（含各状态），范围缺省为全时段。 */
    @Transactional(readOnly = true)
    public List<OccupancyItemView> occupancy(String deviceId, OffsetDateTime fromUtc,
                                             OffsetDateTime toUtc) {
        schedulingRepository.findDevice(deviceId)
                .orElseThrow(() -> ApiException.notFound("DEVICE_NOT_FOUND", "设备不存在：" + deviceId));
        LocalDateTime from = fromUtc == null
                ? LocalDateTime.of(1970, 1, 1, 0, 0) : toUtc(fromUtc);
        LocalDateTime to = toUtc == null
                ? LocalDateTime.of(9999, 1, 1, 0, 0) : toUtc(toUtc);
        return schedulingRepository.findDeviceItems(deviceId, from, to).stream()
                .map(this::toOccupancyView).toList();
    }

    private OccupancyItemView toOccupancyView(ScheduleItemRow item) {
        return new OccupancyItemView(item.id(), item.batchId(), item.patientId(), item.planCode(),
                item.planVersionId(), item.complexityLevel(), item.fractionNo(),
                item.windowStartUtc().atOffset(ZoneOffset.UTC),
                item.windowEndUtc().atOffset(ZoneOffset.UTC), item.status(),
                item.deliveredDoseCgy());
    }

    // ============================ 内部辅助 ============================

    /**
     * 参数级校验（400）：窗口为半开区间且长度恰为等级分钟数；分次号无重复、连续递增；
     * 批内窗口互不重叠（端点相接合法）。返回按分次号升序的列表。
     */
    private List<ScheduleFractionItem> validateAndSortFractions(SchedulePublishRequest request) {
        if (request.fractions().isEmpty() || request.fractions().size() > 10) {
            throw new ApiException(400, "VALIDATION_FAILED",
                    "单批分次数须为1至10个，当前 " + request.fractions().size() + " 个");
        }
        int requiredMinutes = ComplexityLevel.requiredMinutes(request.level());
        List<ScheduleFractionItem> sorted = new ArrayList<>(request.fractions());
        sorted.sort(Comparator.comparing(ScheduleFractionItem::fractionNo));
        for (int i = 0; i < sorted.size(); i++) {
            ScheduleFractionItem item = sorted.get(i);
            if (i > 0 && sorted.get(i - 1).fractionNo() == item.fractionNo()) {
                throw new ApiException(400, "VALIDATION_FAILED",
                        "分次号重复：" + item.fractionNo());
            }
            if (i > 0 && sorted.get(i - 1).fractionNo() + 1 != item.fractionNo()) {
                throw new ApiException(400, "VALIDATION_FAILED",
                        "分次号须连续递增：第 " + sorted.get(i - 1).fractionNo() + " 次之后为第 "
                                + item.fractionNo() + " 次");
            }
            if (!item.windowEndUtc().toInstant().isAfter(item.windowStartUtc().toInstant())) {
                throw new ApiException(400, "VALIDATION_FAILED",
                        "窗口终点须晚于起点：分次 " + item.fractionNo());
            }
            long minutes = Duration.between(item.windowStartUtc().toInstant(),
                    item.windowEndUtc().toInstant()).toMinutes();
            long seconds = Duration.between(item.windowStartUtc().toInstant(),
                    item.windowEndUtc().toInstant()).getSeconds();
            if (minutes != requiredMinutes || seconds != requiredMinutes * 60L) {
                throw new ApiException(400, "VALIDATION_FAILED",
                        "等级 " + request.level() + " 每分次窗口长度须恰为 " + requiredMinutes
                                + " 分钟：分次 " + item.fractionNo());
            }
        }
        List<ScheduleFractionItem> byStart = new ArrayList<>(sorted);
        byStart.sort(Comparator.comparing(ScheduleFractionItem::windowStartUtc));
        for (int i = 1; i < byStart.size(); i++) {
            if (byStart.get(i).windowStartUtc().toInstant()
                    .isBefore(byStart.get(i - 1).windowEndUtc().toInstant())) {
                throw new ApiException(400, "VALIDATION_FAILED",
                        "同批窗口互相重叠：分次 " + byStart.get(i).fractionNo());
            }
        }
        return sorted;
    }

    private List<Map<String, Object>> fractionParams(List<ScheduleFractionItem> sorted) {
        List<Map<String, Object>> fractions = new ArrayList<>();
        for (ScheduleFractionItem item : sorted) {
            Map<String, Object> fraction = new LinkedHashMap<>();
            fraction.put("fractionNo", item.fractionNo());
            fraction.put("windowStartUtc", item.windowStartUtc());
            fraction.put("windowEndUtc", item.windowEndUtc());
            fractions.add(fraction);
        }
        return fractions;
    }

    private LocalDateTime toUtc(OffsetDateTime time) {
        return LocalDateTime.ofInstant(time.toInstant(), ZoneOffset.UTC);
    }

    private RecordedResponse reject(int httpStatus, String code, String message) {
        return new RecordedResponse(httpStatus, new RejectionBody(code, message, false, List.of()));
    }

    private void ensurePatientLock(String patientId) {
        if (!planRepository.lockPatient(patientId)) {
            throw ApiException.notFound("PATIENT_NOT_FOUND", "患者不存在：" + patientId);
        }
    }
}
