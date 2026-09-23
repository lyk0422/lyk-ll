package com.example.starter.calibration.service.impl;

import com.example.starter.calibration.api.CanaryReleaseRequest;
import com.example.starter.calibration.api.CreateParamRequest;
import com.example.starter.calibration.api.ParamView;
import com.example.starter.calibration.api.ReleaseView;
import com.example.starter.calibration.api.ResolutionView;
import com.example.starter.calibration.api.ResolveRequest;
import com.example.starter.calibration.api.ResolveResponse;
import com.example.starter.calibration.api.RollbackRequest;
import com.example.starter.calibration.domain.CalibrationParam;
import com.example.starter.calibration.domain.CanaryRelease;
import com.example.starter.calibration.domain.CanaryVehicle;
import com.example.starter.calibration.domain.ParamStatus;
import com.example.starter.calibration.domain.ReleaseStatus;
import com.example.starter.calibration.domain.ResolutionRecord;
import com.example.starter.calibration.repo.CalibrationRepository;
import com.example.starter.calibration.service.CalibrationService;
import com.example.starter.calibration.service.CalibrationValidator;
import com.example.starter.calibration.service.ConflictException;
import com.example.starter.calibration.service.NotFoundException;
import com.example.starter.calibration.service.ValidationException;
import com.example.starter.calibration.support.JsonSupport;
import com.example.starter.calibration.support.TimeProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 标定参数领域服务实现：
 * 创建校验 → 审批 → 范围解析 → 原子灰度 → 回滚/全量 → resolutionId 历史追踪。
 *
 * <p>解析优先级：灰度中(CANARY)且发布单覆盖该车辆的版本 &gt; 其他生效版本；
 * 普通版本之间取最新（同键完全相同生效范围的两个有效版本在审批时即被拒绝）。
 */
@Service
public class CalibrationServiceImpl implements CalibrationService {

    /** 可参与解析的状态：仅灰度中与已生效；已批准但未发布不影响线上解析。 */
    private static final Set<ParamStatus> RESOLVABLE =
            Set.of(ParamStatus.CANARY, ParamStatus.ACTIVE);

    private final CalibrationRepository repository;
    private final CalibrationValidator validator;
    private final ObjectMapper objectMapper;
    private final TimeProvider timeProvider;

    public CalibrationServiceImpl(CalibrationRepository repository,
                                  CalibrationValidator validator,
                                  ObjectMapper objectMapper,
                                  TimeProvider timeProvider) {
        this.repository = repository;
        this.validator = validator;
        this.objectMapper = objectMapper;
        this.timeProvider = timeProvider;
    }

    // ---------- 创建 / 审批 / 全量 ----------

    @Override
    @Transactional
    public ParamView create(CreateParamRequest request) {
        String operator = normalizeOperator(request.operator());
        Instant now = timeProvider.now();

        String contentJson = JsonSupport.write(objectMapper, request.content());
        JsonNode contentNode = readTree(contentJson);
        // 数值有限、焦距为正、旋转矩阵正交：失败直接 422，不产生任何版本
        validator.validate(request.kind(), request.sensorType(), contentNode);

        validateRanges(request.effectiveFrom(), request.effectiveTo(),
                request.mapVersionMin(), request.mapVersionMax());

        List<String> vehicles = normalize(request.vehicleScope());
        List<String> sensors = normalize(request.sensorScope());

        CalibrationParam param = new CalibrationParam();
        param.setParamKey(request.paramKey());
        param.setKind(request.kind());
        param.setSensorType(request.sensorType());
        param.setVersion(request.version());
        param.setContentJson(contentJson);
        param.setContentDigest(sha256(contentJson));
        param.setStatus(ParamStatus.DRAFT);
        param.setEffectiveFrom(request.effectiveFrom());
        param.setEffectiveTo(request.effectiveTo());
        param.setVehicleScopeJson(vehicles.isEmpty() ? null : JsonSupport.write(objectMapper, vehicles));
        param.setSensorScopeJson(sensors.isEmpty() ? null : JsonSupport.write(objectMapper, sensors));
        param.setMapVersionMin(blankToNull(request.mapVersionMin()));
        param.setMapVersionMax(blankToNull(request.mapVersionMax()));
        param.setCreatedBy(operator);
        param.setCreatedAt(now);
        try {
            repository.insertParam(param);
        } catch (DuplicateKeyException e) {
            throw new ValidationException("参数键 " + request.paramKey()
                    + " 已存在版本 " + request.version());
        }
        repository.insertStatusLog(param.getId(), null, ParamStatus.DRAFT.name(),
                operator, now, null, "参数版本创建");
        return toView(param, vehicles, sensors);
    }

