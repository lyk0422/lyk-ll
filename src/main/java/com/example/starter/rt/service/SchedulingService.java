package com.example.starter.rt.service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.rt.domain.OperationType;
import com.example.starter.rt.domain.PlanStatus;
import com.example.starter.rt.domain.ScheduleRules;
import com.example.starter.rt.domain.ScheduleStatus;
import com.example.starter.rt.dto.DeviceCapabilityRequest;
import com.example.starter.rt.dto.DeviceCapabilityResponse;
import com.example.starter.rt.dto.DeviceRegisterRequest;
import com.example.starter.rt.dto.DeviceResponse;
import com.example.starter.rt.dto.RecordedResponse;
import com.example.starter.rt.dto.RejectionBody;
import com.example.starter.rt.dto.ScheduleCancelRequest;
import com.example.starter.rt.dto.ScheduleCancelResponse;
import com.example.starter.rt.dto.ScheduleItemRequest;
import com.example.starter.rt.dto.SchedulePublishRequest;
import com.example.starter.rt.dto.SchedulePublishResponse;
import com.example.starter.rt.dto.ScheduleResponse;
import com.example.starter.rt.repository.PlanRepository;
import com.example.starter.rt.repository.PlanRepository.ActivePlanRow;
import com.example.starter.rt.repository.PlanRepository.PlanVersionRow;
import com.example.starter.rt.repository.SchedulingRepository;
import com.example.starter.rt.repository.SchedulingRepository.DeviceRow;
import com.example.starter.rt.repository.SchedulingRepository.ScheduleRow;
import com.example.starter.rt.repository.WorkflowRepository;
import com.example.starter.rt.web.ApiException;

