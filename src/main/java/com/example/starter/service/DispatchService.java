package com.example.starter.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.domain.AlertEvent;
import com.example.starter.domain.AlertType;
import com.example.starter.domain.Allocation;
import com.example.starter.domain.CurtailOrder;
import com.example.starter.domain.DayAheadPlan;
import com.example.starter.domain.DispatchCycle;
import com.example.starter.domain.ExecutionReport;
import com.example.starter.domain.IdempotentRecord;
import com.example.starter.domain.LateReportEvent;
import com.example.starter.domain.OrderPlanBase;
import com.example.starter.domain.OrderStatus;
import com.example.starter.domain.PlanSlot;
import com.example.starter.domain.Turbine;
import com.example.starter.dto.AllocationItem;
import com.example.starter.dto.AlertView;
import com.example.starter.dto.OrderDetailResponse;
import com.example.starter.dto.OrderReceiveRequest;
import com.example.starter.dto.OrderReceiveResponse;
import com.example.starter.dto.PlanBindingEvidenceResponse;
import com.example.starter.dto.PlanOrderReceiveRequest;
import com.example.starter.dto.ReportRequest;
import com.example.starter.dto.ReportResponse;
import com.example.starter.dto.SettleResponse;
import com.example.starter.repo.DispatchRepository;
import com.example.starter.repo.IdempotentRepository;
import com.example.starter.repo.PlanRepository;
import com.example.starter.repo.TurbineRepository;
import com.example.starter.support.Fingerprints;
import com.example.starter.support.ServiceClock;
import com.example.starter.web.ApiBadRequestException;
import com.example.starter.web.ApiConflictException;
import com.example.starter.web.ApiNotFoundException;
import com.example.starter.web.ApiUnprocessableException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 限电指令流转服务：接收分摊、新旧指令原子切换、执行回报去重、
 * 下发满 10 分钟未达标告警并按最新可用出力重分摊、日结封存。
 */
@Service
public class DispatchService {

    /** 下发后达标判定时长：恰满 10 分钟即触发判定。 */
    static final long DUE_MINUTES = 10;

    private final ServiceClock clock;
    private final TurbineRepository turbineRepository;
    private final DispatchRepository dispatchRepository;
    private final IdempotentRepository idempotentRepository;
    private final PlanRepository planRepository;
    private final ObjectMapper objectMapper;

