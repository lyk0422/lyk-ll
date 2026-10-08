package com.example.starter.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.domain.ManifestStatus;
import com.example.starter.repo.FacilityRepository;
import com.example.starter.repo.ManifestRepository;
import com.example.starter.support.BizException;
import com.example.starter.support.Fingerprints;

/**
 * 在途改投与联单去向查询。
 * 改投仅允许还未取得有效回执的在途联单：生成新去向版本，保留原称重、小区、品类与净重，
 * 不重新称重。改投与当前版本回执并发时由数据库 CAS 保证只成其一。
 */
@Service
public class ManifestService {

    private final ManifestRepository manifestRepository;
    private final FacilityRepository facilityRepository;
    private final Clock clock;

    public ManifestService(ManifestRepository manifestRepository,
                           FacilityRepository facilityRepository,
                           Clock clock) {
        this.manifestRepository = manifestRepository;
        this.facilityRepository = facilityRepository;
        this.clock = clock;
    }

    /**
     * 改投结果。
     *
     * @param replayed 是否为幂等重放（同 requestId 同参返回首次结果）
     */
    public record RerouteResult(
            long manifestId,
            String requestId,
            String facilityCode,
            int destinationVersion,
            boolean replayed,
            Instant createdAt) {
    }

    /**
     * 去向版本链视图。
     *
     * @param requestId 改投幂等请求号；初始去向（版本 1）为 null
     */
    public record DestinationView(
            int version,
            String facilityCode,
            String requestId,
            Instant createdAt) {
    }

    /**
     * 有效回执视图。
     */
    public record ReceiptView(
            String requestId,
            BigDecimal netKg,
            BigDecimal deviationPct,
            String result,
            String facilityCode,
            Integer destinationVersion,
            Instant receivedAt) {
    }

    /**
     * 迟到回执证据视图：仅留存，不闭环、不计入接收量。
     */
    public record LateReceiptView(
            String requestId,
            String facilityCode,
            int destinationVersion,
            BigDecimal netKg,
            Instant receivedAt) {
    }

    /**
     * 联单详情：完整去向链 + 有效回执 + 迟到证据链。
     */
    public record ManifestDetail(
            long manifestId,
            String status,
            String community,
            String category,
            BigDecimal netKg,
            int destinationVersion,
            String currentFacilityCode,
            List<DestinationView> destinations,
            List<ReceiptView> receipts,
            List<LateReceiptView> lateReceipts) {
    }

