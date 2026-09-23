package com.example.starter.calibration.service;

import com.example.starter.calibration.ConflictException;
import com.example.starter.calibration.NotFoundException;
import com.example.starter.calibration.ValidationException;
import com.example.starter.calibration.api.ApproveRequest;
import com.example.starter.calibration.api.CalibrationResponse;
import com.example.starter.calibration.api.CanaryReleaseRequest;
import com.example.starter.calibration.api.CanaryReleaseResponse;
import com.example.starter.calibration.api.CreateCalibrationRequest;
import com.example.starter.calibration.api.OperatorRequest;
import com.example.starter.calibration.api.ResolutionHistoryResponse;
import com.example.starter.calibration.api.ResolutionResponse;
import com.example.starter.calibration.api.ResolveRequest;
import com.example.starter.calibration.api.TransitionResponse;
import com.example.starter.calibration.domain.CalibrationStatus;
import com.example.starter.calibration.domain.CalibrationVersion;
import com.example.starter.calibration.domain.CanaryRelease;
import com.example.starter.calibration.domain.CanaryStatus;
import com.example.starter.calibration.domain.CanaryVehicle;
import com.example.starter.calibration.domain.ResolutionRecord;
import com.example.starter.calibration.domain.StatusTransition;
import com.example.starter.calibration.repository.CalibrationJdbc;
import com.example.starter.calibration.repository.CanaryReleaseJdbc;
import com.example.starter.calibration.repository.CanaryVehicleJdbc;
import com.example.starter.calibration.repository.ResolutionJdbc;
import com.example.starter.calibration.repository.TransitionJdbc;
import com.example.starter.calibration.support.TimeService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * 标定参数应用服务：创建/校验/审批、范围解析、原子灰度、转正/回滚、历史追踪。
 *
 * <p>解析规则：在某时刻按车辆/传感器/地图版本范围匹配；若车辆属于某进行中灰度批次且灰度
 * 版本范围匹配，则命中灰度版本，否则命中基线（ACTIVE）中最新版本。灰度批次的车辆占用锁
 * 与批次写入处于同一事务，任一车辆冲突或校验失败即整批回滚。</p>
 */
@Service
public class CalibrationService {

    private final CalibrationJdbc calibrationJdbc;
    private final CanaryReleaseJdbc canaryReleaseJdbc;
    private final CanaryVehicleJdbc canaryVehicleJdbc;
    private final ResolutionJdbc resolutionJdbc;
    private final TransitionJdbc transitionJdbc;
    private final CalibrationValidator validator;
    private final TimeService timeService;
    private final ObjectMapper objectMapper;

    public CalibrationService(CalibrationJdbc calibrationJdbc,
                              CanaryReleaseJdbc canaryReleaseJdbc,
                              CanaryVehicleJdbc canaryVehicleJdbc,
                              ResolutionJdbc resolutionJdbc,
                              TransitionJdbc transitionJdbc,
                              CalibrationValidator validator,
                              TimeService timeService,
                              ObjectMapper objectMapper) {
        this.calibrationJdbc = calibrationJdbc;
        this.canaryReleaseJdbc = canaryReleaseJdbc;
        this.canaryVehicleJdbc = canaryVehicleJdbc;
        this.resolutionJdbc = resolutionJdbc;
        this.transitionJdbc = transitionJdbc;
        this.validator = validator;
        this.timeService = timeService;
        this.objectMapper = objectMapper;
    }

    /**
     * 创建草稿版本。内容/范围校验失败返回 422，不会创建任何数据；同键同版本号冲突返回 409。
     */
    @Transactional
    public CalibrationResponse create(CreateCalibrationRequest request) {
        validator.validate(request);
        if (calibrationJdbc.findByKeyAndVersion(request.paramKey(), request.version()).isPresent()) {
            throw new ConflictException("参数键 " + request.paramKey() + " 已存在版本 " + request.version());
        }

        Instant now = timeService.now();
        CalibrationVersion v = new CalibrationVersion();
        v.setParamKey(request.paramKey());
        v.setSensorType(request.sensorType());
        v.setKind(request.kind());
        v.setVersion(request.version());
        String json = writeContent(request.content());
        v.setContentJson(json);
        v.setContentSha256(sha256(json));
        v.setStatus(CalibrationStatus.DRAFT);
        v.setEffectiveFrom(request.effectiveFrom());
        v.setEffectiveTo(request.effectiveTo());
        v.setVehicleFrom(request.vehicleFrom());
        v.setVehicleTo(request.vehicleTo());
        v.setSensorFrom(request.sensorFrom());
        v.setSensorTo(request.sensorTo());
        v.setMapFrom(request.mapFrom());
        v.setMapTo(request.mapTo());
        v.setCreatedBy(request.operator());
        v.setCreatedAt(now);
        try {
            calibrationJdbc.insert(v);
        } catch (DuplicateKeyException e) {
            throw new ConflictException("参数键 " + request.paramKey() + " 已存在版本 " + request.version());
        }
        recordTransition(v.getId(), null, CalibrationStatus.DRAFT, request.operator(), "创建草稿");
        return toResponse(v, request.content());
    }

