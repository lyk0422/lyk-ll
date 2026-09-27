package com.example.starter.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.domain.IdempotentRecord;
import com.example.starter.domain.PlanSegment;
import com.example.starter.domain.PlanVersion;
import com.example.starter.domain.Turbine;
import com.example.starter.dto.PlanPublishRequest;
import com.example.starter.dto.PlanPublishResponse;
import com.example.starter.dto.PlanSegmentInput;
import com.example.starter.dto.PlanVersionResponse;
import com.example.starter.dto.PlanVersionSummary;
import com.example.starter.repo.IdempotentRepository;
import com.example.starter.repo.PlanRepository;
import com.example.starter.repo.TurbineRepository;
import com.example.starter.support.Canonical;
import com.example.starter.support.ServiceClock;
import com.example.starter.web.ApiConflictException;
import com.example.starter.web.ApiNotFoundException;
import com.example.starter.web.ApiUnprocessableException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 日前计划服务：发布不可变版本（96 个 UTC 十五分钟半开时段 × 全场机组核定出力）、
 * 当前指针原子替换、版本查询。发布与计划绑定指令受理共用风场行锁，
 * 保证竞争时校验的版本与指令冻结基数一致。
 */
@Service
public class PlanService {

    static final int SEGMENTS_PER_DAY = 96;

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
     * 发布日前计划版本：
     * 整版须恰好覆盖 96 段 × 全场每台机组各一条，非负且不超过合成装机，否则整版拒绝（422）；
     * expectedVersion 首版传 1，其后须等于当前最新版本号，成功发布版本加 1，不匹配 409；
     * 同 requestId 同参重放首次冻结结果，异参 409；业务失败不占键。
     */
    @Transactional
    public PlanPublishResponse publish(PlanPublishRequest request) {
        Instant now = clock.now();
        // 指纹含调用方给出的精确发布时刻（业务参数的一部分），重放同参稳定、异时刻 409；
        // 版本落库时间仍取服务端受理时钟。
        String fingerprint = fingerprint(request, request.operateAt());

        IdempotentRecord existing = idempotentRepository.findByRequestId(request.requestId());
        if (existing != null) {
            return resolvePublishIdempotent(existing, fingerprint);
        }

        if (!turbineRepository.lockFarmRow(request.farmCode())) {
            throw new ApiNotFoundException("风场不存在：" + request.farmCode());
        }

        existing = idempotentRepository.findByRequestId(request.requestId());
        if (existing != null) {
            return resolvePublishIdempotent(existing, fingerprint);
        }

        List<Turbine> turbines = turbineRepository.findByFarm(request.farmCode());
        if (turbines.isEmpty()) {
            throw new ApiNotFoundException("风场不存在：" + request.farmCode());
        }

        // 先做全部业务校验（失败抛 422，不写幂等占位——失败不占键），再判定版本与写入。
        Map<Integer, Map<String, Integer>> bySegment = validateWholePlan(request, turbines);

        // expectedVersion 为调用方期望本次成功后占用（创建）的版本号：版本从 1 开始，成功变更加 1。
        // 无版本时仅允许 1；其后必须为当前最大版本号 +1。风场行锁内判定，并发竞争仅一方满足。
        Integer currentMax = planRepository.findMaxVersionNo(request.farmCode(), request.planDate());
        int newVersionNo;
        if (currentMax == null) {
            if (request.expectedVersion() != 1) {
                throw new ApiConflictException(
                        "该风场该日尚无计划版本，expectedVersion 必须为 1，实际为 "
                                + request.expectedVersion());
            }
            newVersionNo = 1;
        } else {
            if (request.expectedVersion() != currentMax + 1) {
                throw new ApiConflictException(
                        "expectedVersion=" + request.expectedVersion()
                                + " 与下一个版本号 " + (currentMax + 1) + " 不一致（当前最新 v"
                                + currentMax + "）");
            }
            newVersionNo = currentMax + 1;
        }

        IdempotentRecord record = new IdempotentRecord();
        record.setRequestId(request.requestId());
        record.setBizType("PLAN");
        record.setFingerprint(fingerprint);
        record.setCreatedAt(now);
        record.setResponseBody("");
        if (!idempotentRepository.tryInsert(record)) {
            return resolvePublishIdempotent(
                    idempotentRepository.findByRequestId(request.requestId()), fingerprint);
        }

        PlanVersion version = new PlanVersion();
        version.setFarmCode(request.farmCode());
        version.setPlanDate(request.planDate());
        version.setVersionNo(newVersionNo);
        version.setRequestId(request.requestId());
        version.setFingerprint(fingerprint);
        version.setPublishedAt(now);
        long versionId = planRepository.insertVersion(version);

        Map<String, Long> turbineIdByCode = turbines.stream()
                .collect(Collectors.toMap(Turbine::getTurbineCode, Turbine::getId));
        List<PlanSegment> entities = new ArrayList<>();
        for (int idx = 0; idx < SEGMENTS_PER_DAY; idx++) {
            for (Turbine t : turbines) {
                PlanSegment s = new PlanSegment();
                s.setVersionId(versionId);
                s.setSegmentIndex(idx);
                s.setTurbineId(turbineIdByCode.get(t.getTurbineCode()));
                s.setPowerKw(bySegment.get(idx).get(t.getTurbineCode()));
                entities.add(s);
            }
        }
        planRepository.insertSegments(versionId, entities);
        // 原子替换当前指针：旧当前版本置历史，新版本置当前，历史全部保留。
        planRepository.swapCurrent(request.farmCode(), request.planDate(), versionId);

        PlanPublishResponse response = new PlanPublishResponse(versionId, request.farmCode(),
                request.planDate(), newVersionNo, true, now, entities.size(), turbines.size(),
                request.requestId(), false);
        idempotentRepository.updateRefAndResponse(request.requestId(), versionId, toJson(response));
        return response;
    }

