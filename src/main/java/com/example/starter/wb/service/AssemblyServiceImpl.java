package com.example.starter.wb.service;

import com.example.starter.wb.calc.CargoPayloadSplit;
import com.example.starter.wb.calc.WeightBalanceCalculator;
import com.example.starter.wb.calc.WeightBalanceResult;
import com.example.starter.wb.domain.Aircraft;
import com.example.starter.wb.domain.AssemblyItemRow;
import com.example.starter.wb.domain.AssemblyPlanRow;
import com.example.starter.wb.domain.CompartmentRow;
import com.example.starter.wb.domain.Flight;
import com.example.starter.wb.domain.IdempotentRow;
import com.example.starter.wb.domain.LoadDetailRow;
import com.example.starter.wb.domain.ManifestRow;
import com.example.starter.wb.domain.UldOccupationRow;
import com.example.starter.wb.dto.AssemblyItemRequest;
import com.example.starter.wb.dto.AssemblyItemView;
import com.example.starter.wb.dto.AssemblyPlanResponse;
import com.example.starter.wb.dto.AssemblySubmitRequest;
import com.example.starter.wb.enums.ManifestStatus;
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
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 集装器装配图服务。
 *
 * <p>装配提交：校验双重期望版本（舱单版本号 + 装配图版本），同图编号唯一、同舱位最多一器、
 * 皮重非负、净重为正、总重含皮重且恰到舱位限额合法；总货重须等于该版本货运业载（CARGO+MAIL），
 * 力矩按总重×整数毫米力臂精确求和后交由既有重心规则校验，任一违例整版拒绝。
 * 成功则原子写新图、更新舱单货运力矩（重心）并使旧双岗复核失效。
 *
 * <p>幂等：requestId 指纹覆盖版本、明细集合与精确时刻；同键同参回放首次冻结结果，异参 409；
 * 业务失败不写幂等记录（失败不占键）；业务、版本与幂等结果同事务提交。
 */
@Service
public class AssemblyServiceImpl implements AssemblyService {

    private static final Logger log = LoggerFactory.getLogger(AssemblyServiceImpl.class);