    /** 审批：草稿 → 已批准；审批时重新做内容校验，校验不过的版本不能成为可审批版本。 */
    @Transactional
    public CalibrationResponse approve(long id, ApproveRequest request) {
        CalibrationVersion v = requireVersion(id);
        if (v.getStatus() != CalibrationStatus.DRAFT) {
            throw new ConflictException("仅草稿版本可审批，当前状态 " + v.getStatus());
        }
        requireOperator(request.operator());
        // 重新执行内容校验：校验不过的版本永远不能成为可审批版本。
        // 相同生效范围的新版本允许通过审批，以便对部分车辆灰度；范围冲突在全量生效/转正时拦截。
        validator.validate(toCheckRequest(v));

        v.setStatus(CalibrationStatus.APPROVED);
        v.setApprovedBy(request.operator());
        v.setApprovedAt(timeService.now());
        calibrationJdbc.updateStatus(v);
        recordTransition(v.getId(), CalibrationStatus.DRAFT, CalibrationStatus.APPROVED,
                request.operator(), "审批通过");
        return toResponse(v, readContent(v));
    }

    /** 全量生效：已批准 → 已生效（成为基线）。 */
    @Transactional
    public CalibrationResponse activate(long id, OperatorRequest request) {
        CalibrationVersion v = requireVersion(id);
        if (v.getStatus() != CalibrationStatus.APPROVED) {
            throw new ConflictException("仅已批准版本可全量生效，当前状态 " + v.getStatus());
        }
        requireOperator(request.operator());
        ensureNoIdenticalScope(v);

        v.setStatus(CalibrationStatus.ACTIVE);
        v.setActivatedBy(request.operator());
        v.setActivatedAt(timeService.now());
        calibrationJdbc.updateStatus(v);
        recordTransition(v.getId(), CalibrationStatus.APPROVED, CalibrationStatus.ACTIVE,
                request.operator(), "全量生效");
        return toResponse(v, readContent(v));
    }