    @Override
    @Transactional
    public ParamView approve(String paramKey, String version, String operator) {
        String op = normalizeOperator(operator);
        Instant now = timeProvider.now();
        repository.lockParamKey(paramKey);
        CalibrationParam param = requireVersion(paramKey, version);
        if (param.getStatus() != ParamStatus.DRAFT) {
            throw new ConflictException("仅草稿状态可审批，" + paramKey + "/" + version
                    + " 当前状态为 " + param.getStatus());
        }
        // "完全相同/重叠的生效范围与时间不得同时存在两个有效版本"：
        // 对全量范围候选（未声明车辆子集），若存在另一个已批准待发布(APPROVED)的全量版本范围重叠则拒绝。
        // 声明车辆子集的灰度候选不在此拦截——其车辆互斥由 canaryRelease 保证，
        // 从而允许在灰度进行中准备/审批下一批候选。
        List<String> candidateVehicles = readStringList(param.getVehicleScopeJson());
        if (candidateVehicles.isEmpty()) {
            for (CalibrationParam other : repository.listByParamKey(paramKey)) {
                if (other.getId().equals(param.getId()) || other.getStatus() != ParamStatus.APPROVED) {
                    continue;
                }
                if (readStringList(other.getVehicleScopeJson()).isEmpty()
                        && rangesOverlap(param, other)) {
                    throw new ValidationException("生效范围冲突：版本 " + version
                            + " 与待发布版本 " + other.getVersion()
                            + " 在时间/车辆/传感器/地图版本范围上存在重叠，不得同时存在两个有效版本");
                }
            }
        }
        repository.updateApproval(param.getId(), ParamStatus.APPROVED, op, now);
        repository.insertStatusLog(param.getId(), ParamStatus.DRAFT.name(),
                ParamStatus.APPROVED.name(), op, now, null, "审批通过");
        param.setStatus(ParamStatus.APPROVED);
        param.setApprovedBy(op);
        param.setApprovedAt(now);
        return toView(param);
    }

    @Override
    @Transactional
    public ParamView activate(String paramKey, String version, String operator) {
        String op = normalizeOperator(operator);
        Instant now = timeProvider.now();
        repository.lockParamKey(paramKey);
        CalibrationParam param = requireVersion(paramKey, version);
        if (param.getStatus() != ParamStatus.APPROVED) {
            throw new ConflictException("仅已批准状态可直接全量生效，当前状态为 " + param.getStatus());
        }
        supersedeOverlapping(param, op, now);
        repository.updateParamStatus(param.getId(), ParamStatus.ACTIVE);
        repository.insertStatusLog(param.getId(), ParamStatus.APPROVED.name(),
                ParamStatus.ACTIVE.name(), op, now, null, "直接全量生效");
        param.setStatus(ParamStatus.ACTIVE);
        return toView(param);
    }

    // ---------- 灰度发布 / 回滚 / 全量推广 ----------

