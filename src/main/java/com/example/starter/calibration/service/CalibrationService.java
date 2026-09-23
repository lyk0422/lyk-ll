package com.example.starter.calibration.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.calibration.domain.CalibrationStatus;
import com.example.starter.calibration.domain.CalibrationVersion;
import com.example.starter.calibration.domain.CanaryRelease;
import com.example.starter.calibration.domain.CanaryVehicle;
import com.example.starter.calibration.domain.RangeMatcher;
import com.example.starter.calibration.domain.ReleaseStatus;
import com.example.starter.calibration.domain.ResolutionRecord;
import com.example.starter.calibration.domain.SensorType;
import com.example.starter.calibration.domain.VehicleScope;
import com.example.starter.calibration.domain.CalibrationContentValidator;
import com.example.starter.calibration.domain.payload.CalibrationPayload;
import com.example.starter.calibration.repository.CalibrationVersionRepository;
import com.example.starter.calibration.repository.CanaryReleaseRepository;
import com.example.starter.calibration.repository.ResolutionRecordRepository;
import com.example.starter.calibration.support.TimeProvider;
import com.example.starter.calibration.web.ApiException;
import com.example.starter.calibration.web.dto.CanaryReleaseRequest;
import com.example.starter.calibration.web.dto.CreateVersionRequest;
import com.example.starter.calibration.web.dto.ResolveRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 标定参数核心领域服务：创建校验、审批、全量生效、范围解析、原子灰度、回滚与历史追踪。
 */
@Service
public class CalibrationService {

    private final CalibrationVersionRepository versions;
    private final CanaryReleaseRepository releases;
    private final ResolutionRecordRepository resolutions;
    private final CalibrationContentValidator contentValidator;
    private final TimeProvider timeProvider;
    private final ObjectMapper objectMapper;

    public CalibrationService(CalibrationVersionRepository versions,
            CanaryReleaseRepository releases,
            ResolutionRecordRepository resolutions,
            CalibrationContentValidator contentValidator,
            TimeProvider timeProvider,
            ObjectMapper objectMapper) {
        this.versions = versions;
        this.releases = releases;
        this.resolutions = resolutions;
        this.contentValidator = contentValidator;
        this.timeProvider = timeProvider;
        this.objectMapper = objectMapper;
    }

    /**
     * 创建草稿版本：校验内容与范围，失败返回 422，不会产生可审批版本。
     */
    public CalibrationVersion createVersion(CreateVersionRequest request) {
        CalibrationPayload payload = request.payload();
        // 内容校验：数值有限、焦距为正、旋转矩阵正交
        contentValidator.validate(request.sensorType(), payload);
        validateRanges(request);

        String payloadJson = serializePayload(payload);
        VehicleScope scope = resolveVehicleScope(request.allVehicles(), request.vehicles());

        CalibrationVersion version = new CalibrationVersion();
        version.setCalibrationKey(request.key());
        version.setSensorType(request.sensorType());
        version.setVersion(request.version());
        version.setContentDigest(sha256(payloadJson));
        version.setPayload(payloadJson);
        version.setStatus(CalibrationStatus.DRAFT);
        version.setEffectiveFrom(request.effectiveFrom().toInstant());
        version.setEffectiveTo(request.effectiveTo() == null ? null : request.effectiveTo().toInstant());
        version.setVehicleScope(scope);
        version.setSensorId(request.sensorId());
        version.setMapVersionFrom(request.mapVersionFrom());
        version.setMapVersionTo(request.mapVersionTo());
        version.setCreatedBy(request.operator());
        version.setCreatedAt(timeProvider.now());
        try {
            versions.insert(version);
        } catch (DuplicateKeyException ex) {
            throw new ApiException.Conflict(
                    "标定键 " + request.key() + " 下版本 " + request.version() + " 已存在");
        }
        return version;
    }

    /**
     * 审批草稿：再次校验内容，并检查与已生效版本的范围冲突；通过后变为 APPROVED。
     */
    @Transactional
    public CalibrationVersion approve(String key, String versionName, String operator) {
        CalibrationVersion version = requireVersion(key, versionName);
        if (version.getStatus() != CalibrationStatus.DRAFT) {
            throw new ApiException.Unprocessable(
                    "仅草稿状态可审批，当前状态: " + version.getStatus());
        }
        CalibrationPayload payload = deserializePayload(version);
        contentValidator.validate(version.getSensorType(), payload);
        // 审批通过不直接生效：之后可灰度（车辆显式分区）或全量生效（那时再检查范围冲突）
        versions.markApproved(version.getId(), operator, timeProvider.now());
        return requireVersion(key, versionName);
    }

