package com.example.starter.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.domain.DayAheadPlan;
import com.example.starter.domain.DayAheadPlanEntry;
import com.example.starter.domain.IdempotentRecord;
import com.example.starter.domain.OrderPlanBasis;
import com.example.starter.domain.Turbine;
import com.example.starter.dto.PlanBasisItem;
import com.example.starter.dto.PlanDetailResponse;
import com.example.starter.dto.PlanOrderReceiveRequest;
import com.example.starter.dto.PlanOrderReceiveResponse;
import com.example.starter.dto.PlanPublishRequest;
import com.example.starter.dto.PlanPublishResponse;
import com.example.starter.dto.PlanVersionResponse;
import com.example.starter.repo.DispatchRepository;
import com.example.starter.repo.IdempotentRepository;
import com.example.starter.repo.PlanRepository;
import com.example.starter.repo.TurbineRepository;
import com.example.starter.support.Canonical;
import com.example.starter.support.Fingerprints;
import com.example.starter.support.ServiceClock;
import com.example.starter.web.ApiBadRequestException;
import com.example.starter.web.ApiConflictException;
import com.example.starter.web.ApiNotFoundException;
import com.example.starter.web.ApiUnprocessableException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 日前发电计划版本发布/查询与计划绑定模式限电指令受理。
 *
 * <p>并发约定：发布与指令受理都先持有 wind_farm 行锁（FOR UPDATE），
 * 因此同风场的发布与受理严格串行，受理时校验的版本与冻结的基数必然一致；
 * 版本头自身不可变，发布只新增版本并原子切换 current 指针。
 */
@Service
public class PlanService {

    /** 一日 15 分钟半开时段总数。 */
    static final int SEGMENTS_PER_DAY = 96;

    private final ServiceClock clock;
    private final TurbineRepository turbineRepository;
    private final DispatchRepository dispatchRepository;
    private final PlanRepository planRepository;
    private final IdempotentRepository idempotentRepository;
    private final ObjectMapper objectMapper;