    @Override
    @Transactional
    public CanaryRelease canaryRelease(CanaryReleaseRequest request) {
        String op = normalizeOperator(request.operator());
        Instant now = timeProvider.now();

        List<String> rawVehicles = request.vehicles() == null ? List.of() : request.vehicles();
        if (rawVehicles.stream().anyMatch(v -> v == null || v.isBlank())) {
            throw new ValidationException("灰度车辆标识不能为空");
        }
        if (rawVehicles.size() != new LinkedHashSet<>(rawVehicles).size()) {
            throw new ValidationException("灰度车辆集合存在重复车辆: " + rawVehicles);
        }
        List<String> vehicles = new ArrayList<>(new LinkedHashSet<>(rawVehicles));
        if (vehicles.isEmpty()) {
            throw new ValidationException("灰度车辆集合不能为空");
        }

        repository.lockParamKey(request.paramKey());
        CalibrationParam param = requireVersion(request.paramKey(), request.version());
        if (param.getStatus() != ParamStatus.APPROVED) {
            throw new ConflictException("仅已批准版本可发起灰度，当前状态为 " + param.getStatus());
        }
        List<String> declaredScope = readStringList(param.getVehicleScopeJson());
        if (!declaredScope.isEmpty() && !new LinkedHashSet<>(declaredScope).containsAll(vehicles)) {
            throw new ValidationException("灰度车辆集合必须是版本声明车辆范围的子集，版本声明 "
                    + declaredScope + "，请求 " + vehicles);
        }

        // 任一车辆已处于该参数键的其它灰度中 → 整批失败
        List<CanaryRelease> activeReleases = repository.listActiveReleasesByParamKey(request.paramKey());
        for (CanaryRelease active : activeReleases) {
            Set<String> activeVehicles = new LinkedHashSet<>(readStringList(active.getVehiclesJson()));
            for (String v : vehicles) {
                if (activeVehicles.contains(v)) {
                    throw new ValidationException("车辆 " + v + " 已处于发布单 "
                            + active.getReleaseId() + " 的灰度中，车辆范围冲突，整批不执行");
                }
            }
        }

        // 记录各车辆发布前版本，回滚时恢复（按车辆+时间，不受传感器/地图过滤影响；
        // 新版本此刻仍是 APPROVED，不会被 RESOLVABLE 选中）
        List<CanaryVehicle> snapshots = new ArrayList<>();
        String releaseId = newReleaseId(now);
        for (String vehicle : vehicles) {
            CalibrationParam previous = resolvePreviousForVehicle(request.paramKey(), vehicle, now);
            CanaryVehicle snapshot = new CanaryVehicle();
            snapshot.setReleaseId(null);
            snapshot.setVehicleId(vehicle);
            if (previous != null) {
                snapshot.setPreviousParamId(previous.getId());
                snapshot.setPreviousVersion(previous.getVersion());
            }
            snapshots.add(snapshot);
        }

        CanaryRelease release = new CanaryRelease();
        release.setReleaseId(releaseId);
        release.setParamId(param.getId());
        release.setParamKey(param.getParamKey());
        release.setNewVersion(param.getVersion());
        release.setVehiclesJson(JsonSupport.write(objectMapper, vehicles));
        release.setStatus(ReleaseStatus.ACTIVE);
        release.setCreatedBy(op);
        release.setCreatedAt(now);
        release.setRemark(request.remark());
        repository.insertRelease(release);

        for (CanaryVehicle snapshot : snapshots) {
            snapshot.setReleaseId(release.getId());
        }
        repository.insertCanaryVehicles(snapshots);

        // 灰度版本的车辆范围收敛为显式车辆集合并置为灰度中
        param.setVehicleScopeJson(release.getVehiclesJson());
        repository.updateParamStatus(param.getId(), ParamStatus.CANARY);
        repository.updateVehicleScope(param.getId(), param.getVehicleScopeJson());
        repository.insertStatusLog(param.getId(), ParamStatus.APPROVED.name(),
                ParamStatus.CANARY.name(), op, now, releaseId,
                "灰度发布，车辆 " + vehicles);

        return release;
    }

