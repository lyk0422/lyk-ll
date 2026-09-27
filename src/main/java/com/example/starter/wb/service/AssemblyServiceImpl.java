package com.example.starter.wb.service;

import com.example.starter.wb.calc.CalcLoadItem;
import com.example.starter.wb.calc.WeightBalanceCalculator;
import com.example.starter.wb.calc.WeightBalanceResult;
import com.example.starter.wb.domain.Aircraft;
import com.example.starter.wb.domain.AssemblyPlanRow;
import com.example.starter.wb.domain.Flight;
import com.example.starter.wb.domain.LoadDetailRow;
import com.example.starter.wb.domain.ManifestRow;
import com.example.starter.wb.domain.ReviewRow;
import com.example.starter.wb.domain.UldOccupancyRow;
import com.example.starter.wb.domain.UldPosition;
import com.example.starter.wb.dto.AssemblyItemView;
import com.example.starter.wb.dto.AssemblyPlanView;
import com.example.starter.wb.dto.AssemblyRequest;
import com.example.starter.wb.dto.UldItem;
import com.example.starter.wb.exception.BadRequestException;
import com.example.starter.wb.exception.ConflictException;
import com.example.starter.wb.exception.NotFoundException;
import com.example.starter.wb.exception.UnprocessableException;
import com.example.starter.wb.repo.IdempotentExecutor;
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
 * 集装器装配服务：把装配图接入既有载重平衡流程。
 *
 * <p>整版校验（任一违例整版拒绝）：编号唯一、舱位合法且类型允许、皮重非负/净重为正、
 * 同舱位最多一器、总重（含皮重）恰达舱位限额合法、编号不得跨未关闭航班占用、
 * Σ净重须等于舱单版本货运业载；货运力矩按总重×整数毫米力臂精确求和（long 不丢精度），
 * 再交既有重心包线规则复算。提交以 expectedVersion 乐观控制并发改图，原子写新图、
 * 释放旧图占用并使旧双岗复核失效。
 */
@Service
public class AssemblyServiceImpl implements AssemblyService {

    /** 货运业载类别：装配图以集装器承载，旅客重量不参与装配。 */
    private static final Set<String> FREIGHT_CATEGORIES = Set.of("CARGO", "BAGGAGE", "MAIL");
    /** 1 英寸 = 25.4 毫米，整数毫米力臂换算为既有计算器的英寸口径，保留充足小数位。 */
    private static final BigDecimal MM_PER_INCH = new BigDecimal("25.4");

