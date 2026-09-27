package com.example.starter.wb.service;

import com.example.starter.wb.calc.CalcLoadItem;
import com.example.starter.wb.calc.WeightBalanceCalculator;
import com.example.starter.wb.calc.WeightBalanceResult;
import com.example.starter.wb.domain.Aircraft;
import com.example.starter.wb.domain.CargoItemRow;
import com.example.starter.wb.domain.CargoOccupancyRow;
import com.example.starter.wb.domain.CargoPlanRow;
import com.example.starter.wb.domain.CompartmentRow;
import com.example.starter.wb.domain.Flight;
import com.example.starter.wb.domain.IdempotentRow;
import com.example.starter.wb.domain.LoadDetailRow;
import com.example.starter.wb.domain.ManifestRow;
import com.example.starter.wb.domain.ReviewRow;
import com.example.starter.wb.dto.CargoPlanResponse;
import com.example.starter.wb.dto.CargoPlanSubmitRequest;
import com.example.starter.wb.dto.UldItemInput;
import com.example.starter.wb.dto.UldItemView;
import com.example.starter.wb.enums.ManifestStatus;
import com.example.starter.wb.exception.BadRequestException;
import com.example.starter.wb.exception.ConflictException;
import com.example.starter.wb.exception.NotFoundException;
import com.example.starter.wb.exception.UnprocessableException;
import com.example.starter.wb.repo.WeightBalanceRepository;
import com.example.starter.wb.support.FingerprintUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 集装器装配图服务。
 *
 * <p>装配图挂具体舱单版本：每次成功提交写一张新图（图版本自 1 起，+1），
 * 同事务原子释放该航班旧实物占用并按新图占用、删除旧双岗复核；
 * 力矩按“含皮重总重 × 整数毫米力臂”精确求和（kg·mm），换算英寸后交由既有重心包线规则校验，
 * 任一违例整版拒绝。同器跨未关闭航班占用由 (uld_no, active_flag) 唯一约束决胜。
 *
 * <p>幂等采用“业务与幂等结果同事务”：失败随业务事务回滚、不占键；同键同参回放首次冻结结果，
 * 同键异参 409。
 */
@Service
public class CargoPlanServiceImpl implements CargoPlanService {

    /** 毫米→英寸 25.4 = 127/5，精确换算。 */
    private static final BigDecimal MM_PER_INCH_NUM = BigDecimal.valueOf(5);
    private static final BigDecimal MM_PER_INCH_DEN = BigDecimal.valueOf(127);