    @Override
    @Transactional
    public CanaryRelease rollback(RollbackRequest request) {
        String op = normalizeOperator(request.operator());
        Instant now = timeProvider.now();

        CanaryRelease release = repository.lockRelease(request.releaseId());
        if (release == null) {
            throw new NotFoundException("发布单不存在: " + request.releaseId());
        }
        if (release.getStatus() != ReleaseStatus.ACTIVE) {
            throw new ConflictException("仅灰度中的发布单可回滚，当前状态为 " + release.getStatus());
        }
        repository.lockParamKey(release.getParamKey());

        CalibrationParam param = repository.findParamById(release.getParamId());
        if (param == null || param.getStatus() != ParamStatus.CANARY) {
            throw new ConflictException("灰度版本状态异常，无法回滚: "
                    + (param == null ? "版本缺失" : param.getStatus()));
        }

        // 仅影响该发布单车辆：新版本对这些车辆退出，各车恢复发布前版本（快照见 canary_vehicle）。
        List<CanaryVehicle> vehicles = repository.listCanaryVehicles(release.getId());
        for (CanaryVehicle v : vehicles) {
            CalibrationParam previous = v.getPreviousParamId() == null
                    ? null : repository.findParamById(v.getPreviousParamId());
            if (previous != null && !RESOLVABLE.contains(previous.getStatus())) {
                throw new ConflictException("车辆 " + v.getVehicleId() + " 的发布前版本 "
                        + previous.getVersion() + " 当前状态 " + previous.getStatus()
                        + "，无法恢复，回滚中止");
            }
        }
        repository.updateParamStatus(param.getId(), ParamStatus.ROLLED_BACK);
        repository.insertStatusLog(param.getId(), ParamStatus.CANARY.name(),
                ParamStatus.ROLLED_BACK.name(), op, now, release.getReleaseId(),
                "灰度回滚，恢复各车发布前版本");

        repository.updateReleaseStatus(release.getId(), ReleaseStatus.ROLLED_BACK, op, now);
        release.setStatus(ReleaseStatus.ROLLED_BACK);
        release.setFinishedBy(op);
        release.setFinishedAt(now);
        return release;
    }

    @Override
    @Transactional
    public CanaryRelease promote(String releaseId, String operator) {
        String op = normalizeOperator(operator);
        Instant now = timeProvider.now();

        CanaryRelease release = repository.lockRelease(releaseId);
        if (release == null) {
            throw new NotFoundException("发布单不存在: " + releaseId);
        }
        if (release.getStatus() != ReleaseStatus.ACTIVE) {
            throw new ConflictException("仅灰度中的发布单可全量推广，当前状态为 " + release.getStatus());
        }
        repository.lockParamKey(release.getParamKey());
        CalibrationParam param = repository.findParamById(release.getParamId());
        if (param == null || param.getStatus() != ParamStatus.CANARY) {
            throw new ConflictException("灰度版本状态异常，无法全量推广");
        }

        // 推广即把灰度版本车辆范围放开为全集；先放开再判定替代，使全量旧版本被原子替代
        repository.updateVehicleScope(param.getId(), null);
        param.setVehicleScopeJson(null);
        supersedeOverlapping(param, op, now);
        repository.updateParamStatus(param.getId(), ParamStatus.ACTIVE);
        repository.insertStatusLog(param.getId(), ParamStatus.CANARY.name(),
                ParamStatus.ACTIVE.name(), op, now, releaseId, "灰度全量推广");
        repository.updateReleaseStatus(release.getId(), ReleaseStatus.PROMOTED, op, now);
        release.setStatus(ReleaseStatus.PROMOTED);
        release.setFinishedBy(op);
        release.setFinishedAt(now);
        return release;
    }

    // ---------- 查询 ----------