    /** 当前版本详情；无任何版本时 404。 */
    @Transactional(readOnly = true)
    public PlanVersionResponse getCurrentVersion(String farmCode, LocalDate planDate) {
        PlanVersion current = planRepository.findCurrentVersion(farmCode, planDate);
        if (current == null) {
            throw new ApiNotFoundException(
                    "风场 " + farmCode + " 在 " + planDate + " 无已发布日前计划");
        }
        return toVersionResponse(current);
    }

    /** 指定版本号详情；版本不存在 404（过期版本仍可查询，仅不可被新指令绑定）。 */
    @Transactional(readOnly = true)
    public PlanVersionResponse getVersion(String farmCode, LocalDate planDate, int versionNo) {
        PlanVersion version = planRepository.findVersion(farmCode, planDate, versionNo);
        if (version == null) {
            throw new ApiNotFoundException(
                    "计划版本不存在：" + farmCode + " " + planDate + " v" + versionNo);
        }
        return toVersionResponse(version);
    }

    /** 同场同日全部版本（含历史），按版本号升序。 */
    @Transactional(readOnly = true)
    public List<PlanVersionSummary> listVersions(String farmCode, LocalDate planDate) {
        return planRepository.findVersions(farmCode, planDate).stream()
                .map(v -> new PlanVersionSummary(v.getId(), v.getFarmCode(), v.getPlanDate(),
                        v.getVersionNo(), "Y".equals(v.getCurrentFlag()), v.getPublishedAt()))
                .toList();
    }

    /**
     * 整版校验：恰好 96 个不缺段的时段；每段恰好覆盖全场机组各一条（重复机组/多段/少段拒绝）；
     * 出力非负且不超过各机组合成装机。任何不满足均整版拒绝。
     */
    private Map<Integer, Map<String, Integer>> validateWholePlan(
            PlanPublishRequest request, List<Turbine> turbines) {
        Map<String, Turbine> byCode = turbines.stream()
                .collect(Collectors.toMap(Turbine::getTurbineCode, t -> t));
        Set<String> farmCodes = turbines.stream()
                .map(Turbine::getTurbineCode).collect(Collectors.toSet());

        Map<Integer, Map<String, Integer>> bySegment = new HashMap<>();
        Set<Integer> indices = new HashSet<>();
        for (PlanSegmentInput seg : request.segments()) {
            if (seg.segmentIndex() < 0 || seg.segmentIndex() >= SEGMENTS_PER_DAY) {
                throw new ApiUnprocessableException(
                        "时段序号超出 0..95：" + seg.segmentIndex());
            }
            Turbine turbine = byCode.get(seg.turbineCode());
            if (turbine == null) {
                throw new ApiUnprocessableException(
                        "计划包含风场 " + request.farmCode() + " 中不存在的机组：" + seg.turbineCode());
            }
            if (seg.powerKw() > turbine.getRatedPowerKw()) {
                throw new ApiUnprocessableException(
                        "机组 " + seg.turbineCode() + " 第 " + seg.segmentIndex()
                                + " 段核定出力 " + seg.powerKw() + " kW 超过合成装机容量 "
                                + turbine.getRatedPowerKw() + " kW，整版拒绝");
            }
            Map<String, Integer> oneSegment =
                    bySegment.computeIfAbsent(seg.segmentIndex(), k -> new HashMap<>());
            if (oneSegment.put(seg.turbineCode(), seg.powerKw()) != null) {
                throw new ApiUnprocessableException(
                        "机组 " + seg.turbineCode() + " 在第 " + seg.segmentIndex()
                                + " 段重复出现，整版拒绝");
            }
            indices.add(seg.segmentIndex());
        }

        if (indices.size() != SEGMENTS_PER_DAY) {
            throw new ApiUnprocessableException(
                    "计划缺段：仅含 " + indices.size() + " 个时段，须完整覆盖 96 个十五分钟时段，整版拒绝");
        }
        for (int idx = 0; idx < SEGMENTS_PER_DAY; idx++) {
            Map<String, Integer> oneSegment = bySegment.get(idx);
            if (oneSegment == null || !oneSegment.keySet().equals(farmCodes)) {
                throw new ApiUnprocessableException(
                        "第 " + idx + " 段机组集合不完整（须恰好覆盖全场每台机组各一条），整版拒绝");
            }
        }
        return bySegment;
    }

