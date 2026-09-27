package com.example.starter.rt.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
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
import com.example.starter.rt.dto.CreateDeviceRequest;
import com.example.starter.rt.dto.DeviceCapabilityRequest;
import com.example.starter.rt.dto.DeviceCapabilityResponse;
import com.example.starter.rt.dto.DeviceOccupancyItem;
import com.example.starter.rt.dto.DeviceResponse;
import com.example.starter.rt.dto.FractionResponse;
import com.example.starter.rt.dto.RecordedResponse;
import com.example.starter.rt.dto.RejectionBody;
import com.example.starter.rt.dto.ScheduleBatchResponse;
import com.example.starter.rt.dto.ScheduleCancelRequest;
import com.example.starter.rt.dto.ScheduleItemRequest;
import com.example.starter.rt.dto.ScheduleItemView;
import com.example.starter.rt.dto.SchedulePublishRequest;
import com.example.starter.rt.dto.TreatmentRequest;
import com.example.starter.rt.repository.PlanRepository;
import com.example.starter.rt.repository.PlanRepository.ActivePlanRow;
import com.example.starter.rt.repository.PlanRepository.PlanVersionRow;
import com.example.starter.rt.repository.ScheduleRepository;
import com.example.starter.rt.repository.ScheduleRepository.BatchRow;
import com.example.starter.rt.repository.ScheduleRepository.DeviceRow;
import com.example.starter.rt.repository.ScheduleRepository.ItemRow;
import com.example.starter.rt.repository.WorkflowRepository;
import com.example.starter.rt.web.ApiException;

/**
 * 分次排程服务：合成设备登记与能力变更、连续分次批量排程发布/取消、
 * 受控治疗记录入口与设备占用查询。
 *
 * <p>合成规则：设备可执行复杂度等级 1 至 3；排程发布登记等级，单分次所需分钟数固定为
 * 10/20/30（不依据真实临床规则）。UTC 窗口为半开区间 [start, end)，长度须等于等级对应分钟数，
 * 端点相接可共存；同设备窗口重叠或同分次双占拒绝。单次批量最多 10 个连续分次，
 * 任一冲突整批零占用（先全量校验再统一落库，同事务原子占用）。</p>
 *
 * <p>并发控制：锁顺序全局一致（患者→设备→计划版本→排程明细/批次）。排程发布与改版、
 * 取消与执行竞态由行锁串行化形成唯一结果；改版同事务取消旧版本未开始排程并释放资源，
 * 已执行分次的剂量与实绩不改写。</p>
 */
@Service
public class ScheduleService {

    private final PlanRepository planRepository;
    private final ScheduleRepository scheduleRepository;
    private final WorkflowRepository workflowRepository;
    private final IdempotencyTemplate idempotency;
    private final Clock clock;

