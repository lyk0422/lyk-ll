package com.example.starter.wb.service;

import com.example.starter.wb.calc.CalcLoadItem;
import com.example.starter.wb.calc.WeightBalanceCalculator;
import com.example.starter.wb.calc.WeightBalanceResult;
import com.example.starter.wb.domain.Aircraft;
import com.example.starter.wb.domain.Flight;
import com.example.starter.wb.domain.IdempotentRow;
import com.example.starter.wb.domain.LateEventRow;
import com.example.starter.wb.domain.LoadDetailRow;
import com.example.starter.wb.domain.LoadStation;
import com.example.starter.wb.domain.LmcLogRow;
import com.example.starter.wb.domain.ManifestRow;
import com.example.starter.wb.domain.ReviewRow;
import com.example.starter.wb.dto.CheckinRequest;
import com.example.starter.wb.dto.CloseRequest;
import com.example.starter.wb.dto.CreateManifestRequest;
import com.example.starter.wb.dto.LateAlert;
import com.example.starter.wb.dto.LateCheckinRequest;
import com.example.starter.wb.dto.LoadItem;
import com.example.starter.wb.dto.LmcRequest;
import com.example.starter.wb.dto.ManifestResponse;
import com.example.starter.wb.dto.ReviewRequest;
import com.example.starter.wb.dto.StageRequest;
import com.example.starter.wb.enums.LoadCategory;
import com.example.starter.wb.enums.ManifestStatus;
import com.example.starter.wb.enums.ReviewRole;
import com.example.starter.wb.exception.BadRequestException;
import com.example.starter.wb.exception.ConflictException;
import com.example.starter.wb.exception.NotFoundException;
import com.example.starter.wb.exception.UnprocessableException;
import com.example.starter.wb.repo.IdempotencyStore;
import com.example.starter.wb.repo.WeightBalanceRepository;
import com.example.starter.wb.support.FingerprintUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 载重平衡舱单核心服务。
 *
 * <p>状态机：预配 PRELIMINARY → 值机 CHECKIN → 装载确认 LOADED →
 * 双岗复核 REVIEWED（冻结）→ 放行关闭 CLOSED（终态）；LMC 超限重打产生新版本（SUPERSEDED 链）。
 *
 * <p>所有写操作以 requestId 幂等：SHA-256 指纹覆盖 requestId 与全部业务字段；
 * 同键同参回放首次结果，同键异参 409。加油量变更与重心重算在同一事务同一 UPDATE 内完成。
 */
@Service
public class WeightBalanceServiceImpl implements WeightBalanceService {

    private static final Logger log = LoggerFactory.getLogger(WeightBalanceServiceImpl.class);

    /** LMC 限额：恰达含。 */
    static final int LMC_PAX_LIMIT = 5;
    static final BigDecimal LMC_WEIGHT_LIMIT = new BigDecimal("500.00");