    private final WeightBalanceRepository repo;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public CargoPlanServiceImpl(WeightBalanceRepository repo, ObjectMapper objectMapper, Clock clock) {
        this.repo = repo;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    @Transactional
    public CargoPlanResponse submitPlan(long flightId, CargoPlanSubmitRequest request) {
        String fp = fingerprint(request.requestId(), request);
        Optional<IdempotentRow> existing = repo.findIdempotent(request.requestId());
        if (existing.isPresent()) {
            return replay(existing.get(), fp, request.requestId());
        }

        Flight flight = requireFlight(flightId);
        Aircraft ac = requireAircraft(flight.aircraftId());
        // 舱单版本行加锁：同航班并发改图在此串行，配合 expectedVersion 实现最多一方成功。
        ManifestRow manifest = repo.findActiveByFlightIdForUpdate(flightId)
                .orElseThrow(() -> new NotFoundException("航班 " + flightId + " 不存在有效舱单版本，无法提交装配图"));
        // 持锁后再次确认幂等：并发同键请求中败者回放胜者已冻结的首次结果。
        Optional<IdempotentRow> raced = repo.findIdempotent(request.requestId());
        if (raced.isPresent()) {
            return replay(raced.get(), fp, request.requestId());
        }

        if (ManifestStatus.CLOSED.name().equals(manifest.status())) {
            throw new ConflictException("舱单已放行关闭，装配图历史只读，不能改图");
        }
        if (ManifestStatus.PRELIMINARY.name().equals(manifest.status())) {
            throw new ConflictException("预配阶段货运业载尚未确定，请先值机再提交装配图");
        }

        int currentVersion = repo.findPlansByFlight(flightId).stream()
                .mapToInt(CargoPlanRow::planVersion).max().orElse(0);
        if (request.expectedVersion() != currentVersion) {
            // LMC 重打后旧图只读、图版本仍单调；若图不挂当前舱单须先按新舱单重提，
            // 此处版本冲突先拦截并发改图与过期 expectedVersion。
            throw new ConflictException("装配图版本冲突：expectedVersion=" + request.expectedVersion()
                    + "，当前版本=" + currentVersion);
        }

        Map<String, CompartmentRow> compartments = loadCompartments(ac);
        List<CargoItemRow> items = validateItems(request, compartments);

        int netTotal = items.stream().mapToInt(CargoItemRow::netKg).sum();
        int grossTotal = items.stream().mapToInt(CargoItemRow::grossKg).sum();
        long momentKgMm = items.stream()
                .mapToLong(it -> (long) it.grossKg() * it.armMm())
                .sum();

        // 装配图总货重（含皮重）必须等于该舱单版本的非旅客货运业载（行李/货物/邮件）。
        BigDecimal cargoPayload = repo.findDetails(manifest.id()).stream()
                .filter(d -> !"PASSENGER".equals(d.category()))
                .map(LoadDetailRow::totalWeightKg)
                .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
        if (cargoPayload.compareTo(BigDecimal.valueOf(grossTotal)) != 0) {
            throw new UnprocessableException("装配图总货重（含皮重）" + grossTotal
                    + "kg 与舱单版本 v" + manifest.versionNo() + " 货运业载 " + cargoPayload
                    + "kg 不一致，整版拒绝");
        }

        WeightBalanceResult wb = recalculate(ac, manifest, items);
        if (!wb.withinLimits()) {
            throw new UnprocessableException("按装配图力矩重算载重平衡超限，整版拒绝："
                    + String.join("；", wb.violations()));
        }

        // 实物占用：同器不得跨未关闭航班占用；本航班旧占用原子迁移到新图。
        List<String> uldNos = items.stream().map(CargoItemRow::uldNo).toList();
        List<CargoOccupancyRow> foreign = repo.findActiveOccupanciesByUldNos(uldNos).stream()
                .filter(o -> o.flightId() != flightId)
                .toList();
        if (!foreign.isEmpty()) {
            CargoOccupancyRow o = foreign.get(0);
            throw new ConflictException("集装器 " + o.uldNo() + " 已被未关闭航班 " + o.flightId()
                    + " 占用，本航班不能占用");
        }

        int newPlanVersion = currentVersion + 1;
        LocalDateTime now = LocalDateTime.now(clock);
        CargoPlanRow plan = new CargoPlanRow(
                null, flightId, manifest.id(), manifest.versionNo(), newPlanVersion, request.requestId(), fp,
                netTotal, grossTotal, momentKgMm, manifest.fuelKg(),
                manifest.lmcPaxDelta(), manifest.lmcWeightDelta(),
                wb.cgZfwPct(), wb.cgTowPct(), true,
                request.createdBy(), request.occurredAt(), now);

        long planId;
        try {
            // 原子：写新图 → 释放本航班旧占用 → 按新图占用 → 更新货运力矩/重心 → 旧双岗复核失效。
            repo.releaseOccupanciesByFlight(flightId, now);
            planId = repo.insertPlan(plan);
            repo.insertPlanItems(planId, withPlanId(items, planId));
            for (CargoItemRow it : items) {
                repo.insertOccupancy(new CargoOccupancyRow(
                        null, it.uldNo(), flightId, manifest.id(), planId, 1L, now, null));
            }
            // 货运力矩改由装配图舱位承载：同事务把按图重算的重量/重心写回舱单版本。
            String newStatus = ManifestStatus.REVIEWED.name().equals(manifest.status())
                    ? ManifestStatus.LOADED.name() : manifest.status();
            repo.updateComputed(new ManifestRow(
                    manifest.id(), manifest.flightId(), manifest.versionNo(), manifest.parentVersionId(),
                    manifest.requestId(), manifest.fingerprint(), newStatus,
                    wb.passengerCount(), wb.payloadKg(), wb.fuelKg(), wb.zfwKg(), wb.towKg(),
                    wb.cgZfwPct(), wb.cgTowPct(), true,
                    manifest.lmcPaxDelta(), manifest.lmcWeightDelta(), manifest.activeFlag(),
                    manifest.createdBy(), manifest.createdAt(), now, manifest.closedAt()));
            // 旧双岗复核失效；REVIEWED 改图后已在 updateComputed 中回到 LOADED，复核须重来。
            repo.deleteReviewsByManifest(manifest.id());
        } catch (DuplicateKeyException e) {
            throw new ConflictException("集装器并发占用或请求重复，仅一方成功：" + e.getMostSpecificCause().getMessage());
        }

        ManifestRow refreshed = repo.findManifestById(manifest.id()).orElseThrow();
        CargoPlanResponse response = toResponse(repo.findPlanById(planId).orElseThrow(), refreshed, ac, flight);
        try {
            remember(request.requestId(), fp, safeJson(response), now);
        } catch (DuplicateKeyException e) {
            // 跨航班并发同键：另一事务已冻结首次结果，回放之。
            return repo.findIdempotent(request.requestId())
                    .map(r -> replay(r, fp, request.requestId()))
                    .orElseThrow(() -> new ConflictException("相同请求并发处理，仅一笔生效"));
        }
        return response;
    }

    @Override
    @Transactional(readOnly = true)
    public CargoPlanResponse getActivePlan(long flightId) {
        Flight flight = requireFlight(flightId);
        Aircraft ac = requireAircraft(flight.aircraftId());
        ManifestRow active = repo.findActiveByFlightId(flightId)
                .orElseThrow(() -> new NotFoundException("航班 " + flightId + " 不存在有效舱单版本"));
        CargoPlanRow latest = repo.findPlansByFlight(flightId).stream()
                .reduce((a, b) -> b)
                .orElseThrow(() -> new NotFoundException("航班 " + flightId + " 尚未提交集装器装配图"));
        return toResponse(latest, active, ac, flight);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CargoPlanResponse> listPlanHistory(long flightId) {
        Flight flight = requireFlight(flightId);
        Aircraft ac = requireAircraft(flight.aircraftId());
        Map<Long, ManifestRow> owners = new LinkedHashMap<>();
        for (CargoPlanRow p : repo.findPlansByFlight(flightId)) {
            owners.computeIfAbsent(p.manifestId(),
                    mid -> repo.findManifestById(mid).orElse(null));
        }
        return repo.findPlansByFlight(flightId).stream()
                .map(p -> toResponse(p, owners.get(p.manifestId()), ac, flight))
                .toList();
    }

    /**
     * 关闭放行前由载重平衡服务调用：启用装配控制（存在任何图）的航班，
     * 最新图必须挂当前有效舱单版本、总货重仍等于该版本货运业载且重心合法，
     * 通过后在关闭同事务原子释放全部实物占用。
     */
    @Override
    public void verifyAndReleaseOnClose(Aircraft aircraft, ManifestRow manifest, LocalDateTime now) {
        // 未启用装配控制的舱单 validate 内直接放行；启用时校验版本/货重/重心，任一不符抛 409。
        validateLatestPlanMatches(aircraft, manifest, "关闭放行被拒");
        if (!repo.findPlansByFlight(manifest.flightId()).isEmpty()) {
            repo.releaseOccupanciesByFlight(manifest.flightId(), now);
        }
    }

    @Override
    public void assertReviewable(Aircraft aircraft, ManifestRow manifest) {
        validateLatestPlanMatches(aircraft, manifest, "双岗复核被拒");
    }

    @Override
    public Optional<WeightBalanceResult> physicalResultIfPlanMatches(Aircraft aircraft, ManifestRow manifest) {
        List<CargoPlanRow> flightPlans = repo.findPlansByFlight(manifest.flightId());
        if (flightPlans.isEmpty()) {
            return Optional.empty();
        }
        CargoPlanRow latest = flightPlans.stream().reduce((a, b) -> b).orElseThrow();
        if (latest.manifestId() != manifest.id()
                || latest.fuelKg().compareTo(manifest.fuelKg()) != 0
                || latest.lmcPaxDelta() != manifest.lmcPaxDelta()
                || latest.lmcWeightDelta().compareTo(manifest.lmcWeightDelta()) != 0) {
            return Optional.empty();
        }
        return Optional.of(recalculate(aircraft, manifest, repo.findPlanItems(latest.id())));
    }

    /** 启用装配控制时，最新图必须挂当前舱单版本，且总货重与按图重心仍然成立。 */
    private void validateLatestPlanMatches(Aircraft aircraft, ManifestRow manifest, String prefix) {
        List<CargoPlanRow> flightPlans = repo.findPlansByFlight(manifest.flightId());
        if (flightPlans.isEmpty()) {
            return; // 未启用装配控制的既有舱单：兼容旧流程
        }
        CargoPlanRow latest = flightPlans.stream().reduce((a, b) -> b).orElseThrow();
        if (latest.manifestId() != manifest.id()) {
            throw new ConflictException(prefix + "：最新装配图 v" + latest.planVersion()
                    + " 挂舱单版本 id=" + latest.manifestId() + "，与当前有效舱单 id=" + manifest.id()
                    + " 不匹配；LMC/加油后装配图须重新提交，复核必须对应最新舱单");
        }
        // 加油或 LMC（同版累计增量变化）后，未变化装配图仍可追溯但不再对应当前舱单。
        if (latest.fuelKg().compareTo(manifest.fuelKg()) != 0
                || latest.lmcPaxDelta() != manifest.lmcPaxDelta()
                || latest.lmcWeightDelta().compareTo(manifest.lmcWeightDelta()) != 0) {
            throw new ConflictException(prefix + "：装配图 v" + latest.planVersion()
                    + " 提交后舱单发生加油/LMC 变更，未变化装配图不再对应当前舱单，请重新提交装配图");
        }
        List<CargoItemRow> items = repo.findPlanItems(latest.id());
        BigDecimal cargoPayload = repo.findDetails(manifest.id()).stream()
                .filter(d -> !"PASSENGER".equals(d.category()))
                .map(LoadDetailRow::totalWeightKg)
                .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
        if (cargoPayload.compareTo(BigDecimal.valueOf(latest.cargoGrossKg())) != 0) {
            throw new ConflictException(prefix + "：装配图总货重 " + latest.cargoGrossKg()
                    + "kg 与当前舱单货运业载 " + cargoPayload + "kg 不一致");
        }
        WeightBalanceResult wb = recalculate(aircraft, manifest, items);
        if (!wb.withinLimits()) {
            throw new ConflictException(prefix + "：按最新装配图重算重心超限："
                    + String.join("；", wb.violations()));
        }
    }

    // ================================================================ 内部

    private Map<String, CompartmentRow> loadCompartments(Aircraft ac) {
        Map<String, CompartmentRow> map = new LinkedHashMap<>();
        for (CompartmentRow c : repo.findCompartments(ac.id())) {
            map.put(c.compartmentCode(), c);
        }
        return map;
    }

    private List<CargoItemRow> validateItems(CargoPlanSubmitRequest req, Map<String, CompartmentRow> compartments) {
        Set<String> seenUld = new HashSet<>();
        Set<String> seenCompartment = new HashSet<>();
        List<CargoItemRow> rows = new ArrayList<>();
        int seq = 0;
        for (UldItemInput in : req.items()) {
            String uldNo = in.uldNo().trim();
            String code = in.compartmentCode().trim();
            if (uldNo.length() < 3) {
                throw new BadRequestException("集装器编号 " + uldNo + " 非法：至少 3 位且前 3 位为类型代码");
            }
            CompartmentRow compartment = compartments.get(code);
            if (compartment == null) {
                throw new BadRequestException("飞机不存在舱位 " + code);
            }
            String type = uldNo.substring(0, 3);
            if (!compartment.allowedUldTypes().contains(type)) {
                throw new UnprocessableException("舱位 " + code + " 不允许集装器类型 " + type
                        + "（允许 " + compartment.allowedUldTypes() + "），整版拒绝");
            }
            if (!seenUld.add(uldNo)) {
                throw new UnprocessableException("集装器编号 " + uldNo + " 在同一装配图内重复，整版拒绝");
            }
            if (!seenCompartment.add(code)) {
                throw new UnprocessableException("舱位 " + code + " 已配置一个集装器，同舱位最多一器，整版拒绝");
            }
            int gross = in.tareKg() + in.netKg();
            if (gross > compartment.maxLoadKg()) {
                throw new UnprocessableException("集装器 " + uldNo + " 含皮重总重 " + gross
                        + "kg 超过舱位 " + code + " 限额 " + compartment.maxLoadKg()
                        + "kg（恰达合法），整版拒绝");
            }
            rows.add(new CargoItemRow(null, 0, seq++, uldNo, code,
                    compartment.armMm(), in.tareKg(), in.netKg(), gross));
        }
        return rows;
    }

    /** 旅客仍按舱单明细站位，货运全部以装配图舱位整数毫米力臂（换算英寸）承载，交既有计算器校验。 */
    private WeightBalanceResult recalculate(Aircraft ac, ManifestRow manifest, List<CargoItemRow> items) {
        List<CalcLoadItem> calc = new ArrayList<>();
        Map<String, BigDecimal> paxWeight = new LinkedHashMap<>();
        Map<String, Integer> paxPieces = new LinkedHashMap<>();
        for (LoadDetailRow d : repo.findDetails(manifest.id())) {
            if (!"PASSENGER".equals(d.category())) {
                continue;
            }
            paxWeight.merge(d.stationCode(), d.totalWeightKg(), BigDecimal::add);
            paxPieces.merge(d.stationCode(), d.pieces(), Integer::sum);
        }
        paxWeight.forEach((station, weight) -> calc.add(new CalcLoadItem(
                "PASSENGER", station, paxPieces.get(station), weight.setScale(2, RoundingMode.HALF_UP),
                ac.stations().stream().filter(s -> s.stationCode().equals(station))
                        .findFirst().orElseThrow(() -> new BadRequestException("站位 " + station + " 不存在"))
                        .arm())));
        Map<String, Integer> grossByCompartment = new LinkedHashMap<>();
        for (CargoItemRow it : items) {
            grossByCompartment.merge(it.compartmentCode(), it.grossKg(), Integer::sum);
        }
        for (CargoItemRow it : items) {
            if (!grossByCompartment.containsKey(it.compartmentCode())) {
                continue;
            }
            int gross = grossByCompartment.remove(it.compartmentCode());
            BigDecimal armInch = BigDecimal.valueOf(it.armMm()).multiply(MM_PER_INCH_NUM)
                    .divide(MM_PER_INCH_DEN, 6, RoundingMode.HALF_UP);
            calc.add(new CalcLoadItem("CARGO", it.compartmentCode(), 1,
                    BigDecimal.valueOf(gross).setScale(2, RoundingMode.HALF_UP), armInch));
        }
        return WeightBalanceCalculator.calculate(ac, calc, manifest.fuelKg());
    }

    private List<CargoItemRow> withPlanId(List<CargoItemRow> rows, long planId) {
        return rows.stream()
                .map(r -> new CargoItemRow(r.id(), planId, r.seqNo(), r.uldNo(), r.compartmentCode(),
                        r.armMm(), r.tareKg(), r.netKg(), r.grossKg()))
                .toList();
    }

    private CargoPlanResponse toResponse(CargoPlanRow plan, ManifestRow ownerManifest,
                                         Aircraft ac, Flight flight) {
        List<UldItemView> views = repo.findPlanItems(plan.id()).stream()
                .map(i -> new UldItemView(i.seqNo(), i.uldNo(), i.compartmentCode(),
                        i.armMm(), i.tareKg(), i.netKg(), i.grossKg()))
                .toList();
        List<String> roles = ownerManifest == null ? List.of()
                : repo.findReviews(ownerManifest.id()).stream().map(ReviewRow::roleCode).toList();
        ManifestRow active = repo.findActiveByFlightId(flight.id()).orElse(null);
        boolean match = active != null && plan.manifestId() == active.id()
                && plan.fuelKg().compareTo(active.fuelKg()) == 0
                && plan.lmcPaxDelta() == active.lmcPaxDelta()
                && plan.lmcWeightDelta().compareTo(active.lmcWeightDelta()) == 0;
        String status = ownerManifest == null ? "SUPERSEDED" : ownerManifest.status();
        return new CargoPlanResponse(
                plan.manifestId(), flight.id(), plan.manifestVersionNo(), plan.planVersion(),
                plan.requestId(), status, plan.cargoNetKg(), plan.cargoGrossKg(),
                plan.totalMomentKgMm(), plan.cgZfwPct(), plan.cgTowPct(), plan.withinLimits(),
                match, views, roles, plan.occurredAt(), plan.createdAt());
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

    private String fingerprint(String requestId, Object payload) {
        return FingerprintUtil.sha256(requestId, payload, objectMapper);
    }

    /** 成功结果与业务同事务落幂等表；业务失败随事务回滚，因此不占键。 */
    private void remember(String requestId, String fp, String body, LocalDateTime now) {
        repo.insertIdempotent(new IdempotentRow(requestId, fp, 200, body, now));
    }

    private CargoPlanResponse replay(IdempotentRow row, String currentFp, String requestId) {
        if (!row.fingerprint().equals(currentFp)) {
            throw new ConflictException("requestId " + requestId
                    + " 已用于不同业务参数（指纹不一致），拒绝改图");
        }
        if (row.httpStatus() != 200) {
            // 历史上若缓存过失败结果，按首次语义回放（当前实现失败不占键，正常不会出现）。
            throw new UnprocessableException("装配图首次请求失败：" + row.resultJson());
        }
        try {
            return objectMapper.readValue(row.resultJson(), CargoPlanResponse.class);
        } catch (Exception e) {
            throw new IllegalStateException("回放首次装配图结果失败", e);
        }
    }

    private String safeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }
}