    /**
     * 全量生效：已批准版本变为 EFFECTIVE；与其它已生效版本范围重叠时 409。
     */
    @Transactional
    public CalibrationVersion activate(String key, String versionName, String operator) {
        CalibrationVersion version = requireVersion(key, versionName);
        if (version.getStatus() != CalibrationStatus.APPROVED) {
            throw new ApiException.Unprocessable(
                    "仅已批准状态可全量生效，当前状态: " + version.getStatus());
        }
        ensureNoEffectiveConflict(version);
        versions.markEffective(version.getId(), operator, timeProvider.now());
        return requireVersion(key, versionName);
    }

    /**
     * 灰度发布：显式车辆集合批量切换，整批原子提交。
     * 任一车辆已处于其它活跃灰度、或车辆不在新版本声明范围内，全部回滚。
     */
    @Transactional
    public CanaryReleaseContext canaryRelease(CanaryReleaseRequest request) {
        CalibrationVersion newVersion = requireVersion(request.key(), request.version());
        if (newVersion.getStatus() != CalibrationStatus.APPROVED) {
            throw new ApiException.Unprocessable(
                    "仅已批准版本可发起灰度，当前状态: " + newVersion.getStatus());
        }
        List<String> vehicles = request.vehicles().stream().distinct().sorted().toList();
        for (String vehicleId : vehicles) {
            if (!newVersion.getVehicleScope().contains(vehicleId)) {
                throw new ApiException.Unprocessable(
                        "车辆 " + vehicleId + " 不在版本声明的车辆范围内，灰度验证失败");
            }
        }

        Instant now = timeProvider.now();
        CanaryRelease release = new CanaryRelease();
        release.setCalibrationKey(request.key());
        release.setNewVersionId(newVersion.getId());
        release.setStatus(ReleaseStatus.ROLLED_OUT);
        release.setOperator(request.operator());
        release.setCreatedAt(now);
        releases.insertRelease(release);

        // 逐车辆记录发布前版本并占用活跃槽位；槽位主键冲突说明该车已在其它活跃灰度中
        for (String vehicleId : vehicles) {
            CalibrationVersion previous = findBaselineVersion(
                    request.key(), newVersion.getSensorId(), vehicleId, now);
            CanaryVehicle vehicle = new CanaryVehicle();
            vehicle.setReleaseId(release.getId());
            vehicle.setVehicleId(vehicleId);
            vehicle.setPreviousVersionId(previous == null ? null : previous.getId());
            releases.insertVehicle(vehicle);
            try {
                releases.insertActiveVehicle(request.key(), vehicleId, release.getId());
            } catch (DataIntegrityViolationException | ConcurrencyFailureException ex) {
                // 整批回滚：异常向外抛出，事务标记回滚
                throw new ApiException.Conflict(
                        "车辆 " + vehicleId + " 已处于该标定键的另一个活跃灰度中，本次批量切换全部回滚");
            }
        }

        versions.markInCanary(newVersion.getId(), release.getId());
        List<CanaryVehicle> savedVehicles = releases.findVehiclesByReleaseId(release.getId());
        return new CanaryReleaseContext(release, newVersion, savedVehicles);
    }

    /**
     * 回滚灰度：仅影响该发布单车辆，删除活跃占用并把灰度版本置为 ROLLED_BACK，
     * 各车在后续解析时自然恢复到发布前的已生效版本。
     */
    @Transactional
    public CanaryRelease rollback(long releaseId, String operator, String reason) {
        CanaryRelease release = releases.findReleaseById(releaseId);
        if (release == null) {
            throw new ApiException.NotFound("灰度发布单不存在: " + releaseId);
        }
        if (release.getStatus() != ReleaseStatus.ROLLED_OUT) {
            throw new ApiException.Conflict("发布单当前状态为 " + release.getStatus() + "，无法回滚");
        }
        CalibrationVersion canaryVersion = versions.findById(release.getNewVersionId());
        releases.deleteActiveVehiclesByReleaseId(releaseId);
        versions.markRolledBack(canaryVersion.getId(), operator, timeProvider.now());
        releases.markRolledBack(releaseId, operator, timeProvider.now(), reason);
        return releases.findReleaseById(releaseId);
    }