    public PlanService(ServiceClock clock,
                       TurbineRepository turbineRepository,
                       DispatchRepository dispatchRepository,
                       PlanRepository planRepository,
                       IdempotentRepository idempotentRepository,
                       ObjectMapper objectMapper) {
        this.clock = clock;
        this.turbineRepository = turbineRepository;
        this.dispatchRepository = dispatchRepository;
        this.planRepository = planRepository;
        this.idempotentRepository = idempotentRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 发布日前计划版本：整版校验 96 段、机组集合与装机上限；expectedVersion 乐观校验，
     * 成功后新版本成为唯一当前版本，旧版本保留为历史。业务拒绝发生在幂等占位写入之前，不占用 requestId。
     */
    @Transactional
    public PlanPublishResponse publish(PlanPublishRequest request) {
        validatePlanDate(request.planDate());
        String fingerprint = Fingerprints.sha256(Canonical.planPublish(request));

        IdempotentRecord existing = idempotentRepository.findByRequestId(request.requestId());
        if (existing != null) {
            return resolvePublishIdempotent(existing, fingerprint);
        }

        // 风场行锁串行化同风场发布与指令受理；同时锁定版本组行（首版时无行可锁，仅靠风场锁）。
        if (!turbineRepository.lockFarmRow(request.farmCode())) {
            throw new ApiBadRequestException("风场不存在：" + request.farmCode());
        }
        planRepository.lockPlanGroup(request.farmCode(), request.planDate());

        existing = idempotentRepository.findByRequestId(request.requestId());
        if (existing != null) {
            return resolvePublishIdempotent(existing, fingerprint);
        }

        DayAheadPlan current = planRepository.findCurrentPlan(request.farmCode(), request.planDate());
        int currentVersion = current == null ? 0 : current.getVersionNo();
        if (request.expectedVersion() != currentVersion) {
            throw new ApiConflictException(
                    "期望版本 " + request.expectedVersion() + " 与当前版本 " + currentVersion + " 不一致");
        }

        List<Turbine> turbines = turbineRepository.findByFarm(request.farmCode());
        // 整版校验通过后才构造不可变条目；任一违规整版拒绝（422），不落任何数据。
        List<DayAheadPlanEntry> entries = validateAndBuildEntries(request, turbines);

        IdempotentRecord record = new IdempotentRecord();
        record.setRequestId(request.requestId());
        record.setBizType("PLAN_PUBLISH");
        record.setFingerprint(fingerprint);
        record.setCreatedAt(request.opTime());
        record.setResponseBody("");
        if (!idempotentRepository.tryInsert(record)) {
            return resolvePublishIdempotent(
                    idempotentRepository.findByRequestId(request.requestId()), fingerprint);
        }

        // 同一事务：旧当前版本降为历史 → 插入新版本并置当前，唯一索引兜底同场同日唯一当前指针。
        planRepository.clearCurrentFlag(request.farmCode(), request.planDate());

        DayAheadPlan plan = new DayAheadPlan();
        plan.setFarmCode(request.farmCode());
        plan.setPlanDate(request.planDate());
        plan.setVersionNo(currentVersion + 1);
        plan.setCurrentFlag("Y");
        plan.setPublishedAt(request.opTime());
        long planId = planRepository.insertPlan(plan);
        entries.forEach(e -> e.setPlanId(planId));
        planRepository.insertEntries(entries);

        int turbineCount = turbines.size();
        PlanPublishResponse response = new PlanPublishResponse(
                planId, request.farmCode(), request.planDate(), currentVersion + 1, true,
                request.opTime(), SEGMENTS_PER_DAY, turbineCount, false);
        idempotentRepository.updateRefAndResponse(request.requestId(), planId, toJson(response));
        return response;
    }

    /** 整版校验：恰好 0-95 段且不重复；每段机组不重复、恰好覆盖风场全部机组、不超装机、非负。 */
    private List<DayAheadPlanEntry> validateAndBuildEntries(
            PlanPublishRequest request, List<Turbine> turbines) {
        if (turbines.isEmpty()) {
            throw new ApiUnprocessableException("风场没有机组，无法编制日前计划：" + request.farmCode());
        }
        Map<String, Turbine> turbineByCode = new LinkedHashMap<>();
        turbines.forEach(t -> turbineByCode.put(t.getTurbineCode(), t));

        if (request.segments().size() != SEGMENTS_PER_DAY) {
            throw new ApiUnprocessableException("日前计划必须包含完整 96 个时段，实际 "
                    + request.segments().size() + " 段");
        }
        Set<Integer> seenSegments = new HashSet<>();
        for (PlanPublishRequest.SegmentInput seg : request.segments()) {
            int idx = seg.segmentIndex();
            if (idx < 0 || idx >= SEGMENTS_PER_DAY) {
                throw new ApiUnprocessableException("时段序号超出 0-95：" + idx);
            }
            if (!seenSegments.add(idx)) {
                throw new ApiUnprocessableException("时段重复：" + idx);
            }
            Set<String> seenTurbines = new HashSet<>();
            for (PlanPublishRequest.EntryInput e : seg.entries()) {
                if (!seenTurbines.add(e.turbineCode())) {
                    throw new ApiUnprocessableException("时段 " + idx + " 机组重复：" + e.turbineCode());
                }
                Turbine t = turbineByCode.get(e.turbineCode());
                if (t == null) {
                    throw new ApiUnprocessableException(
                            "时段 " + idx + " 含风场不存在的机组：" + e.turbineCode());
                }
                if (e.plannedPowerKw() > t.getRatedPowerKw()) {
                    throw new ApiUnprocessableException("时段 " + idx + " 机组 " + e.turbineCode()
                            + " 计划 " + e.plannedPowerKw() + " kW 超过合成装机 "
                            + t.getRatedPowerKw() + " kW");
                }
            }
            if (seenTurbines.size() != turbineByCode.size()
                    || !seenTurbines.equals(turbineByCode.keySet())) {
                throw new ApiUnprocessableException(
                        "时段 " + idx + " 机组集合与风场机组不一致，整版拒绝");
            }
        }
        if (seenSegments.size() != SEGMENTS_PER_DAY) {
            throw new ApiUnprocessableException("日前计划时段不完整，整版拒绝");
        }

        List<DayAheadPlanEntry> entries = new ArrayList<>(SEGMENTS_PER_DAY * turbines.size());
        for (PlanPublishRequest.SegmentInput seg : request.segments()) {
            for (PlanPublishRequest.EntryInput e : seg.entries()) {
                Turbine t = turbineByCode.get(e.turbineCode());
                DayAheadPlanEntry entry = new DayAheadPlanEntry();
                entry.setSegmentIndex(seg.segmentIndex());
                entry.setTurbineId(t.getId());
                entry.setTurbineCode(t.getTurbineCode());
                entry.setPlannedPowerKw(e.plannedPowerKw());
                entries.add(entry);
            }
        }
        return entries;
    }

    private PlanPublishResponse resolvePublishIdempotent(IdempotentRecord existing, String fingerprint) {
        if (existing == null) {
            throw new ApiConflictException("并发请求处理中，请重试");
        }
        if (!existing.getFingerprint().equals(fingerprint)) {
            throw new ApiConflictException("相同 requestId 的业务参数与首次请求不一致");
        }
        if (existing.getResponseBody() != null && !existing.getResponseBody().isBlank()) {
            return fromJson(existing.getResponseBody(), PlanPublishResponse.class).asReplay();
        }
        throw new ApiConflictException("首次请求仍在处理中");
    }

    /**
     * 受理计划绑定模式限电指令：校验当前版本，冻结各机组 min(实时可用, 核定计划) 基数，
     * 按可参与出力套用最低保留与比例分摊；目标不可实现整单拒绝（422），不占用 requestId。
     */
    @Transactional
    public PlanOrderReceiveResponse receivePlanOrder(PlanOrderReceiveRequest request) {
        validatePlanDate(request.planDate());
        int segment = request.segmentIndex();
        if (segment < 0 || segment >= SEGMENTS_PER_DAY) {
            throw new ApiBadRequestException("segmentIndex 必须在 0-95 之间：" + segment);
        }
        String fingerprint = Fingerprints.sha256(Canonical.planOrder(request));

        IdempotentRecord existing = idempotentRepository.findByRequestId(request.requestId());
        if (existing != null) {
            return resolvePlanOrderIdempotent(existing, fingerprint);
        }

        // 风场行锁：与同场发布及其他指令受理串行，保证校验的版本与冻结基数一致。
        if (!turbineRepository.lockFarmRow(request.farmCode())) {
            throw new ApiNotFoundException("风场不存在：" + request.farmCode());
        }

        existing = idempotentRepository.findByRequestId(request.requestId());
        if (existing != null) {
            return resolvePlanOrderIdempotent(existing, fingerprint);
        }

        DayAheadPlan current = planRepository.findCurrentPlan(request.farmCode(), request.planDate());
        if (current == null) {
            throw new ApiUnprocessableException(
                    "该风场该 UTC 日期尚无已发布计划版本，不能绑定：" + request.planDate());
        }
        // 过期（历史）版本或版本号不匹配：不允许新指令绑定（422）。
        if (request.expectedVersion() != current.getVersionNo()) {
            throw new ApiUnprocessableException("仅允许绑定当前版本，当前为版本 "
                    + current.getVersionNo() + "，请求绑定版本 " + request.expectedVersion());
        }

        List<Turbine> turbines = turbineRepository.findByFarm(request.farmCode());
        Map<Long, DayAheadPlanEntry> planEntryByTurbineId = new HashMap<>();
        for (DayAheadPlanEntry e : planRepository.findEntriesForSegment(current.getId(), segment)) {
            planEntryByTurbineId.put(e.getTurbineId(), e);
        }

        // 可参与出力 = min(实时可用, 该段核定计划)；同时逐机组冻结基数证据。
        List<OrderPlanBasis> basisRows = new ArrayList<>();
        int participatingTotal = 0;
        for (Turbine t : turbines) {
            DayAheadPlanEntry planEntry = planEntryByTurbineId.get(t.getId());
            if (planEntry == null) {
                // 不可变版本理论上必有该段条目；缺失说明版本数据损坏，按业务拒绝处理。
                throw new ApiUnprocessableException(
                        "版本 " + current.getVersionNo() + " 时段 " + segment
                                + " 缺少机组计划：" + t.getTurbineCode());
            }
            int realtime = t.getAvailablePowerKw();
            int participating = Math.min(realtime, planEntry.getPlannedPowerKw());
            participatingTotal += participating;

            OrderPlanBasis basis = new OrderPlanBasis();
            basis.setTurbineId(t.getId());
            basis.setTurbineCode(t.getTurbineCode());
            basis.setPlannedPowerKw(planEntry.getPlannedPowerKw());
            basis.setRealtimeAvailableKw(realtime);
            basis.setParticipatingKw(participating);
            basisRows.add(basis);

            // 以可参与出力作为分摊输入（保留原技术下限）；不改写机组实时可用值。
            t.setAvailablePowerKw(participating);
        }

        com.example.starter.domain.CurtailOrder active =
                dispatchRepository.findActiveOrder(request.farmCode());
        if (active != null && !active.getReceivedAt().isBefore(request.opTime())) {
            throw new ApiConflictException(
                    "风场同一时刻已有执行中限电指令：" + active.getOrderNo());
        }

        // 目标不可实现（高于可参与出力或低于可实现下限）：整单拒绝（422），在幂等占位之前抛出，不占键。
        List<com.example.starter.domain.Allocation> allocations;
        try {
            allocations = AllocationEngine.allocate(turbines, request.targetPowerKw(), 0L, 1);
        } catch (ApiBadRequestException ex) {
            throw new ApiUnprocessableException(
                    "按可参与出力无法实现目标 " + request.targetPowerKw() + " kW：" + ex.getMessage());
        }

        IdempotentRecord record = new IdempotentRecord();
        record.setRequestId(request.requestId());
        record.setBizType("PLAN_ORDER");
        record.setFingerprint(fingerprint);
        record.setCreatedAt(request.opTime());
        record.setResponseBody("");
        if (!idempotentRepository.tryInsert(record)) {
            return resolvePlanOrderIdempotent(
                    idempotentRepository.findByRequestId(request.requestId()), fingerprint);
        }

        com.example.starter.domain.CurtailOrder order = new com.example.starter.domain.CurtailOrder();
        order.setOrderNo(generateOrderNo(request.opTime()));
        order.setFarmCode(request.farmCode());
        order.setRequestId(request.requestId());
        order.setFingerprint(fingerprint);
        order.setTargetPowerKw(request.targetPowerKw());
        order.setStatus(com.example.starter.domain.OrderStatus.DISPATCHED);
        order.setCurrentCycleNo(1);
        order.setReceivedAt(request.opTime());
        order.setPlanId(current.getId());
        order.setPlanDate(request.planDate());
        order.setSegmentIndex(segment);
        long newId = dispatchRepository.insertOrder(order);
        allocations.forEach(a -> a.setOrderId(newId));
        basisRows.forEach(b -> b.setOrderId(newId));

        if (active != null) {
            dispatchRepository.markSuperseded(active.getId(), newId);
        }
        dispatchRepository.insertCycle(buildCycle(newId, 1, request.opTime()));
        dispatchRepository.insertAllocations(newId, 1, allocations);
        planRepository.insertBasis(basisRows);
        dispatchRepository.activateOrder(newId);

        Map<Long, com.example.starter.domain.Allocation> allocationByTurbineId = new LinkedHashMap<>();
        allocations.forEach(a -> allocationByTurbineId.put(a.getTurbineId(), a));
        List<PlanBasisItem> basisItems = new ArrayList<>();
        for (Turbine t : turbines) {
            OrderPlanBasis b = basisRows.stream()
                    .filter(x -> x.getTurbineId() == t.getId()).findFirst().orElseThrow();
            com.example.starter.domain.Allocation a = allocationByTurbineId.get(t.getId());
            basisItems.add(new PlanBasisItem(t.getTurbineCode(), b.getPlannedPowerKw(),
                    b.getRealtimeAvailableKw(), b.getParticipatingKw(), t.getTechnicalMinKw(),
                    a.getTargetPowerKw(), a.getCurtailKw()));
        }

        PlanOrderReceiveResponse response = new PlanOrderReceiveResponse(
                newId, order.getOrderNo(), request.requestId(), request.farmCode(),
                request.targetPowerKw(), participatingTotal,
                participatingTotal - request.targetPowerKw(),
                com.example.starter.domain.OrderStatus.DISPATCHED.name(), 1,
                current.getId(), request.planDate(), current.getVersionNo(), segment,
                basisItems, false);
        idempotentRepository.updateRefAndResponse(request.requestId(), newId, toJson(response));
        return response;
    }

    private PlanOrderReceiveResponse resolvePlanOrderIdempotent(IdempotentRecord existing, String fingerprint) {
        if (existing == null) {
            throw new ApiConflictException("并发请求处理中，请重试");
        }
        if (!existing.getFingerprint().equals(fingerprint)) {
            throw new ApiConflictException("相同 requestId 的业务参数与首次请求不一致");
        }
        if (existing.getResponseBody() != null && !existing.getResponseBody().isBlank()) {
            return fromJson(existing.getResponseBody(), PlanOrderReceiveResponse.class).asReplay();
        }
        throw new ApiConflictException("首次请求仍在处理中");
    }

    /** 查询风场＋UTC 日期的版本索引（当前版本＋历史版本）。 */
    @Transactional(readOnly = true)
    public PlanVersionResponse versions(String farmCode, String planDate) {
        validatePlanDate(planDate);
        if (!turbineRepository.farmExists(farmCode)) {
            throw new ApiNotFoundException("风场不存在：" + farmCode);
        }
        List<DayAheadPlan> plans = planRepository.findVersions(farmCode, planDate);
        if (plans.isEmpty()) {
            throw new ApiNotFoundException(
                    "该风场该 UTC 日期尚无已发布计划版本：" + farmCode + " " + planDate);
        }
        Integer currentVersionNo = plans.stream()
                .filter(p -> "Y".equals(p.getCurrentFlag()))
                .map(DayAheadPlan::getVersionNo)
                .findFirst().orElse(null);
        List<PlanVersionResponse.VersionSummary> summaries = plans.stream()
                .map(p -> {
                    List<DayAheadPlanEntry> entries = planRepository.findEntries(p.getId());
                    int turbineCount = (int) entries.stream()
                            .map(DayAheadPlanEntry::getTurbineId).distinct().count();
                    int segmentCount = (int) entries.stream()
                            .map(DayAheadPlanEntry::getSegmentIndex).distinct().count();
                    return new PlanVersionResponse.VersionSummary(p.getId(), p.getVersionNo(),
                            "Y".equals(p.getCurrentFlag()), p.getPublishedAt(),
                            segmentCount, turbineCount);
                })
                .toList();
        return new PlanVersionResponse(farmCode, planDate, currentVersionNo, summaries);
    }

    /** 查询指定版本详情。 */
    @Transactional(readOnly = true)
    public PlanDetailResponse detail(long planId) {
        DayAheadPlan plan = planRepository.findPlanById(planId);
        if (plan == null) {
            throw new ApiNotFoundException("计划版本不存在：" + planId);
        }
        List<DayAheadPlanEntry> entries = planRepository.findEntries(planId);
        Map<Integer, List<DayAheadPlanEntry>> bySegment = new LinkedHashMap<>();
        for (int i = 0; i < SEGMENTS_PER_DAY; i++) {
            bySegment.put(i, new ArrayList<>());
        }
        entries.forEach(e -> bySegment.get(e.getSegmentIndex()).add(e));

        List<PlanDetailResponse.SegmentView> segments = bySegment.entrySet().stream()
                .map(en -> new PlanDetailResponse.SegmentView(
                        en.getKey(),
                        en.getValue().stream()
                                .map(e -> new PlanDetailResponse.EntryView(
                                        e.getTurbineCode(), e.getPlannedPowerKw()))
                                .toList()))
                .toList();
        return new PlanDetailResponse(plan.getId(), plan.getFarmCode(), plan.getPlanDate(),
                plan.getVersionNo(), "Y".equals(plan.getCurrentFlag()),
                plan.getPublishedAt(), segments);
    }

    private com.example.starter.domain.DispatchCycle buildCycle(
            long orderId, int cycleNo, Instant dispatchedAt) {
        com.example.starter.domain.DispatchCycle cycle = new com.example.starter.domain.DispatchCycle();
        cycle.setOrderId(orderId);
        cycle.setCycleNo(cycleNo);
        cycle.setDispatchedAt(dispatchedAt);
        cycle.setDueAt(dispatchedAt.plus(DispatchService.DUE_MINUTES,
                java.time.temporal.ChronoUnit.MINUTES));
        return cycle;
    }

    private String generateOrderNo(Instant opTime) {
        return "PO" + opTime.atZone(clock.zone())
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + String.format("%04d", java.util.concurrent.ThreadLocalRandom.current().nextInt(10000));
    }

    static void validatePlanDate(String planDate) {
        try {
            LocalDate.parse(planDate, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException ex) {
            throw new ApiBadRequestException("planDate 必须为 UTC 日期 yyyy-MM-dd：" + planDate);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("响应序列化失败", ex);
        }
    }

    private <T> T fromJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("首次响应无法还原", ex);
        }
    }
}

