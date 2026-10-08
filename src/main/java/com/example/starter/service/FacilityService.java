package com.example.starter.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.domain.WasteCategory;
import com.example.starter.repo.FacilityRepository;
import com.example.starter.support.BizException;
import com.example.starter.support.Fingerprints;

/**
 * 处理设施目录：进场/改投时的设施验收（目录有效、品类可收、当前时刻不在 UTC 停收区间），
 * 以及设施停收改版（expectedVersion 乐观并发，成功加 1、过期 409）。
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
     * 设施改版请求。字段为 null 表示该项不变；clearSuspension 为 true 时清除停收配置。
     *
     * @param categories 可接收品类全集（替换语义）；null 表示不变
     * @param suspension 停收区间 [startAt, endAt)；null 表示不变
     */
    public record RevisionCommand(
            String requestId,
            String facilityCode,
            int expectedVersion,
            Boolean active,
            List<String> categories,
            SuspensionWindow suspension,
            boolean clearSuspension) {
    }

    /**
     * UTC 停收区间 [startAt, endAt)：首端停收、恰 endAt 恢复。
     */
    public record SuspensionWindow(Instant startAt, Instant endAt) {
    }

    /**
     * 设施改版结果。
     *
     * @param replayed 是否为幂等重放（同键同参返回首次结果）
     */
    public record RevisionResult(
            String requestId,
            String facilityCode,
            int version,
            boolean replayed,
            Instant appliedAt) {
    }

    /**
     * 验收设施当前可接收指定品类：在目录内有效、品类可收（未配置品类则不限制）、
     * 当前时刻不在停收区间 [start, end)。首次联单与改投共用的同一套检查。
     */
    public FacilityRepository.FacilityRow checkAcceptance(String facilityCode, String category, Instant now) {
        var facility = facilityRepository.findByCode(facilityCode)
                .filter(FacilityRepository.FacilityRow::active)
                .orElseThrow(() -> BizException.badRequest("FACILITY_NOT_IN_CATALOG",
                        "去向设施不在处理设施目录内：" + facilityCode));

        var accepted = facilityRepository.findCategories(facility.id());
        if (!accepted.isEmpty() && !accepted.contains(category)) {
            throw BizException.badRequest("FACILITY_CATEGORY_NOT_ACCEPTED",
                    "设施 " + facilityCode + " 当前不接收品类 " + category);
        }

        facilityRepository.findSuspension(facility.id()).ifPresent(suspension -> {
            if (!now.isBefore(suspension.startAt()) && now.isBefore(suspension.endAt())) {
                throw BizException.conflict("FACILITY_SUSPENDED",
                        "设施 " + facilityCode + " 当前处于停收区间 [" + suspension.startAt()
                                + ", " + suspension.endAt() + ")");
            }
        });
        return facility;
    }

    @Transactional
    public RevisionResult revise(RevisionCommand command) {
        validate(command);
        String fingerprint = fingerprintOf(command);

        var existing = facilityRepository.findRevisionByRequestId(command.requestId());
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), fingerprint, command.facilityCode());
        }

        var facility = facilityRepository.findByCode(command.facilityCode())
                .orElseThrow(() -> BizException.notFound("FACILITY_NOT_FOUND",
                        "设施不存在：" + command.facilityCode()));

        Instant now = clock.instant();
        int updated;
        try {
            updated = facilityRepository.reviseFacility(facility.id(), command.expectedVersion(), command.active());
            if (updated != 1) {
                throw BizException.conflict("FACILITY_VERSION_STALE",
                        "设施版本已变化，期望 " + command.expectedVersion() + "，当前 " + facility.version());
            }
            if (command.categories() != null) {
                facilityRepository.replaceCategories(facility.id(), command.categories());
            }
            if (command.clearSuspension()) {
                facilityRepository.clearSuspension(facility.id());
            } else if (command.suspension() != null) {
                facilityRepository.upsertSuspension(facility.id(),
                        command.suspension().startAt(), command.suspension().endAt());
            }
            facilityRepository.insertRevision(command.requestId(), fingerprint,
                    facility.id(), command.expectedVersion() + 1, now);
        } catch (DuplicateKeyException e) {
            var duplicated = facilityRepository.findRevisionByRequestId(command.requestId());
            if (duplicated.isPresent()) {
                return replayOrConflict(duplicated.get(), fingerprint, command.facilityCode());
            }
            throw e;
        }

        return new RevisionResult(command.requestId(), command.facilityCode(),
                command.expectedVersion() + 1, false, now);
    }

    private RevisionResult replayOrConflict(FacilityRepository.RevisionRow row, String fingerprint,
                                            String facilityCode) {
        if (!row.fingerprint().equals(fingerprint)) {
            throw BizException.conflict("IDEMPOTENCY_CONFLICT",
                    "相同 requestId 提交了不同业务参数：" + row.requestId());
        }
        return new RevisionResult(row.requestId(), facilityCode, row.version(), true, row.appliedAt());
    }

    private static void validate(RevisionCommand command) {
        if (command.expectedVersion() < 1) {
            throw BizException.badRequest("VERSION_INVALID", "expectedVersion 必须不小于 1");
        }
        if (command.categories() != null) {
            for (String category : command.categories()) {
                try {
                    WasteCategory.valueOf(category);
                } catch (IllegalArgumentException | NullPointerException e) {
                    throw BizException.badRequest("CATEGORY_INVALID", "未知垃圾品类：" + category);
                }
            }
        }
        if (command.clearSuspension() && command.suspension() != null) {
            throw BizException.badRequest("SUSPENSION_CONFLICT", "clearSuspension 与 suspension 不能同时提供");
        }
        if (command.suspension() != null) {
            var window = command.suspension();
            if (window.startAt() == null || window.endAt() == null) {
                throw BizException.badRequest("SUSPENSION_INVALID", "停收区间起止时刻均必填（UTC 精确时刻）");
            }
            if (!window.startAt().isBefore(window.endAt())) {
                throw BizException.badRequest("SUSPENSION_INVALID", "停收区间起点必须早于终点：[start, end)");
            }
        }
    }

    private static String fingerprintOf(RevisionCommand command) {
        String categories = command.categories() == null ? "" : String.join(",", command.categories());
        String suspension = command.suspension() == null ? ""
                : command.suspension().startAt() + ".." + command.suspension().endAt();
        return Fingerprints.of(
                command.requestId(),
                command.facilityCode(),
                String.valueOf(command.expectedVersion()),
                command.active() == null ? "" : command.active().toString(),
                categories,
                suspension,
                String.valueOf(command.clearSuspension()));
    }
}