    private final WeightBalanceRepository repo;
    private final IdempotencyStore idempotencyStore;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public WeightBalanceServiceImpl(WeightBalanceRepository repo, IdempotencyStore idempotencyStore,
                                    ObjectMapper objectMapper, Clock clock) {
        this.repo = repo;
        this.idempotencyStore = idempotencyStore;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ 预配

    @Override
    @Transactional
    public ManifestResponse createManifest(CreateManifestRequest request) {
        return withIdempotency(request.requestId(), request, 201, () -> {
            Flight flight = requireFlight(request.flightId());
            Aircraft ac = requireAircraft(flight.aircraftId());
            if (repo.findActiveByFlightId(flight.id()).isPresent()) {
                throw new ConflictException("航班 " + flight.id() + " 已存在有效舱单版本");
            }
            List<LoadDetailRow> details = toDetails(request.items(), ac, 0);
            WeightBalanceResult wb = WeightBalanceCalculator.calculate(ac, toCalcItems(details, ac), request.fuelKg());
            ensureWithinLimits(wb);

            LocalDateTime now = LocalDateTime.now(clock);
            ManifestRow row = new ManifestRow(
                    null, flight.id(), 1, null, request.requestId(),
                    fingerprint(request.requestId(), request),
                    ManifestStatus.PRELIMINARY.name(),
                    wb.passengerCount(), wb.payloadKg(), wb.fuelKg(), wb.zfwKg(), wb.towKg(),
                    wb.cgZfwPct(), wb.cgTowPct(), true, 0, BigDecimal.ZERO.setScale(2),
                    flight.id(), request.createdBy(), now, now, null);
            long id = insertManifestActive(row);
            repo.insertDetails(id, withManifestId(details, id));
            return toResponse(repo.findManifestById(id).orElseThrow());
        });
    }

    // ------------------------------------------------------------------ 值机

    @Override
    @Transactional
    public ManifestResponse checkin(long flightId, CheckinRequest request) {
        return withIdempotency(request.requestId(), request, 200, () -> {
            ActiveCtx ctx = requireActive(flightId);
            requireStatus(ctx.row(), ManifestStatus.PRELIMINARY, "值机");
            Aircraft ac = ctx.aircraft();
            BigDecimal fuel = request.fuelKg() != null ? request.fuelKg() : ctx.row().fuelKg();
            List<LoadDetailRow> details = toDetails(request.items(), ac, 0);
            WeightBalanceResult wb = WeightBalanceCalculator.calculate(ac, toCalcItems(details, ac), fuel);
            ensureWithinLimits(wb);

            long id = ctx.row().id();
            repo.deleteDetailsByManifest(id);
            repo.insertDetails(id, withManifestId(details, id));
            LocalDateTime now = LocalDateTime.now(clock);
            repo.updateComputed(copyRow(ctx.row(), wb, ManifestStatus.CHECKIN.name(),
                    ctx.row().lmcPaxDelta(), ctx.row().lmcWeightDelta(), now));
            return toResponse(repo.findManifestById(id).orElseThrow());
        });
    }

    // ------------------------------------------------------------- 装载确认

    @Override
    @Transactional
    public ManifestResponse confirmLoad(long flightId, StageRequest request) {
        return withIdempotency(request.requestId(), request, 200, () -> {
            ActiveCtx ctx = requireActive(flightId);
            requireStatus(ctx.row(), ManifestStatus.CHECKIN, "装载确认");
            repo.updateStatus(ctx.row().id(), ManifestStatus.LOADED.name(), null);
            return toResponse(repo.findManifestById(ctx.row().id()).orElseThrow());
        });
    }

    // ----------------------------------------------------------------- LMC

    @Override
    @Transactional
    public ManifestResponse applyLmc(long flightId, LmcRequest request) {
        return withIdempotency(request.requestId(), request, 200, () -> {
            ActiveCtx ctx = requireActive(flightId);
            ManifestRow row = ctx.row();
            if (ManifestStatus.REVIEWED.name().equals(row.status())
                    || ManifestStatus.CLOSED.name().equals(row.status())) {
                throw new ConflictException("舱单已" + (ManifestStatus.CLOSED.name().equals(row.status()) ? "放行关闭" : "复核冻结")
                        + "，不能直接 LMC；关闭后变化走迟到值机登记");
            }
            if (ManifestStatus.PRELIMINARY.name().equals(row.status())) {
                throw new ConflictException("预配阶段请使用值机全量更新，不能 LMC");
            }
            Aircraft ac = ctx.aircraft();
            validateLmcStations(ac, request);

            int newPaxDelta = row.lmcPaxDelta() + request.passengerDelta();
            BigDecimal newWeightDelta = row.lmcWeightDelta().add(request.weightDeltaKg());
            boolean withinLmc = Math.abs(newPaxDelta) <= LMC_PAX_LIMIT
                    && newWeightDelta.abs().compareTo(LMC_WEIGHT_LIMIT) <= 0;

            // 本版既有明细 + 本次增量明细；加油量变更同步参与重心重算（同一事务）。
            List<LoadDetailRow> existing = repo.findDetails(row.id());
            int nextSeq = existing.stream().mapToInt(LoadDetailRow::seqNo).max().orElse(-1) + 1;
            List<LoadDetailRow> deltaRows = lmcDeltaDetails(request, ac, nextSeq);
            List<LoadDetailRow> merged = new ArrayList<>(existing);
            merged.addAll(deltaRows);
            // 退载不得使站位在机人数或货物重量为负。
            for (CalcLoadItem item : toCalcItems(merged, ac)) {
                if (item.isPassenger() && item.pieces() < 0) {
                    throw new UnprocessableException("LMC 后站位 " + item.stationCode() + " 旅客人数为负，退载人数超过在机人数");
                }
                if (item.weightKg().signum() < 0) {
                    throw new UnprocessableException("LMC 后站位 " + item.stationCode()
                            + " 的 " + item.category() + " 重量为负，退载重量超过在机重量");
                }
            }
            BigDecimal newFuel = row.fuelKg().add(request.fuelDeltaKg());
            WeightBalanceResult wb = WeightBalanceCalculator.calculate(ac, toCalcItems(merged, ac), newFuel);
            ensureWithinLimits(wb);

            LocalDateTime now = LocalDateTime.now(clock);
            if (withinLmc) {
                // 限内：同一版本修订，油量与重心在同一 UPDATE 内落库。
                long lmcId = repo.insertLmcLog(new LmcLogRow(
                        null, row.id(), request.requestId(), request.passengerDelta(),
                        request.weightDeltaKg(), request.fuelDeltaKg(), newPaxDelta, newWeightDelta,
                        true, null, now));
                repo.insertDetails(row.id(), withManifestId(deltaRows, row.id()));
                repo.updateComputed(copyRow(row, wb, row.status(), newPaxDelta, newWeightDelta, now));
                return toResponse(repo.findManifestById(row.id()).orElseThrow());
            }

            // 超限：必须重打舱单——旧版作废、生成新版本链；新版本需重新双岗复核。
            log.warn("LMC 超过 ±{} 人/±{}kg 限额（累计 {} 人, {}kg），航班 {} 重打新版本",
                    LMC_PAX_LIMIT, LMC_WEIGHT_LIMIT, newPaxDelta, newWeightDelta, flightId);
            long lmcId = repo.insertLmcLog(new LmcLogRow(
                    null, row.id(), request.requestId(), request.passengerDelta(),
                    request.weightDeltaKg(), request.fuelDeltaKg(), newPaxDelta, newWeightDelta,
                    false, null, now));
            int newVersionNo = row.versionNo() + 1;
            String newStatus = ManifestStatus.LOADED.name().equals(row.status())
                    ? ManifestStatus.LOADED.name() : ManifestStatus.CHECKIN.name();
            ManifestRow newRow = new ManifestRow(
                    null, flightId, newVersionNo, row.id(), request.requestId(),
                    fingerprint(request.requestId(), request),
                    newStatus, wb.passengerCount(), wb.payloadKg(), wb.fuelKg(), wb.zfwKg(), wb.towKg(),
                    wb.cgZfwPct(), wb.cgTowPct(), true, 0, BigDecimal.ZERO.setScale(2),
                    flightId, request.operator(), now, now, null);
            long newId = reprintActive(row, newRow);
            // 新版本明细 = 旧版全部明细（含历次限内增量）+ 本次触发重打的增量，保证可复算。
            repo.insertDetails(newId, withManifestId(merged, newId));
            repo.linkLmcNewVersion(lmcId, newId);
            return toResponse(repo.findManifestById(newId).orElseThrow());
        });
    }

    // ----------------------------------------------------------------- 双岗

    @Override
    @Transactional
    public ManifestResponse review(long flightId, ReviewRequest request) {
        return withIdempotency(request.requestId(), request, 200, () -> {
            ActiveCtx ctx = requireActive(flightId);
            ManifestRow row = ctx.row();
            ReviewRole role = parseRole(request.role());
            if (!ManifestStatus.LOADED.name().equals(row.status())
                    && !ManifestStatus.REVIEWED.name().equals(row.status())) {
                throw new ConflictException("仅装载确认后的舱单可提交复核，当前状态 " + row.status());
            }
            List<ReviewRow> existing = repo.findReviews(row.id());
            Optional<ReviewRow> sameRole = existing.stream()
                    .filter(r -> r.roleCode().equals(role.name())).findFirst();
            if (sameRole.isPresent()) {
                throw new ConflictException("岗位 " + role.name() + " 已由 " + sameRole.get().reviewer() + " 签字，唯一有效");
            }
            if (existing.stream().anyMatch(r -> r.reviewer().equals(request.reviewer()))) {
                throw new UnprocessableException("双岗复核须由两名不同人员完成，" + request.reviewer() + " 已签字");
            }
            LocalDateTime now = LocalDateTime.now(clock);
            try {
                repo.insertReview(new ReviewRow(null, row.id(), request.requestId(),
                        fingerprint(request.requestId(), request), role.name(), request.reviewer(), now));
            } catch (DuplicateKeyException e) {
                throw new ConflictException("岗位 " + role.name() + " 并发签字冲突：每岗位唯一一笔生效");
            }

            List<ReviewRow> after = repo.findReviews(row.id());
            if (after.size() == ReviewRole.values().length) {
                // 两个不同岗位均签字：复核通过，舱单冻结。
                repo.updateStatus(row.id(), ManifestStatus.REVIEWED.name(), null);
            }
            return toResponse(repo.findManifestById(row.id()).orElseThrow());
        });
    }

    // ----------------------------------------------------------------- 关闭

    @Override
    @Transactional
    public ManifestResponse close(long flightId, CloseRequest request) {
        return withIdempotency(request.requestId(), request, 200, () -> {
            ActiveCtx ctx = requireActive(flightId);
            ManifestRow row = ctx.row();
            if (ManifestStatus.CLOSED.name().equals(row.status())) {
                // 终态：重复关闭幂等成功。
                return toResponse(row);
            }
            if (!ManifestStatus.REVIEWED.name().equals(row.status())) {
                throw new ConflictException("双岗复核通过后方可放行关闭，当前状态 " + row.status());
            }
            LocalDateTime now = LocalDateTime.now(clock);
            repo.updateStatus(row.id(), ManifestStatus.CLOSED.name(), now);
            return toResponse(repo.findManifestById(row.id()).orElseThrow());
        });
    }

    // ------------------------------------------------------------- 迟到值机

    @Override
    @Transactional
    public ManifestResponse recordLateCheckin(long flightId, LateCheckinRequest request) {
        return withIdempotency(request.requestId(), request, 200, () -> {
            ActiveCtx ctx = requireActive(flightId);
            ManifestRow row = ctx.row();
            if (!ManifestStatus.CLOSED.name().equals(row.status())) {
                throw new ConflictException("舱单未关闭，迟到值机登记仅在放行关闭后受理，当前状态 " + row.status());
            }
            LocalDateTime now = LocalDateTime.now(clock);
            repo.insertLateEvent(new LateEventRow(
                    null, flightId, row.id(), request.passengerCount(), request.weightKg(),
                    safeJson(request), true, now));
            log.warn("ALERT 航班 {} 关闭后收到迟到值机变化：+{} 人 +{}kg，舱单 {} 不再修改",
                    flightId, request.passengerCount(), request.weightKg(), row.id());
            return toResponse(repo.findManifestById(row.id()).orElseThrow());
        });
    }

    @Override
    @Transactional(readOnly = true)
    public ManifestResponse getActive(long flightId) {
        return toResponse(requireActive(flightId).row());
    }

    @Override
    @Transactional(readOnly = true)
    public List<ManifestResponse> listVersions(long flightId) {
        requireFlight(flightId);
        return repo.findVersionsByFlightId(flightId).stream().map(this::toResponse).toList();
    }

    // ================================================================ 内部

    private record ActiveCtx(ManifestRow row, Aircraft aircraft, Flight flight) {
    }

    private ActiveCtx requireActive(long flightId) {
        Flight flight = requireFlight(flightId);
        Aircraft ac = requireAircraft(flight.aircraftId());
        ManifestRow row = repo.findActiveByFlightIdForUpdate(flightId)
                .orElseThrow(() -> new NotFoundException("航班 " + flightId + " 不存在有效舱单版本"));
        return new ActiveCtx(row, ac, flight);
    }

    private Flight requireFlight(long flightId) {
        Flight flight = repo.findFlightById(flightId);
        if (flight == null) {
            throw new NotFoundException("航班 " + flightId + " 不存在");
        }
        return flight;
    }

    private Aircraft requireAircraft(long aircraftId) {
        Aircraft ac = repo.findAircraftById(aircraftId);
        if (ac == null) {
            throw new NotFoundException("飞机 " + aircraftId + " 不存在");
        }
        return ac;
    }

    private static void requireStatus(ManifestRow row, ManifestStatus expected, String action) {
        if (!expected.name().equals(row.status())) {
            throw new ConflictException(action + "要求舱单处于 " + expected + "，当前状态 " + row.status());
        }
    }

    private static void ensureWithinLimits(WeightBalanceResult wb) {
        if (!wb.withinLimits()) {
            throw new UnprocessableException("载重平衡超出限制：" + String.join("；", wb.violations()));
        }
    }

    private ReviewRole parseRole(String role) {
        try {
            return ReviewRole.valueOf(role);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("未知复核岗位 " + role + "，允许 LOAD_CONTROLLER/FLIGHT_DISPATCHER");
        }
    }

    private void validateLmcStations(Aircraft ac, LmcRequest request) {
        stationArm(ac, request.paxStationCode());
        stationArm(ac, request.weightStationCode());
        try {
            LoadCategory cat = LoadCategory.valueOf(request.weightCategory());
            if (cat == LoadCategory.PASSENGER) {
                throw new BadRequestException("LMC 重量增量类别不能为 PASSENGER，旅客变化使用 passengerDelta");
            }
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("未知业载类别 " + request.weightCategory());
        }
    }

    /** 将 DTO 明细逐条转为持久化明细；totalWeightKg = pieces * unitWeightKg 严格成立。 */
    private List<LoadDetailRow> toDetails(List<LoadItem> items, Aircraft ac, int seqStart) {
        List<LoadDetailRow> rows = new ArrayList<>();
        int seq = seqStart;
        for (LoadItem item : items) {
            LoadCategory cat = parseCategory(item.category());
            stationArm(ac, item.stationCode());
            if (item.unitWeightKg() == null || item.unitWeightKg().signum() < 0 || item.pieces() < 0) {
                throw new BadRequestException("件数不得为负，单位重量不能为空或负数");
            }
            BigDecimal total = item.unitWeightKg().multiply(BigDecimal.valueOf(item.pieces()))
                    .setScale(2, java.math.RoundingMode.HALF_UP);
            rows.add(new LoadDetailRow(null, 0, cat.name(), item.stationCode(),
                    item.pieces(), item.unitWeightKg(), total, seq++));
        }
        return rows;
    }

    /** LMC 增量明细：旅客按人*单重，货物以 1 件承载增量重量，total=pieces*unit 恒成立。 */
    private List<LoadDetailRow> lmcDeltaDetails(LmcRequest req, Aircraft ac, int seqStart) {
        List<LoadDetailRow> rows = new ArrayList<>();
        int seq = seqStart;
        if (req.passengerDelta() != 0) {
            BigDecimal w = req.paxUnitWeightKg().multiply(BigDecimal.valueOf(req.passengerDelta())).setScale(2);
            rows.add(new LoadDetailRow(null, 0, LoadCategory.PASSENGER.name(),
                    req.paxStationCode(), req.passengerDelta(), req.paxUnitWeightKg(), w, seq++));
        }
        if (req.weightDeltaKg().signum() != 0) {
            rows.add(new LoadDetailRow(null, 0, LoadCategory.valueOf(req.weightCategory()).name(),
                    req.weightStationCode(), 1, req.weightDeltaKg().setScale(2),
                    req.weightDeltaKg().setScale(2), seq++));
        }
        return rows;
    }

    private LoadCategory parseCategory(String category) {
        try {
            return LoadCategory.valueOf(category);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("未知业载类别 " + category);
        }
    }

    private static BigDecimal stationArm(Aircraft ac, String stationCode) {
        return ac.stations().stream()
                .filter(s -> s.stationCode().equals(stationCode))
                .map(LoadStation::arm)
                .findFirst()
                .orElseThrow(() -> new BadRequestException("飞机 " + ac.tailNo() + " 不存在站位 " + stationCode));
    }

    private List<CalcLoadItem> toCalcItems(List<LoadDetailRow> details, Aircraft ac) {
        // 相同（类别,站位）多行（LMC 增量链）在计算时按总重聚合，力臂取站位主数据。
        Map<String, BigDecimal> weightByKey = new LinkedHashMap<>();
        Map<String, Integer> piecesByKey = new LinkedHashMap<>();
        for (LoadDetailRow d : details) {
            String key = d.category() + "|" + d.stationCode();
            weightByKey.merge(key, d.totalWeightKg(), BigDecimal::add);
            piecesByKey.merge(key, d.pieces(), Integer::sum);
        }
        List<CalcLoadItem> items = new ArrayList<>();
        weightByKey.forEach((key, weight) -> {
            String[] parts = key.split("\\|", 2);
            items.add(new CalcLoadItem(parts[0], parts[1], piecesByKey.get(key),
                    weight.setScale(2, java.math.RoundingMode.HALF_UP), stationArm(ac, parts[1])));
        });
        return items;
    }

    private List<LoadDetailRow> withManifestId(List<LoadDetailRow> rows, long manifestId) {
        return rows.stream()
                .map(r -> new LoadDetailRow(r.id(), manifestId, r.category(), r.stationCode(),
                        r.pieces(), r.unitWeightKg(), r.totalWeightKg(), r.seqNo()))
                .toList();
    }

    private ManifestRow copyRow(ManifestRow r, WeightBalanceResult wb, String status,
                                int lmcPax, BigDecimal lmcWeight, LocalDateTime now) {
        return new ManifestRow(r.id(), r.flightId(), r.versionNo(), r.parentVersionId(), r.requestId(),
                r.fingerprint(), status, wb.passengerCount(), wb.payloadKg(), wb.fuelKg(), wb.zfwKg(),
                wb.towKg(), wb.cgZfwPct(), wb.cgTowPct(), true, lmcPax, lmcWeight,
                r.activeFlag(), r.createdBy(), r.createdAt(), now, r.closedAt());
    }

    private long insertManifestActive(ManifestRow row) {
        try {
            return repo.insertManifest(row);
        } catch (DuplicateKeyException e) {
            Optional<ManifestRow> active = repo.findActiveByFlightId(row.flightId());
            if (active.isPresent()) {
                throw new ConflictException("航班 " + row.flightId() + " 已存在有效舱单版本（并发创建唯一成功）");
            }
            throw new ConflictException("requestId 已用于不同请求");
        }
    }

    /** 旧版作废后插入新版本；(flight_id, active_flag) 唯一约束保证同时刻仅一个有效版本。 */
    private long reprintActive(ManifestRow oldRow, ManifestRow newRow) {
        repo.supersede(oldRow.id());
        try {
            return repo.insertManifest(newRow);
        } catch (DuplicateKeyException e) {
            throw new ConflictException("重打新版本冲突，航班仅允许一个有效版本");
        }
    }

    private String fingerprint(String requestId, Object payload) {
        return FingerprintUtil.sha256(requestId, payload, objectMapper);
    }

    /**
     * requestId 幂等包装：先查幂等表，同键同参回放首次结果（成功或失败），同键异参 409；
     * 首次结果（含业务失败）以独立事务落快照，业务事务回滚不影响幂等记录。
     */
    private ManifestResponse withIdempotency(String requestId, Object payload, int successStatus,
                                             java.util.function.Supplier<ManifestResponse> action) {
        String fp = fingerprint(requestId, payload);
        Optional<IdempotentRow> existing = idempotencyStore.find(requestId);
        if (existing.isPresent()) {
            return replay(existing.get(), fp, requestId);
        }
        ManifestResponse response;
        try {
            response = action.get();
        } catch (DuplicateKeyException dke) {
            // 并发同键：唯一约束决胜，转为回放或冲突。
            Optional<IdempotentRow> raced = idempotencyStore.find(requestId);
            if (raced.isPresent() && raced.get().fingerprint().equals(fp)) {
                return replay(raced.get(), fp, requestId);
            }
            ConflictException ce = new ConflictException(
                    "相同请求并发处理，唯一一笔生效，requestId=" + requestId);
            rememberFailure(requestId, fp, ce);
            throw ce;
        } catch (RuntimeException ex) {
            rememberFailure(requestId, fp, ex);
            throw ex;
        }
        remember(requestId, fp, successStatus, safeJson(response));
        return response;
    }

    private void rememberFailure(String requestId, String fp, RuntimeException ex) {
        int status = httpStatusOf(ex);
        if (status == 0) {
            // 非可预期业务错误，不缓存
            return;
        }
        String body = safeJson(Map.of("error", errorName(status), "message", String.valueOf(ex.getMessage())));
        remember(requestId, fp, status, body);
    }

    private void remember(String requestId, String fp, int status, String body) {
        try {
            idempotencyStore.insert(new IdempotentRow(requestId, fp, status, body,
                    LocalDateTime.now(clock)));
        } catch (DuplicateKeyException e) {
            // 并发下另一线程已记录，交由调用前的查询逻辑在重试时回放。
        }
    }

    private ManifestResponse replay(IdempotentRow row, String currentFp, String requestId) {
        if (!row.fingerprint().equals(currentFp)) {
            throw new ConflictException("requestId " + requestId
                    + " 已用于不同业务参数（指纹不一致），拒绝产生新版本");
        }
        if (row.httpStatus() == 200 || row.httpStatus() == 201) {
            try {
                return objectMapper.readValue(row.resultJson(), ManifestResponse.class);
            } catch (Exception e) {
                throw new IllegalStateException("回放首次结果失败", e);
            }
        }
        String message = row.resultJson();
        try {
            message = objectMapper.readTree(row.resultJson()).path("message").asText(row.resultJson());
        } catch (Exception ignored) {
            // 保留原文
        }
        throw replayException(row.httpStatus(), message);
    }

    private static int httpStatusOf(RuntimeException ex) {
        if (ex instanceof NotFoundException) {
            return 404;
        }
        if (ex instanceof ConflictException) {
            return 409;
        }
        if (ex instanceof UnprocessableException) {
            return 422;
        }
        if (ex instanceof BadRequestException) {
            return 400;
        }
        return 0;
    }

    private static String errorName(int status) {
        return switch (status) {
            case 400 -> "Bad Request";
            case 404 -> "Not Found";
            case 409 -> "Conflict";
            case 422 -> "Unprocessable Entity";
            default -> "Error";
        };
    }

    private static RuntimeException replayException(int status, String message) {
        return switch (status) {
            case 400 -> new BadRequestException(message);
            case 404 -> new NotFoundException(message);
            case 409 -> new ConflictException(message);
            case 422 -> new UnprocessableException(message);
            default -> new UnprocessableException(message);
        };
    }

    private String safeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    /** 组装响应；重量/重心由明细与油量重新计算，验证可复算，violations 随之得出。 */
    private ManifestResponse toResponse(ManifestRow row) {
        Flight flight = requireFlight(row.flightId());
        Aircraft ac = requireAircraft(flight.aircraftId());
        List<LoadDetailRow> details = repo.findDetails(row.id());
        WeightBalanceResult recomputed = WeightBalanceCalculator.calculate(ac, toCalcItems(details, ac), row.fuelKg());
        List<ReviewRow> reviews = repo.findReviews(row.id());
        List<LateEventRow> lateEvents = repo.findLateEvents(row.flightId());
        Long supersededBy = null;
        if (ManifestStatus.SUPERSEDED.name().equals(row.status())) {
            supersededBy = repo.findVersionsByFlightId(row.flightId()).stream()
                    .filter(v -> row.id().equals(v.parentVersionId()))
                    .map(ManifestRow::id)
                    .findFirst().orElse(null);
        }
        boolean frozen = ManifestStatus.REVIEWED.name().equals(row.status())
                || ManifestStatus.CLOSED.name().equals(row.status())
                || ManifestStatus.SUPERSEDED.name().equals(row.status());
        List<LateAlert> alerts = lateEvents.stream()
                .map(e -> new LateAlert(e.id(), e.passengerCount(), e.weightKg(),
                        e.payloadJson(), e.alerted(), e.createdAt()))
                .toList();
        List<String> roles = reviews.stream().map(ReviewRow::roleCode).toList();
        return new ManifestResponse(
                row.id(), row.flightId(), row.versionNo(), row.parentVersionId(), row.requestId(),
                row.status(), recomputed.passengerCount(), recomputed.payloadKg(), recomputed.fuelKg(),
                recomputed.zfwKg(), recomputed.towKg(), recomputed.cgZfwPct(), recomputed.cgTowPct(),
                recomputed.withinLimits(), recomputed.violations(),
                row.lmcPaxDelta(), row.lmcWeightDelta(),
                Math.abs(row.lmcPaxDelta()) <= LMC_PAX_LIMIT
                        && row.lmcWeightDelta().abs().compareTo(LMC_WEIGHT_LIMIT) <= 0,
                supersededBy, details, roles, frozen, row.closedAt(), alerts);
    }
}
