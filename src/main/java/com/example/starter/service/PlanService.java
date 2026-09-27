package com.example.starter.service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.domain.DayAheadPlan;
import com.example.starter.domain.IdempotentRecord;
import com.example.starter.domain.PlanSlot;
import com.example.starter.domain.Turbine;
import com.example.starter.dto.PlanDetailResponse;
import com.example.starter.dto.PlanPublishRequest;
import com.example.starter.dto.PlanPublishResponse;
import com.example.starter.dto.PlanVersionView;
import com.example.starter.repo.IdempotentRepository;
import com.example.starter.repo.PlanRepository;
import com.example.starter.repo.TurbineRepository;
import com.example.starter.support.Fingerprints;
import com.example.starter.support.ServiceClock;
import com.example.starter.web.ApiBadRequestException;
import com.example.starter.web.ApiConflictException;
import com.example.starter.web.ApiNotFoundException;
import com.example.starter.web.ApiUnprocessableException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 日前发电计划服务：按风场＋UTC 日期编制 96 个 15 分钟半开时段计划。
 * 发布校验整版进行（缺段、重复机组、超装机容量即整版拒绝）；发布后版本不可变，
 * 同场同日仅一个当前版本，新版发布在风场行锁内原子替换当前指针并保留历史。
 */
@Service
public class PlanService {

    /** 一日 96 个 15 分钟时段。 */
    static final int SLOTS_PER_DAY = 96;
    /** 单时段时长（分钟）。 */
    static final long SLOT_MINUTES = 15;

    private final ServiceClock clock;
    private final TurbineRepository turbineRepository;
    private final PlanRepository planRepository;
    private final IdempotentRepository idempotentRepository;
    private final ObjectMapper objectMapper;

