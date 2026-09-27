package com.example.starter.rt.service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
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
import com.example.starter.rt.domain.ScheduleItemStatus;
import com.example.starter.rt.domain.ScheduleStatus;
import com.example.starter.rt.dto.DeviceOccupancyItem;
import com.example.starter.rt.dto.DeviceRegisterRequest;
import com.example.starter.rt.dto.DeviceResponse;
import com.example.starter.rt.dto.DeviceUpdateRequest;
import com.example.starter.rt.dto.RecordedResponse;
import com.example.starter.rt.dto.RejectionBody;
import com.example.starter.rt.dto.ScheduleCancelRequest;
import com.example.starter.rt.dto.ScheduleItemRequest;
import com.example.starter.rt.dto.ScheduleItemView;
import com.example.starter.rt.dto.SchedulePublishRequest;
import com.example.starter.rt.dto.ScheduleResponse;
import com.example.starter.rt.dto.TreatmentRequest;
import com.example.starter.rt.dto.TreatmentResponse;
import com.example.starter.rt.repository.PlanRepository;
import com.example.starter.rt.repository.PlanRepository.ActivePlanRow;
import com.example.starter.rt.repository.PlanRepository.PlanVersionRow;
import com.example.starter.rt.repository.SchedulingRepository;
import com.example.starter.rt.repository.SchedulingRepository.DeviceRow;
import com.example.starter.rt.repository.SchedulingRepository.ScheduleItemRow;
import com.example.starter.rt.repository.SchedulingRepository.ScheduleRow;
import com.example.starter.rt.repository.WorkflowRepository;
import com.example.starter.rt.web.ApiException;

/**
 * 分次设备时长配额与排程门禁服务（合成规则，非临床规则）：
 * <ul>
 *   <li>设备登记复杂度等级上限 1-3，等级固定对应每分次 10/20/30 分钟；</li>
 *   <li>排程发布冻结计划版本/等级/设备/窗口并原子占用资源，单次批量最多 10 个连续分次，
 *       同设备窗口重叠（半开区间，端点相接可共存）或同分次双占整批拒绝、零占用；</li>
 *   <li>受控治疗记录须在窗口内、匹配排程与当前计划版本、遵守顺序分次规则，
 *       完成即原子释放占用并冻结实绩；</li>
 *   <li>计划改版由批准流程同事务取消旧版本未开始排程；设备能力下调只阻断尚未开始项。</li>
 * </ul>
 *
 * <p>并发控制：所有排程写操作先锁患者主记录（SELECT ... FOR UPDATE），再锁设备/排程/排程项，
 * 锁顺序全局一致（患者→设备/批次/明细），同患者并发请求被行锁串行化形成唯一结果。</p>
 *
 * <p>幂等：新增写操作仅首次成功（2xx）占键，业务拒绝不占键；同键同参重放首次冻结结果，
 * 同键异参 409；业务写库、版本号变更与幂等记录同事务提交。</p>
 */
@Service
public class SchedulingService {

    private final SchedulingRepository schedulingRepository;
    private final PlanRepository planRepository;
    private final WorkflowRepository workflowRepository;
    private final IdempotencyTemplate idempotency;
    private final Clock clock;