    /**
     * 发起灰度：显式车辆集合批量原子切换。任一车辆不在新版本适用范围、已在其他进行中灰度中，
     * 或车辆列表非法时，整批回滚（事务保证不残留批次、占用锁或状态变更）。
     */
    @Transactional
    public CanaryReleaseResponse startCanary(CanaryReleaseRequest request) {
        CalibrationVersion canary = requireVersion(request.canaryVersionId());
        if (canary.getStatus() != CalibrationStatus.APPROVED) {
            throw new ConflictException("仅已批准版本可发起灰度，当前状态 " + canary.getStatus());
        }
        requireOperator(request.operator());
        List<String> vehicles = requireDistinctVehicles(request.vehicleIds());
        Instant now = timeService.now();

        // 先做全部业务校验，任何一项失败都抛异常触发回滚
        for (String vehicleId : vehicles) {
            if (!vehicleInRange(canary, vehicleId)) {
                throw new ValidationException(
                        "车辆 " + vehicleId + " 不在灰度版本 " + canary.getVersion() + " 的车辆适用范围内");
            }
            if (!timeEffective(canary, now)) {
                throw new ValidationException(
                        "灰度版本 " + canary.getVersion() + " 在当前时间不在生效时间范围内");
            }
        }

        CanaryRelease release = new CanaryRelease();
        release.setReleaseId(UUID.randomUUID().toString());
        release.setParamKey(canary.getParamKey());
        release.setCanaryVersionId(canary.getId());
        release.setStatus(CanaryStatus.IN_PROGRESS);
        release.setCreatedBy(request.operator());
        release.setCreatedAt(now);
        canaryReleaseJdbc.insert(release);

        // 逐车写入发布前基线快照；占用锁冲突（同键进行中灰度）会导致整批回滚
        List<CanaryVehicle> entries = vehicles.stream()
                .map(vehicleId -> {
                    CanaryVehicle entry = new CanaryVehicle();
                    entry.setVehicleId(vehicleId);
                    entry.setPreviousVersionId(snapshotBaseline(canary.getParamKey(), vehicleId, now));
                    return entry;
                })
                .toList();
        try {
            for (String vehicleId : vehicles) {
                canaryVehicleJdbc.tryLock(canary.getParamKey(), vehicleId);
            }
        } catch (DuplicateKeyException e) {
            throw new ConflictException("存在车辆已参与同参数键的其他进行中灰度，整批拒绝");
        }
        canaryVehicleJdbc.insertBatch(release.getId(), entries);

        canary.setStatus(CalibrationStatus.CANARY);
        calibrationJdbc.updateStatus(canary);
        recordTransition(canary.getId(), CalibrationStatus.APPROVED, CalibrationStatus.CANARY,
                request.operator(), "发起灰度 " + release.getReleaseId());

        return new CanaryReleaseResponse(release.getId(), release.getReleaseId(), release.getParamKey(),
                release.getCanaryVersionId(), release.getStatus(), vehicles,
                release.getCreatedBy(), release.getCreatedAt(),
                release.getPromotedBy(), release.getPromotedAt(),
                release.getRolledBackBy(), release.getRolledBackAt());
    }

    /** 灰度转正：灰度版本成为全量基线，释放车辆占用。 */
    @Transactional
    public CanaryReleaseResponse promoteCanary(String releaseId, OperatorRequest request) {
        requireOperator(request.operator());
        CanaryRelease release = requireRelease(releaseId);
        if (release.getStatus() != CanaryStatus.IN_PROGRESS) {
            throw new ConflictException("仅进行中的灰度可转正，当前状态 " + release.getStatus());
        }
        Instant now = timeService.now();
        CalibrationVersion canary = requireVersion(release.getCanaryVersionId());
        List<String> vehicles = canaryVehicleJdbc.findByReleaseId(release.getId()).stream()
                .map(CanaryVehicle::getVehicleId).toList();

        canaryVehicleJdbc.releaseLocks(release.getParamKey(), vehicles);
        release.setStatus(CanaryStatus.PROMOTED);
        release.setPromotedBy(request.operator());
        release.setPromotedAt(now);
        canaryReleaseJdbc.update(release);

        // 转正即有意替换：与新版本生效范围完全相同的旧基线标记为已替代，
        // 保证“完全相同范围不得同时存在两个有效版本”
        for (CalibrationVersion old : calibrationJdbc.findBaselineCandidates(release.getParamKey())) {
            if (sameScope(canary, old)) {
                old.setStatus(CalibrationStatus.SUPERSEDED);
                calibrationJdbc.updateStatus(old);
                recordTransition(old.getId(), CalibrationStatus.ACTIVE, CalibrationStatus.SUPERSEDED,
                        request.operator(), "灰度转正后被新版本 " + canary.getVersion() + " 替代");
            }
        }

        canary.setStatus(CalibrationStatus.ACTIVE);
        canary.setActivatedBy(request.operator());
        canary.setActivatedAt(now);
        calibrationJdbc.updateStatus(canary);
        recordTransition(canary.getId(), CalibrationStatus.CANARY, CalibrationStatus.ACTIVE,
                request.operator(), "灰度转正 " + release.getReleaseId());
        return toReleaseResponse(release, vehicles);
    }

