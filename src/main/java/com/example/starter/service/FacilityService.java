package com.example.starter.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.StringJoiner;
import java.util.TreeSet;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.domain.WasteCategory;
import com.example.starter.repo.FacilityRepository;
import com.example.starter.support.BizException;
import com.example.starter.support.Fingerprints;

/**
 * 处理设施目录维护：可接收品类与 UTC 停收区间 [start, end) 的全量修改。
 * 设施版本自 1 起，修改须携带 expectedVersion，成功 +1、过期 409；
 * 改版只影响之后的首次联单与改投校验，不追改已经闭环的联单。
 */
@Service
public class FacilityService {

    private final FacilityRepository facilityRepository;
    private final Clock clock;

    public FacilityService(FacilityRepository facilityRepository, Clock clock) {
        this.facilityRepository = facilityRepository;
        this.clock = clock;
    }

    /**
     * 停收区间视图：UTC [startAt, endAt)，首端含、末端不含。
     */
    public record SuspensionView(Instant startAt, Instant endAt) {
    }

    /**
     * 设施目录视图。
     */
    public record FacilityView(
            String code,
            String name,
            boolean active,
            int version,
            List<String> categories,
            List<SuspensionView> suspensions) {
    }

    /**
     * 设施修改结果。
     *
     * @param replayed 是否为幂等重放（同 requestId 同参返回首次结果）
     */
    public record FacilityUpdateResult(
            String code,
            int version,
            List<String> categories,
            List<SuspensionView> suspensions,
            boolean replayed,
            Instant appliedAt) {
    }

    /**
     * 全量修改设施可接收品类与停收区间。指纹覆盖全部品类与区间精确时刻。
     */
    @Transactional
    public FacilityUpdateResult update(String code, String requestId, int expectedVersion,
                                       List<String> categories, List<SuspensionView> suspensions) {
        List<String> normalizedCategories = normalizeCategories(categories);
        List<SuspensionView> normalizedSuspensions = normalizeSuspensions(suspensions);
        String fingerprint = fingerprintOf(requestId, code, expectedVersion,
                normalizedCategories, normalizedSuspensions);

        var existing = facilityRepository.findRevisionByRequestId(requestId);
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), fingerprint);
        }

        var facility = facilityRepository.findByCode(code)
                .orElseThrow(() -> BizException.notFound("FACILITY_NOT_FOUND", "设施不存在：" + code));
        if (facility.version() != expectedVersion) {
            throw BizException.conflict("FACILITY_VERSION_CONFLICT",
                    "设施版本已变化：期望 " + expectedVersion + "，当前 " + facility.version());
        }

        Instant now = clock.instant();
        int newVersion = expectedVersion + 1;
        try {
            facilityRepository.insertRevision(facility.id(), requestId, fingerprint, newVersion, now);
        } catch (DuplicateKeyException e) {
            var duplicated = facilityRepository.findRevisionByRequestId(requestId);
            if (duplicated.isPresent()) {
                return replayOrConflict(duplicated.get(), fingerprint);
            }
            throw BizException.conflict("FACILITY_VERSION_CONFLICT", "设施版本已被并发修改：" + code);
        }
        int updated = facilityRepository.bumpVersion(facility.id(), expectedVersion);
        if (updated != 1) {
            throw BizException.conflict("FACILITY_VERSION_CONFLICT", "设施版本已变化，修改失败：" + code);
        }
        facilityRepository.replaceCategories(facility.id(), normalizedCategories);
        facilityRepository.replaceSuspensions(facility.id(), normalizedSuspensions.stream()
                .map(s -> new FacilityRepository.SuspensionRow(s.startAt(), s.endAt()))
                .toList());

        return new FacilityUpdateResult(code, newVersion, normalizedCategories, normalizedSuspensions, false, now);
    }

    /**
     * 设施目录查询：版本、可接收品类与停收区间。
     */
    @Transactional(readOnly = true)
    public FacilityView detail(String code) {
        var facility = facilityRepository.findByCode(code)
                .orElseThrow(() -> BizException.notFound("FACILITY_NOT_FOUND", "设施不存在：" + code));
        return new FacilityView(facility.code(), facility.name(), facility.active(), facility.version(),
                facilityRepository.listCategories(facility.id()),
                facilityRepository.listSuspensions(facility.id()).stream()
                        .map(s -> new SuspensionView(s.startAt(), s.endAt()))
                        .toList());
    }

    private FacilityUpdateResult replayOrConflict(FacilityRepository.RevisionRow row, String fingerprint) {
        if (!row.fingerprint().equals(fingerprint)) {
            throw BizException.conflict("IDEMPOTENCY_CONFLICT",
                    "相同 requestId 提交了不同业务参数：" + row.requestId());
        }
        var facility = facilityRepository.findById(row.facilityId())
                .orElseThrow(() -> BizException.notFound("FACILITY_NOT_FOUND", "设施不存在：" + row.facilityId()));
        return new FacilityUpdateResult(facility.code(), row.version(),
                facilityRepository.listCategories(facility.id()),
                facilityRepository.listSuspensions(facility.id()).stream()
                        .map(s -> new SuspensionView(s.startAt(), s.endAt()))
                        .toList(),
                true, row.appliedAt());
    }

    private static List<String> normalizeCategories(List<String> categories) {
        if (categories == null) {
            throw BizException.badRequest("CATEGORY_LIST_INVALID", "可接收品类列表不能为空引用");
        }
        TreeSet<String> normalized = new TreeSet<>();
        for (String category : categories) {
            try {
                normalized.add(WasteCategory.valueOf(category).name());
            } catch (IllegalArgumentException | NullPointerException e) {
                throw BizException.badRequest("CATEGORY_INVALID", "未知垃圾品类：" + category);
            }
        }
        return List.copyOf(normalized);
    }

    private static List<SuspensionView> normalizeSuspensions(List<SuspensionView> suspensions) {
        if (suspensions == null) {
            return List.of();
        }
        for (SuspensionView suspension : suspensions) {
            if (suspension.startAt() == null || suspension.endAt() == null
                    || !suspension.startAt().isBefore(suspension.endAt())) {
                throw BizException.badRequest("SUSPENSION_INTERVAL_INVALID",
                        "停收区间须满足 startAt 早于 endAt（[start, end)）");
            }
        }
        return suspensions.stream()
                .sorted(java.util.Comparator.comparing(SuspensionView::startAt)
                        .thenComparing(SuspensionView::endAt))
                .toList();
    }

    private static String fingerprintOf(String requestId, String code, int expectedVersion,
                                        List<String> categories, List<SuspensionView> suspensions) {
        StringJoiner categoryJoin = new StringJoiner(",");
        categories.forEach(categoryJoin::add);
        StringJoiner suspensionJoin = new StringJoiner(",");
        for (SuspensionView s : suspensions) {
            suspensionJoin.add(s.startAt() + "~" + s.endAt());
        }
        return Fingerprints.of(requestId, code, String.valueOf(expectedVersion),
                categoryJoin.toString(), suspensionJoin.toString());
    }
}