    public DispatchService(ServiceClock clock,
                           TurbineRepository turbineRepository,
                           DispatchRepository dispatchRepository,
                           IdempotentRepository idempotentRepository,
                           PlanRepository planRepository,
                           ObjectMapper objectMapper) {
        this.clock = clock;
        this.turbineRepository = turbineRepository;
        this.dispatchRepository = dispatchRepository;
        this.idempotentRepository = idempotentRepository;
        this.planRepository = planRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 接收限电指令：加风场行锁串行化同风场并发接收；新指令生效与旧指令作废在同一事务完成。
     */
    @Transactional
    public OrderReceiveResponse receive(OrderReceiveRequest request) {
        Instant now = clock.now();
        String fingerprint = Fingerprints.sha256(
                "ORDER|" + request.requestId() + "|" + request.farmCode() + "|" + request.targetPowerKw());

        // 行锁前先处理确定的重放/冲突；未命中的并发情况在锁后二次确认。
        IdempotentRecord existing = idempotentRepository.findByRequestId(request.requestId());
        if (existing != null) {
            return resolveOrderIdempotent(existing, fingerprint);
        }

        if (!turbineRepository.lockFarmRow(request.farmCode())) {
            throw new ApiBadRequestException("风场不存在：" + request.farmCode());
        }

        existing = idempotentRepository.findByRequestId(request.requestId());
        if (existing != null) {
            return resolveOrderIdempotent(existing, fingerprint);
        }

        List<Turbine> turbines = turbineRepository.findByFarm(request.farmCode());
        int farmAvailable = turbines.stream().mapToInt(Turbine::getAvailablePowerKw).sum();

        CurtailOrder active = dispatchRepository.findActiveOrder(request.farmCode());

        // 同一风场同一接收时刻只允许一个执行中指令：与执行中指令同时刻到达的并发接收冲突 409，
        // 保证并发接收唯一受理；早于当前时刻存在的旧指令视为先后到达，由新指令原子切换。
        if (active != null && !active.getReceivedAt().isBefore(now)) {
            throw new ApiConflictException(
                    "风场同一时刻已有执行中限电指令：" + active.getOrderNo());
        }

        // 先落幂等占位（唯一约束兜底并发受理），再做业务写入。
        IdempotentRecord record = new IdempotentRecord();
        record.setRequestId(request.requestId());
        record.setBizType("ORDER");
        record.setFingerprint(fingerprint);
        record.setCreatedAt(now);
        record.setResponseBody("");
        if (!idempotentRepository.tryInsert(record)) {
            IdempotentRecord winner = idempotentRepository.findByRequestId(request.requestId());
            return resolveOrderIdempotent(winner, fingerprint);
        }

        List<Allocation> allocations =
                AllocationEngine.allocate(turbines, request.targetPowerKw(), 0L, 1);

        CurtailOrder order = new CurtailOrder();
        order.setOrderNo(generateOrderNo(now));
        order.setFarmCode(request.farmCode());
        order.setRequestId(request.requestId());
        order.setFingerprint(fingerprint);
        order.setTargetPowerKw(request.targetPowerKw());
        order.setStatus(OrderStatus.DISPATCHED);
        order.setCurrentCycleNo(1);
        order.setReceivedAt(now);
        // 先以非执行中插入拿 id，旧指令作废后再置执行中，避免唯一索引出现双 Y。
        long newId = dispatchRepository.insertOrder(order);
        allocations.forEach(a -> a.setOrderId(newId));

        if (active != null) {
            // 同一事务：旧指令作废（active 释放）→ 新指令生效，不存在双指令同时生效窗口。
            dispatchRepository.markSuperseded(active.getId(), newId);
        }
        dispatchRepository.insertCycle(buildCycle(newId, 1, now));
        dispatchRepository.insertAllocations(newId, 1, allocations);
        // 旧指令已在本事务作废（active 释放），此处将新指令置为执行中。
        dispatchRepository.activateOrder(newId);

        OrderReceiveResponse response = buildReceiveResponse(
                order, turbines, allocations, farmAvailable, false);
        idempotentRepository.updateRefAndResponse(request.requestId(), newId, toJson(response));
        return response;
    }

    private OrderReceiveResponse resolveOrderIdempotent(IdempotentRecord existing, String fingerprint) {
        if (!existing.getFingerprint().equals(fingerprint)) {
            throw new ApiConflictException("相同 requestId 的业务参数与首次请求不一致");
        }
        if (existing.getResponseBody() != null && !existing.getResponseBody().isBlank()) {
            return fromJson(existing.getResponseBody(), OrderReceiveResponse.class).asReplay();
        }
        // 占位尚未回填（理论仅极端并发），按实体重建。
        CurtailOrder order = dispatchRepository.findOrderById(existing.getRefId());
        throw new ApiConflictException("首次请求仍在处理中");
    }

    /**
     * 接收计划绑定模式限电指令：须指定日期、时段与已发布版本。
     * 可参与出力为各机组实时可用出力与该段核定计划的较小者，套用既有最低保留与分摊规则；
     * 受理时在风场行锁内冻结机组基数及版本（与并发发布串行，校验的版本和基数一致），
     * 目标不可实现整单拒绝（422），过期版本不允许绑定（422）。
     */
    @Transactional
    public OrderReceiveResponse receivePlanBound(PlanOrderReceiveRequest request) {
        Instant now = clock.now();
        String fingerprint = Fingerprints.structured("PLAN_ORDER", List.of(
                request.requestId(),
                request.farmCode(),
                String.valueOf(request.targetPowerKw()),
                request.planDate().toString(),
                String.valueOf(request.slotIndex()),
                String.valueOf(request.planVersion())));

        IdempotentRecord existing = idempotentRepository.findByRequestId(request.requestId());
        if (existing != null) {
            return resolveOrderIdempotent(existing, fingerprint);
        }

        if (!turbineRepository.lockFarmRow(request.farmCode())) {
            throw new ApiNotFoundException("风场不存在：" + request.farmCode());
        }

        existing = idempotentRepository.findByRequestId(request.requestId());
        if (existing != null) {
            return resolveOrderIdempotent(existing, fingerprint);
        }

        // 版本校验在风场行锁内：与并发计划发布串行，校验通过的版本与读取的基数必属同一版本。
        DayAheadPlan plan = planRepository.findByVersion(
                request.farmCode(), request.planDate(), request.planVersion());
        if (plan == null) {
            throw new ApiNotFoundException("计划版本不存在：" + request.farmCode()
                    + " " + request.planDate() + " v" + request.planVersion());
        }
        if (!plan.isCurrent()) {
            throw new ApiUnprocessableException(
                    "计划版本已过期，不允许被新指令绑定：" + request.farmCode()
                            + " " + request.planDate() + " v" + request.planVersion());
        }

        List<Turbine> turbines = turbineRepository.findByFarm(request.farmCode());
        Map<Long, Integer> plannedByTurbine = new LinkedHashMap<>();
        for (PlanSlot slot : planRepository.findSlotsOfIndex(plan.getId(), request.slotIndex())) {
            plannedByTurbine.put(slot.getTurbineId(), slot.getPlannedKw());
        }

        // 可参与出力 = min(实时可用出力, 该段核定计划)；目标不可实现整单拒绝。
        int effectiveSum = turbines.stream()
                .mapToInt(t -> Math.min(t.getAvailablePowerKw(),
                        plannedByTurbine.getOrDefault(t.getId(), 0)))
                .sum();
        int floorSum = turbines.stream()
                .mapToInt(t -> Math.min(Math.min(t.getAvailablePowerKw(),
                        plannedByTurbine.getOrDefault(t.getId(), 0)), t.getTechnicalMinKw()))
                .sum();
        if (request.targetPowerKw() > effectiveSum) {
            throw new ApiUnprocessableException(
                    "目标出力 " + request.targetPowerKw() + " kW 高于全场可参与出力 "
                            + effectiveSum + " kW，目标不可实现");
        }
        if (request.targetPowerKw() < floorSum) {
            throw new ApiUnprocessableException(
                    "目标出力 " + request.targetPowerKw() + " kW 低于全场可实现下限 "
                            + floorSum + " kW，目标不可实现");
        }

        CurtailOrder active = dispatchRepository.findActiveOrder(request.farmCode());
        if (active != null && !active.getReceivedAt().isBefore(now)) {
            throw new ApiConflictException(
                    "风场同一时刻已有执行中限电指令：" + active.getOrderNo());
        }

        // 先落幂等占位（唯一约束兜底并发受理），业务写入与占位同事务，失败整体回滚不占键。
        IdempotentRecord record = new IdempotentRecord();
        record.setRequestId(request.requestId());
        record.setBizType("PLAN_ORDER");
        record.setFingerprint(fingerprint);
        record.setCreatedAt(now);
        record.setResponseBody("");
        if (!idempotentRepository.tryInsert(record)) {
            return resolveOrderIdempotent(
                    idempotentRepository.findByRequestId(request.requestId()), fingerprint);
        }

        // 以有效可参与出力分摊（复用既有最低保留与比例分摊规则）。
        List<Turbine> effectiveTurbines = turbines.stream()
                .map(t -> withAvailable(t, Math.min(t.getAvailablePowerKw(),
                        plannedByTurbine.getOrDefault(t.getId(), 0))))
                .toList();
        List<Allocation> allocations =
                AllocationEngine.allocate(effectiveTurbines, request.targetPowerKw(), 0L, 1);

        CurtailOrder order = new CurtailOrder();
        order.setOrderNo(generateOrderNo(now));
        order.setFarmCode(request.farmCode());
        order.setRequestId(request.requestId());
        order.setFingerprint(fingerprint);
        order.setTargetPowerKw(request.targetPowerKw());
        order.setStatus(OrderStatus.DISPATCHED);
        order.setCurrentCycleNo(1);
        order.setPlanId(plan.getId());
        order.setPlanVersion(plan.getVersion());
        order.setPlanDate(plan.getPlanDate());
        order.setSlotIndex(request.slotIndex());
        order.setReceivedAt(now);
        long newId = dispatchRepository.insertOrder(order);
        allocations.forEach(a -> a.setOrderId(newId));

        // 受理即冻结机组基数及版本：后续新计划发布不重算已受理指令。
        List<OrderPlanBase> bases = turbines.stream().map(t -> {
            OrderPlanBase base = new OrderPlanBase();
            base.setOrderId(newId);
            base.setTurbineId(t.getId());
            base.setPlannedKw(plannedByTurbine.getOrDefault(t.getId(), 0));
            base.setAvailableKw(t.getAvailablePowerKw());
            return base;
        }).toList();
        planRepository.insertBases(newId, bases);

        if (active != null) {
            dispatchRepository.markSuperseded(active.getId(), newId);
        }
        dispatchRepository.insertCycle(buildCycle(newId, 1, now));
        dispatchRepository.insertAllocations(newId, 1, allocations);
        dispatchRepository.activateOrder(newId);

        OrderReceiveResponse response = buildReceiveResponse(
                order, turbines, allocations, effectiveSum, false);
        idempotentRepository.updateRefAndResponse(request.requestId(), newId, toJson(response));
        return response;
    }

    /**
     * 指令基数证据：绑定模式指令受理时冻结的计划版本、时段与各机组基数。
     * 未绑定计划的指令返回 bound=false；指令不存在返回 404。
     */
    public PlanBindingEvidenceResponse planBindingEvidence(String orderNo) {
        CurtailOrder order = dispatchRepository.findOrderByNo(orderNo);
        if (order == null) {
            throw new ApiNotFoundException("指令不存在：" + orderNo);
        }
        if (order.getPlanId() == null) {
            return new PlanBindingEvidenceResponse(orderNo, false,
                    null, null, null, null, null, null, List.of());
        }
        Map<Long, Turbine> turbineById = turbineRepository.findByFarm(order.getFarmCode())
                .stream().collect(Collectors.toMap(Turbine::getId, t -> t));
        List<PlanBindingEvidenceResponse.TurbineBaseView> bases =
                planRepository.findBases(order.getId()).stream()
                        .map(b -> {
                            Turbine t = turbineById.get(b.getTurbineId());
                            return new PlanBindingEvidenceResponse.TurbineBaseView(
                                    t == null ? String.valueOf(b.getTurbineId()) : t.getTurbineCode(),
                                    b.getPlannedKw(), b.getAvailableKw(),
                                    Math.min(b.getPlannedKw(), b.getAvailableKw()));
                        })
                        .toList();
        Instant slotStart = order.getPlanDate().atStartOfDay()
                .toInstant(java.time.ZoneOffset.UTC)
                .plus(PlanService.SLOT_MINUTES * order.getSlotIndex(), ChronoUnit.MINUTES);
        return new PlanBindingEvidenceResponse(orderNo, true,
                order.getPlanId(), order.getPlanVersion(), order.getPlanDate(),
                order.getSlotIndex(), slotStart,
                slotStart.plus(PlanService.SLOT_MINUTES, ChronoUnit.MINUTES), bases);
    }

    /** 复制机组并替换可用出力（绑定模式以有效可参与出力分摊，不改写机组实时值）。 */
    private Turbine withAvailable(Turbine t, int availableKw) {
        Turbine copy = new Turbine();
        copy.setId(t.getId());
        copy.setFarmCode(t.getFarmCode());
        copy.setTurbineCode(t.getTurbineCode());
        copy.setRatedPowerKw(t.getRatedPowerKw());
        copy.setTechnicalMinKw(t.getTechnicalMinKw());
        copy.setAvailablePowerKw(availableKw);
        copy.setUpdatedAt(t.getUpdatedAt());
        return copy;
    }


    /**
     * 机组执行回报：按机组＋时刻去重；满 10 分钟未达标则告警并按最新可用出力重分摊。
     */
    @Transactional
    public ReportResponse report(ReportRequest request) {
        Instant now = clock.now();
        String fingerprint = Fingerprints.sha256(
                "REPORT|" + request.requestId() + "|" + request.turbineCode() + "|"
                        + request.measuredAt() + "|" + request.powerKw());

        IdempotentRecord existing = idempotentRepository.findByRequestId(request.requestId());
        if (existing != null) {
            return resolveReportIdempotent(existing, fingerprint);
        }

        Turbine turbine = turbineRepository.findByCode(request.turbineCode());
        if (turbine == null) {
            throw new ApiNotFoundException("机组不存在：" + request.turbineCode());
        }

        // 优先归属执行中指令；无执行中指令时取最新指令用于判定封存后的迟到回报。
        CurtailOrder order = dispatchRepository.findActiveOrder(turbine.getFarmCode());
        if (order == null) {
            order = dispatchRepository.findLatestOrder(turbine.getFarmCode());
        }
        if (order == null) {
            throw new ApiBadRequestException("该风场尚无任何限电指令，无法接收回报");
        }

        // 封存（或作废）后迟到回报：只登记迟到事件，不进入执行曲线。
        if (order.getStatus() == OrderStatus.SETTLED || order.getStatus() == OrderStatus.SUPERSEDED) {
            return recordLateReport(order, request, now, fingerprint);
        }

        // 锁定执行中指令行，串行化同指令的并发回报：保证达标判定/告警重分摊不重入。
        CurtailOrder locked = dispatchRepository.lockOrderById(order.getId());
        if (locked.getStatus() != OrderStatus.DISPATCHED) {
            // 持锁期间指令状态可能已被日结/切换改变：按终态迟到回报处理。
            return recordLateReport(locked, request, now, fingerprint);
        }
        order = locked;

        // 按机组＋时刻去重：不同 requestId 回报同一时刻点，返回首次结果，不重复入库。
        ExecutionReport samePoint =
                dispatchRepository.findReportByTurbineAndTime(turbine.getId(), request.measuredAt());
        if (samePoint != null) {
            CurtailPointView view = loadPointView(samePoint, turbine);
            return new ReportResponse(samePoint.getRequestId(), view.orderNo(), turbine.getTurbineCode(),
                    samePoint.getMeasuredAt().toString(), samePoint.getPowerKw(), false, true);
        }

        IdempotentRecord record = newIdempotent(request.requestId(), "REPORT", fingerprint, now);
        if (!idempotentRepository.tryInsert(record)) {
            return resolveReportIdempotent(
                    idempotentRepository.findByRequestId(request.requestId()), fingerprint);
        }

        ExecutionReport report = new ExecutionReport();
        report.setRequestId(request.requestId());
        report.setOrderId(order.getId());
        report.setTurbineId(turbine.getId());
        report.setMeasuredAt(request.measuredAt());
        report.setPowerKw(request.powerKw());
        report.setReceivedAt(now);
        try {
            dispatchRepository.insertReport(report);
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            // 同机组＋时刻并发回报：唯一约束兜底，回查返回首次结果（按时刻去重）。
            ExecutionReport winner =
                    dispatchRepository.findReportByTurbineAndTime(turbine.getId(), request.measuredAt());
            CurtailOrder owner = winner == null ? order
                    : dispatchRepository.findOrderById(winner.getOrderId());
            ReportResponse dedup = new ReportResponse(
                    winner == null ? request.requestId() : winner.getRequestId(),
                    owner.getOrderNo(), turbine.getTurbineCode(),
                    request.measuredAt().toString(),
                    winner == null ? request.powerKw() : winner.getPowerKw(),
                    false, true);
            idempotentRepository.updateRefAndResponse(
                    request.requestId(), winner == null ? null : winner.getId(), toJson(dedup));
            return dedup;
        }

        // 最新可用出力以回报实测值刷新，作为重分摊依据。
        turbineRepository.updateAvailablePower(turbine.getId(), request.powerKw(), now);
        turbine.setAvailablePowerKw(request.powerKw());

        maybeAlertAndReallocate(order, now);

        ReportResponse response = new ReportResponse(request.requestId(), order.getOrderNo(),
                request.turbineCode(), request.measuredAt().toString(), request.powerKw(),
                false, false);
        idempotentRepository.updateRefAndResponse(request.requestId(), report.getId(), toJson(response));
        return response;
    }

    private record CurtailPointView(String orderNo) {
    }

    /** 登记封存/作废后的迟到回报：幂等占位 + 迟到事件，返回 late=true。 */
    private ReportResponse recordLateReport(CurtailOrder order, ReportRequest request,
                                            Instant now, String fingerprint) {
        IdempotentRecord record = newIdempotent(request.requestId(), "REPORT", fingerprint, now);
        if (!idempotentRepository.tryInsert(record)) {
            return resolveReportIdempotent(
                    idempotentRepository.findByRequestId(request.requestId()), fingerprint);
        }
        LateReportEvent event = new LateReportEvent();
        event.setOrderId(order.getId());
        event.setTurbineCode(request.turbineCode());
        event.setMeasuredAt(request.measuredAt());
        event.setPowerKw(request.powerKw());
        event.setRequestId(request.requestId());
        event.setRecordedAt(now);
        dispatchRepository.insertLateReport(event);
        ReportResponse response = new ReportResponse(request.requestId(), order.getOrderNo(),
                request.turbineCode(), request.measuredAt().toString(), request.powerKw(),
                true, false);
        idempotentRepository.updateRefAndResponse(request.requestId(), null, toJson(response));
        return response;
    }

    private CurtailPointView loadPointView(ExecutionReport report, Turbine turbine) {
        CurtailOrder owner = dispatchRepository.findOrderById(report.getOrderId());
        return new CurtailPointView(owner == null ? null : owner.getOrderNo());
    }

    /**
     * 达标判定与重分摊：当前轮次下发恰满 10 分钟，全场最新出力仍高于目标则告警，
     * 并按各机组最新可用出力重分摊生成下一轮。
     */
    private void maybeAlertAndReallocate(CurtailOrder order, Instant now) {
        int cycleNo = order.getCurrentCycleNo();
        DispatchCycle cycle = dispatchRepository.findCycles(order.getId()).stream()
                .filter(c -> c.getCycleNo() == cycleNo)
                .findFirst()
                .orElseThrow(() -> new ApiBadRequestException("当前下发轮次缺失：" + cycleNo));

        // 恰满 10 分钟判定：now 早于 dueAt 不触发。
        if (now.isBefore(cycle.getDueAt())) {
            return;
        }
        if (dispatchRepository.findAlert(order.getId(), cycleNo) != null) {
            return;
        }

        List<Turbine> turbines = turbineRepository.findByFarm(order.getFarmCode());
        int farmActual = turbines.stream().mapToInt(Turbine::getAvailablePowerKw).sum();
        if (farmActual <= order.getTargetPowerKw()) {
            return;
        }

        AlertEvent alert = new AlertEvent();
        alert.setOrderId(order.getId());
        alert.setCycleNo(cycleNo);
        alert.setType(AlertType.TARGET_NOT_REACHED);
        alert.setFiredAt(now);
        alert.setFarmActualKw(farmActual);
        dispatchRepository.insertAlert(alert);

        int newCycleNo = cycleNo + 1;
        // 绑定计划的指令：重分摊仍引用受理时冻结的核定计划，仅以最新实时可用值刷新
        // 有效可参与出力 = min(最新实时可用, 冻结计划)；未绑定指令行为不变。
        List<Turbine> allocInput = turbines;
        if (order.getPlanId() != null) {
            Map<Long, Integer> plannedByTurbine = new LinkedHashMap<>();
            for (OrderPlanBase base : planRepository.findBases(order.getId())) {
                plannedByTurbine.put(base.getTurbineId(), base.getPlannedKw());
            }
            allocInput = turbines.stream()
                    .map(t -> withAvailable(t, Math.min(t.getAvailablePowerKw(),
                            plannedByTurbine.getOrDefault(t.getId(), 0))))
                    .toList();
        }
        List<Allocation> reallocations =
                AllocationEngine.allocate(allocInput, order.getTargetPowerKw(), order.getId(), newCycleNo);
        dispatchRepository.insertCycle(buildCycle(order.getId(), newCycleNo, now));
        dispatchRepository.insertAllocations(order.getId(), newCycleNo, reallocations);
        dispatchRepository.updateAlertReallocated(order.getId(), cycleNo, newCycleNo);
        dispatchRepository.updateCurrentCycle(order.getId(), newCycleNo);
        order.setCurrentCycleNo(newCycleNo);
    }

    private ReportResponse resolveReportIdempotent(IdempotentRecord existing, String fingerprint) {
        if (existing == null) {
            throw new ApiConflictException("并发请求处理中，请重试");
        }
        if (!existing.getFingerprint().equals(fingerprint)) {
            throw new ApiConflictException("相同 requestId 的业务参数与首次请求不一致");
        }
        if (existing.getResponseBody() != null && !existing.getResponseBody().isBlank()) {
            return fromJson(existing.getResponseBody(), ReportResponse.class).asReplay();
        }
        throw new ApiConflictException("首次请求仍在处理中");
    }

    /**
     * 日结封存：终态；已封存重复封存幂等成功。
     */
    @Transactional
    public SettleResponse settle(String orderNo) {
        CurtailOrder order = dispatchRepository.findOrderByNo(orderNo);
        if (order == null) {
            throw new ApiNotFoundException("指令不存在：" + orderNo);
        }
        if (order.getStatus() == OrderStatus.SETTLED) {
            return new SettleResponse(order.getId(), order.getOrderNo(),
                    order.getStatus().name(), order.getSettledAt(), true);
        }
        if (order.getStatus() == OrderStatus.SUPERSEDED) {
            throw new ApiBadRequestException("指令已被新指令作废，不能日结封存：" + orderNo);
        }
        Instant now = clock.now();
        dispatchRepository.markSettled(order.getId(), now);
        return new SettleResponse(order.getId(), order.getOrderNo(),
                OrderStatus.SETTLED.name(), now, false);
    }

    /**
     * 指令详情：各轮分摊明细、按机组＋时刻归位的执行曲线、告警链与迟到事件，均可复算。
     */
    public OrderDetailResponse detail(String orderNo) {
        CurtailOrder order = dispatchRepository.findOrderByNo(orderNo);
        if (order == null) {
            throw new ApiNotFoundException("指令不存在：" + orderNo);
        }
        List<Turbine> turbines = turbineRepository.findByFarm(order.getFarmCode());
        Map<Long, Turbine> turbineById = turbines.stream()
                .collect(Collectors.toMap(Turbine::getId, t -> t));

        List<DispatchCycle> cycles = dispatchRepository.findCycles(order.getId());
        List<Allocation> allAllocations = dispatchRepository.findAllAllocations(order.getId());
        List<OrderDetailResponse.CycleView> cycleViews = new ArrayList<>();
        for (DispatchCycle c : cycles) {
            List<AllocationItem> items = allAllocations.stream()
                    .filter(a -> a.getCycleNo() == c.getCycleNo())
                    .map(a -> toItem(a, turbineById.get(a.getTurbineId())))
                    .toList();
            cycleViews.add(new OrderDetailResponse.CycleView(
                    c.getCycleNo(), c.getDispatchedAt(), c.getDueAt(), items));
        }

        List<ExecutionReport> reports =
                dispatchRepository.findReportsOfFarm(order.getFarmCode()).stream()
                        .filter(r -> r.getOrderId() == order.getId())
                        .toList();
        List<OrderDetailResponse.ReportPoint> curve = reports.stream()
                .map(r -> new OrderDetailResponse.ReportPoint(
                        turbineById.get(r.getTurbineId()).getTurbineCode(),
                        r.getMeasuredAt(), r.getPowerKw(), r.getReceivedAt()))
                .toList();

        List<AlertView> alerts = dispatchRepository.findAlerts(order.getId())
                .stream()
                .map(a -> new AlertView(a.getCycleNo(), a.getType().name(),
                        a.getFiredAt(), a.getFarmActualKw(), a.getReallocatedCycleNo()))
                .toList();

        List<OrderDetailResponse.LateReportView> late = dispatchRepository.findLateReports(order.getId())
                .stream()
                .map(e -> new OrderDetailResponse.LateReportView(e.getTurbineCode(),
                        e.getMeasuredAt(), e.getPowerKw(), e.getRequestId(), e.getRecordedAt()))
                .toList();

        return new OrderDetailResponse(order.getId(), order.getOrderNo(), order.getFarmCode(),
                order.getRequestId(), order.getTargetPowerKw(), order.getStatus().name(),
                order.getCurrentCycleNo(), order.getReceivedAt(), order.getSettledAt(),
                cycleViews, curve, alerts, late);
    }

    private OrderReceiveResponse buildReceiveResponse(CurtailOrder order, List<Turbine> turbines,
                                                      List<Allocation> allocations,
                                                      int farmAvailable, boolean replayed) {
        Map<Long, Turbine> byId = new LinkedHashMap<>();
        turbines.forEach(t -> byId.put(t.getId(), t));
        List<AllocationItem> items = allocations.stream()
                .map(a -> toItem(a, byId.get(a.getTurbineId())))
                .toList();
        boolean noCurtailment = farmAvailable == order.getTargetPowerKw();
        return new OrderReceiveResponse(order.getId(), order.getOrderNo(), order.getRequestId(),
                order.getFarmCode(), order.getTargetPowerKw(), farmAvailable,
                farmAvailable - order.getTargetPowerKw(), order.getStatus().name(),
                order.getCurrentCycleNo(), noCurtailment, items, replayed);
    }

    private AllocationItem toItem(Allocation a, Turbine t) {
        return new AllocationItem(
                t == null ? String.valueOf(a.getTurbineId()) : t.getTurbineCode(),
                a.getBaseAvailableKw(),
                t == null ? 0 : t.getTechnicalMinKw(),
                a.getTargetPowerKw(),
                a.getCurtailKw());
    }

    private DispatchCycle buildCycle(long orderId, int cycleNo, Instant dispatchedAt) {
        DispatchCycle cycle = new DispatchCycle();
        cycle.setOrderId(orderId);
        cycle.setCycleNo(cycleNo);
        cycle.setDispatchedAt(dispatchedAt);
        cycle.setDueAt(dispatchedAt.plus(DUE_MINUTES, ChronoUnit.MINUTES));
        return cycle;
    }

    private IdempotentRecord newIdempotent(String requestId, String bizType,
                                           String fingerprint, Instant now) {
        IdempotentRecord record = new IdempotentRecord();
        record.setRequestId(requestId);
        record.setBizType(bizType);
        record.setFingerprint(fingerprint);
        record.setCreatedAt(now);
        record.setResponseBody("");
        return record;
    }

    private String generateOrderNo(Instant now) {
        return "CO" + now.atZone(clock.zone())
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + String.format("%04d", ThreadLocalRandom.current().nextInt(10000));
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("响应序列化失败", ex);
        }
    }

    private <T> T fromJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("首次响应无法还原", ex);
        }
    }
}
