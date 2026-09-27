package com.example.starter.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
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
import com.example.starter.domain.DayAheadPlanItem;
import com.example.starter.domain.DispatchCycle;
import com.example.starter.domain.ExecutionReport;
import com.example.starter.domain.IdempotentRecord;
import com.example.starter.domain.LateReportEvent;
import com.example.starter.domain.OrderPlanBase;
import com.example.starter.domain.OrderStatus;
import com.example.starter.domain.Turbine;
import com.example.starter.dto.AllocationItem;
import com.example.starter.dto.AlertView;
import com.example.starter.dto.OrderDetailResponse;
import com.example.starter.dto.OrderReceiveRequest;
import com.example.starter.dto.OrderReceiveResponse;
import com.example.starter.dto.PlanBaseEvidenceResponse;
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
     * 计划绑定模式（planDate/periodIndex/planVersion 同时非空）：以 min(实时可用, 该段核定计划)
     * 冻结各机组基数后再套用最低保留与分摊规则；基数随指令冻结，后续新计划发布不重算。
     */
    @Transactional
    public OrderReceiveResponse receive(OrderReceiveRequest request) {
        Instant now = clock.now();
        validateBindingFields(request);
        String fingerprint = Fingerprints.struct(
                request.requestId(), request.farmCode(), String.valueOf(request.targetPowerKw()),
                request.planDate() == null ? null : request.planDate().toString(),
                request.periodIndex() == null ? null : String.valueOf(request.periodIndex()),
                request.planVersion() == null ? null : String.valueOf(request.planVersion()));

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

        // 计划绑定：风场行锁与计划发布互斥，保证校验时的版本与基数一致；冻结后与新版本脱钩。
        PlanBinding binding = null;
        List<Turbine> effectiveTurbines = turbines;
        if (request.planBound()) {
            binding = resolvePlanBinding(request, turbines);
            effectiveTurbines = binding.effectiveTurbines();
        }
        int farmAvailable = effectiveTurbines.stream().mapToInt(Turbine::getAvailablePowerKw).sum();

        CurtailOrder active = dispatchRepository.findActiveOrder(request.farmCode());

        // 同一风场同一接收时刻只允许一个执行中指令：与执行中指令同时刻到达的并发接收冲突 409，
        // 保证并发接收唯一受理；早于当前时刻存在的旧指令视为先后到达，由新指令原子切换。
        if (active != null && !active.getReceivedAt().isBefore(now)) {
            throw new ApiConflictException(
                    "风场同一时刻已有执行中限电指令：" + active.getOrderNo());
        }

        // 先落幂等占位（唯一约束兜底并发受理），再做业务写入；后续业务拒绝随事务回滚，失败不占键。
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

        List<Allocation> allocations;
        try {
            allocations = AllocationEngine.allocate(
                    effectiveTurbines, request.targetPowerKw(), 0L, 1);
        } catch (ApiBadRequestException ex) {
            // 计划绑定模式下目标不可实现属业务拒绝 422；未绑定模式保持原 400 语义。
            if (request.planBound()) {
                throw new ApiUnprocessableException(ex.getMessage());
            }
            throw ex;
        }

        CurtailOrder order = new CurtailOrder();
        order.setOrderNo(generateOrderNo(now));
        order.setFarmCode(request.farmCode());
        order.setRequestId(request.requestId());
        order.setFingerprint(fingerprint);
        order.setTargetPowerKw(request.targetPowerKw());
        order.setStatus(OrderStatus.DISPATCHED);
        order.setCurrentCycleNo(1);
        order.setReceivedAt(now);
        if (binding != null) {
            order.setPlanId(binding.planId());
            order.setPlanVersion(binding.planVersion());
            order.setPlanDate(request.planDate());
            order.setPeriodIndex(request.periodIndex());
        }
        // 先以非执行中插入拿 id，旧指令作废后再置执行中，避免唯一索引出现双 Y。
        long newId = dispatchRepository.insertOrder(order);
        allocations.forEach(a -> a.setOrderId(newId));
        if (binding != null) {
            dispatchRepository.insertPlanBases(buildFrozenBases(newId, binding));
        }

        if (active != null) {
            // 同一事务：旧指令作废（active 释放）→ 新指令生效，不存在双指令同时生效窗口。
            dispatchRepository.markSuperseded(active.getId(), newId);
        }
        dispatchRepository.insertCycle(buildCycle(newId, 1, now));
        dispatchRepository.insertAllocations(newId, 1, allocations);
        // 旧指令已在本事务作废（active 释放），此处将新指令置为执行中。
        dispatchRepository.activateOrder(newId);

        OrderReceiveResponse response = buildReceiveResponse(
                order, effectiveTurbines, allocations, farmAvailable, false);
        idempotentRepository.updateRefAndResponse(request.requestId(), newId, toJson(response));
        return response;
    }

    /** 计划绑定上下文：版本标识、冻结基数（原机组实时值/计划值/可参与值）与供分摊的等效机组。 */
    private record PlanBinding(long planId, int planVersion, List<Turbine> effectiveTurbines,
                               List<FrozenTurbineBase> frozenBases) {
    }

    /** 单机组受理时冻结三元组。 */
    private record FrozenTurbineBase(Turbine turbine, int plannedKw, int availableKw, int baseKw) {
    }

    /** 绑定字段必须全部为空（原模式）或全部非空；时段须在 1..96，版本号须为正。 */
    private void validateBindingFields(OrderReceiveRequest request) {
        if (request.planBound() || request.planFree()) {
            if (request.planBound()) {
                if (request.periodIndex() < 1 || request.periodIndex() > PlanService.PERIODS_PER_DAY) {
                    throw new ApiBadRequestException(
                            "时段序号须在 1.." + PlanService.PERIODS_PER_DAY + " 之间："
                                    + request.periodIndex());
                }
                if (request.planVersion() <= 0) {
                    throw new ApiBadRequestException("计划版本号须为正整数：" + request.planVersion());
                }
            }
            return;
        }
        throw new ApiBadRequestException(
                "计划绑定字段 planDate/periodIndex/planVersion 须同时提供或同时省略");
    }

    /**
     * 在风场行锁内解析计划绑定：指定版本须存在（404）且仍为当前版本（过期 422），
     * 该时段须覆盖全部机组；冻结 min(实时可用, 核定计划) 作为可参与出力基数。
     */
    private PlanBinding resolvePlanBinding(OrderReceiveRequest request, List<Turbine> turbines) {
        DayAheadPlan plan = planRepository.findByVersion(
                request.farmCode(), request.planDate(), request.planVersion());
        if (plan == null) {
            throw new ApiNotFoundException(
                    "计划版本不存在：" + request.farmCode() + " " + request.planDate()
                            + " v" + request.planVersion());
        }
        DayAheadPlan current = planRepository.findCurrent(request.farmCode(), request.planDate());
        if (current == null || current.getId() != plan.getId()) {
            throw new ApiUnprocessableException("过期计划版本不允许绑定新指令：v"
                    + request.planVersion() + "，当前版本 v"
                    + (current == null ? "无" : current.getVersion()));
        }

        Map<Long, DayAheadPlanItem> itemByTurbine = new HashMap<>();
        for (DayAheadPlanItem item : planRepository.findItemsByPeriod(
                plan.getId(), request.periodIndex())) {
            itemByTurbine.put(item.getTurbineId(), item);
        }

        List<Turbine> effective = new ArrayList<>(turbines.size());
        List<FrozenTurbineBase> frozen = new ArrayList<>(turbines.size());
        for (Turbine t : turbines) {
            DayAheadPlanItem item = itemByTurbine.get(t.getId());
            if (item == null) {
                throw new ApiUnprocessableException(
                        "计划时段未覆盖机组：" + t.getTurbineCode());
            }
            int baseKw = Math.min(t.getAvailablePowerKw(), item.getPlannedKw());
            effective.add(copyWithAvailable(t, baseKw));
            frozen.add(new FrozenTurbineBase(t, item.getPlannedKw(), t.getAvailablePowerKw(), baseKw));
        }
        return new PlanBinding(plan.getId(), plan.getVersion(), effective, frozen);
    }

    /** 以指定可参与出力构造等效机组副本（不改动机组表实时值）。 */
    private Turbine copyWithAvailable(Turbine source, int availableKw) {
        Turbine copy = new Turbine();
        copy.setId(source.getId());
        copy.setFarmCode(source.getFarmCode());
        copy.setTurbineCode(source.getTurbineCode());
        copy.setRatedPowerKw(source.getRatedPowerKw());
        copy.setTechnicalMinKw(source.getTechnicalMinKw());
        copy.setAvailablePowerKw(availableKw);
        copy.setUpdatedAt(source.getUpdatedAt());
        return copy;
    }

    private List<OrderPlanBase> buildFrozenBases(long orderId, PlanBinding binding) {
        List<OrderPlanBase> bases = new ArrayList<>(binding.frozenBases().size());
        for (FrozenTurbineBase f : binding.frozenBases()) {
            OrderPlanBase base = new OrderPlanBase();
            base.setOrderId(orderId);
            base.setPlanId(binding.planId());
            base.setTurbineId(f.turbine().getId());
            base.setPlannedKw(f.plannedKw());
            base.setAvailableKw(f.availableKw());
            base.setBaseKw(f.baseKw());
            bases.add(base);
        }
        return bases;
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
     * 机组执行回报：按机组＋时刻去重；满 10 分钟未达标则告警并按最新可用出力重分摊。
     */
    @Transactional
    public ReportResponse report(ReportRequest request) {
        Instant now = clock.now();
        String fingerprint = Fingerprints.struct(
                request.requestId(), request.turbineCode(),
                request.measuredAt() == null ? null : request.measuredAt().toString(),
                String.valueOf(request.powerKw()));

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
        // 计划绑定指令：重分摊仍引用受理时冻结的原计划上限，仅以最新实时可用值参与；
        // 新计划发布不影响已受理指令。
        List<Turbine> effectiveTurbines = applyFrozenPlanCaps(order, turbines);
        int farmActual = effectiveTurbines.stream().mapToInt(Turbine::getAvailablePowerKw).sum();
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
        List<Allocation> reallocations =
                AllocationEngine.allocate(effectiveTurbines, order.getTargetPowerKw(),
                        order.getId(), newCycleNo);
        dispatchRepository.insertCycle(buildCycle(order.getId(), newCycleNo, now));
        dispatchRepository.insertAllocations(order.getId(), newCycleNo, reallocations);
        dispatchRepository.updateAlertReallocated(order.getId(), cycleNo, newCycleNo);
        dispatchRepository.updateCurrentCycle(order.getId(), newCycleNo);
        order.setCurrentCycleNo(newCycleNo);
    }

    /**
     * 计划绑定指令以冻结的原计划上限裁剪最新实时可用出力：base = min(实时可用, 冻结计划)；
     * 未绑定计划的指令直接返回实时机组列表，行为不变。
     */
    private List<Turbine> applyFrozenPlanCaps(CurtailOrder order, List<Turbine> turbines) {
        if (order.getPlanId() == null) {
            return turbines;
        }
        Map<Long, Integer> plannedById = new HashMap<>();
        for (OrderPlanBase base : dispatchRepository.findPlanBases(order.getId())) {
            plannedById.put(base.getTurbineId(), base.getPlannedKw());
        }
        if (plannedById.isEmpty()) {
            return turbines;
        }
        List<Turbine> effective = new ArrayList<>(turbines.size());
        for (Turbine t : turbines) {
            Integer planned = plannedById.get(t.getId());
            if (planned == null) {
                effective.add(t);
            } else {
                effective.add(copyWithAvailable(t, Math.min(t.getAvailablePowerKw(), planned)));
            }
        }
        return effective;
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

    /**
     * 指令计划基数证据：受理时冻结的版本与各机组计划/实时/可参与基数，重复查询结果稳定。
     * 指令不存在 404；未绑定计划的指令无基数证据，返回 404。
     */
    public PlanBaseEvidenceResponse planBaseEvidence(String orderNo) {
        CurtailOrder order = dispatchRepository.findOrderByNo(orderNo);
        if (order == null) {
            throw new ApiNotFoundException("指令不存在：" + orderNo);
        }
        if (order.getPlanId() == null) {
            throw new ApiNotFoundException("指令未绑定日前计划，无计划基数证据：" + orderNo);
        }
        List<Turbine> turbines = turbineRepository.findByFarm(order.getFarmCode());
        Map<Long, String> codeById = new HashMap<>();
        for (Turbine t : turbines) {
            codeById.put(t.getId(), t.getTurbineCode());
        }
        List<PlanBaseEvidenceResponse.BaseItem> items = dispatchRepository.findPlanBases(order.getId())
                .stream()
                .map(b -> new PlanBaseEvidenceResponse.BaseItem(
                        codeById.getOrDefault(b.getTurbineId(), String.valueOf(b.getTurbineId())),
                        b.getPlannedKw(), b.getAvailableKw(), b.getBaseKw()))
                .toList();
        return new PlanBaseEvidenceResponse(order.getId(), order.getOrderNo(), order.getPlanId(),
                order.getPlanVersion(), order.getPlanDate(), order.getPeriodIndex(),
                order.getReceivedAt(), items);
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
