package com.example.starter.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.domain.Allocation;
import com.example.starter.domain.AlertEvent;
import com.example.starter.domain.AlertType;
import com.example.starter.domain.ControlledPlan;
import com.example.starter.domain.ControlledPlanStatus;
import com.example.starter.domain.ControlledPlanStep;
import com.example.starter.domain.ControlledPlanTurbine;
import com.example.starter.domain.CurtailOrder;
import com.example.starter.domain.DispatchCycle;
import com.example.starter.domain.IdempotentRecord;
import com.example.starter.domain.OrderStatus;
import com.example.starter.domain.PlanSegment;
import com.example.starter.domain.PlanVersion;
import com.example.starter.domain.Turbine;
import com.example.starter.dto.ControlledCancelRequest;
import com.example.starter.dto.ControlledCancelResponse;
import com.example.starter.dto.ControlledOrderReceiveRequest;
import com.example.starter.dto.ControlledOrderReceiveResponse;
import com.example.starter.dto.ControlledPlanViewResponse;
import com.example.starter.dto.ControlledReplanRequest;
import com.example.starter.dto.ControlledReplanResponse;
import com.example.starter.dto.ControlledStepItem;
import com.example.starter.dto.ControlledTurbineInput;
import com.example.starter.dto.ControlledTurbinePlanItem;
import com.example.starter.dto.MinuteConfirmRequest;
import com.example.starter.dto.MinuteConfirmResponse;
import com.example.starter.dto.MinuteReportItem;
import com.example.starter.dto.ReplanTurbineInput;
import com.example.starter.dto.ReplayableResponse;
import com.example.starter.repo.ControlledPlanRepository;
import com.example.starter.repo.DispatchRepository;
import com.example.starter.repo.IdempotentRepository;
import com.example.starter.repo.PlanRepository;
import com.example.starter.repo.TurbineRepository;
import com.example.starter.support.Canonical;
import com.example.starter.support.ServiceClock;
import com.example.starter.web.ApiBadRequestException;
import com.example.starter.web.ApiConflictException;
import com.example.starter.web.ApiNotFoundException;
import com.example.starter.web.ApiUnprocessableException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 受控分段执行模式服务（可选；旧未启用指令保持原行为）。
 * 登记：原最低保留、日前基数和分摊规则继续决定最终目标，另冻结各机组起始功率、
 * 每分钟升降上限与期限，生成完整阶梯；任一机组期限内无法到达则全单拒绝，不写部分计划。
 * 每机组同一时刻只能一份执行中分段计划（controlled_occupation 主键兜底）。
 */
@Service
public class ControlledPlanService {

    /** 下发后达标判定时长：与旧模式一致，恰满 10 分钟即触发判定。 */
    static final long DUE_MINUTES = 10;

    private final ServiceClock clock;
    private final TurbineRepository turbineRepository;
    private final DispatchRepository dispatchRepository;
    private final PlanRepository planRepository;
    private final IdempotentRepository idempotentRepository;
    private final ControlledPlanRepository controlledPlanRepository;
    private final ObjectMapper objectMapper;

