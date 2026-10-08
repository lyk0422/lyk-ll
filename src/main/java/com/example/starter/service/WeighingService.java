package com.example.starter.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.domain.ManifestStatus;
import com.example.starter.domain.WasteCategory;
import com.example.starter.repo.ManifestRepository;
import com.example.starter.repo.VehicleRepository;
import com.example.starter.support.BizException;
import com.example.starter.support.Fingerprints;

/**
 * 进场称重与收运联单登记。
 * 皮重取车辆最近一次空车称重；超过 7 日未更新时须在同一事务内先复称皮重再登记联单。
 */
@Service
public class WeighingService {

    /** 皮重有效期：超过 7 日未更新须复称。 */
    static final Duration TARE_VALIDITY = Duration.ofDays(7);

    private final VehicleRepository vehicleRepository;
    private final ManifestRepository manifestRepository;
    private final FacilityService facilityService;
    private final Clock clock;

    public WeighingService(VehicleRepository vehicleRepository,
                           ManifestRepository manifestRepository,
                           FacilityService facilityService,
                           Clock clock) {
        this.vehicleRepository = vehicleRepository;
        this.manifestRepository = manifestRepository;
        this.facilityService = facilityService;
        this.clock = clock;
    }

    /**
     * 进场登记请求。tareKg 仅在皮重缺失或超过 7 日须复称时必填。
     */
    public record EntryCommand(
            String requestId,
            String plateNo,
            String community,
            String category,
            BigDecimal grossKg,
            String facilityCode,
            BigDecimal tareKg) {
    }

    /**
     * 进场登记结果。
     *
     * @param reweighed 本次是否在同一事务内复称了皮重
     * @param replayed  是否为幂等重放（同键同参返回首次结果）
     */
    public record EntryResult(
            long manifestId,
            String requestId,
            String plateNo,
            String community,
            String category,
            BigDecimal grossKg,
            BigDecimal tareKg,
            BigDecimal netKg,
            String facilityCode,
            String status,
            boolean reweighed,
            boolean replayed,
            Instant createdAt) {
    }

    @Transactional
    public EntryResult registerEntry(EntryCommand command) {
        validate(command);
        String fingerprint = fingerprintOf(command);

        var existing = manifestRepository.findManifestByRequestId(command.requestId());
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), fingerprint, command.plateNo(), command.facilityCode());
        }

        WasteCategory category = parseCategory(command.category());
        var vehicle = vehicleRepository.findByPlateNo(command.plateNo())
                .orElseThrow(() -> BizException.notFound("VEHICLE_NOT_FOUND", "车辆未登记：" + command.plateNo()));

        Instant now = clock.instant();
        // 首次联单登记时验收：设施有效、品类可收、当前时刻不在停收区间
        var facility = facilityService.checkAcceptance(command.facilityCode(), category.name(), now);

        BigDecimal tareKg;
        boolean reweighed;
        var latestTare = vehicleRepository.findLatestTare(vehicle.id());
        if (latestTare.isPresent()
                && !latestTare.get().weighedAt().isBefore(now.minus(TARE_VALIDITY))) {
            tareKg = latestTare.get().tareKg();
            reweighed = false;
        } else {
            if (command.tareKg() == null) {
                throw BizException.badRequest("TARE_REWEIGH_REQUIRED",
                        "皮重超过 7 日未更新或缺失，进场须先复称皮重");
            }
            if (command.tareKg().compareTo(BigDecimal.ZERO) <= 0) {
                throw BizException.badRequest("TARE_INVALID", "复称皮重必须为正数");
            }
            vehicleRepository.insertTare(vehicle.id(), command.tareKg(), now);
            tareKg = command.tareKg();
            reweighed = true;
        }

        BigDecimal netKg = command.grossKg().subtract(tareKg);
        if (netKg.compareTo(BigDecimal.ZERO) == 0) {
            throw BizException.badRequest("NET_WEIGHT_ZERO", "净重恰等于 0，拒绝登记");
        }
        if (netKg.compareTo(BigDecimal.ZERO) < 0) {
            throw BizException.badRequest("NET_WEIGHT_NEGATIVE", "净重为负，毛重不得小于皮重");
        }

        long manifestId;
        try {
            manifestId = manifestRepository.insertManifest(
                    command.requestId(), fingerprint, vehicle.id(), command.community(),
                    category.name(), command.grossKg(), tareKg, netKg,
                    facility.id(), ManifestStatus.IN_TRANSIT.name(), now);
        } catch (DuplicateKeyException e) {
            // 并发下唯一约束冲突：可能是同 requestId 幂等重试，也可能是同车已有执行中联单
            var duplicated = manifestRepository.findManifestByRequestId(command.requestId());
            if (duplicated.isPresent()) {
                return replayOrConflict(duplicated.get(), fingerprint, command.plateNo(), command.facilityCode());
            }
            throw BizException.conflict("VEHICLE_MANIFEST_IN_FLIGHT",
                    "同一车辆同时刻只允许一条执行中联单：" + command.plateNo());
        }

        // 初始去向版本 1，与联单同事务落库
        manifestRepository.insertDestination(manifestId, 1, facility.id(),
                command.requestId(), fingerprint, now);

        return new EntryResult(manifestId, command.requestId(), command.plateNo(), command.community(),
                category.name(), command.grossKg(), tareKg, netKg, facility.code(),
                ManifestStatus.IN_TRANSIT.name(), reweighed, false, now);
    }

    private EntryResult replayOrConflict(ManifestRepository.ManifestRow row, String fingerprint,
                                         String plateNo, String facilityCode) {
        if (!row.fingerprint().equals(fingerprint)) {
            throw BizException.conflict("IDEMPOTENCY_CONFLICT",
                    "相同 requestId 提交了不同业务参数：" + row.requestId());
        }
        return new EntryResult(row.id(), row.requestId(), plateNo, row.community(), row.category(),
                row.grossKg(), row.tareKg(), row.netKg(), facilityCode, row.status(), false, true, row.createdAt());
    }

    private static void validate(EntryCommand command) {
        if (command.grossKg() == null || command.grossKg().compareTo(BigDecimal.ZERO) <= 0) {
            throw BizException.badRequest("GROSS_INVALID", "毛重必须为正数");
        }
    }

    private static WasteCategory parseCategory(String category) {
        try {
            return WasteCategory.valueOf(category);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw BizException.badRequest("CATEGORY_INVALID", "未知垃圾品类：" + category);
        }
    }

    private static String fingerprintOf(EntryCommand command) {
        return Fingerprints.of(
                command.requestId(),
                command.plateNo(),
                command.community(),
                command.category(),
                command.grossKg() == null ? "" : command.grossKg().stripTrailingZeros().toPlainString(),
                command.facilityCode(),
                command.tareKg() == null ? "" : command.tareKg().stripTrailingZeros().toPlainString());
    }
}
