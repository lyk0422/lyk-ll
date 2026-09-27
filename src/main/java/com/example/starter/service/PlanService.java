package com.example.starter.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.domain.DayAheadPlan;
import com.example.starter.domain.DayAheadPlanItem;
import com.example.starter.domain.IdempotentRecord;
import com.example.starter.domain.Turbine;
import com.example.starter.dto.PlanPublishRequest;
import com.example.starter.dto.PlanPublishResponse;
import com.example.starter.dto.PlanVersionResponse;
import com.example.starter.repo.IdempotentRepository;
import com.example.starter.repo.PlanRepository;
import com.example.starter.repo.TurbineRepository;
import com.example.starter.support.Fingerprints;
import com.example.starter.support.ServiceClock;
import com.example.starter.web.ApiBadRequestException;
import com.example.starter.web.ApiConflictException;
import com.example.starter.web.ApiNotFoundException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 日前计划服务：发布（整版校验、版本从 1 递增、原子替换当前指针、历史保留）、
 * 当前版本与历史版本查询。发布与指令受理共用风场行锁，保证版本与基数校验一致。
 */
@Service
public class PlanService {

    /** 每日完整时段数：96 个十五分钟半开时段。 */
    public static final int PERIODS_PER_DAY = 96;

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
     * 发布日前计划：整版校验（完整 96 段、机组恰好覆盖全场且不重复、不超装机容量），
     * expectedVersion 须等于当前版本号；成功后版本加 1 并原子替换当前指针，历史版本保留。
     * 同 requestId 同参重放首次结果，异参 409；校验失败事务回滚，不占幂等键。
     */
    @Transactional
    public PlanPublishResponse publish(PlanPublishRequest request) {
        Instant now = clock.now();
        String fingerprint = fingerprintOf(request);

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

        List<Turbine> farmTurbines = turbineRepository.findByFarm(request.farmCode());
        Map<String, Turbine> byCode = new HashMap<>();
        for (Turbine t : farmTurbines) {
            byCode.put(t.getTurbineCode(), t);
        }
        validateAndResolve(request, byCode, farmTurbines.size());

        DayAheadPlan current = planRepository.findCurrent(request.farmCode(), request.planDate());
        int currentVersion = current == null ? 0 : current.getVersion();
        if (request.expectedVersion() != currentVersion) {
            throw new ApiConflictException("期望版本 " + request.expectedVersion()
                    + " 与当前版本 " + currentVersion + " 不一致");
        }

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
        plan.setVersion(currentVersion + 1);
        plan.setRequestId(request.requestId());
        plan.setFingerprint(fingerprint);
        plan.setCurrent(false);
        plan.setPublishedAt(now);
        long planId = planRepository.insertPlan(plan);

        List<DayAheadPlanItem> items = new ArrayList<>();
        for (PlanPublishRequest.TurbinePlan tp : request.turbines()) {
            Turbine turbine = byCode.get(tp.turbineCode());
            for (int i = 0; i < PERIODS_PER_DAY; i++) {
                DayAheadPlanItem item = new DayAheadPlanItem();
                item.setPlanId(planId);
                item.setPeriodIndex(i + 1);
                item.setTurbineId(turbine.getId());
                item.setPlannedKw(tp.plannedKw().get(i));
                items.add(item);
            }
        }
        planRepository.insertItems(planId, items);

        // 同一事务：先撤下旧当前指针再置新版本为当前，同场同日任意时刻至多一个当前版本。
        planRepository.clearCurrent(request.farmCode(), request.planDate());
        planRepository.markCurrent(planId);

        PlanPublishResponse response = new PlanPublishResponse(planId, request.farmCode(),
                request.planDate(), plan.getVersion(), request.turbines().size(),
                PERIODS_PER_DAY, now, false);
        idempotentRepository.updateRefAndResponse(request.requestId(), planId, toJson(response));
        return response;
    }

    /** 查询同场同日当前版本详情；无已发布版本返回 404。 */
    public PlanVersionResponse currentPlan(String farmCode, java.time.LocalDate planDate) {
        DayAheadPlan plan = planRepository.findCurrent(farmCode, planDate);
        if (plan == null) {
            throw new ApiNotFoundException(
                    "该风场该日期无已发布计划：" + farmCode + " " + planDate);
        }
        return toVersionView(plan);
    }

    /** 查询指定版本详情（含历史版本）；版本不存在返回 404。 */
    public PlanVersionResponse planVersion(String farmCode, java.time.LocalDate planDate, int version) {
        DayAheadPlan plan = planRepository.findByVersion(farmCode, planDate, version);
        if (plan == null) {
            throw new ApiNotFoundException(
                    "计划版本不存在：" + farmCode + " " + planDate + " v" + version);
        }
        return toVersionView(plan);
    }