    public PlanService(ServiceClock clock,
                       TurbineRepository turbineRepository,
                       PlanRepository planRepository,
                       IdempotentRepository idempotentRepository,
                       ObjectMapper objectMapper) {
        this.clock = clock;
        this.turbineRepository = turbineRepository;
        this.planRepository = planRepository;
        this.idempotentRepository = idempotentRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 发布日前计划：风场行锁串行化同场并发发布；版本、当前指针与幂等结果同事务。
     */
    @Transactional
    public PlanPublishResponse publish(PlanPublishRequest request) {
        Instant now = clock.now();
        String fingerprint = planFingerprint(request);

        IdempotentRecord existing = idempotentRepository.findByRequestId(request.requestId());
        if (existing != null) {
            return resolvePlanIdempotent(existing, fingerprint);
        }

        if (!turbineRepository.lockFarmRow(request.farmCode())) {
            throw new ApiNotFoundException("风场不存在：" + request.farmCode());
        }

        existing = idempotentRepository.findByRequestId(request.requestId());
        if (existing != null) {
            return resolvePlanIdempotent(existing, fingerprint);
        }

        List<Turbine> turbines = turbineRepository.findByFarm(request.farmCode());
        List<PlanSlot> slots = validateAndBuildSlots(request, turbines);

        // 乐观版本校验：expectedVersion 必须等于当前最新版本号（无已发布版本时为 0）。
        Integer latest = planRepository.findLatestVersion(request.farmCode(), request.planDate());
        int expected = latest == null ? 0 : latest;
        if (request.expectedVersion() != expected) {
            throw new ApiConflictException(
                    "计划版本冲突：期望当前版本 " + request.expectedVersion()
                            + "，实际当前版本 " + expected);
        }

        // 先落幂等占位（唯一约束兜底并发受理），业务写入与占位同事务，失败整体回滚不占键。
        IdempotentRecord record = new IdempotentRecord();
        record.setRequestId(request.requestId());
        record.setBizType("PLAN");
        record.setFingerprint(fingerprint);
        record.setCreatedAt(now);
        record.setResponseBody("");
        if (!idempotentRepository.tryInsert(record)) {
            return resolvePlanIdempotent(
                    idempotentRepository.findByRequestId(request.requestId()), fingerprint);
        }

        DayAheadPlan plan = new DayAheadPlan();
        plan.setFarmCode(request.farmCode());
        plan.setPlanDate(request.planDate());
        plan.setVersion(expected + 1);
        plan.setRequestId(request.requestId());
        plan.setFingerprint(fingerprint);
        plan.setPublishedAt(now);
        long planId = planRepository.insertPlan(plan);
        slots.forEach(s -> s.setPlanId(planId));
        planRepository.insertSlots(planId, slots);

        // 同一事务：旧当前版本指针释放 → 新版本置当前，历史版本保留。
        planRepository.clearCurrent(request.farmCode(), request.planDate());
        planRepository.markCurrent(planId);

        PlanPublishResponse response = new PlanPublishResponse(
                planId, plan.getFarmCode(), plan.getPlanDate(), plan.getVersion(),
                true, SLOTS_PER_DAY, now, false);
        idempotentRepository.updateRefAndResponse(request.requestId(), planId, toJson(response));
        return response;
    }

    private PlanPublishResponse resolvePlanIdempotent(IdempotentRecord existing, String fingerprint) {
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
     * 结构校验并构建时段明细：恰 96 段、slotIndex 覆盖 0-95 无重复、
     * 每段机组集合与风场机组一致且无重复、计划值不超过装机容量；任一不满足整版拒绝（422）。
     */
    private List<PlanSlot> validateAndBuildSlots(PlanPublishRequest request, List<Turbine> turbines) {
        if (request.slots().size() != SLOTS_PER_DAY) {
            throw new ApiUnprocessableException(
                    "计划须含完整 " + SLOTS_PER_DAY + " 个时段，实际 " + request.slots().size() + " 段");
        }
        Map<String, Turbine> byCode = new HashMap<>();
        for (Turbine t : turbines) {
            byCode.put(t.getTurbineCode(), t);
        }

        Set<Integer> seenSlots = new HashSet<>();
        List<PlanSlot> result = new ArrayList<>(SLOTS_PER_DAY * turbines.size());
        for (PlanPublishRequest.SlotPlan slot : request.slots()) {
            if (slot == null || slot.slotIndex() == null || slot.turbines() == null) {
                throw new ApiBadRequestException("计划时段结构不完整");
            }
            int slotIndex = slot.slotIndex();
            if (slotIndex < 0 || slotIndex >= SLOTS_PER_DAY) {
                throw new ApiUnprocessableException("时段序号越界（0-95）：" + slotIndex);
            }
            if (!seenSlots.add(slotIndex)) {
                throw new ApiUnprocessableException("时段重复：" + slotIndex);
            }
            if (slot.turbines().size() != turbines.size()) {
                throw new ApiUnprocessableException(
                        "时段 " + slotIndex + " 机组数 " + slot.turbines().size()
                                + " 与风场机组数 " + turbines.size() + " 不一致");
            }
            Set<String> seenTurbines = new HashSet<>();
            for (PlanPublishRequest.TurbinePlan tp : slot.turbines()) {
                if (tp == null || tp.turbineCode() == null
                        || tp.plannedKw() == null || tp.plannedKw() < 0) {
                    throw new ApiBadRequestException(
                            "时段 " + slotIndex + " 机组计划值非法（须为非负整数 kW）");
                }
                if (!seenTurbines.add(tp.turbineCode())) {
                    throw new ApiUnprocessableException(
                            "时段 " + slotIndex + " 机组重复：" + tp.turbineCode());
                }
                Turbine turbine = byCode.get(tp.turbineCode());
                if (turbine == null) {
                    throw new ApiUnprocessableException(
                            "时段 " + slotIndex + " 含风场不存在的机组：" + tp.turbineCode());
                }
                if (tp.plannedKw() > turbine.getRatedPowerKw()) {
                    throw new ApiUnprocessableException(
                            "时段 " + slotIndex + " 机组 " + tp.turbineCode()
                                    + " 计划 " + tp.plannedKw() + " kW 超过装机容量 "
                                    + turbine.getRatedPowerKw() + " kW");
                }
                PlanSlot ps = new PlanSlot();
                ps.setSlotIndex(slotIndex);
                ps.setTurbineId(turbine.getId());
                ps.setPlannedKw(tp.plannedKw());
                result.add(ps);
            }
        }
        for (int i = 0; i < SLOTS_PER_DAY; i++) {
            if (!seenSlots.contains(i)) {
                throw new ApiUnprocessableException("计划时段不完整：缺段 " + i);
            }
        }
        return result;
    }

    /**
     * 计划指纹：覆盖 requestId、风场、日期、期望版本与全部时段集合。
     * 时段按序号、段内机组按编码排序后长度前缀编码，与请求内集合顺序无关且结构无歧义。
     */
    private String planFingerprint(PlanPublishRequest request) {
        List<PlanPublishRequest.SlotPlan> sortedSlots = new ArrayList<>(request.slots());
        sortedSlots.sort(Comparator.comparing(PlanPublishRequest.SlotPlan::slotIndex));
        List<String> slotFields = new ArrayList<>(sortedSlots.size());
        for (PlanPublishRequest.SlotPlan slot : sortedSlots) {
            List<PlanPublishRequest.TurbinePlan> sortedTurbines = new ArrayList<>(slot.turbines());
            sortedTurbines.sort(Comparator.comparing(PlanPublishRequest.TurbinePlan::turbineCode));
            List<String> turbineFields = new ArrayList<>(sortedTurbines.size() * 2);
            for (PlanPublishRequest.TurbinePlan tp : sortedTurbines) {
                turbineFields.add(tp.turbineCode());
                turbineFields.add(String.valueOf(tp.plannedKw()));
            }
            slotFields.add(Fingerprints.encode("SLOT", List.of(
                    String.valueOf(slot.slotIndex()),
                    Fingerprints.encode("TURBINES", turbineFields))));
        }
        return Fingerprints.structured("PLAN", List.of(
                request.requestId(),
                request.farmCode(),
                request.planDate().toString(),
                String.valueOf(request.expectedVersion()),
                Fingerprints.encode("SLOTS", slotFields)));
    }

    /** 查询当前版本（含 96 段明细）；无当前版本返回 404。 */
    public PlanDetailResponse current(String farmCode, java.time.LocalDate planDate) {
        DayAheadPlan plan = planRepository.findCurrent(farmCode, planDate);
        if (plan == null) {
            throw new ApiNotFoundException(
                    "无当前计划版本：" + farmCode + " " + planDate);
        }
        return toDetail(plan);
    }

    /** 同场同日全部版本（含历史），按版本号升序。 */
    public List<PlanVersionView> versions(String farmCode, java.time.LocalDate planDate) {
        return planRepository.findVersions(farmCode, planDate).stream()
                .map(p -> new PlanVersionView(p.getId(), p.getFarmCode(), p.getPlanDate(),
                        p.getVersion(), p.isCurrent(), p.getPublishedAt()))
                .toList();
    }

    /** 按 id 查询版本详情（历史版本亦可查）。 */
    public PlanDetailResponse detail(long planId) {
        DayAheadPlan plan = planRepository.findById(planId);
        if (plan == null) {
            throw new ApiNotFoundException("计划版本不存在：" + planId);
        }
        return toDetail(plan);
    }

    private PlanDetailResponse toDetail(DayAheadPlan plan) {
        List<PlanSlot> slots = planRepository.findSlots(plan.getId());
        Map<Long, String> codeById = new HashMap<>();
        for (Turbine t : turbineRepository.findByFarm(plan.getFarmCode())) {
            codeById.put(t.getId(), t.getTurbineCode());
        }
        Map<Integer, List<PlanSlot>> bySlot = new java.util.TreeMap<>();
        for (PlanSlot s : slots) {
            bySlot.computeIfAbsent(s.getSlotIndex(), k -> new ArrayList<>()).add(s);
        }
        Instant dayStart = plan.getPlanDate().atStartOfDay().toInstant(ZoneOffset.UTC);
        List<PlanDetailResponse.SlotView> slotViews = new ArrayList<>(bySlot.size());
        for (Map.Entry<Integer, List<PlanSlot>> entry : bySlot.entrySet()) {
            Instant slotStart = dayStart.plus(SLOT_MINUTES * entry.getKey(),
                    java.time.temporal.ChronoUnit.MINUTES);
            List<PlanDetailResponse.TurbinePlanView> turbineViews = entry.getValue().stream()
                    .sorted(Comparator.comparing(s -> codeById.getOrDefault(
                            s.getTurbineId(), String.valueOf(s.getTurbineId()))))
                    .map(s -> new PlanDetailResponse.TurbinePlanView(
                            codeById.getOrDefault(s.getTurbineId(),
                                    String.valueOf(s.getTurbineId())),
                            s.getPlannedKw()))
                    .toList();
            slotViews.add(new PlanDetailResponse.SlotView(entry.getKey(), slotStart,
                    slotStart.plus(SLOT_MINUTES, java.time.temporal.ChronoUnit.MINUTES),
                    turbineViews));
        }
        return new PlanDetailResponse(plan.getId(), plan.getFarmCode(), plan.getPlanDate(),
                plan.getVersion(), plan.isCurrent(), plan.getPublishedAt(), slotViews);
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