    public ScheduleService(PlanRepository planRepository, ScheduleRepository scheduleRepository,
                           WorkflowRepository workflowRepository, IdempotencyTemplate idempotency,
                           Clock clock) {
        this.planRepository = planRepository;
        this.scheduleRepository = scheduleRepository;
        this.workflowRepository = workflowRepository;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    // ============================ 设备登记与能力变更 ============================

    /** 登记合成设备（等级上限 1 至 3，初始版本 1）；同 ID 重复登记返回 409。 */
    @Transactional
    public DeviceResponse createDevice(CreateDeviceRequest request) {
        LocalDateTime now = LocalDateTime.now(clock);
        try {
            scheduleRepository.insertDevice(request.deviceId(), request.deviceName(),
                    request.maxLevel(), now);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("DEVICE_EXISTS", "设备已存在：" + request.deviceId());
        }
        return new DeviceResponse(request.deviceId(), request.deviceName(),
                request.maxLevel(), 1);
    }

    /**
     * 设备能力变更（乐观锁 expectedVersion，成功变更加 1）。
     * 能力下调只阻断尚未开始的排程项（执行入口校验当前能力），不改写已完成实绩。
     */
    @Transactional
    public RecordedResponse changeCapability(String deviceId, DeviceCapabilityRequest request) {
        DeviceRow device = scheduleRepository.lockDevice(deviceId)
                .orElseThrow(() -> ApiException.notFound("DEVICE_NOT_FOUND", "设备不存在：" + deviceId));
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("deviceId", deviceId);
        params.put("maxLevel", request.maxLevel());
        params.put("expectedVersion", request.expectedVersion());
        return idempotency.executeSuccessOnly(OperationType.DEVICE_CAPABILITY, request.requestId(),
                params, () -> doChangeCapability(device, request));
    }

    private RecordedResponse doChangeCapability(DeviceRow device, DeviceCapabilityRequest request) {
        LocalDateTime now = LocalDateTime.now(clock);
        int updated = scheduleRepository.updateDeviceCapability(device.deviceId(),
                request.maxLevel(), request.expectedVersion(), now);
        if (updated == 0) {
            return reject(409, "DEVICE_VERSION_CONFLICT",
                    "设备版本冲突：期望版本 " + request.expectedVersion()
                            + "，当前版本 " + device.version() + "，请刷新后重试");
        }
        DeviceCapabilityResponse response = new DeviceCapabilityResponse(device.deviceId(),
                request.maxLevel(), device.version() + 1, request.requestId(), false);
        return new RecordedResponse(200, response);
    }

    // ============================ 排程发布 ============================

    /**
     * 连续分次批量排程发布：只引用已批准且首次验证合格的当前计划版本与尚未执行分次；
     * 发布冻结计划版本、等级、设备与窗口，原子占用资源；任一冲突整批零占用。
     */
    @Transactional
    public RecordedResponse publish(SchedulePublishRequest request) {
        validatePublishShape(request);
        if (!planRepository.lockPatient(request.patientId())) {
            throw ApiException.notFound("PATIENT_NOT_FOUND", "患者不存在：" + request.patientId());
        }
        DeviceRow device = scheduleRepository.lockDevice(request.deviceId())
                .orElseThrow(() -> ApiException.notFound("DEVICE_NOT_FOUND",
                        "设备不存在：" + request.deviceId()));
        return idempotency.executeSuccessOnly(OperationType.SCHEDULE_PUBLISH, request.requestId(),
                publishParams(request), () -> doPublish(request, device));
    }

    private RecordedResponse doPublish(SchedulePublishRequest request, DeviceRow device) {
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
                    "排程只引用已批准且首次验证合格的当前计划，当前状态 " + version.status());
        }
        if (version.versionNo() != request.versionNo()) {
            return reject(422, "PLAN_VERSION_NOT_CURRENT",
                    "排程版本门禁：请求版本 v" + request.versionNo() + " 不是当前可执行版本 v"
                            + version.versionNo() + "，不能用失效版本排程");
        }
        if (device.maxLevel() < request.level()) {
            return reject(422, "DEVICE_CAPABILITY_INSUFFICIENT",
                    "设备 " + device.deviceId() + " 可执行等级上限 " + device.maxLevel()
                            + "，低于排程等级 " + request.level());
        }

        for (ScheduleItemRequest item : request.items()) {
            if (item.fractionNo() > version.fractionCount()) {
                return reject(422, "FRACTION_OUT_OF_RANGE",
                        "分次 " + item.fractionNo() + " 超出处方分次数 " + version.fractionCount());
            }
            if (workflowRepository.findFraction(request.patientId(), request.planCode(),
                    item.fractionNo()).isPresent()) {
                return reject(422, "FRACTION_ALREADY_EXECUTED",
                        "分次 " + item.fractionNo() + " 已执行，排程只引用尚未执行分次");
            }
            if (scheduleRepository.findActiveItem(request.patientId(), request.planCode(),
                    item.fractionNo()).isPresent()) {
                return reject(422, "FRACTION_ALREADY_SCHEDULED",
                        "分次 " + item.fractionNo() + " 已存在占用中的排程，同分次不允许双占");
            }
            // 设备行锁保护下的占用检查：同设备 ACTIVE 窗口重叠即拒绝，整批零占用。
            int overlaps = scheduleRepository.countOverlappingActiveItems(device.deviceId(),
                    utc(item.startAt()), utc(item.endAt()));
            if (overlaps > 0) {
                return reject(422, "DEVICE_WINDOW_CONFLICT",
                        "设备 " + device.deviceId() + " 在分次 " + item.fractionNo()
                                + " 的窗口存在占用冲突，整批不予占用");
            }
        }