    private PlanPublishResponse resolvePublishIdempotent(IdempotentRecord existing, String fingerprint) {
        if (!existing.getFingerprint().equals(fingerprint)) {
            throw new ApiConflictException("相同 requestId 的业务参数与首次请求不一致");
        }
        if (existing.getResponseBody() != null && !existing.getResponseBody().isBlank()) {
            return fromJson(existing.getResponseBody(), PlanPublishResponse.class).asReplay();
        }
        throw new ApiConflictException("首次请求仍在处理中");
    }

    private PlanVersionResponse toVersionResponse(PlanVersion version) {
        Map<Long, String> codeById = turbineRepository.findByFarm(version.getFarmCode()).stream()
                .collect(Collectors.toMap(Turbine::getId, Turbine::getTurbineCode));
        List<PlanVersionResponse.SegmentView> views = planRepository.findSegments(version.getId())
                .stream()
                .sorted(Comparator.comparingInt(PlanSegment::getSegmentIndex)
                        .thenComparing(s -> codeById.getOrDefault(s.getTurbineId(),
                                String.valueOf(s.getTurbineId()))))
                .map(s -> {
                    Instant start = version.getPlanDate().atStartOfDay()
                            .plusMinutes(15L * s.getSegmentIndex()).toInstant(ZoneOffset.UTC);
                    return new PlanVersionResponse.SegmentView(s.getSegmentIndex(), start,
                            start.plusSeconds(15 * 60L),
                            codeById.getOrDefault(s.getTurbineId(), String.valueOf(s.getTurbineId())),
                            s.getPowerKw());
                })
                .toList();
        return new PlanVersionResponse(version.getId(), version.getFarmCode(), version.getPlanDate(),
                version.getVersionNo(), "Y".equals(version.getCurrentFlag()),
                version.getPublishedAt(), version.getRequestId(), views);
    }

    /**
     * 结构化指纹：TLV 规范编码，精确到发布时刻；段集合按（时段、机组）确定性排序。
     * 合法文本含分隔符也无歧义：边界由长度前缀决定。
     */
    private String fingerprint(PlanPublishRequest request, Instant publishedAt) {
        List<String> segmentTokens = request.segments().stream()
                .sorted(Comparator.comparingInt(PlanSegmentInput::segmentIndex)
                        .thenComparing(PlanSegmentInput::turbineCode))
                .map(s -> Canonical.obj(
                        "segmentIndex", Canonical.integer(s.segmentIndex()),
                        "turbineCode", Canonical.str(s.turbineCode()),
                        "powerKw", Canonical.integer(s.powerKw())))
                .toList();
        String canonical = Canonical.obj(
                "bizType", Canonical.str("PLAN_PUBLISH"),
                "requestId", Canonical.str(request.requestId()),
                "farmCode", Canonical.str(request.farmCode()),
                "planDate", Canonical.date(request.planDate()),
                "expectedVersion", Canonical.integer(request.expectedVersion()),
                "operateAt", Canonical.instant(publishedAt),
                "segments", Canonical.list(segmentTokens));
        return Canonical.sha256(canonical);
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