    /**
     * 灰度回滚：只影响该次灰度车辆，恢复各自发布前基线；未参与灰度的车辆本来也不会命中该版本。
     */
    @Transactional
    public CanaryReleaseResponse rollbackCanary(String releaseId, OperatorRequest request) {
        requireOperator(request.operator());
        CanaryRelease release = requireRelease(releaseId);
        if (release.getStatus() != CanaryStatus.IN_PROGRESS) {
            throw new ConflictException("仅进行中的灰度可回滚，当前状态 " + release.getStatus());
        }
        Instant now = timeService.now();
        CalibrationVersion canary = requireVersion(release.getCanaryVersionId());
        List<CanaryVehicle> entries = canaryVehicleJdbc.findByReleaseId(release.getId());
        List<String> vehicles = entries.stream().map(CanaryVehicle::getVehicleId).toList();

        canaryVehicleJdbc.releaseLocks(release.getParamKey(), vehicles);
        release.setStatus(CanaryStatus.ROLLED_BACK);
        release.setRolledBackBy(request.operator());
        release.setRolledBackAt(now);
        canaryReleaseJdbc.update(release);

        canary.setStatus(CalibrationStatus.ROLLED_BACK);
        canary.setRolledBackBy(request.operator());
        canary.setRolledBackAt(now);
        calibrationJdbc.updateStatus(canary);
        recordTransition(canary.getId(), CalibrationStatus.CANARY, CalibrationStatus.ROLLED_BACK,
                request.operator(),
                "灰度回滚 " + release.getReleaseId() + "，恢复各车发布前版本");
        return toReleaseResponse(release, vehicles);
    }

    /**
     * 解析参数：灰度车辆优先命中进行中灰度版本，其余车辆命中基线；写入不可变历史记录。
     */
    @Transactional
    public ResolutionResponse resolve(ResolveRequest request) {
        if (request.paramKey() == null || request.paramKey().isBlank()) {
            throw new ValidationException("paramKey 不能为空");
        }
        if (request.vehicleId() == null || request.vehicleId().isBlank()) {
            throw new ValidationException("vehicleId 不能为空");
        }
        Instant at = request.at() == null ? timeService.now() : request.at();

        CalibrationVersion selected = null;
        CanaryRelease hitRelease = null;

        for (CanaryRelease release : canaryReleaseJdbc.findInProgressByParamKey(request.paramKey())) {
            List<CanaryVehicle> members = canaryVehicleJdbc.findByReleaseId(release.getId());
            boolean member = members.stream().anyMatch(m -> m.getVehicleId().equals(request.vehicleId()));
            if (!member) {
                continue;
            }
            CalibrationVersion candidate = requireVersion(release.getCanaryVersionId());
            if (matches(candidate, request.vehicleId(), request.sensorId(), request.mapVersion(), at)) {
                selected = candidate;
                hitRelease = release;
                break;
            }
        }

        if (selected == null) {
            selected = selectBaseline(request.paramKey(), request.vehicleId(),
                    request.sensorId(), request.mapVersion(), at);
        }
        if (selected == null) {
            throw new NotFoundException("无匹配的标定版本：paramKey=" + request.paramKey()
                    + ", vehicleId=" + request.vehicleId());
        }

        ResolutionRecord record = new ResolutionRecord();
        record.setResolutionId(UUID.randomUUID().toString());
        record.setParamKey(request.paramKey());
        record.setVehicleId(request.vehicleId());
        record.setSensorId(request.sensorId());
        record.setMapVersion(request.mapVersion());
        record.setResolvedAt(at);
        record.setSelectedVersionId(selected.getId());
        record.setCanaryReleaseId(hitRelease == null ? null : hitRelease.getId());
        resolutionJdbc.insert(record);

        return new ResolutionResponse(record.getResolutionId(), record.getParamKey(),
                record.getVehicleId(), record.getSensorId(), record.getMapVersion(), record.getResolvedAt(),
                selected.getId(), selected.getVersion(), selected.getStatus(), readContent(selected),
                hitRelease == null ? null : hitRelease.getReleaseId());
    }

    /** 按 resolutionId 查询当时使用的参数版本；历史记录只追加，后续发布不能改写。 */
    @Transactional(readOnly = true)
    public ResolutionHistoryResponse getHistory(String resolutionId) {
        ResolutionRecord record = resolutionJdbc.findByResolutionId(resolutionId)
                .orElseThrow(() -> new NotFoundException("解析记录不存在：" + resolutionId));
        CalibrationVersion version = requireVersion(record.getSelectedVersionId());
        String canaryBusinessId = null;
        if (record.getCanaryReleaseId() != null) {
            canaryBusinessId = canaryReleaseJdbc.findById(record.getCanaryReleaseId())
                    .map(CanaryRelease::getReleaseId).orElse(null);
        }
        return new ResolutionHistoryResponse(record.getResolutionId(), record.getParamKey(),
                record.getVehicleId(), record.getSensorId(), record.getMapVersion(), record.getResolvedAt(),
                version.getId(), version.getVersion(), readContent(version), canaryBusinessId);
    }

