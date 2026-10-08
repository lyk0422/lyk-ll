package com.example.starter.service;

import java.time.Clock;
import java.time.Instant;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.domain.ManifestStatus;
import com.example.starter.repo.FacilityRepository;
import com.example.starter.repo.ManifestRepository;
import com.example.starter.support.BizException;
import com.example.starter.support.Fingerprints;

/**
 * 在途改投：仅允许还未取得有效回执的在途联单；保留原称重、小区、品类与净重，
 * 生成新的去向版本（当前版本 +1），不重新称重。与当前版本回执并发时只能先成一个。
 */
@Service
public class RerouteService {

    private final ManifestRepository manifestRepository;
    private final FacilityRepository facilityRepository;
    private final FacilityService facilityService;
    private final Clock clock;

    public RerouteService(ManifestRepository manifestRepository,
                          FacilityRepository facilityRepository,
                          FacilityService facilityService,
                          Clock clock) {
        this.manifestRepository = manifestRepository;
        this.facilityRepository = facilityRepository;
        this.facilityService = facilityService;
        this.clock = clock;
    }

    /**
     * 改投请求。expectedVersion 为调用方看到的当前去向版本，过期返回 409。
     */
    public record RerouteCommand(
            String requestId,
            long manifestId,
            String newFacilityCode,
            int expectedVersion) {
    }

    /**
     * 改投结果。
     *
     * @param replayed 是否为幂等重放（同键同参返回首次结果）
     */
    public record RerouteResult(
            String requestId,
            long manifestId,
            String facilityCode,
            int destinationVersion,
            boolean replayed,
            Instant reroutedAt) {
    }

    @Transactional
    public RerouteResult reroute(RerouteCommand command) {
        if (command.expectedVersion() < 1) {
            throw BizException.badRequest("VERSION_INVALID", "expectedVersion 必须不小于 1");
        }
        String fingerprint = fingerprintOf(command);

        var existing = manifestRepository.findDestinationByRequestId(command.requestId());
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), fingerprint);
        }

        var manifest = manifestRepository.findManifestById(command.manifestId())
                .orElseThrow(() -> BizException.notFound("MANIFEST_NOT_FOUND", "联单不存在：" + command.manifestId()));
        if (!ManifestStatus.IN_TRANSIT.name().equals(manifest.status())) {
            throw BizException.conflict("MANIFEST_NOT_IN_TRANSIT",
                    "联单已取得有效回执或已闭环，不允许改投：" + manifest.status());
        }
        if (manifest.destinationVersion() != command.expectedVersion()) {
            throw BizException.conflict("DESTINATION_VERSION_STALE",
                    "去向版本已变化，期望 " + command.expectedVersion()
                            + "，当前 " + manifest.destinationVersion());
        }

        var currentFacility = facilityRepository.findById(manifest.facilityId())
                .orElseThrow(() -> new IllegalStateException("联单去向设施缺失：" + manifest.facilityId()));
        if (currentFacility.code().equals(command.newFacilityCode())) {
            throw BizException.badRequest("REROUTE_SAME_FACILITY",
                    "新设施不能等于当前设施：" + command.newFacilityCode());
        }

        Instant now = clock.instant();
        // 改投时重查：新设施有效、品类可收、当前时刻不在停收区间
        var newFacility = facilityService.checkAcceptance(
                command.newFacilityCode(), manifest.category(), now);

        int newVersion = command.expectedVersion() + 1;
        try {
            int updated = manifestRepository.rerouteManifest(
                    command.manifestId(), command.expectedVersion(), newFacility.id());
            if (updated != 1) {
                throw BizException.conflict("DESTINATION_VERSION_STALE",
                        "联单状态或去向版本已变化，改投失败：" + command.manifestId());
            }
            manifestRepository.insertDestination(command.manifestId(), newVersion, newFacility.id(),
                    command.requestId(), fingerprint, now);
        } catch (DuplicateKeyException e) {
            var duplicated = manifestRepository.findDestinationByRequestId(command.requestId());
            if (duplicated.isPresent()) {
                return replayOrConflict(duplicated.get(), fingerprint);
            }
            throw e;
        }

        return new RerouteResult(command.requestId(), command.manifestId(),
                newFacility.code(), newVersion, false, now);
    }

    private RerouteResult replayOrConflict(ManifestRepository.DestinationRow row, String fingerprint) {
        if (!row.fingerprint().equals(fingerprint)) {
            throw BizException.conflict("IDEMPOTENCY_CONFLICT",
                    "相同 requestId 提交了不同业务参数：" + row.requestId());
        }
        var facility = facilityRepository.findById(row.facilityId())
                .orElseThrow(() -> new IllegalStateException("去向设施缺失：" + row.facilityId()));
        return new RerouteResult(row.requestId(), row.manifestId(), facility.code(),
                row.version(), true, row.createdAt());
    }

    private static String fingerprintOf(RerouteCommand command) {
        return Fingerprints.of(
                command.requestId(),
                String.valueOf(command.manifestId()),
                command.newFacilityCode(),
                String.valueOf(command.expectedVersion()));
    }
}