    /**
     * 在途改投：校验在途、版本、新设施目录内且非当前设施、品类可收、当前不在停收区间，
     * 然后同事务写入新去向版本并推进联单当前去向。
     */
    @Transactional
    public RerouteResult reroute(long manifestId, String requestId, String facilityCode, int expectedVersion) {
        String fingerprint = Fingerprints.of(
                requestId, String.valueOf(manifestId), facilityCode, String.valueOf(expectedVersion));

        var existing = manifestRepository.findDestinationByRequestId(requestId);
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), fingerprint);
        }

        var manifest = manifestRepository.findManifestById(manifestId)
                .orElseThrow(() -> BizException.notFound("MANIFEST_NOT_FOUND", "联单不存在：" + manifestId));
        if (!ManifestStatus.IN_TRANSIT.name().equals(manifest.status())) {
            throw BizException.conflict("MANIFEST_NOT_IN_TRANSIT",
                    "联单已取得有效回执或已闭环，不允许改投：" + manifest.status());
        }
        if (manifest.destinationVersion() != expectedVersion) {
            throw BizException.conflict("VERSION_CONFLICT",
                    "去向版本已变化：期望 " + expectedVersion + "，当前 " + manifest.destinationVersion());
        }
        var facility = facilityRepository.findByCode(facilityCode)
                .filter(f -> f.active())
                .orElseThrow(() -> BizException.badRequest("FACILITY_NOT_IN_CATALOG",
                        "改投设施不在处理设施目录内：" + facilityCode));
        if (facility.id() == manifest.facilityId()) {
            throw BizException.badRequest("REROUTE_SAME_FACILITY",
                    "改投设施不能等于当前去向设施：" + facilityCode);
        }
        if (!facilityRepository.acceptsCategory(facility.id(), manifest.category())) {
            throw BizException.badRequest("FACILITY_CATEGORY_REJECTED",
                    "设施不接收该品类：" + facilityCode + " / " + manifest.category());
        }
        Instant now = clock.instant();
        if (facilityRepository.isSuspendedAt(facility.id(), now)) {
            throw BizException.badRequest("FACILITY_SUSPENDED",
                    "设施当前处于停收区间：" + facilityCode);
        }

        int newVersion = expectedVersion + 1;
        try {
            manifestRepository.insertDestination(manifestId, newVersion, facility.id(),
                    requestId, fingerprint, now);
        } catch (DuplicateKeyException e) {
            // 并发下同 requestId 幂等重试，或同联单同版本的并发改投
            var duplicated = manifestRepository.findDestinationByRequestId(requestId);
            if (duplicated.isPresent()) {
                return replayOrConflict(duplicated.get(), fingerprint);
            }
            throw BizException.conflict("VERSION_CONFLICT",
                    "去向版本已被并发改投占用：" + manifestId);
        }
        // CAS 推进当前去向：并发回执先闭环则命中 0 行，本次改投整体回滚
        int updated = manifestRepository.rerouteGuarded(manifestId, expectedVersion, facility.id());
        if (updated != 1) {
            throw BizException.conflict("VERSION_CONFLICT",
                    "联单状态或去向版本已变化，改投失败：" + manifestId);
        }

        return new RerouteResult(manifestId, requestId, facility.code(), newVersion, false, now);
    }

    /**
     * 联单详情查询：完整去向链与迟到证据链。
     */
    @Transactional(readOnly = true)
    public ManifestDetail detail(long manifestId) {
        var manifest = manifestRepository.findManifestById(manifestId)
                .orElseThrow(() -> BizException.notFound("MANIFEST_NOT_FOUND", "联单不存在：" + manifestId));
        String currentFacilityCode = facilityRepository.findById(manifest.facilityId())
                .map(FacilityRepository.FacilityRow::code)
                .orElse(null);

        List<DestinationView> destinations = manifestRepository.listDestinations(manifestId).stream()
                .map(d -> new DestinationView(d.version(), facilityCodeOf(d.facilityId()),
                        d.requestId(), d.createdAt()))
                .toList();
        List<ReceiptView> receipts = manifestRepository.listReceipts(manifestId).stream()
                .map(r -> new ReceiptView(r.requestId(), r.netKg(), r.deviationPct(), r.result(),
                        r.facilityId() == null ? null : facilityCodeOf(r.facilityId()),
                        r.destinationVersion(), r.receivedAt()))
                .toList();
        List<LateReceiptView> lateReceipts = manifestRepository.listLateReceipts(manifestId).stream()
                .map(l -> new LateReceiptView(l.requestId(), facilityCodeOf(l.facilityId()),
                        l.destinationVersion(), l.netKg(), l.receivedAt()))
                .toList();

        return new ManifestDetail(manifest.id(), manifest.status(), manifest.community(), manifest.category(),
                manifest.netKg(), manifest.destinationVersion(), currentFacilityCode,
                destinations, receipts, lateReceipts);
    }

    private RerouteResult replayOrConflict(ManifestRepository.DestinationRow row, String fingerprint) {
        if (!fingerprint.equals(row.fingerprint())) {
            throw BizException.conflict("IDEMPOTENCY_CONFLICT",
                    "相同 requestId 提交了不同业务参数：" + row.requestId());
        }
        String facilityCode = facilityCodeOf(row.facilityId());
        return new RerouteResult(row.manifestId(), row.requestId(), facilityCode,
                row.version(), true, row.createdAt());
    }

    private String facilityCodeOf(long facilityId) {
        return facilityRepository.findById(facilityId)
                .map(FacilityRepository.FacilityRow::code)
                .orElse(null);
    }
}
