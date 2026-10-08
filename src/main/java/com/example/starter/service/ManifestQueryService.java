package com.example.starter.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.repo.FacilityRepository;
import com.example.starter.repo.ManifestRepository;
import com.example.starter.support.BizException;

/**
 * 联单查询：展示完整去向版本链与迟到回执证据链。
 */
@Service
public class ManifestQueryService {

    private final ManifestRepository manifestRepository;
    private final FacilityRepository facilityRepository;

    public ManifestQueryService(ManifestRepository manifestRepository,
                                FacilityRepository facilityRepository) {
        this.manifestRepository = manifestRepository;
        this.facilityRepository = facilityRepository;
    }

    /**
     * 去向版本视图。
     */
    public record DestinationView(
            int version,
            String facilityCode,
            String requestId,
            Instant createdAt) {
    }

    /**
     * 迟到回执证据视图：仅存证据，未入账。
     */
    public record LateReceiptView(
            String requestId,
            String facilityCode,
            int destinationVersion,
            BigDecimal netKg,
            Instant receivedAt) {
    }

    /**
     * 联单明细视图：基本字段 + 完整去向链 + 迟到证据链。
     */
    public record ManifestDetail(
            long manifestId,
            String community,
            String category,
            BigDecimal netKg,
            String status,
            String currentFacilityCode,
            int destinationVersion,
            Instant createdAt,
            List<DestinationView> destinations,
            List<LateReceiptView> lateReceipts) {
    }

    @Transactional(readOnly = true)
    public ManifestDetail detail(long manifestId) {
        var manifest = manifestRepository.findManifestById(manifestId)
                .orElseThrow(() -> BizException.notFound("MANIFEST_NOT_FOUND", "联单不存在：" + manifestId));
        var currentFacility = facilityRepository.findById(manifest.facilityId())
                .orElseThrow(() -> new IllegalStateException("联单去向设施缺失：" + manifest.facilityId()));

        var destinations = manifestRepository.findDestinations(manifestId).stream()
                .map(d -> new DestinationView(d.version(), facilityCodeOf(d.facilityId()), d.requestId(), d.createdAt()))
                .toList();
        var lateReceipts = manifestRepository.findLateReceipts(manifestId).stream()
                .map(l -> new LateReceiptView(l.requestId(), l.facilityCode(), l.destinationVersion(),
                        l.netKg(), l.receivedAt()))
                .toList();

        return new ManifestDetail(manifest.id(), manifest.community(), manifest.category(), manifest.netKg(),
                manifest.status(), currentFacility.code(), manifest.destinationVersion(), manifest.createdAt(),
                destinations, lateReceipts);
    }

    private String facilityCodeOf(long facilityId) {
        return facilityRepository.findById(facilityId)
                .orElseThrow(() -> new IllegalStateException("去向设施缺失：" + facilityId))
                .code();
    }
}