    /**
     * 按车辆、传感器、地图版本和业务时间解析参数，记录不可变历史并返回 resolutionId。
     */
    @Transactional
    public ResolutionRecord resolve(ResolveRequest request) {
        Instant time = request.time() == null ? timeProvider.now() : request.time().toInstant();
        Selection selection = selectVersion(
                request.key(), request.sensorId(), request.vehicleId(), request.mapVersion(), time);
        if (selection == null) {
            throw new ApiException.NotFound(String.format(
                    "无匹配标定版本: key=%s, sensor=%s, vehicle=%s, map=%s, time=%s",
                    request.key(), request.sensorId(), request.vehicleId(),
                    request.mapVersion(), time));
        }
        CalibrationVersion selected = selection.version();

        ResolutionRecord record = new ResolutionRecord();
        record.setResolutionId(UUID.randomUUID().toString());
        record.setCalibrationKey(request.key());
        record.setSensorId(request.sensorId());
        record.setVehicleId(request.vehicleId());
        record.setMapVersion(request.mapVersion());
        record.setResolvedTime(time);
        record.setResolvedAt(timeProvider.now());
        record.setSelectedVersionId(selected.getId());
        record.setSelectedVersion(selected.getVersion());
        record.setContentDigest(selected.getContentDigest());
        // 快照内容：即使原版本后续被回滚或新版本发布，历史引用保持不变
        record.setPayload(selected.getPayload());
        record.setCanaryReleaseId(selection.viaCanaryReleaseId());
        resolutions.insert(record);
        return record;
    }

    public ResolutionRecord getResolution(String resolutionId) {
        ResolutionRecord record = resolutions.findByResolutionId(resolutionId);
        if (record == null) {
            throw new ApiException.NotFound("解析记录不存在: " + resolutionId);
        }
        return record;
    }

    public CalibrationVersion getVersion(String key, String versionName) {
        return requireVersion(key, versionName);
    }

    public CalibrationVersion getVersionById(long versionId) {
        CalibrationVersion version = versions.findById(versionId);
        if (version == null) {
            throw new ApiException.NotFound("标定版本不存在: id=" + versionId);
        }
        return version;
    }

    public CanaryRelease getRelease(long releaseId) {
        CanaryRelease release = releases.findReleaseById(releaseId);
        if (release == null) {
            throw new ApiException.NotFound("灰度发布单不存在: " + releaseId);
        }
        return release;
    }

    public List<CanaryVehicle> getReleaseVehicles(long releaseId) {
        getRelease(releaseId);
        return releases.findVehiclesByReleaseId(releaseId);
    }

    // ------------------------------------------------------------------
    // 内部逻辑
    // ------------------------------------------------------------------

    private record Selection(CalibrationVersion version, Long viaCanaryReleaseId) {
    }

    /** 一次灰度发布的上下文结果，供控制器组装响应。 */
    public record CanaryReleaseContext(CanaryRelease release, CalibrationVersion newVersion,
            List<CanaryVehicle> vehicles) {
    }

    /**
     * 解析选择：优先车辆所属活跃灰度的新版本，否则回退到全量已生效版本。
     */
    private Selection selectVersion(String key, String sensorId, String vehicleId,
            Integer mapVersion, Instant time) {
        Long activeReleaseId = releases.findActiveReleaseId(key, vehicleId);
        if (activeReleaseId != null) {
            CanaryRelease activeRelease = releases.findReleaseById(activeReleaseId);
            CalibrationVersion canaryVersion = versions.findById(activeRelease.getNewVersionId());
            if (canaryVersion.getStatus() == CalibrationStatus.IN_CANARY
                    && canaryVersion.getSensorId().equals(sensorId)
                    && RangeMatcher.mapMatches(canaryVersion, mapVersion)
                    && RangeMatcher.timeMatches(canaryVersion, time)) {
                return new Selection(canaryVersion, activeReleaseId);
            }
        }
        CalibrationVersion baseline = selectEffectiveVersion(key, sensorId, vehicleId, mapVersion, time);
        return baseline == null ? null : new Selection(baseline, null);
    }