    public SchedulingService(SchedulingRepository schedulingRepository, PlanRepository planRepository,
                             WorkflowRepository workflowRepository, IdempotencyTemplate idempotency,
                             Clock clock) {
        this.schedulingRepository = schedulingRepository;
        this.planRepository = planRepository;
        this.workflowRepository = workflowRepository;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    // ============================ 设备登记与能力变更 ============================

    /** 设备登记：版本从 1 开始；同 ID 重复登记 409。 */
    @Transactional
    public RecordedResponse registerDevice(DeviceRegisterRequest request) {
        Map<String, Object> structure = new LinkedHashMap<>();
        structure.put("deviceId", request.deviceId());
        structure.put("deviceName", request.deviceName());
        structure.put("maxLevel", request.maxLevel());
        return idempotency.executeOnSuccess(OperationType.DEVICE_REGISTER, request.requestId(),
                structure, () -> doRegisterDevice(request));
    }

    private RecordedResponse doRegisterDevice(DeviceRegisterRequest request) {
        LocalDateTime now = LocalDateTime.now(clock);
        try {
            schedulingRepository.insertDevice(request.deviceId(), request.deviceName(),
                    request.maxLevel(), now);
        } catch (DuplicateKeyException e) {
            return new RecordedResponse(409, RejectionBody.of("DEVICE_EXISTS",
                    "设备已存在：" + request.deviceId()));
        }
        DeviceRow device = schedulingRepository.findDevice(request.deviceId()).orElseThrow();
        return new RecordedResponse(201, toDeviceResponse(device, request.requestId(), false));
    }

    /** 设备能力变更：携带 expectedVersion，成功版本加 1；下调只阻断尚未开始项。 */
    @Transactional
    public RecordedResponse updateDevice(String deviceId, DeviceUpdateRequest request) {
        Map<String, Object> structure = new LinkedHashMap<>();
        structure.put("deviceId", deviceId);
        structure.put("maxLevel", request.maxLevel());
        structure.put("expectedVersion", request.expectedVersion());
        return idempotency.executeOnSuccess(OperationType.DEVICE_UPDATE, request.requestId(),
                structure, () -> doUpdateDevice(deviceId, request));
    }

    private RecordedResponse doUpdateDevice(String deviceId, DeviceUpdateRequest request) {
        DeviceRow device = schedulingRepository.lockDevice(deviceId)
                .orElseThrow(() -> ApiException.notFound("DEVICE_NOT_FOUND", "设备不存在：" + deviceId));
        if (device.version() != request.expectedVersion()) {
            return new RecordedResponse(409, RejectionBody.of("VERSION_CONFLICT",
                    "设备版本冲突：期望 " + request.expectedVersion() + "，当前 " + device.version()));
        }
        schedulingRepository.updateDeviceLevel(deviceId, request.maxLevel(), LocalDateTime.now(clock));
        DeviceRow updated = schedulingRepository.findDevice(deviceId).orElseThrow();
        return new RecordedResponse(200, toDeviceResponse(updated, request.requestId(), false));
    }

    // ============================ 排程发布 ============================

    /**
     * 排程批量发布：仅引用已批准且首次验证合格的当前计划及尚未执行分次；
     * 窗口为 UTC 半开区间且长度等于等级分钟数；任一冲突整批零占用。
     */
    @Transactional
    public RecordedResponse publish(SchedulePublishRequest request) {
        validatePublishParams(request);
        ensurePatientLock(request.patientId());
        return idempotency.executeOnSuccess(OperationType.SCHEDULE_PUBLISH, request.requestId(),
                publishStructure(request), () -> doPublish(request));
    }

    private RecordedResponse doPublish(SchedulePublishRequest request) {
        LocalDateTime now = LocalDateTime.now(clock);
        ActivePlanRow active = planRepository.findActivePlan(request.patientId()).orElse(null);
        if (active == null || !"ACTIVE".equals(active.status())) {
            return reject(422, "NO_ACTIVE_PLAN", "患者不存在执行中计划，不能发布排程");
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
        if (request.level() > device.maxLevel()) {
            return reject(422, "DEVICE_CAPABILITY_INSUFFICIENT",
                    "设备 " + request.deviceId() + " 可执行等级上限 " + device.maxLevel()
                            + "，低于排程等级 " + request.level());
        }

        int executed = workflowRepository.countFractions(request.patientId(), request.planCode());
        List<ScheduleItemRow> scheduled =
                schedulingRepository.findScheduledItems(request.patientId(), request.planCode());
        for (ScheduleItemRequest item : request.items()) {
            if (item.fractionNo() <= executed) {
                return reject(422, "FRACTION_ALREADY_EXECUTED",
                        "分次 " + item.fractionNo() + " 已执行，排程只引用尚未执行分次");
            }
            boolean doubleBooked = scheduled.stream()
                    .anyMatch(s -> s.fractionNo() == item.fractionNo());
            if (doubleBooked) {
                return reject(422, "FRACTION_ALREADY_SCHEDULED",
                        "分次 " + item.fractionNo() + " 已有生效排程，同分次双占拒绝");
            }
        }

        // 同设备窗口重叠校验：半开区间，端点相接可共存；含批次内自重叠。
        List<Window> windows = request.items().stream()
                .map(i -> new Window(i.fractionNo(), toUtc(i.windowStartUtc()), toUtc(i.windowEndUtc())))
                .sorted(Comparator.comparing(Window::start)).toList();
        for (int i = 1; i < windows.size(); i++) {
            if (windows.get(i).start().isBefore(windows.get(i - 1).end())) {
                return reject(422, "DEVICE_WINDOW_OVERLAP",
                        "批次内分次 " + windows.get(i).fractionNo() + " 与分次 "
                                + windows.get(i - 1).fractionNo() + " 窗口重叠，同设备重叠拒绝");
            }
        }
        for (Window window : windows) {
            if (!schedulingRepository.findOverlappingItems(
                    request.deviceId(), window.start(), window.end()).isEmpty()) {
                return reject(422, "DEVICE_WINDOW_OVERLAP",
                        "设备 " + request.deviceId() + " 在分次 " + window.fractionNo()
                                + " 的窗口已被占用，同设备重叠拒绝");
            }
        }

        long scheduleId = schedulingRepository.insertSchedule(request.patientId(), request.planCode(),
                version.id(), request.level(), request.deviceId(), request.requestId(), now);
        for (ScheduleItemRequest item : request.items()) {
            schedulingRepository.insertItem(scheduleId, request.patientId(), request.planCode(),
                    version.id(), item.fractionNo(), request.deviceId(), request.level(),
                    toUtc(item.windowStartUtc()), toUtc(item.windowEndUtc()), now);
        }
        ScheduleRow schedule = schedulingRepository.findSchedule(scheduleId).orElseThrow();
        return new RecordedResponse(201,
                toScheduleResponse(schedule, version.versionNo(), request.requestId(), false));
    }

    /** 参数合法性校验（400）：窗口长度=等级分钟数、半开区间、分次号连续且不重复。 */
    private void validatePublishParams(SchedulePublishRequest request) {
        long expectedMinutes = ComplexityLevel.minutesOf(request.level());
        for (ScheduleItemRequest item : request.items()) {
            if (!item.windowEndUtc().isAfter(item.windowStartUtc())) {
                throw new ApiException(400, "VALIDATION_FAILED",
                        "分次 " + item.fractionNo() + " 窗口结束须晚于开始（半开区间）");
            }
            long seconds = Duration.between(item.windowStartUtc(), item.windowEndUtc()).getSeconds();
            if (seconds != expectedMinutes * 60) {
                throw new ApiException(400, "VALIDATION_FAILED",
                        "分次 " + item.fractionNo() + " 窗口长度须等于等级 " + request.level()
                                + " 对应的 " + expectedMinutes + " 分钟");
            }
        }
        List<Integer> fractionNos = request.items().stream()
                .map(ScheduleItemRequest::fractionNo).sorted().toList();
        for (int i = 1; i < fractionNos.size(); i++) {
            if (fractionNos.get(i).equals(fractionNos.get(i - 1))) {
                throw new ApiException(400, "VALIDATION_FAILED",
                        "批次内分次号重复：" + fractionNos.get(i));
            }
            if (fractionNos.get(i) != fractionNos.get(i - 1) + 1) {
                throw new ApiException(400, "VALIDATION_FAILED",
                        "单次批量须为连续分次：" + fractionNos.get(i - 1) + " 与 "
                                + fractionNos.get(i) + " 不连续");
            }
        }
    }

    private Object publishStructure(SchedulePublishRequest request) {
        Map<String, Object> structure = new LinkedHashMap<>();
        structure.put("patientId", request.patientId());
        structure.put("planCode", request.planCode());
        structure.put("level", request.level());
        structure.put("deviceId", request.deviceId());
        List<Object> items = new ArrayList<>();
        for (ScheduleItemRequest item : request.items()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("fractionNo", item.fractionNo());
            entry.put("windowStartUtc", item.windowStartUtc().toInstant().toString());
            entry.put("windowEndUtc", item.windowEndUtc().toInstant().toString());
            items.add(entry);
        }
        structure.put("items", items);
        return structure;
    }

    // ============================ 排程取消 ============================

    /** 排程取消：未开始项取消并释放占用，已完成项保留实绩；成功版本加 1。 */
    @Transactional
    public RecordedResponse cancel(long scheduleId, ScheduleCancelRequest request) {
        ScheduleRow schedule = schedulingRepository.findSchedule(scheduleId)
                .orElseThrow(() -> ApiException.notFound("SCHEDULE_NOT_FOUND",
                        "排程不存在：" + scheduleId));
        ensurePatientLock(schedule.patientId());
        Map<String, Object> structure = new LinkedHashMap<>();
        structure.put("scheduleId", scheduleId);
        structure.put("expectedVersion", request.expectedVersion());
        return idempotency.executeOnSuccess(OperationType.SCHEDULE_CANCEL, request.requestId(),
                structure, () -> doCancel(scheduleId, request));
    }

    private RecordedResponse doCancel(long scheduleId, ScheduleCancelRequest request) {
        LocalDateTime now = LocalDateTime.now(clock);
        ScheduleRow schedule = schedulingRepository.lockSchedule(scheduleId)
                .orElseThrow(() -> ApiException.notFound("SCHEDULE_NOT_FOUND",
                        "排程不存在：" + scheduleId));
        if (ScheduleStatus.CANCELLED.name().equals(schedule.status())) {
            return reject(422, "SCHEDULE_ALREADY_CANCELLED", "排程已取消，不能重复取消");
        }
        if (schedule.version() != request.expectedVersion()) {
            return reject(409, "VERSION_CONFLICT",
                    "排程版本冲突：期望 " + request.expectedVersion() + "，当前 " + schedule.version());
        }
        schedulingRepository.cancelScheduledItems(scheduleId, now);
        schedulingRepository.cancelSchedule(scheduleId, now);
        ScheduleRow updated = schedulingRepository.findSchedule(scheduleId).orElseThrow();
        Integer versionNo = planRepository.findVersionById(updated.planVersionId())
                .map(PlanVersionRow::versionNo).orElse(null);
        return new RecordedResponse(200,
                toScheduleResponse(updated, versionNo, request.requestId(), false));
    }

    // ============================ 受控治疗记录 ============================

    /**
     * 受控治疗记录：须在排程窗口内、匹配排程/当前计划版本、遵守顺序分次规则；
     * 完成原子释放占用并冻结实绩；失效版本不能治疗。
     */
    @Transactional
    public RecordedResponse treat(TreatmentRequest request) {
        ensurePatientLock(request.patientId());
        Map<String, Object> structure = new LinkedHashMap<>();
        structure.put("patientId", request.patientId());
        structure.put("planCode", request.planCode());
        structure.put("fractionNo", request.fractionNo());
        structure.put("deliveredDoseCgy", request.deliveredDoseCgy());
        return idempotency.executeOnSuccess(OperationType.TREATMENT, request.requestId(),
                structure, () -> doTreat(request));
    }

    private RecordedResponse doTreat(TreatmentRequest request) {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime nowUtc = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        ActivePlanRow active = planRepository.findActivePlan(request.patientId()).orElse(null);
        if (active == null || !"ACTIVE".equals(active.status())) {
            return reject(422, "NO_ACTIVE_PLAN", "患者不存在执行中计划，不能登记受控治疗");
        }
        if (!active.planCode().equals(request.planCode())) {
            return reject(409, "ANOTHER_PLAN_ACTIVE",
                    "患者当前执行中计划为 " + active.planCode() + "，与请求计划 " + request.planCode() + " 不一致");
        }
        PlanVersionRow version = planRepository.lockVersionById(active.planVersionId())
                .orElseThrow(() -> ApiException.notFound("PLAN_VERSION_NOT_FOUND", "执行中计划版本不存在"));
        if (!PlanStatus.VERIFIED.name().equals(version.status())
                && !PlanStatus.EXECUTING.name().equals(version.status())) {
            return reject(422, "PLAN_VERSION_NOT_EXECUTABLE",
                    "计划版本当前状态 " + version.status() + "，不能用失效或未验证版本治疗");
        }

        ScheduleItemRow item = schedulingRepository
                .findScheduledItem(request.patientId(), request.planCode(), request.fractionNo())
                .orElse(null);
        if (item == null) {
            return reject(422, "SCHEDULE_NOT_FOUND",
                    "分次 " + request.fractionNo() + " 无生效排程，受控入口拒绝");
        }
        ScheduleItemRow locked = schedulingRepository.lockItem(item.id()).orElseThrow();
        if (!ScheduleItemStatus.SCHEDULED.name().equals(locked.status())) {
            return reject(422, "SCHEDULE_NOT_ACTIVE",
                    "分次 " + request.fractionNo() + " 排程状态 " + locked.status() + "，不能执行");
        }
        if (locked.planVersionId() != version.id()) {
            return reject(422, "SCHEDULE_VERSION_MISMATCH",
                    "排程冻结版本与当前计划版本不一致，不能用失效版本治疗");
        }
        if (nowUtc.isBefore(locked.windowStartUtc()) || !nowUtc.isBefore(locked.windowEndUtc())) {
            return reject(422, "OUTSIDE_SCHEDULE_WINDOW",
                    "当前时刻不在分次 " + request.fractionNo() + " 的排程窗口 ["
                            + locked.windowStartUtc() + "Z, " + locked.windowEndUtc() + "Z) 内");
        }
        DeviceRow device = schedulingRepository.lockDevice(locked.deviceId())
                .orElseThrow(() -> ApiException.notFound("DEVICE_NOT_FOUND",
                        "设备不存在：" + locked.deviceId()));
        if (locked.level() > device.maxLevel()) {
            return reject(422, "DEVICE_CAPABILITY_INSUFFICIENT",
                    "设备 " + locked.deviceId() + " 能力已下调至等级 " + device.maxLevel()
                            + "，尚未开始项被阻断（等级 " + locked.level() + "）");
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

        long fractionId;
        try {
            fractionId = workflowRepository.insertFraction(request.patientId(), request.planCode(),
                    version.id(), request.fractionNo(), request.deliveredDoseCgy(),
                    request.requestId(), now);
        } catch (DuplicateKeyException e) {
            return reject(409, "FRACTION_ALREADY_RECORDED",
                    "分次 " + request.fractionNo() + " 已存在（并发去重），拒绝重复登记");
        }
        planRepository.markExecutingIfFirst(version.id(), now);
        schedulingRepository.completeItem(locked.id(), request.deliveredDoseCgy(), nowUtc,
                fractionId, now);

        TreatmentResponse response = new TreatmentResponse(fractionId, locked.id(),
                request.patientId(), request.planCode(), version.id(), version.versionNo(),
                request.fractionNo(), request.deliveredDoseCgy(), nowUtc.atOffset(ZoneOffset.UTC),
                request.requestId(), false);
        return new RecordedResponse(200, response);
    }

    // ============================ 查询 ============================

    /** 设备当前占用（SCHEDULED 排程项）。 */
    @Transactional(readOnly = true)
    public List<DeviceOccupancyItem> occupancy(String deviceId) {
        schedulingRepository.findDevice(deviceId)
                .orElseThrow(() -> ApiException.notFound("DEVICE_NOT_FOUND", "设备不存在：" + deviceId));
        return schedulingRepository.findDeviceOccupancy(deviceId).stream()
                .map(item -> new DeviceOccupancyItem(item.scheduleId(), item.id(), item.patientId(),
                        item.planCode(), item.fractionNo(), item.level(),
                        item.windowStartUtc().atOffset(ZoneOffset.UTC),
                        item.windowEndUtc().atOffset(ZoneOffset.UTC)))
                .toList();
    }

    /** 患者+计划的全部排程批次（含明细）。 */
    @Transactional(readOnly = true)
    public List<ScheduleResponse> schedules(String patientId, String planCode) {
        return schedulingRepository.findSchedules(patientId, planCode).stream()
                .map(schedule -> {
                    Integer versionNo = planRepository.findVersionById(schedule.planVersionId())
                            .map(PlanVersionRow::versionNo).orElse(null);
                    return toScheduleResponse(schedule, versionNo, schedule.requestId(), false);
                })
                .toList();
    }

    // ============================ 内部辅助 ============================

    private RecordedResponse reject(int httpStatus, String code, String message) {
        return new RecordedResponse(httpStatus, new RejectionBody(code, message, false, List.of()));
    }

    private void ensurePatientLock(String patientId) {
        if (!planRepository.lockPatient(patientId)) {
            throw ApiException.notFound("PATIENT_NOT_FOUND", "患者不存在：" + patientId);
        }
    }

    private LocalDateTime toUtc(java.time.OffsetDateTime time) {
        return LocalDateTime.ofInstant(time.toInstant(), ZoneOffset.UTC);
    }

    private DeviceResponse toDeviceResponse(DeviceRow device, String requestId, boolean replayed) {
        return new DeviceResponse(device.deviceId(), device.deviceName(), device.maxLevel(),
                device.version(), requestId, replayed);
    }

    private ScheduleResponse toScheduleResponse(ScheduleRow schedule, Integer versionNo,
                                                String requestId, boolean replayed) {
        List<ScheduleItemView> items = schedulingRepository.findItemsBySchedule(schedule.id())
                .stream()
                .map(item -> new ScheduleItemView(item.id(), item.fractionNo(),
                        item.windowStartUtc().atOffset(ZoneOffset.UTC),
                        item.windowEndUtc().atOffset(ZoneOffset.UTC),
                        item.status(), item.deliveredDoseCgy(),
                        item.executedAt() == null ? null
                                : item.executedAt().atOffset(ZoneOffset.UTC)))
                .toList();
        return new ScheduleResponse(schedule.id(), schedule.patientId(), schedule.planCode(),
                schedule.planVersionId(), versionNo, schedule.level(), schedule.deviceId(),
                schedule.version(), schedule.status(), requestId, replayed, items);
    }

    /** 发布校验用的窗口值对象。 */
    private record Window(int fractionNo, LocalDateTime start, LocalDateTime end) {
    }
}