    @Override
    @Transactional(readOnly = true)
    public List<ParamView> listVersions(String paramKey) {
        return repository.listByParamKey(paramKey).stream().map(this::toView).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ReleaseView getRelease(String releaseId) {
        CanaryRelease release = repository.findReleaseByReleaseId(releaseId);
        if (release == null) {
            throw new NotFoundException("发布单不存在: " + releaseId);
        }
        List<ReleaseView.VehicleSnapshot> snapshots = repository.listCanaryVehicles(release.getId())
                .stream()
                .map(v -> new ReleaseView.VehicleSnapshot(
                        v.getVehicleId(), v.getPreviousParamId(), v.getPreviousVersion()))
                .toList();
        return new ReleaseView(release.getReleaseId(), release.getParamKey(),
                release.getNewVersion(), release.getParamId(),
                readStringList(release.getVehiclesJson()), release.getStatus(),
                release.getCreatedBy(), release.getCreatedAt(),
                release.getFinishedBy(), release.getFinishedAt(), release.getRemark(), snapshots);
    }

    @Override
    @Transactional
    public ResolveResponse resolve(ResolveRequest request) {
        if (isBlank(request.paramKey()) || isBlank(request.vehicleId())) {
            throw new ValidationException("paramKey 与 vehicleId 不能为空");
        }
        Instant at = request.at() != null ? request.at() : timeProvider.now();
        CalibrationParam selected = resolveCandidate(request.paramKey(), request.vehicleId(),
                request.sensorId(), request.mapVersion(), at, null);
        if (selected == null) {
            throw new NotFoundException("未找到匹配的有效参数版本: paramKey=" + request.paramKey()
                    + ", vehicle=" + request.vehicleId()
                    + ", map=" + request.mapVersion() + ", at=" + at);
        }

        String resolutionId = UUID.randomUUID().toString();
        ResolutionRecord record = new ResolutionRecord();
        record.setResolutionId(resolutionId);
        record.setParamKey(selected.getParamKey());
        record.setVehicleId(request.vehicleId());
        record.setSensorId(request.sensorId());
        record.setMapVersion(request.mapVersion());
        record.setResolveTime(at);
        record.setResolvedParamId(selected.getId());
        record.setResolvedVersion(selected.getVersion());
        record.setContentDigest(selected.getContentDigest());
        record.setContentJson(selected.getContentJson());
        // 只增记录：任何后续发布/回滚都不会修改该行（内容为当时快照）
        repository.insertResolution(record);

        Object content;
        try {
            content = objectMapper.readValue(selected.getContentJson(), Object.class);
        } catch (Exception e) {
            throw new IllegalStateException("存储的参数内容无法解析: " + e.getMessage(), e);
        }
        return ResolveResponse.of(selected, resolutionId, content, at);
    }

    @Override
    @Transactional(readOnly = true)
    public ResolutionView getResolution(String resolutionId) {
        ResolutionRecord record = repository.findResolution(resolutionId);
        if (record == null) {
            throw new NotFoundException("resolutionId 不存在: " + resolutionId);
        }
        Object content;
        try {
            content = objectMapper.readValue(record.getContentJson(), Object.class);
        } catch (Exception e) {
            throw new IllegalStateException("历史快照内容无法解析: " + e.getMessage(), e);
        }
        return new ResolutionView(record.getResolutionId(), record.getParamKey(),
                record.getVehicleId(), record.getSensorId(), record.getMapVersion(),
                record.getResolveTime(), record.getResolvedParamId(),
                record.getResolvedVersion(), record.getContentDigest(), content);
    }

    // ---------- 范围匹配 ----------

    /**
     * 计算某车辆在灰度发布前应解析到的版本（仅按车辆与时间，忽略传感器/地图过滤），
     * 用于回滚恢复快照。
     */
    private CalibrationParam resolvePreviousForVehicle(String paramKey, String vehicleId, Instant at) {
        List<CalibrationParam> candidates = repository.listByParamKey(paramKey).stream()
                .filter(p -> RESOLVABLE.contains(p.getStatus()))
                .filter(p -> timeMatches(p, at))
                .filter(p -> vehicleMatches(p, vehicleId))
                .toList();
        if (candidates.isEmpty()) {
            return null;
        }
        CalibrationParam canary = candidates.stream()
                .filter(p -> p.getStatus() == ParamStatus.CANARY)
                .max(Comparator.comparingLong(CalibrationParam::getId))
                .orElse(null);
        if (canary != null) {
            return canary;
        }
        return candidates.stream().max(Comparator.comparingLong(CalibrationParam::getId)).orElse(null);
    }

    /**
     * 按车辆/传感器/地图版本/时间选出唯一有效版本。
     *
     * @param excludeReleaseId 灰度发布过程中计算“发布前版本”时保留位（当前未使用）
     */
    private CalibrationParam resolveCandidate(String paramKey, String vehicleId, String sensorId,
                                              String mapVersion, Instant at, String excludeReleaseId) {
        List<CalibrationParam> candidates = repository.listByParamKey(paramKey).stream()
                .filter(p -> RESOLVABLE.contains(p.getStatus()))
                .filter(p -> timeMatches(p, at))
                .filter(p -> mapMatches(p, mapVersion))
                .filter(p -> sensorMatches(p, sensorId))
                .filter(p -> vehicleMatches(p, vehicleId))
                .toList();
        if (candidates.isEmpty()) {
            return null;
        }
        // 灰度版本优先（仅对发布单覆盖车辆生效，其车辆范围已收敛为灰度车辆集合）
        CalibrationParam canary = candidates.stream()
                .filter(p -> p.getStatus() == ParamStatus.CANARY)
                .max(Comparator.comparingLong(CalibrationParam::getId))
                .orElse(null);
        if (canary != null) {
            return canary;
        }
        return candidates.stream().max(Comparator.comparingLong(CalibrationParam::getId)).orElse(null);
    }

    private boolean timeMatches(CalibrationParam p, Instant at) {
        return (p.getEffectiveFrom() == null || !p.getEffectiveFrom().isAfter(at))
                && (p.getEffectiveTo() == null || at.isBefore(p.getEffectiveTo()));
    }

    private boolean mapMatches(CalibrationParam p, String mapVersion) {
        if (isBlank(mapVersion)) {
            return p.getMapVersionMin() == null && p.getMapVersionMax() == null;
        }
        if (p.getMapVersionMin() != null
                && compareMapVersion(mapVersion, p.getMapVersionMin()) < 0) {
            return false;
        }
        if (p.getMapVersionMax() != null
                && compareMapVersion(mapVersion, p.getMapVersionMax()) > 0) {
            return false;
        }
        return true;
    }

    private boolean sensorMatches(CalibrationParam p, String sensorId) {
        List<String> scope = readStringList(p.getSensorScopeJson());
        if (scope.isEmpty()) {
            return true;
        }
        return !isBlank(sensorId) && scope.contains(sensorId);
    }

    private boolean vehicleMatches(CalibrationParam p, String vehicleId) {
        List<String> scope = readStringList(p.getVehicleScopeJson());
        return scope.isEmpty() || scope.contains(vehicleId);
    }

    /**
     * 两个版本的生效范围是否在某个（车辆, 传感器, 地图版本, 时间）点上同时覆盖。
     */
    private boolean rangesOverlap(CalibrationParam a, CalibrationParam b) {
        if (!timeRangesOverlap(a.getEffectiveFrom(), a.getEffectiveTo(),
                b.getEffectiveFrom(), b.getEffectiveTo())) {
            return false;
        }
        if (!mapRangesOverlap(a.getMapVersionMin(), a.getMapVersionMax(),
                b.getMapVersionMin(), b.getMapVersionMax())) {
            return false;
        }
        if (!scopesOverlap(readStringList(a.getSensorScopeJson()),
                readStringList(b.getSensorScopeJson()))) {
            return false;
        }
        return scopesOverlap(readStringList(a.getVehicleScopeJson()),
                readStringList(b.getVehicleScopeJson()));
    }

    private boolean timeRangesOverlap(Instant fromA, Instant toA, Instant fromB, Instant toB) {
        // null 下界视为负无穷，null 上界视为正无穷
        Instant start;
        if (fromA == null) {
            start = fromB;
        } else if (fromB == null) {
            start = fromA;
        } else {
            start = fromA.isAfter(fromB) ? fromA : fromB;
        }
        Instant end;
        if (toA == null) {
            end = toB;
        } else if (toB == null) {
            end = toA;
        } else {
            end = toA.isBefore(toB) ? toA : toB;
        }
        if (start == null || end == null) {
            return true;
        }
        return start.isBefore(end);
    }

    private boolean mapRangesOverlap(String minA, String maxA, String minB, String maxB) {
        String low = minA == null ? minB : (minB == null ? minA : maxMap(minA, minB));
        String high = maxA == null ? maxB : (maxB == null ? maxA : minMap(maxA, maxB));
        if (low == null || high == null) {
            return true;
        }
        return compareMapVersion(low, high) <= 0;
    }

    private boolean scopesOverlap(List<String> a, List<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return true;
        }
        for (String s : a) {
            if (b.contains(s)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 全量生效/推广：仅当目标版本范围在时间/地图/车辆/传感器各维度上完全覆盖旧版本时，
     * 才把旧版本置为 SUPERSEDED；目标只是更窄子集（如传感器定向版本）时旧版本保留，
     * 继续服务未被覆盖的对象。进行中的灰度（CANARY）始终不在此处理。
     */
    private void supersedeOverlapping(CalibrationParam target, String operator, Instant now) {
        for (CalibrationParam other : repository.listByParamKey(target.getParamKey())) {
            if (other.getId().equals(target.getId())) {
                continue;
            }
            if ((other.getStatus() == ParamStatus.APPROVED || other.getStatus() == ParamStatus.ACTIVE)
                    && rangesOverlap(target, other)
                    && rangeFullyCovers(target, other)) {
                repository.updateParamStatus(other.getId(), ParamStatus.SUPERSEDED);
                repository.insertStatusLog(other.getId(), other.getStatus().name(),
                        ParamStatus.SUPERSEDED.name(), operator, now, null,
                        "被版本 " + target.getVersion() + " 替代");
            }
        }
    }

    /** target 的生效范围是否完全覆盖 other（车辆/传感器集合、地图区间、时间区间）。 */
    private boolean rangeFullyCovers(CalibrationParam target, CalibrationParam other) {
        return scopeCovers(readStringList(target.getVehicleScopeJson()),
                readStringList(other.getVehicleScopeJson()))
                && scopeCovers(readStringList(target.getSensorScopeJson()),
                readStringList(other.getSensorScopeJson()))
                && mapRangeCovers(target.getMapVersionMin(), target.getMapVersionMax(),
                other.getMapVersionMin(), other.getMapVersionMax())
                && timeRangeCovers(target.getEffectiveFrom(), target.getEffectiveTo(),
                other.getEffectiveFrom(), other.getEffectiveTo());
    }

    /** 空集合表示全集；target 非空时必须包含 other 的全部元素，且 other 为全集时无法覆盖。 */
    private boolean scopeCovers(List<String> target, List<String> other) {
        if (target.isEmpty()) {
            return true;
        }
        if (other.isEmpty()) {
            return false;
        }
        return target.containsAll(other);
    }

    private boolean mapRangeCovers(String tMin, String tMax, String oMin, String oMax) {
        boolean low = tMin == null || (oMin != null && compareMapVersion(tMin, oMin) <= 0);
        boolean high = tMax == null || (oMax != null && compareMapVersion(tMax, oMax) >= 0);
        return low && high;
    }

    private boolean timeRangeCovers(Instant tFrom, Instant tTo, Instant oFrom, Instant oTo) {
        boolean from = tFrom == null || (oFrom != null && !tFrom.isAfter(oFrom));
        boolean to = tTo == null || (oTo != null && !tTo.isBefore(oTo));
        return from && to;
    }

    // ---------- 杂项 ----------

    private CalibrationParam requireVersion(String paramKey, String version) {
        CalibrationParam param = repository.findParamByKeyAndVersion(paramKey, version);
        if (param == null) {
            throw new NotFoundException("参数版本不存在: " + paramKey + "/" + version);
        }
        return param;
    }

    private void validateRanges(Instant from, Instant to, String mapMin, String mapMax) {
        if (from != null && to != null && !from.isBefore(to)) {
            throw new ValidationException("生效时间范围非法：effectiveFrom 必须早于 effectiveTo");
        }
        if (!isBlank(mapMin) && !isBlank(mapMax) && compareMapVersion(mapMin, mapMax) > 0) {
            throw new ValidationException("地图版本范围非法：mapVersionMin 不能大于 mapVersionMax");
        }
    }

    private ParamView toView(CalibrationParam p) {
        return toView(p, readStringList(p.getVehicleScopeJson()),
                readStringList(p.getSensorScopeJson()));
    }

    private ParamView toView(CalibrationParam p, List<String> vehicles, List<String> sensors) {
        return new ParamView(p.getId(), p.getParamKey(), p.getKind(), p.getSensorType(),
                p.getVersion(), p.getContentDigest(), p.getStatus(),
                p.getEffectiveFrom(), p.getEffectiveTo(), vehicles, sensors,
                p.getMapVersionMin(), p.getMapVersionMax(),
                p.getCreatedBy(), p.getCreatedAt(), p.getApprovedBy(), p.getApprovedAt());
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new ValidationException("参数内容不是合法 JSON: " + e.getMessage());
        }
    }

    private List<String> readStringList(String json) {
        return JsonSupport.readStringList(objectMapper, json);
    }

    private static List<String> normalize(List<String> input) {
        if (input == null) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String s : input) {
            if (s == null || s.isBlank()) {
                throw new ValidationException("车辆/传感器标识不能为空");
            }
            if (!result.contains(s)) {
                result.add(s);
            }
        }
        return result;
    }

    private static String normalizeOperator(String value) {
        return isBlank(value) ? "system" : value.trim();
    }

    private static String blankToNull(String s) {
        return isBlank(s) ? null : s.trim();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String newReleaseId(Instant now) {
        String ts = now.atZone(java.time.ZoneOffset.UTC)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        return "REL-" + ts + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    // ---------- 地图版本自然序比较（数字段按数值比较，如 map-2 &lt; map-10） ----------

    static int compareMapVersion(String a, String b) {
        if (a.equals(b)) {
            return 0;
        }
        java.util.regex.Matcher ma = java.util.regex.Pattern.compile("\\d+|[^\\d.]+").matcher(a);
        java.util.regex.Matcher mb = java.util.regex.Pattern.compile("\\d+|[^\\d.]+").matcher(b);
        List<String> ta = new ArrayList<>();
        List<String> tb = new ArrayList<>();
        ma.results().forEach(m -> ta.add(m.group()));
        mb.results().forEach(m -> tb.add(m.group()));
        int n = Math.min(ta.size(), tb.size());
        for (int i = 0; i < n; i++) {
            String x = ta.get(i);
            String y = tb.get(i);
            int cmp;
            if (x.chars().allMatch(Character::isDigit) && y.chars().allMatch(Character::isDigit)) {
                cmp = Long.compare(Long.parseLong(x), Long.parseLong(y));
            } else {
                cmp = x.compareTo(y);
            }
            if (cmp != 0) {
                return cmp;
            }
        }
        return Integer.compare(ta.size(), tb.size());
    }

    private static String maxMap(String a, String b) {
        return compareMapVersion(a, b) >= 0 ? a : b;
    }

    private static String minMap(String a, String b) {
        return compareMapVersion(a, b) <= 0 ? a : b;
    }
}