    /**
     * 在 EFFECTIVE 版本中选择覆盖车辆/时间的最新基线版本（不按地图版本过滤），
     * 用于灰度发布时记录每辆车发布前命中的版本。
     */
    private CalibrationVersion findBaselineVersion(String key, String sensorId, String vehicleId,
            Instant time) {
        List<CalibrationVersion> candidates = versions.findResolutionCandidates(
                key, sensorId, null, time);
        CalibrationVersion selected = null;
        for (CalibrationVersion candidate : candidates) {
            if (candidate.getStatus() != CalibrationStatus.EFFECTIVE) {
                continue;
            }
            if (candidate.getVehicleScope().contains(vehicleId) && RangeMatcher.timeMatches(candidate, time)) {
                if (selected == null || candidate.getId() > selected.getId()) {
                    selected = candidate;
                }
            }
        }
        return selected;
    }

    /**
     * 在 EFFECTIVE 版本中选择覆盖车辆/地图/时间的最新版本。
     */
    private CalibrationVersion selectEffectiveVersion(String key, String sensorId, String vehicleId,
            Integer mapVersion, Instant time) {
        List<CalibrationVersion> candidates = versions.findResolutionCandidates(key, sensorId,
                mapVersion, time);
        CalibrationVersion selected = null;
        for (CalibrationVersion candidate : candidates) {
            if (candidate.getStatus() != CalibrationStatus.EFFECTIVE) {
                continue;
            }
            if (RangeMatcher.covers(candidate, vehicleId, mapVersion, time)) {
                if (selected == null || candidate.getId() > selected.getId()) {
                    selected = candidate;
                }
            }
        }
        return selected;
    }

    /**
     * 与所有已生效版本做三维范围重叠检查；车辆/地图/时间同时重叠即构成生效范围冲突。
     */
    private void ensureNoEffectiveConflict(CalibrationVersion candidate) {
        List<CalibrationVersion> live = versions.findOverlappingCandidates(
                candidate.getCalibrationKey(), candidate.getSensorId(),
                List.of(CalibrationStatus.EFFECTIVE));
        for (CalibrationVersion existing : live) {
            if (RangeMatcher.scopesOverlap(candidate, existing)) {
                throw new ApiException.Conflict(String.format(
                        "生效范围冲突：版本 %s 与已生效版本 %s 在车辆、地图版本和时间范围上存在重叠",
                        candidate.getVersion(), existing.getVersion()));
            }
        }
    }

    private CalibrationVersion requireVersion(String key, String versionName) {
        CalibrationVersion version = versions.findByKeyAndVersion(key, versionName);
        if (version == null) {
            throw new ApiException.NotFound("标定版本不存在: " + key + "@" + versionName);
        }
        return version;
    }

    private void validateRanges(CreateVersionRequest request) {
        if (request.effectiveTo() != null
                && !request.effectiveTo().isAfter(request.effectiveFrom())) {
            throw new ApiException.Unprocessable("生效结束时间必须晚于起始时间（区间左闭右开）");
        }
        if (request.mapVersionFrom() != null && request.mapVersionTo() != null
                && request.mapVersionFrom() > request.mapVersionTo()) {
            throw new ApiException.Unprocessable("地图版本范围下界不能大于上界");
        }
        resolveVehicleScope(request.allVehicles(), request.vehicles());
    }

    private VehicleScope resolveVehicleScope(Boolean allVehicles, List<String> vehicles) {
        boolean all = allVehicles == null || allVehicles;
        if (all) {
            return new VehicleScope.All();
        }
        if (vehicles == null || vehicles.isEmpty()) {
            throw new ApiException.Unprocessable("allVehicles=false 时必须显式提供车辆集合");
        }
        if (vehicles.stream().anyMatch(String::isBlank)) {
            throw new ApiException.Unprocessable("车辆标识不能为空白");
        }
        return new VehicleScope.Vehicles(vehicles);
    }

    private String serializePayload(CalibrationPayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new ApiException.Unprocessable("标定内容无法序列化为 JSON: " + ex.getOriginalMessage());
        }
    }

    private CalibrationPayload deserializePayload(CalibrationVersion version) {
        try {
            return objectMapper.readValue(version.getPayload(), CalibrationPayload.class);
        } catch (JsonProcessingException ex) {
            throw new ApiException.Unprocessable("标定内容 JSON 损坏: " + ex.getOriginalMessage());
        }
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }
}