    private final WeightBalanceRepository repo;
    private final IdempotencyStore idempotencyStore;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AssemblyServiceImpl(WeightBalanceRepository repo, IdempotencyStore idempotencyStore,
                               ObjectMapper objectMapper, Clock clock) {
        this.repo = repo;
        this.idempotencyStore = idempotencyStore;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // --------------------------------------------------------------- 提交

    @Override
    @Transactional
    public AssemblyPlanResponse submit(long flightId, AssemblySubmitRequest request) {
        String fp = FingerprintUtil.sha256(request.requestId(), request, objectMapper);
        Optional<IdempotentRow> existing = idempotencyStore.find(request.requestId());
        if (existing.isPresent()) {
            return replay(existing.get(), fp, request.requestId());
        }
        try {
            AssemblyPlanResponse response = doSubmit(flightId, request, fp);
            // 业务、版本与幂等结果同事务：仅成功结果占键，失败回滚不占键。
            repo.insertIdempotent(new IdempotentRow(request.requestId(), fp, 201,
                    safeJson(response), LocalDateTime.now(clock)));
            return response;
        } catch (DuplicateKeyException dke) {
            // 并发同键/同图版本/同集装器：唯一约束决胜，业务写入随回滚作废。
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            Optional<IdempotentRow> raced = repo.findIdempotent(request.requestId());
            if (raced.isPresent()) {
                return replay(raced.get(), fp, request.requestId());
            }
            throw new ConflictException(
                    "集装器被其他未关闭航班占用或装配版本并发冲突，唯一一笔生效，requestId=" + request.requestId());
        }
    }

    private AssemblyPlanResponse doSubmit(long flightId, AssemblySubmitRequest req, String fp) {
        Flight flight = requireFlight(flightId);
        ManifestRow manifest = repo.findActiveByFlightIdForUpdate(flightId)
                .orElseThrow(() -> new NotFoundException("航班 " + flightId + " 不存在有效舱单版本"));
        // 并发同键：持舱单行锁后同事务复查，赢家已提交则直接回放首次冻结结果。
        Optional<IdempotentRow> raced = repo.findIdempotent(req.requestId());
        if (raced.isPresent()) {
            return replay(raced.get(), fp, req.requestId());
        }
        if (!manifest.assemblyControlled()) {
            throw new UnprocessableException("舱单未启用集装器装配控制，不能提交装配图");
        }
        if (ManifestStatus.CLOSED.name().equals(manifest.status())) {
            throw new ConflictException("舱单已放行关闭，装配图只读，不能再提交");
        }
        if (req.expectedManifestVersion() != manifest.versionNo()) {
            throw new ConflictException("舱单版本已变化：期望 " + req.expectedManifestVersion()
                    + "，当前有效版本 " + manifest.versionNo());
        }
        AssemblyPlanRow current = repo.findActivePlanByFlightIdForUpdate(flightId).orElse(null);
        int currentPlanVersion = current == null ? 0 : current.planVersion();
        if (req.expectedPlanVersion() != currentPlanVersion) {
            throw new ConflictException("装配图版本已变化：期望 " + req.expectedPlanVersion()
                    + "，当前有效装配图版本 " + currentPlanVersion);
        }
        Aircraft ac = requireAircraft(flight.aircraftId());
        Map<String, CompartmentRow> compartments = new HashMap<>();
        for (CompartmentRow c : repo.findCompartments(ac.id())) {
            compartments.put(c.compartmentCode(), c);
        }

        List<LoadDetailRow> details = repo.findDetails(manifest.id());
        BigDecimal cargoPayload = CargoPayloadSplit.cargoPayloadKg(details);
        List<AssemblyItemRow> items = validateAndBuildItems(req, compartments, cargoPayload);

        BigDecimal moment = BigDecimal.ZERO;
        int tareSum = 0;
        int netSum = 0;
        for (AssemblyItemRow item : items) {
            moment = moment.add(item.momentKgMm());
            tareSum += item.tareKg();
            netSum += item.netKg();
        }
        int totalWeight = tareSum + netSum;

        // 力矩按总重×力臂精确求和（kg·mm），交由既有重心规则校验；任一违例整版拒绝。
        WeightBalanceResult wb = WeightBalanceCalculator.calculateWithCargoMoment(
                ac, CargoPayloadSplit.nonCargoItems(details, ac), BigDecimal.valueOf(totalWeight),
                moment, manifest.fuelKg());
        if (!wb.withinLimits()) {
            throw new UnprocessableException("装配图载重平衡超出限制，整版拒绝："
                    + String.join("；", wb.violations()));
        }

        LocalDateTime now = LocalDateTime.now(clock);
        // 原子写新图：旧图作废 → 新图生效 → 明细 → 实物占用换新 → 旧复核失效 → 舱单货运力矩更新。
        if (current != null) {
            repo.supersedePlan(current.id());
        }
        int newPlanVersion = currentPlanVersion + 1;
        AssemblyPlanRow plan = new AssemblyPlanRow(
                null, flightId, manifest.id(), manifest.versionNo(), newPlanVersion,
                req.requestId(), fp, "ACTIVE", tareSum, netSum, totalWeight, moment,
                wb.cgZfwPct(), wb.cgTowPct(), flightId, req.submittedAt(), req.operator(), now);
        long planId = repo.insertPlan(plan);
        repo.insertPlanItems(planId, items);
        repo.deleteOccupationsByFlightId(flightId);
        for (AssemblyItemRow item : items) {
            repo.insertOccupation(new UldOccupationRow(item.uldNumber(), flightId, planId, now));
        }
        // 旧双岗复核失效，不得沿用；已复核冻结的舱单退回装载确认状态等待重新复核。
        repo.deleteReviewsByManifest(manifest.id());
        String newStatus = ManifestStatus.REVIEWED.name().equals(manifest.status())
                ? ManifestStatus.LOADED.name() : manifest.status();
        repo.updateComputed(new ManifestRow(
                manifest.id(), manifest.flightId(), manifest.versionNo(), manifest.parentVersionId(),
                manifest.requestId(), manifest.fingerprint(), newStatus,
                wb.passengerCount(), wb.payloadKg(), wb.fuelKg(), wb.zfwKg(), wb.towKg(),
                wb.cgZfwPct(), wb.cgTowPct(), true, manifest.lmcPaxDelta(), manifest.lmcWeightDelta(),
                manifest.assemblyControlled(), manifest.activeFlag(), manifest.createdBy(),
                manifest.createdAt(), now, manifest.closedAt()));
        log.info("航班 {} 装配图版本 {} 生效：{} 器，总货重 {}kg，货运力矩 {} kg·mm",
                flightId, newPlanVersion, items.size(), totalWeight, moment);
        return toResponse(repo.findPlansByFlightId(flightId).stream()
                .filter(p -> p.planVersion() == newPlanVersion).findFirst().orElseThrow());
    }

    // --------------------------------------------------------------- 查询

    @Override
    @Transactional(readOnly = true)
    public AssemblyPlanResponse getActive(long flightId) {
        requireFlight(flightId);
        return repo.findActivePlanByFlightId(flightId)
                .map(this::toResponse)
                .orElseThrow(() -> new NotFoundException("航班 " + flightId + " 无有效装配图"));
    }

    @Override
    @Transactional(readOnly = true)
    public List<AssemblyPlanResponse> listVersions(long flightId) {
        requireFlight(flightId);
        return repo.findPlansByFlightId(flightId).stream().map(this::toResponse).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UldOccupationRow> getOccupation(String uldNumber) {
        return repo.findOccupation(uldNumber);
    }

    // ============================================================ 内部

    /** 逐项校验并构建装配明细：编号唯一、舱位唯一、类型允许、总重恰达限额合法。 */
    private List<AssemblyItemRow> validateAndBuildItems(AssemblySubmitRequest req,
                                                        Map<String, CompartmentRow> compartments,
                                                        BigDecimal cargoPayload) {
        Set<String> seenUld = new HashSet<>();
        Set<String> seenCompartment = new HashSet<>();
        List<AssemblyItemRow> items = new ArrayList<>();
        int seq = 0;
        int totalWeight = 0;
        for (AssemblyItemRequest item : req.items()) {
            if (item.uldNumber() == null || item.uldNumber().isBlank()
                    || item.uldType() == null || item.uldType().isBlank()
                    || item.compartmentCode() == null || item.compartmentCode().isBlank()) {
                throw new BadRequestException("集装器编号、类型与舱位均不能为空");
            }
            if (item.tareKg() == null || item.tareKg() < 0) {
                throw new BadRequestException("集装器 " + item.uldNumber() + " 皮重不能为空或为负");
            }
            if (item.netKg() == null || item.netKg() <= 0) {
                throw new BadRequestException("集装器 " + item.uldNumber() + " 净重必须为正");
            }
            if (!seenUld.add(item.uldNumber())) {
                throw new BadRequestException("集装器编号 " + item.uldNumber() + " 在同一装配图内重复");
            }
            if (!seenCompartment.add(item.compartmentCode())) {
                throw new UnprocessableException("舱位 " + item.compartmentCode() + " 最多装载一器");
            }
            CompartmentRow comp = compartments.get(item.compartmentCode());
            if (comp == null) {
                throw new BadRequestException("飞机不存在舱位 " + item.compartmentCode());
            }
            if (!comp.allows(item.uldType())) {
                throw new UnprocessableException("舱位 " + item.compartmentCode()
                        + " 不允许集装器类型 " + item.uldType() + "，允许 " + comp.allowedUldTypes());
            }
            int total = item.tareKg() + item.netKg();
            if (total > comp.maxLoadKg()) {
                throw new UnprocessableException("集装器 " + item.uldNumber() + " 总重 " + total
                        + "kg 超过舱位 " + item.compartmentCode() + " 限额 " + comp.maxLoadKg() + "kg");
            }
            totalWeight += total;
            items.add(new AssemblyItemRow(null, 0, item.uldNumber(), item.uldType(),
                    item.compartmentCode(), item.tareKg(), item.netKg(), total, comp.armMm(),
                    BigDecimal.valueOf(total).multiply(BigDecimal.valueOf(comp.armMm())), seq++));
        }
        if (cargoPayload.compareTo(BigDecimal.valueOf(totalWeight)) != 0) {
            throw new UnprocessableException("装配图总货重 " + totalWeight
                    + "kg 须等于该版本货运业载 " + cargoPayload + "kg");
        }
        return items;
    }

    /** 舱单货运业载（CARGO+MAIL 明细合计），kg。 */
    static BigDecimal cargoPayloadKg(List<LoadDetailRow> details) {
        return CargoPayloadSplit.cargoPayloadKg(details);
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

    private AssemblyPlanResponse toResponse(AssemblyPlanRow plan) {
        List<AssemblyItemView> items = repo.findPlanItems(plan.id()).stream()
                .map(i -> new AssemblyItemView(i.uldNumber(), i.uldType(), i.compartmentCode(),
                        i.tareKg(), i.netKg(), i.totalKg(), i.armMm(), i.momentKgMm()))
                .toList();
        return new AssemblyPlanResponse(
                plan.id(), plan.flightId(), plan.manifestId(), plan.manifestVersionNo(),
                plan.planVersion(), plan.requestId(), plan.status(),
                plan.totalTareKg(), plan.totalNetKg(), plan.totalWeightKg(),
                plan.cargoMomentKgMm(), plan.cgZfwPct(), plan.cgTowPct(),
                items, plan.createdBy(), plan.submittedAt(), plan.createdAt());
    }

    /** 同键同参回放首次冻结结果；同键异参 409。 */
    private AssemblyPlanResponse replay(IdempotentRow row, String currentFp, String requestId) {
        if (!row.fingerprint().equals(currentFp)) {
            throw new ConflictException("requestId " + requestId
                    + " 已用于不同业务参数（指纹不一致），拒绝产生新版本");
        }
        if (row.httpStatus() == 201) {
            try {
                return objectMapper.readValue(row.resultJson(), AssemblyPlanResponse.class);
            } catch (Exception e) {
                throw new IllegalStateException("回放首次结果失败", e);
            }
        }
        throw new ConflictException("requestId " + requestId + " 首次请求未成功，请更换请求 ID");
    }

    private String safeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }
}