        LocalDateTime nowUtc = nowUtc();
        long batchId = scheduleRepository.insertBatch(request.patientId(), request.planCode(),
                version.id(), device.deviceId(), request.level(),
                ComplexityLevel.minutesOf(request.level()), request.items().size(),
                request.requestId(), nowUtc);
        for (ScheduleItemRequest item : request.items()) {
            scheduleRepository.insertItem(batchId, request.patientId(), request.planCode(),
                    version.id(), device.deviceId(), request.level(), item.fractionNo(),
                    utc(item.startAt()), utc(item.endAt()));
        }
        BatchRow batch = scheduleRepository.findBatchById(batchId).orElseThrow();
        return new RecordedResponse(200, toBatchResponse(batch,
                scheduleRepository.findItemsByBatch(batchId), version.versionNo(), false));
    }

    /** 发布形状校验（参数非法 400）：等级、批内连续、窗口长度与批内重叠。 */
    private void validatePublishShape(SchedulePublishRequest request) {
        if (!ComplexityLevel.isValid(request.level())) {
            throw new ApiException(400, "VALIDATION_FAILED", "复杂度等级须为1至3：" + request.level());
        }
        List<ScheduleItemRequest> items = request.items();
        if (items == null || items.isEmpty() || items.size() > 10) {
            throw new ApiException(400, "VALIDATION_FAILED", "单次批量排程分次数须为1至10");
        }
        int minutes = ComplexityLevel.minutesOf(request.level());
        List<ScheduleItemRequest> byFraction = new ArrayList<>(items);
        byFraction.sort(Comparator.comparing(ScheduleItemRequest::fractionNo));
        for (int i = 0; i < byFraction.size(); i++) {
            ScheduleItemRequest item = byFraction.get(i);
            if (item.fractionNo() != byFraction.get(0).fractionNo() + i) {
                throw new ApiException(400, "VALIDATION_FAILED",
                        "批量排程分次号须连续，第 " + (i + 1) + " 项应为 "
                                + (byFraction.get(0).fractionNo() + i) + "，实际 " + item.fractionNo());
            }
            if (item.startAt() == null || item.endAt() == null
                    || !item.startAt().plus(minutes, ChronoUnit.MINUTES).equals(item.endAt())) {
                throw new ApiException(400, "VALIDATION_FAILED",
                        "分次 " + item.fractionNo() + " 窗口长度须等于等级 " + request.level()
                                + " 对应的 " + minutes + " 分钟（UTC 半开区间）");
            }
        }
        List<ScheduleItemRequest> byStart = new ArrayList<>(items);
        byStart.sort(Comparator.comparing(ScheduleItemRequest::startAt));
        for (int i = 1; i < byStart.size(); i++) {
            if (byStart.get(i).startAt().isBefore(byStart.get(i - 1).endAt())) {
                throw new ApiException(400, "VALIDATION_FAILED",
                        "批内分次窗口互相重叠：分次 " + byStart.get(i).fractionNo()
                                + " 与分次 " + byStart.get(i - 1).fractionNo());
            }
        }
    }

    private Map<String, Object> publishParams(SchedulePublishRequest request) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("patientId", request.patientId());
        params.put("planCode", request.planCode());
        params.put("versionNo", request.versionNo());
        params.put("deviceId", request.deviceId());
        params.put("level", request.level());
        List<Map<String, Object>> items = new ArrayList<>();
        List<ScheduleItemRequest> sorted = new ArrayList<>(request.items());
        sorted.sort(Comparator.comparing(ScheduleItemRequest::fractionNo));
        for (ScheduleItemRequest item : sorted) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("fractionNo", item.fractionNo());
            entry.put("startAt", item.startAt().toString());
            entry.put("endAt", item.endAt().toString());
            items.add(entry);
        }
        params.put("items", items);
        return params;
    }

    // ============================ 排程取消 ============================

    /** 排程批次取消（乐观锁 expectedVersion，成功取消版本加 1）：释放全部未开始占用。 */
    @Transactional
    public RecordedResponse cancel(long batchId, ScheduleCancelRequest request) {
        BatchRow probe = scheduleRepository.findBatchById(batchId)
                .orElseThrow(() -> ApiException.notFound("SCHEDULE_NOT_FOUND",
                        "排程批次不存在：" + batchId));
        if (!planRepository.lockPatient(probe.patientId())) {
            throw ApiException.notFound("PATIENT_NOT_FOUND", "患者不存在：" + probe.patientId());
        }
        BatchRow batch = scheduleRepository.lockBatchById(batchId).orElseThrow();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("batchId", batchId);
        params.put("expectedVersion", request.expectedVersion());
        return idempotency.executeSuccessOnly(OperationType.SCHEDULE_CANCEL, request.requestId(),
                params, () -> doCancel(batch, request));
    }

    private RecordedResponse doCancel(BatchRow batch, ScheduleCancelRequest request) {
        if (batch.version() != request.expectedVersion()) {
            return reject(409, "SCHEDULE_VERSION_CONFLICT",
                    "排程批次版本冲突：期望版本 " + request.expectedVersion()
                            + "，当前版本 " + batch.version() + "，请刷新后重试");
        }
        if (!"ACTIVE".equals(batch.status())) {
            return reject(422, "SCHEDULE_NOT_ACTIVE",
                    "排程批次当前状态 " + batch.status() + "，仅 ACTIVE 状态可取消");
        }
        LocalDateTime nowUtc = nowUtc();
        for (ItemRow item : scheduleRepository.findItemsByBatch(batch.id())) {
            if ("ACTIVE".equals(item.status())) {
                scheduleRepository.cancelItem(item.id(), nowUtc);
            }
        }
        scheduleRepository.markBatchCancelled(batch.id(), nowUtc);
        BatchRow cancelled = scheduleRepository.findBatchById(batch.id()).orElseThrow();
        return new RecordedResponse(200, toBatchResponse(cancelled,
                scheduleRepository.findItemsByBatch(batch.id()), versionNoOf(cancelled), false));
    }

    // ============================ 受控治疗记录入口 ============================

    /**
     * 受控治疗记录：须在排程窗口内且匹配排程/计划版本、遵守原顺序分次规则；
     * 完成原子释放占用并冻结实绩。未启用排程控制的历史入口（/fractions）不受影响。
     */
    @Transactional
    public RecordedResponse treat(TreatmentRequest request) {
        if (!planRepository.lockPatient(request.patientId())) {
            throw ApiException.notFound("PATIENT_NOT_FOUND", "患者不存在：" + request.patientId());
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("patientId", request.patientId());
        params.put("planCode", request.planCode());
        params.put("fractionNo", request.fractionNo());
        params.put("deliveredDoseCgy", request.deliveredDoseCgy());
        return idempotency.executeSuccessOnly(OperationType.TREATMENT, request.requestId(),
                params, () -> doTreat(request));
    }

    private RecordedResponse doTreat(TreatmentRequest request) {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime nowUtc = nowUtc();
        ActivePlanRow active = planRepository.findActivePlan(request.patientId()).orElse(null);
        if (active == null) {
            return reject(422, "NO_ACTIVE_PLAN", "患者不存在执行中计划，不能登记治疗");
        }
        if ("CLOSED".equals(active.status())) {
            return reject(422, "COURSE_CLOSED", "患者疗程已结案（终态），受控入口不再登记治疗");
        }
        if (!active.planCode().equals(request.planCode())) {
            return reject(409, "ANOTHER_PLAN_ACTIVE",
                    "患者当前执行中计划为 " + active.planCode() + "，与请求计划 " + request.planCode() + " 不一致");
        }
        PlanVersionRow version = planRepository.lockVersionById(active.planVersionId())
                .orElseThrow(() -> ApiException.notFound("PLAN_VERSION_NOT_FOUND", "执行中计划版本不存在"));
        if (PlanStatus.INVALID.name().equals(version.status())
                || PlanStatus.CLOSED.name().equals(version.status())) {
            return reject(422, "PLAN_VERSION_NOT_EXECUTABLE",
                    "计划版本当前状态 " + version.status() + "，不能用失效版本治疗");
        }
        if (!PlanStatus.VERIFIED.name().equals(version.status())
                && !PlanStatus.EXECUTING.name().equals(version.status())) {
            return reject(422, "FIRST_VERIFICATION_REQUIRED",
                    "首次治疗前必须通过模体验证（当前状态 " + version.status() + "），不得执行分次");
        }

        ItemRow item = scheduleRepository
                .findActiveItem(request.patientId(), request.planCode(), request.fractionNo())
                .orElse(null);
        if (item == null) {
            return reject(422, "SCHEDULE_REQUIRED",
                    "分次 " + request.fractionNo() + " 不存在占用中的排程，受控入口须匹配排程");
        }
        // 取消与执行竞态：患者行锁已串行化，此处再锁定明细行确认最终状态。
        ItemRow locked = scheduleRepository.lockItemById(item.id()).orElseThrow();
        if (!"ACTIVE".equals(locked.status())) {
            return reject(422, "SCHEDULE_NOT_ACTIVE",
                    "分次 " + request.fractionNo() + " 的排程已取消或已执行，不能用于治疗");
        }
        if (locked.planVersionId() != version.id()) {
            return reject(422, "SCHEDULE_VERSION_MISMATCH",
                    "排程冻结的计划版本与当前可执行版本不一致，不能用失效版本治疗");
        }
        DeviceRow device = scheduleRepository.lockDevice(locked.deviceId())
                .orElseThrow(() -> ApiException.notFound("DEVICE_NOT_FOUND",
                        "设备不存在：" + locked.deviceId()));
        if (device.maxLevel() < locked.level()) {
            return reject(422, "DEVICE_CAPABILITY_INSUFFICIENT",
                    "设备 " + device.deviceId() + " 能力已下调至等级 " + device.maxLevel()
                            + "，低于排程等级 " + locked.level() + "，尚未开始项被阻断");
        }
        if (nowUtc.isBefore(locked.startUtc()) || !nowUtc.isBefore(locked.endUtc())) {
            return reject(422, "OUTSIDE_SCHEDULE_WINDOW",
                    "当前时刻不在分次 " + request.fractionNo() + " 的排程窗口 ["
                            + locked.startUtc() + "Z, " + locked.endUtc() + "Z) 内，拒绝执行");
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
        // 完成原子释放占用并冻结实绩（同事务）。
        scheduleRepository.completeItem(locked.id(), request.deliveredDoseCgy(), nowUtc);
        refreshBatchStatus(locked.batchId(), nowUtc);
        planRepository.markExecutingIfFirst(version.id(), now);

        FractionResponse response = new FractionResponse(fractionId, request.patientId(),
                request.planCode(), version.id(), version.versionNo(), request.fractionNo(),
                request.deliveredDoseCgy(), request.requestId(), false, Responses.odt(now));
        return new RecordedResponse(200, response);
    }

    // ============================ 改版联动（供 WorkflowService 同事务调用） ============================

    /**
     * 计划改版联动：取消同患者+计划下保留版本以外的全部未开始排程并释放资源；
     * 已执行分次的剂量与实绩不改写。调用方须已持有患者行锁。
     */
    @Transactional
    public void cancelActiveSchedulesOfOtherVersions(String patientId, String planCode,
                                                     long keepVersionId) {
        LocalDateTime nowUtc = nowUtc();
        List<Long> batchIds = scheduleRepository.cancelActiveItemsOfOtherVersions(
                patientId, planCode, keepVersionId, nowUtc);
        for (Long batchId : batchIds) {
            refreshBatchStatus(batchId, nowUtc);
        }
    }

    // ============================ 查询 ============================

    /** 设备占用查询：返回与可选 UTC 区间重叠的全部排程明细（含已释放，便于核对释放结果）。 */
    @Transactional(readOnly = true)
    public List<DeviceOccupancyItem> occupancy(String deviceId, Instant from, Instant to) {
        scheduleRepository.findDevice(deviceId)
                .orElseThrow(() -> ApiException.notFound("DEVICE_NOT_FOUND", "设备不存在：" + deviceId));
        LocalDateTime fromUtc = from == null ? null : utc(from);
        LocalDateTime toUtc = to == null ? null : utc(to);
        return scheduleRepository.findItemsByDevice(deviceId, fromUtc, toUtc).stream()
                .map(this::toOccupancyItem).toList();
    }

    /** 计划排程批次列表（含明细）。 */
    @Transactional(readOnly = true)
    public List<ScheduleBatchResponse> schedulesOfPlan(String patientId, String planCode) {
        return scheduleRepository.findBatchesByPlan(patientId, planCode).stream()
                .map(batch -> toBatchResponse(batch, scheduleRepository.findItemsByBatch(batch.id()),
                        versionNoOf(batch), false))
                .toList();
    }

    // ============================ 内部辅助 ============================

    /** 批次状态推进：任一明细占用中为 ACTIVE；全部已执行为 COMPLETED；否则 CANCELLED。版本加 1。 */
    private void refreshBatchStatus(long batchId, LocalDateTime nowUtc) {
        List<ItemRow> items = scheduleRepository.findItemsByBatch(batchId);
        boolean anyActive = items.stream().anyMatch(i -> "ACTIVE".equals(i.status()));
        if (anyActive) {
            return;
        }
        boolean allCompleted = items.stream().allMatch(i -> "COMPLETED".equals(i.status()));
        scheduleRepository.updateBatchStatus(batchId, allCompleted ? "COMPLETED" : "CANCELLED",
                nowUtc);
    }

    private RecordedResponse reject(int httpStatus, String code, String message) {
        return new RecordedResponse(httpStatus, new RejectionBody(code, message, false, List.of()));
    }

    private LocalDateTime nowUtc() {
        return LocalDateTime.ofInstant(Instant.now(clock), ZoneOffset.UTC);
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(LocalDateTime utcTime) {
        return utcTime == null ? null : utcTime.toInstant(ZoneOffset.UTC);
    }

    private Integer versionNoOf(BatchRow batch) {
        return planRepository.findVersionById(batch.planVersionId())
                .map(PlanVersionRow::versionNo).orElse(null);
    }

    private ScheduleBatchResponse toBatchResponse(BatchRow batch, List<ItemRow> items,
                                                  Integer versionNo, boolean replayed) {
        List<ScheduleItemView> views = items.stream()
                .map(item -> new ScheduleItemView(item.id(), item.fractionNo(),
                        instant(item.startUtc()), instant(item.endUtc()), item.status(),
                        item.deliveredDoseCgy(), instant(item.executedAtUtc())))
                .toList();
        return new ScheduleBatchResponse(batch.id(), batch.patientId(), batch.planCode(),
                batch.planVersionId(), versionNo, batch.deviceId(), batch.level(),
                batch.requiredMinutes(), batch.status(), batch.version(), batch.requestId(),
                replayed, views, instant(batch.createdAt()));
    }

    private DeviceOccupancyItem toOccupancyItem(ItemRow item) {
        return new DeviceOccupancyItem(item.id(), item.batchId(), item.patientId(), item.planCode(),
                item.planVersionId(), item.fractionNo(), item.level(),
                instant(item.startUtc()), instant(item.endUtc()), item.status());
    }
}