    private PlanVersionResponse toVersionView(DayAheadPlan plan) {
        List<Turbine> farmTurbines = turbineRepository.findByFarm(plan.getFarmCode());
        Map<Long, String> codeById = new HashMap<>();
        for (Turbine t : farmTurbines) {
            codeById.put(t.getId(), t.getTurbineCode());
        }
        List<PlanVersionResponse.PlanItemView> items = planRepository.findItems(plan.getId())
                .stream()
                .map(item -> new PlanVersionResponse.PlanItemView(item.getPeriodIndex(),
                        codeById.getOrDefault(item.getTurbineId(), String.valueOf(item.getTurbineId())),
                        item.getPlannedKw()))
                .toList();
        return new PlanVersionResponse(plan.getId(), plan.getFarmCode(), plan.getPlanDate(),
                plan.getVersion(), plan.isCurrent(), plan.getPublishedAt(), items);
    }

    /** 整版校验：缺段/多段、重复机组、未知机组、未覆盖全场机组、负值或超装机容量均整版拒绝（400）。 */
    private void validateAndResolve(PlanPublishRequest request, Map<String, Turbine> byCode,
                                    int farmTurbineCount) {
        if (request.turbines() == null || request.turbines().isEmpty()) {
            throw new ApiBadRequestException("计划须包含至少一台机组的 96 段计划");
        }
        Set<String> seen = new HashSet<>();
        for (PlanPublishRequest.TurbinePlan tp : request.turbines()) {
            if (tp.turbineCode() == null || tp.turbineCode().isBlank()) {
                throw new ApiBadRequestException("机组编码不能为空");
            }
            if (!seen.add(tp.turbineCode())) {
                throw new ApiBadRequestException("计划含重复机组：" + tp.turbineCode());
            }
            Turbine turbine = byCode.get(tp.turbineCode());
            if (turbine == null) {
                throw new ApiBadRequestException("机组不属于该风场：" + tp.turbineCode());
            }
            if (tp.plannedKw() == null || tp.plannedKw().size() != PERIODS_PER_DAY) {
                throw new ApiBadRequestException("机组 " + tp.turbineCode() + " 计划须含完整 "
                        + PERIODS_PER_DAY + " 个时段");
            }
            for (int i = 0; i < PERIODS_PER_DAY; i++) {
                Integer kw = tp.plannedKw().get(i);
                if (kw == null || kw < 0) {
                    throw new ApiBadRequestException("机组 " + tp.turbineCode()
                            + " 时段 " + (i + 1) + " 计划出力须为非负整数");
                }
                if (kw > turbine.getRatedPowerKw()) {
                    throw new ApiBadRequestException("机组 " + tp.turbineCode() + " 时段 " + (i + 1)
                            + " 计划出力 " + kw + " kW 超过装机容量 " + turbine.getRatedPowerKw() + " kW");
                }
            }
        }
        if (seen.size() != farmTurbineCount) {
            throw new ApiBadRequestException("计划须覆盖风场全部 " + farmTurbineCount
                    + " 台机组，实际仅 " + seen.size() + " 台");
        }
    }

    /** 结构化指纹：覆盖 requestId、风场、日期、期望版本与每台机组全部 96 段值，长度前缀无歧义。 */
    private String fingerprintOf(PlanPublishRequest request) {
        List<String> parts = new ArrayList<>();
        parts.add(request.requestId());
        parts.add(request.farmCode());
        parts.add(request.planDate() == null ? null : request.planDate().toString());
        parts.add(String.valueOf(request.expectedVersion()));
        if (request.turbines() != null) {
            for (PlanPublishRequest.TurbinePlan tp : request.turbines()) {
                parts.add(tp.turbineCode());
                if (tp.plannedKw() != null) {
                    for (Integer kw : tp.plannedKw()) {
                        parts.add(kw == null ? null : String.valueOf(kw));
                    }
                }
            }
        }
        return Fingerprints.struct(parts.toArray(new String[0]));
    }

    private PlanPublishResponse resolvePlanIdempotent(IdempotentRecord existing, String fingerprint) {
        if (!existing.getFingerprint().equals(fingerprint)) {
            throw new ApiConflictException("相同 requestId 的业务参数与首次请求不一致");
        }
        if (existing.getResponseBody() != null && !existing.getResponseBody().isBlank()) {
            return fromJson(existing.getResponseBody(), PlanPublishResponse.class).asReplay();
        }
        throw new ApiConflictException("首次请求仍在处理中");
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