    @Transactional(readOnly = true)
    public List<TransitionResponse> getTransitions(long versionId) {
        requireVersion(versionId);
        return transitionJdbc.findByVersionId(versionId).stream()
                .map(t -> new TransitionResponse(t.getId(), t.getVersionId(),
                        t.getFromStatus() == null ? null : t.getFromStatus().name(),
                        t.getToStatus().name(), t.getOperator(), t.getOperatedAt(), t.getRemark()))
                .toList();
    }

    // ---------------------------------------------------------------------
    // 内部辅助
    // ---------------------------------------------------------------------

    /**
     * 基线选择：ACTIVE 版本中范围与时间均匹配者，取 ID 最大（最近转正）的一个；无匹配返回 null。
     */
    private CalibrationVersion selectBaseline(String paramKey, String vehicleId,
                                              String sensorId, String mapVersion, Instant at) {
        CalibrationVersion best = null;
        for (CalibrationVersion v : calibrationJdbc.findBaselineCandidates(paramKey)) {
            if (matches(v, vehicleId, sensorId, mapVersion, at) && (best == null || v.getId() > best.getId())) {
                best = v;
            }
        }
        return best;
    }

    /**
     * 灰度发起时快照车辆发布前基线：以车辆维度在当前时刻选择最新 ACTIVE 版本
     * （sensorId/mapVersion 上下文为空，视为通配，不施加传感器/地图约束）。
     */
    private Long snapshotBaseline(String paramKey, String vehicleId, Instant now) {
        CalibrationVersion baseline = selectBaseline(paramKey, vehicleId, null, null, now);
        return baseline == null ? null : baseline.getId();
    }

    private boolean matches(CalibrationVersion v, String vehicleId, String sensorId,
                            String mapVersion, Instant at) {
        return vehicleInRange(v, vehicleId)
                && stringInRange(sensorId, v.getSensorFrom(), v.getSensorTo())
                && stringInRange(mapVersion, v.getMapFrom(), v.getMapTo())
                && timeEffective(v, at);
    }

    private boolean vehicleInRange(CalibrationVersion v, String vehicleId) {
        return stringInRange(vehicleId, v.getVehicleFrom(), v.getVehicleTo());
    }

    /**
     * 字符串区间包含（闭区间，字典序）：边界 null 表示开放端；查询值为 null 时视为通配，
     * 不对该维度施加约束。
     */
    private boolean stringInRange(String value, String from, String to) {
        if (value == null) {
            return true;
        }
        if (from != null && value.compareTo(from) < 0) {
            return false;
        }
        return to == null || value.compareTo(to) <= 0;
    }

    private boolean timeEffective(CalibrationVersion v, Instant at) {
        if (v.getEffectiveFrom() != null && at.isBefore(v.getEffectiveFrom())) {
            return false;
        }
        return v.getEffectiveTo() == null || at.isBefore(v.getEffectiveTo());
    }

    /**
     * 同一参数键下，不得与既有“参与解析的有效版本”（CANARY/ACTIVE）拥有完全相同的
     * 生效时间范围与车辆/传感器/地图版本范围。仅已批准（APPROVED）但尚未生效的版本
     * 不阻止全量生效——它本身还不会被任何车辆解析到。
     */
    private void ensureNoIdenticalScope(CalibrationVersion candidate) {
        List<CalibrationVersion> existing = calibrationJdbc.findByParamKeyAndStatuses(
                candidate.getParamKey(),
                List.of(CalibrationStatus.CANARY, CalibrationStatus.ACTIVE));
        for (CalibrationVersion other : existing) {
            if (other.getId() == candidate.getId()) {
                continue;
            }
            if (sameScope(candidate, other)) {
                throw new ConflictException(
                        "版本 " + candidate.getVersion() + " 与既有有效版本 " + other.getVersion()
                                + " 的生效时间和适用范围完全相同，禁止同时生效");
            }
        }
    }