    public ControlledPlanService(ServiceClock clock,
                                 TurbineRepository turbineRepository,
                                 DispatchRepository dispatchRepository,
                                 PlanRepository planRepository,
                                 IdempotentRepository idempotentRepository,
                                 ControlledPlanRepository controlledPlanRepository,
                                 ObjectMapper objectMapper) {
        this.clock = clock;
        this.turbineRepository = turbineRepository;
        this.dispatchRepository = dispatchRepository;
        this.planRepository = planRepository;
        this.idempotentRepository = idempotentRepository;
        this.controlledPlanRepository = controlledPlanRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 登记受控分段指令：风场行锁串行化；最终目标由原最低保留、日前基数与分摊规则决定；
     * 冻结计划基数、最终分配及斜率并生成完整阶梯；任一机组期限内无法到达全单拒绝（422），
     * 事务回滚不写部分计划、失败不占键；同 requestId 同参重放，异参 409。
     */
    @Transactional
    public ControlledOrderReceiveResponse register(ControlledOrderReceiveRequest request) {
        Instant now = clock.now();
        validatePlanBindingShape(request);
        String fingerprint = fingerprintRegister(request);

        IdempotentRecord existing = idempotentRepository.findByRequestId(request.requestId());
        if (existing != null) {
            return resolveIdempotent(existing, fingerprint, ControlledOrderReceiveResponse.class);
        }

        if (!turbineRepository.lockFarmRow(request.farmCode())) {
            throw new ApiBadRequestException("风场不存在：" + request.farmCode());
        }

        existing = idempotentRepository.findByRequestId(request.requestId());
        if (existing != null) {
            return resolveIdempotent(existing, fingerprint, ControlledOrderReceiveResponse.class);
        }

        List<Turbine> turbines = turbineRepository.findByFarm(request.farmCode());
        Map<String, Turbine> turbineByCode = turbines.stream()
                .collect(Collectors.toMap(Turbine::getTurbineCode, t -> t));
        // 参与机组须恰好覆盖全场每台机组各一条。
        Map<String, ControlledTurbineInput> inputByCode = new LinkedHashMap<>();
        for (ControlledTurbineInput input : request.turbines()) {
            Turbine t = turbineByCode.get(input.turbineCode());
            if (t == null) {
                throw new ApiUnprocessableException(
                        "受控指令包含风场 " + request.farmCode() + " 中不存在的机组：" + input.turbineCode());
            }
            if (inputByCode.put(input.turbineCode(), input) != null) {
                throw new ApiUnprocessableException("机组重复登记：" + input.turbineCode());
            }
        }
        if (!inputByCode.keySet().equals(turbineByCode.keySet())) {
            throw new ApiUnprocessableException("受控指令须恰好覆盖全场每台机组各一条，缺项或多项整单拒绝");
        }
        // 起始值须在原最低保留（与旧分摊一致：实际可用与技术下限的较小者）与当时可用出力之间。
        for (Turbine t : turbines) {
            int start = inputByCode.get(t.getTurbineCode()).startPowerKw();
            int floor = Math.min(t.getTechnicalMinKw(), t.getAvailablePowerKw());
            if (start < floor || start > t.getAvailablePowerKw()) {
                throw new ApiUnprocessableException("机组 " + t.getTurbineCode() + " 起始功率 " + start
                        + " kW 不在最低保留 " + floor + " kW 与当时可用出力 "
                        + t.getAvailablePowerKw() + " kW 之间");
            }
        }

        CurtailOrder active = dispatchRepository.findActiveOrder(request.farmCode());
        if (active != null && !active.getReceivedAt().isBefore(now)) {
            throw new ApiConflictException(
                    "风场同一时刻已有执行中限电指令：" + active.getOrderNo());
        }

        // 可选日前计划绑定：冻结版本与该段核定基数；过期版本拒绝（422）。
        PlanVersion version = null;
        Map<Long, Integer> plannedById = null;
        List<Turbine> participating = turbines;
        if (request.planBound()) {
            version = planRepository.lockVersion(
                    request.farmCode(), request.planDate(), request.planVersionNo());
            if (version == null) {
                throw new ApiNotFoundException("计划版本不存在：" + request.farmCode() + " "
                        + request.planDate() + " v" + request.planVersionNo());
            }
            if (!"Y".equals(version.getCurrentFlag())) {
                throw new ApiUnprocessableException(
                        "计划版本 v" + request.planVersionNo() + " 已过期，新指令只能绑定当前版本");
            }
            List<PlanSegment> segments =
                    planRepository.findSegments(version.getId(), request.segmentIndex());
            plannedById = segments.stream()
                    .collect(Collectors.toMap(PlanSegment::getTurbineId, PlanSegment::getPowerKw));
            if (segments.size() != turbines.size()
                    || !plannedById.keySet().equals(
                            turbines.stream().map(Turbine::getId).collect(Collectors.toSet()))) {
                throw new ApiUnprocessableException(
                        "计划第 " + request.segmentIndex() + " 段机组基数不完整，无法受理");
            }
            participating = DispatchService.applyPlanBasis(turbines, plannedById);
        }
        int farmParticipating = participating.stream().mapToInt(Turbine::getAvailablePowerKw).sum();

        // 最终分配：原最低保留、日前基数与分摊规则。目标不可实现整单拒绝（422），失败不占键。
        List<Allocation> allocations;
        try {
            allocations = AllocationEngine.allocate(participating, request.targetPowerKw(), 0L, 1);
        } catch (ApiBadRequestException ex) {
            throw new ApiUnprocessableException(ex.getMessage());
        }
        if (plannedById != null) {
            DispatchService.freezePlanBasis(allocations, turbines, plannedById);
        }
        Map<Long, Integer> targetById = allocations.stream()
                .collect(Collectors.toMap(Allocation::getTurbineId, Allocation::getTargetPowerKw));

        // 斜率可行性 + 完整阶梯：任一机组期限内无法到达 → 全单拒绝，不先写部分计划。
        int maxDuration = request.turbines().stream()
                .mapToInt(ControlledTurbineInput::durationMinutes).max().orElse(0);
        Map<Long, List<Integer>> stepsByTurbine = new LinkedHashMap<>();
        for (Turbine t : turbines) {
            ControlledTurbineInput input = inputByCode.get(t.getTurbineCode());
            int target = targetById.get(t.getId());
            List<Integer> ownSteps;
            try {
                ownSteps = RampStaircase.build(input.startPowerKw(), target,
                        input.rampRateKwPerMin(), input.durationMinutes());
            } catch (ApiUnprocessableException ex) {
                throw new ApiUnprocessableException(
                        "机组 " + t.getTurbineCode() + " " + ex.getMessage() + "，全单拒绝");
            }
            // 扩展到计划总分钟数：提前到达的机组其后保持各自目标。
            List<Integer> full = new ArrayList<>(maxDuration);
            for (int minute = 1; minute <= maxDuration; minute++) {
                full.add(minute <= ownSteps.size() ? ownSteps.get(minute - 1) : target);
            }
            stepsByTurbine.put(t.getId(), full);
        }

        // 全部校验通过后才落幂等占位：失败不占键。
        IdempotentRecord record = newIdempotent(request.requestId(), "CTRL_ORDER", fingerprint, now);
        if (!idempotentRepository.tryInsert(record)) {
            return resolveIdempotent(idempotentRepository.findByRequestId(request.requestId()),
                    fingerprint, ControlledOrderReceiveResponse.class);
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
        order.setControlledFlag("Y");
        if (version != null) {
            order.setPlanVersionId(version.getId());
            order.setPlanVersionNo(version.getVersionNo());
            order.setPlanDate(request.planDate());
            order.setSegmentIndex(request.segmentIndex());
        }
        long newId = dispatchRepository.insertOrder(order);
        allocations.forEach(a -> a.setOrderId(newId));

        if (active != null) {
            // 同一事务：旧指令作废并释放其受控占用 → 新指令生效。
            dispatchRepository.markSuperseded(active.getId(), newId);
            controlledPlanRepository.closeOpenPlanAndRelease(active.getId(), now);
        }
        dispatchRepository.insertCycle(buildCycle(newId, 1, now));
        dispatchRepository.insertAllocations(newId, 1, allocations);

        ControlledPlan plan = newPlanVersion(newId, 1, now, maxDuration,
                request.requestId(), fingerprint, now);
        long planId = controlledPlanRepository.insertPlan(plan);
        List<ControlledPlanTurbine> planTurbines = new ArrayList<>();
        List<ControlledPlanStep> steps = new ArrayList<>();
        for (Turbine t : turbines) {
            ControlledTurbineInput input = inputByCode.get(t.getTurbineCode());
            ControlledPlanTurbine pt = new ControlledPlanTurbine();
            pt.setPlanId(planId);
            pt.setTurbineId(t.getId());
            pt.setStartPowerKw(input.startPowerKw());
            pt.setRampRateKwPerMin(input.rampRateKwPerMin());
            pt.setDurationMinutes(input.durationMinutes());
            pt.setTargetPowerKw(targetById.get(t.getId()));
            planTurbines.add(pt);
            List<Integer> turbineSteps = stepsByTurbine.get(t.getId());
            for (int minute = 1; minute <= maxDuration; minute++) {
                ControlledPlanStep s = new ControlledPlanStep();
                s.setPlanId(planId);
                s.setTurbineId(t.getId());
                s.setMinuteNo(minute);
                s.setPowerKw(turbineSteps.get(minute - 1));
                steps.add(s);
            }
        }
        controlledPlanRepository.insertPlanTurbines(planId, planTurbines);
        controlledPlanRepository.insertSteps(planId, steps);
        occupyAll(turbines, newId, planId, now);
        // 旧指令已在本事务作废，此处将新指令置为执行中。
        dispatchRepository.activateOrder(newId);

        ControlledOrderReceiveResponse response = new ControlledOrderReceiveResponse(
                newId, order.getOrderNo(), request.requestId(), request.farmCode(),
                request.targetPowerKw(), farmParticipating, farmParticipating - request.targetPowerKw(),
                OrderStatus.DISPATCHED.name(), 1, now, maxDuration,
                version == null ? null : version.getId(),
                version == null ? null : version.getVersionNo(),
                request.planDate(), request.segmentIndex(),
                toTurbineItems(turbines, planTurbines, stepsByTurbine, now), false);
        idempotentRepository.updateRefAndResponse(request.requestId(), newId, toJson(response));
        return response;
    }

    /**
     * 受控分段整分钟回执：按分钟序号给齐全部参与机组功率且须等于冻结该分钟阶梯；
     * 计划时刻 = 启动 UTC 时刻 + 序号分钟，不能提前执行或跳号；整分钟一起确认后才更新
     * 实际功率与进度，缺一机组零更新；最后一分钟确认后指令完成。
     * 每分钟事务内重查机组可用状态：任一失效则该分钟不执行、计划暂停，已执行历史不回退。
     */
    @Transactional
    public MinuteConfirmResponse confirmMinute(String orderNo, int minuteNo,
                                               MinuteConfirmRequest request) {
        Instant now = clock.now();
        CurtailOrder order = dispatchRepository.findOrderByNo(orderNo);
        if (order == null) {
            throw new ApiNotFoundException("指令不存在：" + orderNo);
        }
        String fingerprint = fingerprintConfirm(orderNo, minuteNo, request);

        IdempotentRecord existing = idempotentRepository.findByRequestId(request.requestId());
        if (existing != null) {
            return resolveIdempotent(existing, fingerprint, MinuteConfirmResponse.class);
        }

        // 锁定指令行，串行化同指令的并发分钟确认与超时重规划。
        CurtailOrder locked = dispatchRepository.lockOrderById(order.getId());
        if (!locked.isControlled()) {
            throw new ApiBadRequestException("指令非受控分段模式，无分段阶梯：" + orderNo);
        }
        if (locked.getStatus() != OrderStatus.DISPATCHED) {
            throw new ApiConflictException("指令已非执行中（" + locked.getStatus() + "），不能确认分钟");
        }
        ControlledPlan plan = controlledPlanRepository.findLatestPlan(locked.getId());
        if (plan == null) {
            throw new ApiConflictException("受控指令缺少分段计划：" + orderNo);
        }
        if (plan.getStatus() == ControlledPlanStatus.PAUSED) {
            throw new ApiConflictException("分段计划已暂停，须重规划或取消后才能继续：" + orderNo);
        }
        if (plan.getStatus() != ControlledPlanStatus.EXECUTING) {
            throw new ApiConflictException("分段计划已关闭（" + plan.getStatus() + "）");
        }

        existing = idempotentRepository.findByRequestId(request.requestId());
        if (existing != null) {
            return resolveIdempotent(existing, fingerprint, MinuteConfirmResponse.class);
        }

        if (minuteNo < 1 || minuteNo > plan.getDurationMinutes()) {
            throw new ApiBadRequestException(
                    "分钟序号超出 1.." + plan.getDurationMinutes() + "：" + minuteNo);
        }
        if (minuteNo != plan.getConfirmedMinute() + 1) {
            throw new ApiConflictException("须按分钟序号依次确认，当前应确认第 "
                    + (plan.getConfirmedMinute() + 1) + " 分钟，不能跳号或重复确认");
        }
        Instant scheduledAt = plan.getStartAt().plus(minuteNo, ChronoUnit.MINUTES);
        if (now.isBefore(scheduledAt)) {
            throw new ApiConflictException(
                    "第 " + minuteNo + " 分钟计划时刻 " + scheduledAt + " 未到，不能提前执行");
        }

        List<ControlledPlanTurbine> planTurbines =
                controlledPlanRepository.findPlanTurbines(plan.getId());
        Map<Long, Integer> stepPowerById = new LinkedHashMap<>();
        controlledPlanRepository.findSteps(plan.getId(), minuteNo)
                .forEach(s -> stepPowerById.put(s.getTurbineId(), s.getPowerKw()));
        List<Turbine> turbines = turbineRepository.findByFarm(locked.getFarmCode());
        Map<String, Turbine> turbineByCode = turbines.stream()
                .collect(Collectors.toMap(Turbine::getTurbineCode, t -> t));
        Map<Long, Turbine> turbineById = turbines.stream()
                .collect(Collectors.toMap(Turbine::getId, t -> t));
        Map<Long, ControlledPlanTurbine> planTurbineById = planTurbines.stream()
                .collect(Collectors.toMap(ControlledPlanTurbine::getTurbineId, pt -> pt));

        // 回执须恰好给齐全部参与机组各一条；缺一机组零更新（校验失败整体回滚）。
        Map<String, MinuteReportItem> reportByCode = new LinkedHashMap<>();
        for (MinuteReportItem item : request.reports()) {
            Turbine t = turbineByCode.get(item.turbineCode());
            if (t == null || !planTurbineById.containsKey(t.getId())) {
                throw new ApiUnprocessableException("回执包含非参与机组：" + item.turbineCode());
            }
            if (reportByCode.put(item.turbineCode(), item) != null) {
                throw new ApiUnprocessableException("回执机组重复：" + item.turbineCode());
            }
        }
        if (reportByCode.size() != planTurbines.size()) {
            throw new ApiUnprocessableException("回执缺项：须给齐全部 " + planTurbines.size()
                    + " 台参与机组该分钟功率，缺一机组零更新");
        }
        for (MinuteReportItem item : request.reports()) {
            Turbine t = turbineByCode.get(item.turbineCode());
            int expected = stepPowerById.get(t.getId());
            if (item.powerKw() != expected) {
                throw new ApiUnprocessableException("机组 " + item.turbineCode() + " 第 " + minuteNo
                        + " 分钟功率 " + item.powerKw() + " kW 不等于冻结阶梯 " + expected + " kW");
            }
        }

        IdempotentRecord record = newIdempotent(request.requestId(), "CTRL_CONFIRM", fingerprint, now);
        if (!idempotentRepository.tryInsert(record)) {
            return resolveIdempotent(idempotentRepository.findByRequestId(request.requestId()),
                    fingerprint, MinuteConfirmResponse.class);
        }

        // 每分钟事务内重查机组可用状态：任一失效则该分钟不执行、计划暂停，已执行历史不回退。
        boolean anyDown = planTurbines.stream()
                .anyMatch(pt -> !"AVAILABLE".equals(turbineById.get(pt.getTurbineId()).getRunStatus()));
        if (anyDown) {
            controlledPlanRepository.updateStatus(plan.getId(), ControlledPlanStatus.PAUSED, null);
            MinuteConfirmResponse paused = new MinuteConfirmResponse(orderNo, request.requestId(),
                    plan.getPlanVersion(), minuteNo, scheduledAt, now, false,
                    ControlledPlanStatus.PAUSED.name(), locked.getStatus().name(),
                    plan.getConfirmedMinute(), plan.getDurationMinutes(), false,
                    request.reports(), false);
            idempotentRepository.updateRefAndResponse(
                    request.requestId(), plan.getId(), toJson(paused));
            return paused;
        }

        // 整分钟一起确认：登记确认记录、逐机组更新实际功率、推进进度，同事务提交。
        controlledPlanRepository.insertMinuteConfirm(plan.getId(), minuteNo, request.requestId(), now);
        for (MinuteReportItem item : request.reports()) {
            turbineRepository.updateAvailablePower(
                    turbineByCode.get(item.turbineCode()).getId(), item.powerKw(), now);
        }
        controlledPlanRepository.updateProgress(plan.getId(), minuteNo);
        plan.setConfirmedMinute(minuteNo);

        boolean completed = minuteNo == plan.getDurationMinutes();
        String orderStatus = locked.getStatus().name();
        String planStatus = ControlledPlanStatus.EXECUTING.name();
        if (completed) {
            // 最后一分钟才完成指令：计划完成、指令终态、释放机组占用。
            controlledPlanRepository.updateStatus(plan.getId(), ControlledPlanStatus.COMPLETED, now);
            dispatchRepository.markCompleted(locked.getId());
            controlledPlanRepository.releaseOccupations(locked.getId());
            orderStatus = OrderStatus.COMPLETED.name();
            planStatus = ControlledPlanStatus.COMPLETED.name();
        } else {
            // 旧超时重分配：恰满 10 分钟仍未达标则告警，并重新形成守斜率的新剩余计划。
            maybeTimeoutReplan(locked, plan, now);
            planStatus = controlledPlanRepository.findLatestPlan(locked.getId()).getStatus().name();
        }

        MinuteConfirmResponse response = new MinuteConfirmResponse(orderNo, request.requestId(),
                plan.getPlanVersion(), minuteNo, scheduledAt, now, true, planStatus, orderStatus,
                minuteNo, plan.getDurationMinutes(), completed, request.reports(), false);
        idempotentRepository.updateRefAndResponse(request.requestId(), plan.getId(), toJson(response));
        return response;
    }

    /**
     * 旧超时重分配的受控形态：当前轮次下发恰满 10 分钟，全场最新出力仍高于目标则告警，
     * 按原规则（原日前基数不换）重分摊生成下一轮，并重新形成守斜率的新剩余计划；
     * 斜率内无法到达新目标时计划暂停，等待人工重规划或取消。
     */
    private void maybeTimeoutReplan(CurtailOrder order, ControlledPlan plan, Instant now) {
        int cycleNo = order.getCurrentCycleNo();
        DispatchCycle cycle = dispatchRepository.findCycles(order.getId()).stream()
                .filter(c -> c.getCycleNo() == cycleNo)
                .findFirst()
                .orElseThrow(() -> new ApiBadRequestException("当前下发轮次缺失：" + cycleNo));
        if (now.isBefore(cycle.getDueAt())) {
            return;
        }
        if (dispatchRepository.findAlert(order.getId(), cycleNo) != null) {
            return;
        }

        List<Turbine> turbines = turbineRepository.findByFarm(order.getFarmCode());
        Map<Long, Integer> plannedById = null;
        List<Turbine> participating = turbines;
        if (order.getPlanVersionId() != null) {
            // 原日前基数不换：仍引用受理时冻结的核定计划，仅刷新实时可用。
            plannedById = dispatchRepository.findAllocations(order.getId(), 1).stream()
                    .collect(Collectors.toMap(
                            Allocation::getTurbineId, Allocation::getPlannedPowerKw));
            participating = DispatchService.applyPlanBasis(turbines, plannedById);
        }
        int farmActual = participating.stream().mapToInt(Turbine::getAvailablePowerKw).sum();
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
        List<Allocation> reallocations = AllocationEngine.allocate(
                participating, order.getTargetPowerKw(), order.getId(), newCycleNo);
        if (plannedById != null) {
            DispatchService.freezePlanBasis(reallocations, turbines, plannedById);
        }
        dispatchRepository.insertCycle(buildCycle(order.getId(), newCycleNo, now));
        dispatchRepository.insertAllocations(order.getId(), newCycleNo, reallocations);
        dispatchRepository.updateAlertReallocated(order.getId(), cycleNo, newCycleNo);
        dispatchRepository.updateCurrentCycle(order.getId(), newCycleNo);
        order.setCurrentCycleNo(newCycleNo);

        // 守斜率的新剩余计划：起始=当前实际功率，斜率与期限沿用登记值，目标=新一轮分摊。
        Map<Long, Integer> targetById = reallocations.stream()
                .collect(Collectors.toMap(Allocation::getTurbineId, Allocation::getTargetPowerKw));
        List<ControlledPlanTurbine> regs = controlledPlanRepository.findPlanTurbines(plan.getId());
        Map<Long, Integer> rampById = regs.stream().collect(Collectors.toMap(
                ControlledPlanTurbine::getTurbineId, ControlledPlanTurbine::getRampRateKwPerMin));
        Map<Long, Integer> durationById = regs.stream().collect(Collectors.toMap(
                ControlledPlanTurbine::getTurbineId, ControlledPlanTurbine::getDurationMinutes));
        Map<Long, Integer> startById = turbines.stream()
                .collect(Collectors.toMap(Turbine::getId, Turbine::getAvailablePowerKw));
        String autoKey = "AUTO-REALLOC-" + order.getId() + "-" + newCycleNo;
        FormedPlan formed = tryFormRemainingPlan(order, plan, startById, rampById, durationById,
                targetById, autoKey, now);
        if (formed == null) {
            // 斜率内无法到达新目标：计划暂停（占用保留），已执行历史不回退。
            controlledPlanRepository.updateStatus(plan.getId(), ControlledPlanStatus.PAUSED, null);
        }
    }

    /** 重规划结果载体：新计划版本 id、版本号与完整阶梯。 */
    private record FormedPlan(long planId, int planVersion, int durationMinutes,
                              List<ControlledPlanTurbine> turbines,
                              Map<Long, List<Integer>> stepsByTurbine) {
    }

    /**
     * 尝试形成守斜率的新剩余计划版本：任一机组斜率内无法到达则返回 null（不写任何记录）；
     * 可行则旧版本置 REPLACED、新版本 EXECUTING（版本号+1、进度归零、启动时刻为现在），
     * 机组占用迁移到新版本。起始功率、目标、斜率与期限由调用方给出。
     */
    private FormedPlan tryFormRemainingPlan(CurtailOrder order, ControlledPlan currentPlan,
                                            Map<Long, Integer> startById,
                                            Map<Long, Integer> rampById,
                                            Map<Long, Integer> durationById,
                                            Map<Long, Integer> targetById,
                                            String requestId, Instant now) {
        List<Turbine> turbines = turbineRepository.findByFarm(order.getFarmCode());
        int maxDuration = durationById.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        Map<Long, List<Integer>> stepsByTurbine = new LinkedHashMap<>();
        for (Turbine t : turbines) {
            int start = startById.get(t.getId());
            int target = targetById.get(t.getId());
            int ramp = rampById.get(t.getId());
            int duration = durationById.get(t.getId());
            if (!RampStaircase.reachable(start, target, ramp, duration)) {
                return null;
            }
            List<Integer> own = RampStaircase.build(start, target, ramp, duration);
            List<Integer> full = new ArrayList<>(maxDuration);
            for (int minute = 1; minute <= maxDuration; minute++) {
                full.add(minute <= own.size() ? own.get(minute - 1) : target);
            }
            stepsByTurbine.put(t.getId(), full);
        }

        int newVersion = currentPlan.getPlanVersion() + 1;
        String fingerprint = fingerprintAutoReplan(order.getId(), newVersion, startById,
                rampById, durationById, targetById);
        controlledPlanRepository.updateStatus(
                currentPlan.getId(), ControlledPlanStatus.REPLACED, now);
        ControlledPlan newPlan = newPlanVersion(order.getId(), newVersion, now, maxDuration,
                requestId, fingerprint, now);
        long newPlanId = controlledPlanRepository.insertPlan(newPlan);
        List<ControlledPlanTurbine> planTurbines = new ArrayList<>();
        List<ControlledPlanStep> steps = new ArrayList<>();
        for (Turbine t : turbines) {
            ControlledPlanTurbine pt = new ControlledPlanTurbine();
            pt.setPlanId(newPlanId);
            pt.setTurbineId(t.getId());
            pt.setStartPowerKw(startById.get(t.getId()));
            pt.setRampRateKwPerMin(rampById.get(t.getId()));
            pt.setDurationMinutes(durationById.get(t.getId()));
            pt.setTargetPowerKw(targetById.get(t.getId()));
            planTurbines.add(pt);
            List<Integer> turbineSteps = stepsByTurbine.get(t.getId());
            for (int minute = 1; minute <= maxDuration; minute++) {
                ControlledPlanStep s = new ControlledPlanStep();
                s.setPlanId(newPlanId);
                s.setTurbineId(t.getId());
                s.setMinuteNo(minute);
                s.setPowerKw(turbineSteps.get(minute - 1));
                steps.add(s);
            }
        }
        controlledPlanRepository.insertPlanTurbines(newPlanId, planTurbines);
        controlledPlanRepository.insertSteps(newPlanId, steps);
        controlledPlanRepository.moveOccupationToPlan(order.getId(), newPlanId);
        return new FormedPlan(newPlanId, newVersion, maxDuration, planTurbines, stepsByTurbine);
    }

    /** 自动重规划版本的留证指纹：覆盖新版本的全部冻结参数。 */
    private String fingerprintAutoReplan(long orderId, int newVersion,
                                         Map<Long, Integer> startById,
                                         Map<Long, Integer> rampById,
                                         Map<Long, Integer> durationById,
                                         Map<Long, Integer> targetById) {
        List<String> turbineTokens = startById.keySet().stream().sorted()
                .map(id -> Canonical.obj(
                        "turbineId", Canonical.integer(id),
                        "startPowerKw", Canonical.integer(startById.get(id)),
                        "rampRateKwPerMin", Canonical.integer(rampById.get(id)),
                        "durationMinutes", Canonical.integer(durationById.get(id)),
                        "targetPowerKw", Canonical.integer(targetById.get(id))))
                .toList();
        return Canonical.sha256(Canonical.obj(
                "bizType", Canonical.str("CTRL_AUTO_REPLAN"),
                "orderId", Canonical.integer(orderId),
                "planVersion", Canonical.integer(newVersion),
                "turbines", Canonical.list(turbineTokens)));
    }

    /** 整分钟回执指纹：覆盖指令、分钟序号与全部回执项（按机组编码确定性排序）。 */
    private String fingerprintConfirm(String orderNo, int minuteNo, MinuteConfirmRequest request) {
        List<String> reportTokens = request.reports().stream()
                .sorted(Comparator.comparing(MinuteReportItem::turbineCode))
                .map(r -> Canonical.obj(
                        "turbineCode", Canonical.str(r.turbineCode()),
                        "powerKw", Canonical.integer(r.powerKw())))
                .toList();
        return Canonical.sha256(Canonical.obj(
                "bizType", Canonical.str("CTRL_CONFIRM"),
                "requestId", Canonical.str(request.requestId()),
                "orderNo", Canonical.str(orderNo),
                "minuteNo", Canonical.integer(minuteNo),
                "reports", Canonical.list(reportTokens)));
    }

    /**
     * 修改（重规划）受控分段计划：从当前实际功率重新形成守斜率的剩余计划，目标沿用当前轮次分摊。
     * expectedVersion 须等于最新版本，成功版本加 1，旧版 409；斜率内无法到达 422，
     * 失败不占键、版本不变；同 requestId 同参重放，异参 409。
     */
    @Transactional
    public ControlledReplanResponse replan(String orderNo, ControlledReplanRequest request) {
        Instant now = clock.now();
        CurtailOrder order = dispatchRepository.findOrderByNo(orderNo);
        if (order == null) {
            throw new ApiNotFoundException("指令不存在：" + orderNo);
        }
        String fingerprint = fingerprintReplan(orderNo, request);

        IdempotentRecord existing = idempotentRepository.findByRequestId(request.requestId());
        if (existing != null) {
            return resolveIdempotent(existing, fingerprint, ControlledReplanResponse.class);
        }

        CurtailOrder locked = dispatchRepository.lockOrderById(order.getId());
        if (!locked.isControlled()) {
            throw new ApiBadRequestException("指令非受控分段模式：" + orderNo);
        }
        if (locked.getStatus() != OrderStatus.DISPATCHED) {
            throw new ApiConflictException("指令已非执行中（" + locked.getStatus() + "），不能重规划");
        }
        ControlledPlan plan = controlledPlanRepository.findLatestPlan(locked.getId());
        if (plan == null) {
            throw new ApiConflictException("受控指令缺少分段计划：" + orderNo);
        }
        if (plan.getStatus() == ControlledPlanStatus.CANCELLED
                || plan.getStatus() == ControlledPlanStatus.COMPLETED) {
            throw new ApiConflictException("分段计划已终态（" + plan.getStatus() + "），不能重规划");
        }
        if (plan.getPlanVersion() != request.expectedVersion()) {
            throw new ApiConflictException("expectedVersion=" + request.expectedVersion()
                    + " 与当前最新计划版本 v" + plan.getPlanVersion() + " 不一致（旧版拒绝）");
        }

        existing = idempotentRepository.findByRequestId(request.requestId());
        if (existing != null) {
            return resolveIdempotent(existing, fingerprint, ControlledReplanResponse.class);
        }

        // 目标沿用当前轮次分摊结果；起始功率取当前实际功率（不自动恢复原功率）。
        Map<Long, Integer> targetById = dispatchRepository
                .findAllocations(locked.getId(), locked.getCurrentCycleNo()).stream()
                .collect(Collectors.toMap(Allocation::getTurbineId, Allocation::getTargetPowerKw));
        List<Turbine> turbines = turbineRepository.findByFarm(locked.getFarmCode());
        Map<String, Turbine> turbineByCode = turbines.stream()
                .collect(Collectors.toMap(Turbine::getTurbineCode, t -> t));
        Map<Long, ControlledPlanTurbine> planTurbineById = controlledPlanRepository
                .findPlanTurbines(plan.getId()).stream()
                .collect(Collectors.toMap(ControlledPlanTurbine::getTurbineId, pt -> pt));

        Map<String, ReplanTurbineInput> inputByCode = new LinkedHashMap<>();
        for (ReplanTurbineInput input : request.turbines()) {
            Turbine t = turbineByCode.get(input.turbineCode());
            if (t == null || !planTurbineById.containsKey(t.getId())) {
                throw new ApiUnprocessableException("重规划包含非参与机组：" + input.turbineCode());
            }
            if (inputByCode.put(input.turbineCode(), input) != null) {
                throw new ApiUnprocessableException("重规划机组重复：" + input.turbineCode());
            }
        }
        if (inputByCode.size() != planTurbineById.size()) {
            throw new ApiUnprocessableException("重规划须恰好覆盖全部 " + planTurbineById.size()
                    + " 台参与机组");
        }

        Map<Long, Integer> startById = turbines.stream()
                .collect(Collectors.toMap(Turbine::getId, Turbine::getAvailablePowerKw));
        Map<Long, Integer> rampById = new LinkedHashMap<>();
        Map<Long, Integer> durationById = new LinkedHashMap<>();
        for (Turbine t : turbines) {
            ReplanTurbineInput input = inputByCode.get(t.getTurbineCode());
            rampById.put(t.getId(), input.rampRateKwPerMin());
            durationById.put(t.getId(), input.durationMinutes());
        }
        // 斜率可行性：任一机组期限内无法到达 → 422，失败不占键、版本不变。
        for (Turbine t : turbines) {
            if (!RampStaircase.reachable(startById.get(t.getId()), targetById.get(t.getId()),
                    rampById.get(t.getId()), durationById.get(t.getId()))) {
                throw new ApiUnprocessableException("机组 " + t.getTurbineCode() + " 起始 "
                        + startById.get(t.getId()) + " kW 以每分钟 " + rampById.get(t.getId())
                        + " kW 升降，" + durationById.get(t.getId()) + " 分钟内无法到达目标 "
                        + targetById.get(t.getId()) + " kW，重规划拒绝");
            }
        }

        IdempotentRecord record = newIdempotent(request.requestId(), "CTRL_REPLAN", fingerprint, now);
        if (!idempotentRepository.tryInsert(record)) {
            return resolveIdempotent(idempotentRepository.findByRequestId(request.requestId()),
                    fingerprint, ControlledReplanResponse.class);
        }

        FormedPlan formed = tryFormRemainingPlan(locked, plan, startById, rampById, durationById,
                targetById, request.requestId(), now);
        if (formed == null) {
            // 不可达：上面已逐机组校验。
            throw new ApiUnprocessableException("重规划不可行，版本不变");
        }

        ControlledReplanResponse response = new ControlledReplanResponse(orderNo,
                request.requestId(), formed.planVersion(), now, formed.durationMinutes(),
                ControlledPlanStatus.EXECUTING.name(),
                toTurbineItems(turbines, formed.turbines(), formed.stepsByTurbine(), now), false);
        idempotentRepository.updateRefAndResponse(
                request.requestId(), formed.planId(), toJson(response));
        return response;
    }

    /**
     * 取消暂停中的分段计划：释放机组占用，不自动恢复原功率；指令终态 CANCELLED。
     * expectedVersion 须等于最新版本，旧版 409；同 requestId 同参重放，异参 409。
     */
    @Transactional
    public ControlledCancelResponse cancel(String orderNo, ControlledCancelRequest request) {
        Instant now = clock.now();
        CurtailOrder order = dispatchRepository.findOrderByNo(orderNo);
        if (order == null) {
            throw new ApiNotFoundException("指令不存在：" + orderNo);
        }
        String fingerprint = fingerprintCancel(orderNo, request);

        IdempotentRecord existing = idempotentRepository.findByRequestId(request.requestId());
        if (existing != null) {
            return resolveIdempotent(existing, fingerprint, ControlledCancelResponse.class);
        }

        CurtailOrder locked = dispatchRepository.lockOrderById(order.getId());
        if (!locked.isControlled()) {
            throw new ApiBadRequestException("指令非受控分段模式：" + orderNo);
        }
        ControlledPlan plan = controlledPlanRepository.findLatestPlan(locked.getId());
        if (plan == null) {
            throw new ApiConflictException("受控指令缺少分段计划：" + orderNo);
        }
        if (plan.getPlanVersion() != request.expectedVersion()) {
            throw new ApiConflictException("expectedVersion=" + request.expectedVersion()
                    + " 与当前最新计划版本 v" + plan.getPlanVersion() + " 不一致（旧版拒绝）");
        }
        if (plan.getStatus() != ControlledPlanStatus.PAUSED) {
            throw new ApiConflictException("仅暂停中的分段计划可取消，当前状态：" + plan.getStatus());
        }

        existing = idempotentRepository.findByRequestId(request.requestId());
        if (existing != null) {
            return resolveIdempotent(existing, fingerprint, ControlledCancelResponse.class);
        }

        IdempotentRecord record = newIdempotent(request.requestId(), "CTRL_CANCEL", fingerprint, now);
        if (!idempotentRepository.tryInsert(record)) {
            return resolveIdempotent(idempotentRepository.findByRequestId(request.requestId()),
                    fingerprint, ControlledCancelResponse.class);
        }

        controlledPlanRepository.updateStatus(plan.getId(), ControlledPlanStatus.CANCELLED, now);
        controlledPlanRepository.releaseOccupations(locked.getId());
        dispatchRepository.markCancelled(locked.getId());

        ControlledCancelResponse response = new ControlledCancelResponse(orderNo,
                request.requestId(), plan.getPlanVersion(), ControlledPlanStatus.CANCELLED.name(),
                OrderStatus.CANCELLED.name(), now, false);
        idempotentRepository.updateRefAndResponse(request.requestId(), plan.getId(), toJson(response));
        return response;
    }

    /** 当前分段计划查询：版本、状态、进度与完整冻结阶梯。 */
    @Transactional(readOnly = true)
    public ControlledPlanViewResponse planView(String orderNo) {
        CurtailOrder order = dispatchRepository.findOrderByNo(orderNo);
        if (order == null) {
            throw new ApiNotFoundException("指令不存在：" + orderNo);
        }
        ControlledPlan plan = controlledPlanRepository.findLatestPlan(order.getId());
        if (plan == null) {
            throw new ApiNotFoundException("指令无受控分段计划：" + orderNo);
        }
        List<Turbine> turbines = turbineRepository.findByFarm(order.getFarmCode());
        List<ControlledPlanTurbine> planTurbines =
                controlledPlanRepository.findPlanTurbines(plan.getId());
        Map<Long, List<Integer>> stepsByTurbine = new LinkedHashMap<>();
        controlledPlanRepository.findSteps(plan.getId()).forEach(s ->
                stepsByTurbine.computeIfAbsent(s.getTurbineId(), k -> new ArrayList<>())
                        .add(s.getPowerKw()));
        return new ControlledPlanViewResponse(orderNo, order.getId(), plan.getPlanVersion(),
                plan.getStatus().name(), order.getStatus().name(), plan.getStartAt(),
                plan.getDurationMinutes(), plan.getConfirmedMinute(),
                toTurbineItems(turbines, planTurbines, stepsByTurbine, plan.getStartAt()));
    }

    /** 重规划指纹：覆盖指令、期望版本与全部机组新参数（按编码排序）。 */
    private String fingerprintReplan(String orderNo, ControlledReplanRequest request) {
        List<String> turbineTokens = request.turbines().stream()
                .sorted(Comparator.comparing(ReplanTurbineInput::turbineCode))
                .map(t -> Canonical.obj(
                        "turbineCode", Canonical.str(t.turbineCode()),
                        "rampRateKwPerMin", Canonical.integer(t.rampRateKwPerMin()),
                        "durationMinutes", Canonical.integer(t.durationMinutes())))
                .toList();
        return Canonical.sha256(Canonical.obj(
                "bizType", Canonical.str("CTRL_REPLAN"),
                "requestId", Canonical.str(request.requestId()),
                "orderNo", Canonical.str(orderNo),
                "expectedVersion", Canonical.integer(request.expectedVersion()),
                "turbines", Canonical.list(turbineTokens)));
    }

    /** 取消指纹：覆盖指令与期望版本。 */
    private String fingerprintCancel(String orderNo, ControlledCancelRequest request) {
        return Canonical.sha256(Canonical.obj(
                "bizType", Canonical.str("CTRL_CANCEL"),
                "requestId", Canonical.str(request.requestId()),
                "orderNo", Canonical.str(orderNo),
                "expectedVersion", Canonical.integer(request.expectedVersion())));
    }

    /** 计划绑定三字段必须同时给出或同时缺省。 */
    private void validatePlanBindingShape(ControlledOrderReceiveRequest request) {        int present = (request.planDate() != null ? 1 : 0)
                + (request.segmentIndex() != null ? 1 : 0)
                + (request.planVersionNo() != null ? 1 : 0);
        if (present != 0 && present != 3) {
            throw new ApiBadRequestException(
                    "planDate、segmentIndex、planVersionNo 必须同时给出或同时缺省");
        }
    }

    /** 占用全部参与机组；主键冲突说明同机组已有执行中/暂停中计划（理论不可达，兜底回滚）。 */
    private void occupyAll(List<Turbine> turbines, long orderId, long planId, Instant now) {
        for (Turbine t : turbines) {
            if (!controlledPlanRepository.tryOccupy(t.getId(), orderId, planId, now)) {
                throw new ApiConflictException("机组 " + t.getTurbineCode() + " 已有执行中分段计划");
            }
        }
    }

    private ControlledPlan newPlanVersion(long orderId, int planVersion, Instant startAt,
                                          int durationMinutes, String requestId,
                                          String fingerprint, Instant now) {
        ControlledPlan plan = new ControlledPlan();
        plan.setOrderId(orderId);
        plan.setPlanVersion(planVersion);
        plan.setStatus(ControlledPlanStatus.EXECUTING);
        plan.setStartAt(startAt);
        plan.setDurationMinutes(durationMinutes);
        plan.setConfirmedMinute(0);
        plan.setRequestId(requestId);
        plan.setFingerprint(fingerprint);
        plan.setCreatedAt(now);
        return plan;
    }

    private List<ControlledTurbinePlanItem> toTurbineItems(
            List<Turbine> turbines, List<ControlledPlanTurbine> planTurbines,
            Map<Long, List<Integer>> stepsByTurbine, Instant startAt) {
        Map<Long, ControlledPlanTurbine> ptById = planTurbines.stream()
                .collect(Collectors.toMap(ControlledPlanTurbine::getTurbineId, pt -> pt));
        List<ControlledTurbinePlanItem> items = new ArrayList<>();
        for (Turbine t : turbines) {
            ControlledPlanTurbine pt = ptById.get(t.getId());
            List<Integer> powers = stepsByTurbine.get(t.getId());
            List<ControlledStepItem> stepItems = new ArrayList<>(powers.size());
            for (int minute = 1; minute <= powers.size(); minute++) {
                stepItems.add(new ControlledStepItem(minute,
                        startAt.plus(minute, ChronoUnit.MINUTES), powers.get(minute - 1)));
            }
            items.add(new ControlledTurbinePlanItem(t.getTurbineCode(), pt.getStartPowerKw(),
                    pt.getRampRateKwPerMin(), pt.getDurationMinutes(), pt.getTargetPowerKw(),
                    stepItems));
        }
        return items;
    }

    /** 受控登记指纹：结构化 TLV，覆盖全部业务字段；机组参数按编码确定性排序。 */
    private String fingerprintRegister(ControlledOrderReceiveRequest request) {
        List<String> turbineTokens = request.turbines().stream()
                .sorted(Comparator.comparing(ControlledTurbineInput::turbineCode))
                .map(t -> Canonical.obj(
                        "turbineCode", Canonical.str(t.turbineCode()),
                        "startPowerKw", Canonical.integer(t.startPowerKw()),
                        "rampRateKwPerMin", Canonical.integer(t.rampRateKwPerMin()),
                        "durationMinutes", Canonical.integer(t.durationMinutes())))
                .toList();
        String canonical = Canonical.obj(
                "bizType", Canonical.str("CTRL_ORDER"),
                "requestId", Canonical.str(request.requestId()),
                "farmCode", Canonical.str(request.farmCode()),
                "targetPowerKw", Canonical.integer(request.targetPowerKw()),
                "planDate", request.planDate() == null
                        ? Canonical.str("") : Canonical.date(request.planDate()),
                "segmentIndex", Canonical.integer(
                        request.segmentIndex() == null ? -1 : request.segmentIndex()),
                "planVersionNo", Canonical.integer(
                        request.planVersionNo() == null ? -1 : request.planVersionNo()),
                "turbines", Canonical.list(turbineTokens));
        return Canonical.sha256(canonical);
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

    /** 同键异参 409；同参重放首次冻结结果；占位未回填视为处理中。 */
    private <T extends ReplayableResponse<T>> T resolveIdempotent(
            IdempotentRecord existing, String fingerprint, Class<T> type) {
        if (existing == null) {
            throw new ApiConflictException("并发请求处理中，请重试");
        }
        if (!existing.getFingerprint().equals(fingerprint)) {
            throw new ApiConflictException("相同 requestId 的业务参数与首次请求不一致");
        }
        if (existing.getResponseBody() != null && !existing.getResponseBody().isBlank()) {
            return fromJson(existing.getResponseBody(), type).asReplay();
        }
        throw new ApiConflictException("首次请求仍在处理中");
    }

    private String generateOrderNo(Instant now) {
        return "CO" + now.atZone(clock.zone())
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + "C" + String.format("%03d", ThreadLocalRandom.current().nextInt(1000));
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