/**
 * 分次设备时长配额与排程门禁服务。
 *
 * <p>合成规则：设备能力等级 1-3，等级对应固定时长 10/20/30 分钟（{@link ScheduleRules}）；
 * 排程仅引用已批准且首次验证合格的当前计划与尚未执行分次；UTC 半开区间窗口长度恰等对应分钟数，
 * 端点相接可共存，同设备重叠或同分次双占拒绝。</p>
 *
 * <p>并发与原子性：发布/取消/执行/改版均先锁患者行（全局锁序：患者→版本→设备/排程），
 * 同设备发布再锁设备行串行化；整批占用与守卫行在同一事务提交，任一冲突整批零占用。
 * 新写操作（能力下调、发布、取消）使用结构化指纹幂等，仅首次成功占键，失败不占键。</p>
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

    // ============================ 设备登记与能力下调 ============================

    /** 登记合成设备（初始版本为1）；重复登记 409。 */
    @Transactional
    public DeviceResponse registerDevice(DeviceRegisterRequest request) {
        LocalDateTime now = LocalDateTime.now(clock);
        try {
            schedulingRepository.insertDevice(request.deviceId(), request.deviceName(),
                    request.capabilityLevel(), now);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("DEVICE_EXISTS", "设备已存在：" + request.deviceId());
        }
        DeviceRow row = schedulingRepository.findDevice(request.deviceId()).orElseThrow();
        return toDeviceResponse(row);
    }

    /**
     * 设备能力下调。只能下调；expectedVersion 不一致 409；成功后版本加1。
     * 下调只阻断尚未开始项：已 EXECUTED 实绩不改变；已发布未执行的高等级排程在执行门禁处被阻断。
     */
    @Transactional
    public RecordedResponse downgradeCapability(DeviceCapabilityRequest request) {
        DeviceRow locked = schedulingRepository.lockDevice(request.deviceId())
                .orElseThrow(() -> ApiException.notFound("DEVICE_NOT_FOUND",
                        "设备不存在：" + request.deviceId()));
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("deviceId", request.deviceId());
        params.put("expectedVersion", request.expectedVersion());
        params.put("newCapabilityLevel", request.newCapabilityLevel());
        return idempotency.executeStructured(OperationType.DEVICE_CAPABILITY, request.requestId(),
                params, () -> doDowngrade(locked, request));
    }

    private RecordedResponse doDowngrade(DeviceRow locked, DeviceCapabilityRequest request) {
        if (locked.version() != request.expectedVersion()) {
            return reject(409, "DEVICE_VERSION_CONFLICT",
                    "设备版本已变更：当前版本 " + locked.version() + "，expectedVersion="
                            + request.expectedVersion());
        }
        if (request.newCapabilityLevel() >= locked.capabilityLevel()) {
            return reject(422, "DEVICE_DOWNGRADE_ONLY",
                    "设备能力只允许下调：当前等级 " + locked.capabilityLevel()
                            + "，请求等级 " + request.newCapabilityLevel());
        }
        int updated = schedulingRepository.downgradeDevice(request.deviceId(),
                request.expectedVersion(), request.newCapabilityLevel(), LocalDateTime.now(clock));
        if (updated == 0) {
            return reject(409, "DEVICE_VERSION_CONFLICT", "设备能力下调并发冲突，版本已变化");
        }
        DeviceRow row = schedulingRepository.findDevice(request.deviceId()).orElseThrow();
        return new RecordedResponse(200, new DeviceCapabilityResponse(row.deviceId(),
                row.capabilityLevel(), row.version(), request.requestId(), false));
    }

    // ============================ 排程发布 ============================

    /**
     * 批量发布分次设备时长排程（1-10个连续分次）。冻结计划版本/等级/设备/UTC窗口并原子占用；
     * 任一冲突整批零占用；同键同参重放首次冻结结果，异参409，失败不占键。
     */
    @Transactional
    public RecordedResponse publish(SchedulePublishRequest request) {
        ensurePatientLock(request.patientId());
        PlanVersionRow version = planRepository
                .lockVersion(request.patientId(), request.planCode(), request.versionNo())
                .orElseThrow(() -> ApiException.notFound("PLAN_VERSION_NOT_FOUND",
                        "计划版本不存在：" + request.planCode() + "#v" + request.versionNo()));

        List<NormalizedItem> items = normalizeItems(request.items());
        if (items.size() > ScheduleRules.MAX_BATCH_FRACTIONS) {
            throw new ApiException(400, "SCHEDULE_BATCH_TOO_LARGE",
                    "单次批量排程最多 " + ScheduleRules.MAX_BATCH_FRACTIONS + " 个连续分次，实际 "
                            + items.size() + " 个");
        }
        validateContinuousFractions(request.items(), items);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("patientId", request.patientId());
        params.put("planCode", request.planCode());
        params.put("versionNo", request.versionNo());
        params.put("items", items.stream().map(SchedulingService::itemParams).toList());
        return idempotency.executeStructured(OperationType.SCHEDULE_PUBLISH, request.requestId(),
                params, () -> doPublish(request, version, items));
    }

    private RecordedResponse doPublish(SchedulePublishRequest request, PlanVersionRow version,
                                       List<NormalizedItem> items) {
        LocalDateTime now = LocalDateTime.now(clock);
        ActivePlanRow active = planRepository.findActivePlan(request.patientId()).orElse(null);
        if (active == null || !"ACTIVE".equals(active.status())) {
            return reject(422, "NO_ACTIVE_PLAN", "患者不存在执行中（未结案）计划，不能发布排程");
        }
        if (!active.planCode().equals(request.planCode())
                || active.planVersionId() != version.id()) {
            return reject(422, "PLAN_VERSION_NOT_CURRENT",
                    "排程只能引用患者当前计划版本：当前版本ID " + active.planVersionId()
                            + "，请求版本ID " + version.id());
        }
        if (!PlanStatus.VERIFIED.name().equals(version.status())
                && !PlanStatus.EXECUTING.name().equals(version.status())) {
            return reject(422, "FIRST_VERIFICATION_REQUIRED",
                    "排程只引用已批准且首次验证合格的计划版本（当前状态 " + version.status() + "）");
        }

        int executedCount = workflowRepository.countFractions(request.patientId(), request.planCode());

        // 窗口长度、分次范围、设备能力与存在性校验（设备按ID排序加锁，保持锁序）。
        List<String> deviceIds = items.stream().map(NormalizedItem::deviceId).distinct().sorted().toList();
        Map<String, DeviceRow> devices = new LinkedHashMap<>();
        for (String deviceId : deviceIds) {
            DeviceRow device = schedulingRepository.lockDevice(deviceId).orElse(null);
            if (device == null) {
                throw ApiException.notFound("DEVICE_NOT_FOUND", "设备不存在：" + deviceId);
            }
            devices.put(deviceId, device);
        }
        for (NormalizedItem item : items) {
            Duration length = Duration.between(item.startUtc(), item.endUtc());
            int required = ScheduleRules.requiredMinutes(item.complexityLevel());
            if (!length.equals(Duration.ofMinutes(required))) {
                throw new ApiException(400, "SCHEDULE_WINDOW_LENGTH_INVALID",
                        "分次 " + item.fractionNo() + " 的UTC窗口长度须恰为 " + required
                                + " 分钟，实际为 " + length.toMinutes() + " 分钟");
            }
            if (item.fractionNo() <= executedCount) {
                return reject(422, "FRACTION_ALREADY_EXECUTED",
                        "分次 " + item.fractionNo() + " 已执行，排程只能引用尚未执行分次");
            }
            if (item.fractionNo() > version.fractionCount()) {
                return reject(422, "FRACTION_OUT_OF_PLAN_RANGE",
                        "分次 " + item.fractionNo() + " 超出计划处方分次总数 "
                                + version.fractionCount());
            }
            DeviceRow device = devices.get(item.deviceId());
            if (device.capabilityLevel() < item.complexityLevel()) {
                return reject(422, "DEVICE_CAPABILITY_INSUFFICIENT",
                        "设备 " + device.deviceId() + " 当前能力等级 " + device.capabilityLevel()
                                + "，不能执行复杂度等级 " + item.complexityLevel());
            }
        }

        // 同分次双占：已 BOOKED 排程（守卫表唯一约束为最终防线）。
        List<Integer> fractionNos = items.stream().map(NormalizedItem::fractionNo).toList();
        List<ScheduleRow> bookedFractions = schedulingRepository.findBookedByFractionNumbers(
                request.patientId(), request.planCode(), fractionNos);
        if (!bookedFractions.isEmpty()) {
            return reject(422, "FRACTION_ALREADY_SCHEDULED",
                    "分次已被排程占用：" + bookedFractions.stream()
                            .map(s -> String.valueOf(s.fractionNo())).sorted().toList());
        }

        // 同设备窗口重叠：数据库既有 BOOKED 窗口 + 同批内两两检查；端点相接允许共存。
        for (NormalizedItem item : items) {
            List<ScheduleRow> overlapping = schedulingRepository.findBookedOverlappingDevice(
                    item.deviceId(), item.startUtc(), item.endUtc());
            if (!overlapping.isEmpty()) {
                ScheduleRow hit = overlapping.get(0);
                return reject(422, "DEVICE_WINDOW_CONFLICT",
                        "设备 " + item.deviceId() + " 窗口 " + item.startUtc() + "/" + item.endUtc()
                                + " 与已发布排程 " + hit.id() + "（分次 " + hit.fractionNo()
                                + "）重叠");
            }
        }
        for (int i = 0; i < items.size(); i++) {
            for (int j = i + 1; j < items.size(); j++) {
                NormalizedItem a = items.get(i);
                NormalizedItem b = items.get(j);
                if (a.deviceId().equals(b.deviceId()) && overlaps(a, b)) {
                    return reject(422, "DEVICE_WINDOW_CONFLICT",
                            "同批内设备 " + a.deviceId() + " 的分次 " + a.fractionNo() + " 与分次 "
                                    + b.fractionNo() + " 窗口重叠");
                }
            }
        }

        // 全部校验通过后方写入：排程行与活跃占用守卫同事务提交，任一唯一约束冲突整批回滚零占用。
        List<ScheduleResponse> responses = new ArrayList<>();
        try {
            for (NormalizedItem item : items) {
                long scheduleId = schedulingRepository.insertSchedule(request.patientId(),
                        request.planCode(), version.id(), version.versionNo(), item.fractionNo(),
                        item.deviceId(), item.complexityLevel(), item.startUtc(), item.endUtc(),
                        request.requestId(), now);
                schedulingRepository.insertOccupancy(request.patientId(), request.planCode(),
                        item.fractionNo(), scheduleId, now);
                ScheduleRow row = schedulingRepository.findScheduleById(scheduleId).orElseThrow();
                responses.add(toScheduleResponse(row));
            }
        } catch (DuplicateKeyException e) {
            // 并发下守卫/发布键决胜：抛出后整个事务回滚，整批零占用。
            throw ApiException.conflict("SCHEDULE_PUBLISH_RACE",
                    "排程发布并发冲突（同分次双占），整批未占用");
        }
        return new RecordedResponse(200,
                new SchedulePublishResponse(request.requestId(), false, responses));
    }

    // ============================ 排程取消 ============================

    /** 取消 BOOKED 排程：expectedVersion 门禁，原子释放设备窗口与分次占用；终态排程 422。 */
    @Transactional
    public RecordedResponse cancel(ScheduleCancelRequest request) {
        ScheduleRow schedule = schedulingRepository.findScheduleById(request.scheduleId())
                .orElseThrow(() -> ApiException.notFound("SCHEDULE_NOT_FOUND",
                        "排程不存在：" + request.scheduleId()));
        ensurePatientLock(schedule.patientId());
        ScheduleRow locked = schedulingRepository.lockScheduleById(request.scheduleId()).orElseThrow();

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("scheduleId", request.scheduleId());
        params.put("expectedVersion", request.expectedVersion());
        return idempotency.executeStructured(OperationType.SCHEDULE_CANCEL, request.requestId(),
                params, () -> doCancel(locked, request));
    }

    private RecordedResponse doCancel(ScheduleRow locked, ScheduleCancelRequest request) {
        if (locked.version() != request.expectedVersion()) {
            return reject(409, "SCHEDULE_VERSION_CONFLICT",
                    "排程版本已变更：当前版本 " + locked.version() + "，expectedVersion="
                            + request.expectedVersion());
        }
        if (!ScheduleStatus.BOOKED.name().equals(locked.status())) {
            return reject(422, "SCHEDULE_NOT_BOOKED",
                    "排程当前状态 " + locked.status() + "，仅已发布未完成(BOOKED)排程可取消");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        int updated = schedulingRepository.markCancelled(locked.id(), request.requestId(), now);
        if (updated == 0) {
            return reject(409, "SCHEDULE_VERSION_CONFLICT", "排程取消并发冲突，状态已变化");
        }
        schedulingRepository.deleteOccupancyBySchedule(locked.id());
        return new RecordedResponse(200, new ScheduleCancelResponse(locked.id(),
                ScheduleStatus.CANCELLED.name(), locked.version() + 1, request.requestId(), false));
    }

    // ============================ 查询 ============================

    /** 全部合成设备。 */
    @Transactional(readOnly = true)
    public List<DeviceResponse> devices() {
        return schedulingRepository.findAllDevices().stream().map(SchedulingService::toDeviceResponse).toList();
    }

    /** 设备占用查询：返回设备在 UTC 区间 [fromUtc,toUtc) 内重叠的 BOOKED 排程；区间为空返回全部BOOKED。 */
    @Transactional(readOnly = true)
    public List<ScheduleResponse> deviceOccupancies(String deviceId, OffsetDateTime fromUtc,
                                                    OffsetDateTime toUtc) {
        schedulingRepository.findDevice(deviceId)
                .orElseThrow(() -> ApiException.notFound("DEVICE_NOT_FOUND", "设备不存在：" + deviceId));
        LocalDateTime from = fromUtc == null ? null : fromUtc.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        LocalDateTime to = toUtc == null ? null : toUtc.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        return schedulingRepository.findDeviceOccupancies(deviceId, from, to).stream()
                .map(SchedulingService::toScheduleResponse).toList();
    }

    /** 患者+计划的排程与占用视图，可按状态过滤；含 EXECUTED/CANCELLED 历史证据。 */
    @Transactional(readOnly = true)
    public List<ScheduleResponse> planSchedules(String patientId, String planCode, String status) {
        return schedulingRepository.findSchedulesForPlan(patientId, planCode).stream()
                .filter(row -> status == null || status.isBlank() || row.status().equals(status))
                .map(SchedulingService::toScheduleResponse).toList();
    }

    // ============================ 内部辅助 ============================

    /** 请求项归一化为 UTC 绝对时刻；偏移缺失等非法参数 400。 */
    private List<NormalizedItem> normalizeItems(List<ScheduleItemRequest> raw) {
        List<NormalizedItem> items = new ArrayList<>();
        for (ScheduleItemRequest r : raw) {
            if (r.windowStartUtc() == null || r.windowEndUtc() == null) {
                throw new ApiException(400, "VALIDATION_FAILED", "排程窗口时刻不能为空");
            }
            LocalDateTime start = r.windowStartUtc().withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
            LocalDateTime end = r.windowEndUtc().withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
            if (!end.isAfter(start)) {
                throw new ApiException(400, "SCHEDULE_WINDOW_INVALID",
                        "分次 " + r.fractionNo() + " 的UTC窗口终点必须晚于起点（半开区间）");
            }
            items.add(new NormalizedItem(r.fractionNo(), r.deviceId(), r.complexityLevel(), start, end));
        }
        return items;
    }

    /** 同批分次必须连续且不重复（允许不是从1开始，如 3,4,5）。 */
    private void validateContinuousFractions(List<ScheduleItemRequest> raw, List<NormalizedItem> items) {
        List<Integer> sorted = items.stream().map(NormalizedItem::fractionNo).sorted().toList();
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i) == sorted.get(i - 1)) {
                throw new ApiException(400, "SCHEDULE_FRACTION_DUPLICATE",
                        "同批排程分次号重复：" + sorted.get(i));
            }
            if (sorted.get(i) != sorted.get(i - 1) + 1) {
                throw new ApiException(400, "SCHEDULE_FRACTIONS_NOT_CONTINUOUS",
                        "单次批量排程的分次必须连续：" + sorted);
            }
        }
        if (sorted.size() != raw.size()) {
            throw new ApiException(400, "SCHEDULE_FRACTION_DUPLICATE", "同批排程分次号重复");
        }
    }

    private static Map<String, Object> itemParams(NormalizedItem item) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("fractionNo", item.fractionNo());
        map.put("deviceId", item.deviceId());
        map.put("complexityLevel", item.complexityLevel());
        map.put("windowStartUtc", item.startUtc().atOffset(ZoneOffset.UTC));
        map.put("windowEndUtc", item.endUtc().atOffset(ZoneOffset.UTC));
        return map;
    }

    private static boolean overlaps(NormalizedItem a, NormalizedItem b) {
        // 半开区间：a.start < b.end && b.start < a.end；端点相接不重叠。
        return a.startUtc().isBefore(b.endUtc()) && b.startUtc().isBefore(a.endUtc());
    }

    private void ensurePatientLock(String patientId) {
        if (!planRepository.lockPatient(patientId)) {
            throw ApiException.notFound("PATIENT_NOT_FOUND", "患者不存在：" + patientId);
        }
    }

    private static RecordedResponse reject(int httpStatus, String code, String message) {
        return new RecordedResponse(httpStatus, RejectionBody.of(code, message));
    }

    private static DeviceResponse toDeviceResponse(DeviceRow row) {
        return new DeviceResponse(row.deviceId(), row.deviceName(), row.capabilityLevel(),
                row.version(), Responses.odt(row.createdAt()), Responses.odt(row.updatedAt()));
    }

    private static ScheduleResponse toScheduleResponse(ScheduleRow row) {
        int requiredMinutes = ScheduleRules.requiredMinutes(row.complexityLevel());
        return new ScheduleResponse(
                row.id(), row.patientId(), row.planCode(), row.planVersionId(), row.versionNo(),
                row.fractionNo(), row.deviceId(), row.complexityLevel(), requiredMinutes,
                row.windowStartUtc().atOffset(ZoneOffset.UTC),
                row.windowEndUtc().atOffset(ZoneOffset.UTC),
                row.status(), row.version(), row.publishRequestId(), row.cancelRequestId(),
                row.executedFractionId(),
                Responses.odt(row.publishedAt()),
                Responses.odt(row.cancelledAt()),
                Responses.odt(row.executedAt()));
    }

    /** 归一化后的排程项：窗口为 UTC 本地时刻。 */
    private record NormalizedItem(
            int fractionNo,
            String deviceId,
            int complexityLevel,
            LocalDateTime startUtc,
            LocalDateTime endUtc) {
    }
}