    private final WeightBalanceRepository repo;
    private final IdempotentExecutor idempotent;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AssemblyServiceImpl(WeightBalanceRepository repo, IdempotentExecutor idempotent,
                               ObjectMapper objectMapper, Clock clock) {
        this.repo = repo;
        this.idempotent = idempotent;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    @Transactional
    public AssemblyPlanView submitAssembly(long flightId, AssemblyRequest request) {
        return idempotent.execute(request.requestId(), request, AssemblyPlanView.class, () -> {
            Flight flight = requireFlight(flightId);
            Aircraft ac = requireAircraft(flight.aircraftId());

            // 锁定当前有效舱单版本与当前有效装配图，序列化并发改图 / LMC 重打。
            ManifestRow manifest = repo.findActiveByFlightIdForUpdate(flightId)
                    .orElseThrow(() -> new NotFoundException("航班 " + flightId + " 不存在有效舱单版本，无法提交装配图"));
            Optional<AssemblyPlanRow> currentOpt = repo.findActivePlanForUpdate(flightId);
            AssemblyPlanRow current = currentOpt.orElse(null);

            int currentPlanVersion = current == null ? 0 : current.planVersion();
            if (request.expectedVersion() != currentPlanVersion) {
                throw new ConflictException("装配图版本不匹配：expectedVersion=" + request.expectedVersion()
                        + "，当前版本=" + currentPlanVersion + "，请基于最新装配图重试");
            }
            if (manifest.status().equals("CLOSED")) {
                throw new ConflictException("航班已放行关闭，装配图历史只读，不能再改图");
            }
            if (!"LOADED".equals(manifest.status()) && !"REVIEWED".equals(manifest.status())) {
                throw new ConflictException("装载确认后才能提交装配图，当前舱单状态 " + manifest.status());
            }

            // 逐项校验 + 力矩/货重精确求和。
            Map<String, UldPosition> positionByCode = new LinkedHashMap<>();
            ac.uldPositions().forEach(p -> positionByCode.put(p.positionCode(), p));
            Set<String> seenNo = new HashSet<>();
            Set<String> seenPosition = new HashSet<>();
            long cargoMoment = 0L;
            int netSum = 0;
            List<UldOccupancyRow> newRows = new ArrayList<>();
            LocalDateTime now = LocalDateTime.now(clock);
            for (UldItem item : request.items()) {
                UldPosition pos = positionByCode.get(item.positionCode());
                if (pos == null) {
                    throw new BadRequestException("飞机 " + ac.tailNo() + " 不存在集装器舱位 " + item.positionCode());
                }
                if (!pos.allowedUldTypes().contains(item.uldType())) {
                    throw new UnprocessableException("舱位 " + item.positionCode() + " 不允许集装器类型 "
                            + item.uldType() + "，允许 " + pos.allowedUldTypes());
                }
                if (!seenNo.add(item.uldNo())) {
                    throw new UnprocessableException("集装器编号 " + item.uldNo() + " 在同一装配图内重复");
                }
                if (!seenPosition.add(item.positionCode())) {
                    throw new UnprocessableException("舱位 " + item.positionCode() + " 已放置一个集装器，同舱位最多一器");
                }
                int gross = item.tareKg() + item.netKg();
                if (gross > pos.maxPayloadKg()) {
                    throw new UnprocessableException("集装器 " + item.uldNo() + " 总重 " + gross
                            + "kg（含皮重 " + item.tareKg() + "kg）超过舱位 " + item.positionCode()
                            + " 限额 " + pos.maxPayloadKg() + "kg");
                }
                // 跨未关闭航班占用：本航班旧图即将被替换，其占用视为可重新分配。
                long currentPlanId = current == null ? -1L : current.id();
                for (UldOccupancyRow open : repo.findOpenOccupanciesByUldNo(item.uldNo())) {
                    if (open.flightId() != flightId || open.planId() != currentPlanId) {
                        throw new ConflictException("集装器 " + item.uldNo() + " 已被航班 " + open.flightId()
                                + " 未关闭占用，不能跨航班重复占用");
                    }
                }
                cargoMoment += Math.multiplyExact((long) gross, (long) pos.armMm());
                netSum += item.netKg();
                newRows.add(new UldOccupancyRow(null, item.uldNo(), item.uldType(), flightId,
                        manifest.id(), 0L, item.positionCode(), pos.armMm(), item.tareKg(),
                        item.netKg(), gross, request.occurredAt(), 0L, null));
            }

            // Σ净重须等于该舱单版本货运业载（CARGO/BAGGAGE/MAIL）。
            BigDecimal freightPayload = freightPayload(repo.findDetails(manifest.id()));
            if (new BigDecimal(netSum).compareTo(freightPayload) != 0) {
                throw new UnprocessableException("装配图总货重 " + netSum + "kg 与舱单版本货运业载 "
                        + freightPayload.toPlainString() + "kg 不一致，整版拒绝");
            }

            // 实物装配（总重×力臂）交既有重心规则复算：旅客按明细，货运按集装器总重。
            WeightBalanceResult wb = recomputeWithUlds(ac, manifest, request.items(), positionByCode);
            if (!wb.withinLimits()) {
                throw new UnprocessableException("实物装配重心/重量超出限制：" + String.join("；", wb.violations()));
            }

            int newVersion = currentPlanVersion + 1;
            AssemblyPlanRow planRow = new AssemblyPlanRow(
                    null, flightId, manifest.id(), newVersion, request.expectedVersion(),
                    manifest.versionNo(), request.requestId(),
                    FingerprintUtil.sha256(request.requestId(), request, objectMapper),
                    freightPayload, manifest.fuelKg(), cargoMoment, wb.cgZfwPct(), wb.cgTowPct(),
                    flightId, request.operator(), request.occurredAt(), now, null);

            long planId;
            try {
                // 原子：作废旧图 → 释放旧占用 → 写新图 → 写新占用；唯一约束兜底并发。
                if (current != null) {
                    repo.supersedePlan(current.id());
                    repo.releaseOccupanciesByPlan(current.id(), now);
                }
                planId = repo.insertPlan(planRow);
            } catch (DuplicateKeyException e) {
                throw new ConflictException("并发改图冲突，航班仅允许一张有效装配图");
            }
            List<UldOccupancyRow> toInsert = new ArrayList<>();
            for (UldOccupancyRow r : newRows) {
                toInsert.add(new UldOccupancyRow(r.id(), r.uldNo(), r.uldType(), r.flightId(),
                        r.manifestId(), planId, r.positionCode(), r.armMm(), r.tareKg(), r.netKg(),
                        r.grossKg(), r.occupiedAt(), r.openFlag(), r.releasedAt()));
            }
            try {
                repo.insertOccupancies(toInsert);
            } catch (DuplicateKeyException e) {
                // (uld_no,open_flag) 唯一：两航班抢同器，最多一方成功。
                throw new ConflictException("集装器并发占用冲突：该编号已被未关闭航班占用，仅一方成功");
            }

            // 新图使旧双岗复核失效：舱单回到 LOADED，须基于最新舱单重新双岗复核。
            if (!"LOADED".equals(manifest.status())) {
                repo.updateStatus(manifest.id(), "LOADED", null);
            }
            repo.deleteReviewsByManifest(manifest.id());

            return toView(repo.findPlanById(planId).orElseThrow(), List.of(), "LOADED");
        });
    }

    @Override
    @Transactional(readOnly = true)
    public AssemblyPlanView getActivePlan(long flightId) {
        requireFlight(flightId);
        AssemblyPlanRow row = repo.findActivePlan(flightId)
                .orElseThrow(() -> new NotFoundException("航班 " + flightId + " 尚无有效装配图"));
        ManifestRow manifest = repo.findManifestById(row.manifestId()).orElse(null);
        String manifestStatus = manifest == null ? null : manifest.status();
        return toView(row, repo.findReviews(row.manifestId()), manifestStatus);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AssemblyPlanView> listPlans(long flightId) {
        requireFlight(flightId);
        return repo.findPlansByFlight(flightId).stream().map(row -> {
            ManifestRow manifest = repo.findManifestById(row.manifestId()).orElse(null);
            String manifestStatus = manifest == null ? null : manifest.status();
            return toView(row, List.of(), manifestStatus);
        }).toList();
    }

    @Override
    @Transactional
    public Long validateForClose(long flightId) {
        Optional<AssemblyPlanRow> active = repo.findActivePlan(flightId);
        if (active.isEmpty()) {
            // 未启用装配控制的既有舱单：走原有兼容路径。
            return null;
        }
        AssemblyPlanRow plan = active.get();
        ManifestRow manifest = repo.findActiveByFlightIdForUpdate(flightId)
                .orElseThrow(() -> new ConflictException("航班 " + flightId + " 不存在有效舱单版本"));
        if (plan.manifestId() != manifest.id()) {
            throw new ConflictException("装配图（舱单版本 " + plan.manifestVersion()
                    + "）与当前舱单版本 " + manifest.versionNo() + " 不匹配，LMC/加油变更后须重新提交装配并复核");
        }
        // 同版本限内 LMC 也可能改变货运业载：净货重必须仍与装配图一致，否则实物与舱单对不上。
        BigDecimal currentFreight = freightPayload(repo.findDetails(manifest.id()));
        if (currentFreight.compareTo(plan.cargoPayloadKg()) != 0) {
            throw new ConflictException("LMC 后舱单货运业载变为 " + currentFreight.toPlainString()
                    + "kg，与有效装配图总货重 " + plan.cargoPayloadKg().toPlainString()
                    + "kg 不一致，须基于最新舱单重新提交装配并复核");
        }
        if (manifest.fuelKg().compareTo(plan.fuelKg()) != 0) {
            throw new ConflictException("加油量已由装配时 " + plan.fuelKg().toPlainString()
                    + "kg 变为 " + manifest.fuelKg().toPlainString()
                    + "kg，须基于最新舱单重新提交装配并复核");
        }
        // 以实物装配和当前油量再交既有重心规则校验，防止加油变更后重心出包线。
        Aircraft ac = requireAircraft(requireFlight(flightId).aircraftId());
        List<UldItem> items = repo.findOccupanciesByPlan(plan.id()).stream()
                .filter(o -> o.openFlag() != null)
                .map(o -> new UldItem(o.uldNo(), o.uldType(), o.positionCode(), o.tareKg(), o.netKg()))
                .toList();
        Map<String, UldPosition> positionByCode = new LinkedHashMap<>();
        ac.uldPositions().forEach(p -> positionByCode.put(p.positionCode(), p));
        WeightBalanceResult wb = recomputeWithUlds(ac, manifest, items, positionByCode);
        if (!wb.withinLimits()) {
            throw new UnprocessableException("关闭放行前实物装配重心/重量复核不通过：" + String.join("；", wb.violations()));
        }
        return plan.id();
    }

    @Override
    @Transactional
    public void releaseOnClose(long flightId, Long planId, LocalDateTime closedAt) {
        if (planId == null) {
            return;
        }
        repo.releaseOpenOccupanciesForFlight(flightId, closedAt);
        repo.markPlanReleased(planId, closedAt);
    }

    // ================================================================ 内部

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

    private static BigDecimal freightPayload(List<LoadDetailRow> details) {
        BigDecimal sum = BigDecimal.ZERO.setScale(2);
        for (LoadDetailRow d : details) {
            if (FREIGHT_CATEGORIES.contains(d.category())) {
                sum = sum.add(d.totalWeightKg());
            }
        }
        return sum;
    }

    /**
     * 以实物装配重建计算项：旅客明细按站位（英寸），货运按集装器总重（皮+净）、
     * 毫米力臂换算英寸；同一舱位多器聚合后参与既有重心计算器。
     */
    private WeightBalanceResult recomputeWithUlds(Aircraft ac, ManifestRow manifest,
                                                  List<UldItem> items,
                                                  Map<String, UldPosition> positionByCode) {
        List<LoadDetailRow> details = repo.findDetails(manifest.id());
        // 旅客（非货运）明细聚合，力臂取既有英寸站位。
        Map<String, BigDecimal> weightByKey = new LinkedHashMap<>();
        Map<String, Integer> piecesByKey = new LinkedHashMap<>();
        for (LoadDetailRow d : details) {
            if (FREIGHT_CATEGORIES.contains(d.category())) {
                continue;
            }
            String key = d.category() + "|" + d.stationCode();
            weightByKey.merge(key, d.totalWeightKg(), BigDecimal::add);
            piecesByKey.merge(key, d.pieces(), Integer::sum);
        }
        List<CalcLoadItem> calcItems = new ArrayList<>();
        weightByKey.forEach((key, weight) -> {
            String[] parts = key.split("\\|", 2);
            BigDecimal arm = ac.stations().stream()
                    .filter(s -> s.stationCode().equals(parts[1]))
                    .map(s -> s.arm())
                    .findFirst()
                    .orElseThrow(() -> new BadRequestException("飞机 " + ac.tailNo() + " 不存在站位 " + parts[1]));
            calcItems.add(new CalcLoadItem(parts[0], parts[1], piecesByKey.get(key),
                    weight.setScale(2, RoundingMode.HALF_UP), arm));
        });
        // 货运：同舱位多器总重聚合，力臂为整数毫米换算的英寸。
        Map<String, BigDecimal> grossByPosition = new LinkedHashMap<>();
        Map<String, Integer> countByPosition = new LinkedHashMap<>();
        for (UldItem item : items) {
            grossByPosition.merge(item.positionCode(),
                    BigDecimal.valueOf((long) item.tareKg() + item.netKg()), BigDecimal::add);
            countByPosition.merge(item.positionCode(), 1, Integer::sum);
        }
        for (Map.Entry<String, BigDecimal> e : grossByPosition.entrySet()) {
            UldPosition pos = positionByCode.get(e.getKey());
            BigDecimal armInch = BigDecimal.valueOf(pos.armMm())
                    .divide(MM_PER_INCH, 6, RoundingMode.HALF_UP);
            calcItems.add(new CalcLoadItem("CARGO", e.getKey(), countByPosition.get(e.getKey()),
                    e.getValue().setScale(2, RoundingMode.HALF_UP), armInch));
        }
        return WeightBalanceCalculator.calculate(ac, calcItems, manifest.fuelKg());
    }

    private AssemblyPlanView toView(AssemblyPlanRow row, List<ReviewRow> reviews, String manifestStatus) {
        List<AssemblyItemView> itemViews = repo.findOccupanciesByPlan(row.id()).stream()
                .map(o -> new AssemblyItemView(o.uldNo(), o.uldType(), o.positionCode(), o.armMm(),
                        o.tareKg(), o.netKg(), o.grossKg()))
                .toList();
        List<String> roles = reviews.stream().map(ReviewRow::roleCode).toList();
        boolean active = row.activeFlag() != null;
        return new AssemblyPlanView(
                row.id(), row.flightId(), row.planVersion(), row.manifestId(), row.manifestVersion(),
                row.cargoPayloadKg(), row.fuelKg(), row.cargoMomentKgmm(), active, row.releasedAt() != null,
                roles, manifestStatus, row.occurredAt(), row.releasedAt(), itemViews);
    }
}
