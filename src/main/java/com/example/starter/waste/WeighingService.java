package com.example.starter.waste;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 进场称重与联单登记服务。
 *
 * <p>规则：净重=毛重-皮重，净重不大于 0 拒绝；去向须命中设施目录；皮重取最近一次空车称重，
 * 超过 7 日未更新时须携带复称皮重，复称与联单登记在同一事务提交；同一车辆同时刻仅一条执行中联单；
 * 写操作按 requestId 幂等，同键同参返回首次结果，同键异参 409。
 */
@Service
public class WeighingService {

    /** 皮重有效期：超过 7 日须复称，恰 7 日仍有效。 */
    static final Duration TARE_VALIDITY = Duration.ofDays(7);

    private final VehicleRepository vehicleRepository;
    private final FacilityRepository facilityRepository;
    private final ManifestRepository manifestRepository;
    private final Clock clock;

    public WeighingService(VehicleRepository vehicleRepository, FacilityRepository facilityRepository,
            ManifestRepository manifestRepository, Clock businessClock) {
        this.vehicleRepository = vehicleRepository;
        this.facilityRepository = facilityRepository;
        this.manifestRepository = manifestRepository;
        this.clock = businessClock;
    }

    /**
     * 进场称重登记联单。
     */
    @Transactional
    public ManifestResponse registerEntry(EntryWeighingRequest request) {
        String fingerprint = fingerprintOf(request);
        var replay = manifestRepository.findByRequestId(request.requestId());
        if (replay.isPresent()) {
            return resolveReplay(replay.get(), fingerprint);
        }

        FacilityRepository.Facility facility = facilityRepository.findActiveByCode(request.facilityCode())
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "FACILITY_NOT_IN_CATALOG",
                        "去向设施不在处理设施目录内: " + request.facilityCode()));

        LocalDateTime now = LocalDateTime.now(clock);
        long vehicleId = vehicleRepository.findVehicleIdByPlate(request.plateNo())
                .orElseGet(() -> vehicleRepository.insertVehicle(request.plateNo(), now));

        BigDecimal tareKg = resolveTare(vehicleId, request, now);
        BigDecimal netKg = request.grossKg().subtract(tareKg);
        if (netKg.signum() <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "NET_WEIGHT_INVALID",
                    "净重必须大于 0，毛重=" + request.grossKg() + "，皮重=" + tareKg);
        }

        var draft = new ManifestRepository.ManifestDraft(
                request.requestId(), fingerprint, vehicleId, request.community(), request.category(),
                facility.id(), request.grossKg(), tareKg, netKg, ManifestStatus.IN_PROGRESS, now);
        try {
            long manifestId = manifestRepository.insert(draft);
            return toResponse(manifestRepository.findById(manifestId).orElseThrow());
        } catch (DuplicateKeyException e) {
            return resolveConcurrentConflict(request.requestId(), fingerprint);
        }
    }

    /**
     * 皮重取值：最近一次空车称重未超过 7 日则沿用；否则须以请求携带的复称皮重在同一事务内先落皮重记录。
     */
    private BigDecimal resolveTare(long vehicleId, EntryWeighingRequest request, LocalDateTime now) {
        var latest = vehicleRepository.findLatestTare(vehicleId);
        boolean fresh = latest.isPresent()
                && !Duration.between(latest.get().weighedAt(), now).minus(TARE_VALIDITY).isPositive();
        if (fresh) {
            return latest.get().tareKg();
        }
        if (request.tareKg() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TARE_REWEIGH_REQUIRED",
                    "皮重超过 7 日未更新，进场须先复称皮重");
        }
        vehicleRepository.insertTare(vehicleId, request.tareKg(), now);
        return request.tareKg();
    }

    /**
     * 唯一约束冲突仲裁：request_id 冲突按幂等回放处理，active_vehicle_key 冲突为一车一单冲突。
     */
    private ManifestResponse resolveConcurrentConflict(String requestId, String fingerprint) {
        var existing = manifestRepository.findByRequestId(requestId);
        if (existing.isPresent()) {
            return resolveReplay(existing.get(), fingerprint);
        }
        throw new ApiException(HttpStatus.CONFLICT, "VEHICLE_BUSY", "同一车辆同时刻只允许一条执行中联单");
    }

    /**
     * 幂等回放：同键同参返回首次结果，同键异参 409。
     */
    private ManifestResponse resolveReplay(Manifest existing, String fingerprint) {
        if (!existing.fingerprint().equals(fingerprint)) {
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
                    "相同 requestId 提交了不同的业务参数");
        }
        return toResponse(existing);
    }

    private ManifestResponse toResponse(Manifest manifest) {
        String plateNo = vehiclePlate(manifest.vehicleId());
        String facilityCode = facilityCode(manifest.facilityId());
        return new ManifestResponse(
                manifest.id(), manifest.requestId(), plateNo, manifest.community(), manifest.category(),
                facilityCode, manifest.grossKg(), manifest.tareKg(), manifest.netKg(),
                manifest.status().name(), manifest.createdAt());
    }

    private String vehiclePlate(long vehicleId) {
        return vehicleRepository.findPlateById(vehicleId);
    }

    private String facilityCode(long facilityId) {
        return facilityRepository.findCodeById(facilityId);
    }

    static String fingerprintOf(EntryWeighingRequest request) {
        return Fingerprints.of(List.of(
                request.requestId(),
                request.plateNo(),
                request.community(),
                request.category(),
                request.facilityCode(),
                request.grossKg().toPlainString(),
                request.tareKg() == null ? "" : request.tareKg().toPlainString()));
    }
}