    private boolean sameScope(CalibrationVersion a, CalibrationVersion b) {
        return eqNullable(a.getEffectiveFrom(), b.getEffectiveFrom())
                && eqNullable(a.getEffectiveTo(), b.getEffectiveTo())
                && eqNullable(a.getVehicleFrom(), b.getVehicleFrom())
                && eqNullable(a.getVehicleTo(), b.getVehicleTo())
                && eqNullable(a.getSensorFrom(), b.getSensorFrom())
                && eqNullable(a.getSensorTo(), b.getSensorTo())
                && eqNullable(a.getMapFrom(), b.getMapFrom())
                && eqNullable(a.getMapTo(), b.getMapTo());
    }

    private boolean eqNullable(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }

    private List<String> requireDistinctVehicles(List<String> vehicleIds) {
        if (vehicleIds == null || vehicleIds.isEmpty()) {
            throw new ValidationException("灰度车辆集合不能为空");
        }
        List<String> distinct = vehicleIds.stream().distinct().toList();
        if (distinct.size() != vehicleIds.size()) {
            throw new ValidationException("灰度车辆集合存在重复车辆");
        }
        if (distinct.stream().anyMatch(s -> s == null || s.isBlank())) {
            throw new ValidationException("灰度车辆编号不能为空");
        }
        return distinct;
    }

    private void requireOperator(String operator) {
        if (operator == null || operator.isBlank()) {
            throw new ValidationException("operator 不能为空");
        }
    }

    private CalibrationVersion requireVersion(long id) {
        return calibrationJdbc.findById(id)
                .orElseThrow(() -> new NotFoundException("标定版本不存在：id=" + id));
    }

    private CanaryRelease requireRelease(String releaseId) {
        return canaryReleaseJdbc.findByReleaseId(releaseId)
                .orElseThrow(() -> new NotFoundException("灰度批次不存在：releaseId=" + releaseId));
    }

    private void recordTransition(long versionId, CalibrationStatus from, CalibrationStatus to,
                                  String operator, String remark) {
        StatusTransition t = new StatusTransition();
        t.setVersionId(versionId);
        t.setFromStatus(from);
        t.setToStatus(to);
        t.setOperator(operator);
        t.setOperatedAt(timeService.now());
        t.setRemark(remark);
        transitionJdbc.insert(t);
    }

    private CreateCalibrationRequest toCheckRequest(CalibrationVersion v) {
        return new CreateCalibrationRequest(v.getParamKey(), v.getSensorType(), v.getKind(),
                v.getVersion(), readContent(v),
                v.getEffectiveFrom(), v.getEffectiveTo(),
                v.getVehicleFrom(), v.getVehicleTo(),
                v.getSensorFrom(), v.getSensorTo(),
                v.getMapFrom(), v.getMapTo(),
                v.getCreatedBy());
    }

    private CalibrationResponse toResponse(CalibrationVersion v, JsonNode content) {
        return new CalibrationResponse(v.getId(), v.getParamKey(), v.getSensorType(), v.getKind(),
                v.getVersion(), v.getContentSha256(), content, v.getStatus(),
                v.getEffectiveFrom(), v.getEffectiveTo(),
                v.getVehicleFrom(), v.getVehicleTo(), v.getSensorFrom(), v.getSensorTo(),
                v.getMapFrom(), v.getMapTo(),
                v.getCreatedBy(), v.getCreatedAt(),
                v.getApprovedBy(), v.getApprovedAt(),
                v.getActivatedBy(), v.getActivatedAt(),
                v.getRolledBackBy(), v.getRolledBackAt());
    }

    private CanaryReleaseResponse toReleaseResponse(CanaryRelease release, List<String> vehicles) {
        return new CanaryReleaseResponse(release.getId(), release.getReleaseId(), release.getParamKey(),
                release.getCanaryVersionId(), release.getStatus(), vehicles,
                release.getCreatedBy(), release.getCreatedAt(),
                release.getPromotedBy(), release.getPromotedAt(),
                release.getRolledBackBy(), release.getRolledBackAt());
    }

    private String writeContent(JsonNode content) {
        try {
            String json = objectMapper.writeValueAsString(content);
            return json;
        } catch (Exception e) {
            throw new ValidationException("参数内容不是合法 JSON：" + e.getMessage());
        }
    }

    private JsonNode readContent(CalibrationVersion v) {
        try {
            return objectMapper.readTree(v.getContentJson());
        } catch (Exception e) {
            throw new IllegalStateException("存储的参数内容解析失败 id=" + v.getId(), e);
        }
    }

    private String sha256(String json) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(json.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
