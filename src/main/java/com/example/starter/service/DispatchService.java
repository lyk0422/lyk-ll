package com.example.starter.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
import com.example.starter.domain.DispatchCycle;
import com.example.starter.domain.ExecutionReport;
import com.example.starter.domain.IdempotentRecord;
import com.example.starter.domain.LateReportEvent;
import com.example.starter.domain.OrderStatus;
import com.example.starter.domain.PlanSegment;
import com.example.starter.domain.PlanVersion;
import com.example.starter.domain.Turbine;
import com.example.starter.dto.AllocationItem;
import com.example.starter.dto.AlertView;
import com.example.starter.dto.OrderBasisResponse;
import com.example.starter.dto.OrderDetailResponse;
import com.example.starter.dto.OrderReceiveRequest;
import com.example.starter.dto.OrderReceiveResponse;
import com.example.starter.dto.PlanOrderReceiveRequest;
import com.example.starter.dto.PlanOrderReceiveResponse;
import com.example.starter.dto.ReportRequest;
import com.example.starter.dto.ReportResponse;
import com.example.starter.dto.SettleResponse;
import com.example.starter.repo.DispatchRepository;
import com.example.starter.repo.IdempotentRepository;
import com.example.starter.repo.PlanRepository;
import com.example.starter.repo.TurbineRepository;
import com.example.starter.support.Canonical;
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

    /**
     * 计划绑定模式接收限电指令：
     * 在风场行锁 + 计划版本行锁内校验并冻结版本与该段核定基数；过期（非当前）版本拒绝绑定（422）；
     * 可参与出力 = min(各机组实时可用, 该段核定计划)，据此套用最低保留与比例分摊，目标不可实现整单拒绝（422）；
     * 冻结后新计划发布不重算已受理指令；同 requestId 同参重放首次冻结结果，异参 409，失败不占键。
     */
    @Transactional
    public PlanOrderReceiveResponse receivePlanOrder(PlanOrderReceiveRequest request) {
        Instant now = clock.now();
        // 指纹覆盖调用方给出的精确受理时刻（业务参数一部分）；指令落库受理时刻取服务端时钟。
        String fingerprint = fingerprintPlanOrder(request, request.operateAt());

        IdempotentRecord existing = idempotentRepository.findByRequestId(request.requestId());
        if (existing != null) {
            return resolvePlanOrderIdempotent(existing, fingerprint);
        }

        if (!turbineRepository.lockFarmRow(request.farmCode())) {
            throw new ApiBadRequestException("风场不存在：" + request.farmCode());
        }

        existing = idempotentRepository.findByRequestId(request.requestId());
        if (existing != null) {
            return resolvePlanOrderIdempotent(existing, fingerprint);
        }

        CurtailOrder active = dispatchRepository.findActiveOrder(request.farmCode());
        if (active != null && !active.getReceivedAt().isBefore(now)) {
            throw new ApiConflictException(
                    "风场同一时刻已有执行中限电指令：" + active.getOrderNo());
        }

        // 锁定指定版本行：与计划发布竞争时，要么在发布替换指针前读到它（此时它仍是当前版本），
        // 要么等发布提交后读到最新值；校验的版本与后续读取的段基数始终来自同一锁定快照。
        PlanVersion version = planRepository.lockVersion(
                request.farmCode(), request.planDate(), request.planVersionNo());
        if (version == null) {
            throw new ApiNotFoundException(
                    "计划版本不存在：" + request.farmCode() + " " + request.planDate()
                            + " v" + request.planVersionNo());
        }
        if (!"Y".equals(version.getCurrentFlag())) {
            throw new ApiUnprocessableException(
                    "计划版本 v" + request.planVersionNo() + " 已过期，新指令只能绑定当前版本");
        }

        List<Turbine> turbines = turbineRepository.findByFarm(request.farmCode());
        List<PlanSegment> segments =
                planRepository.findSegments(version.getId(), request.segmentIndex());
        Map<Long, Integer> plannedById = segments.stream()
                .collect(Collectors.toMap(PlanSegment::getTurbineId, PlanSegment::getPowerKw));
        if (segments.size() != turbines.size()
                || !plannedById.keySet().equals(
                        turbines.stream().map(Turbine::getId).collect(Collectors.toSet()))) {
            // 不可达：发布已整版校验；保留防御。
            throw new ApiUnprocessableException(
                    "计划第 " + request.segmentIndex() + " 段机组基数不完整，无法受理");
        }

        List<Turbine> participating = applyPlanBasis(turbines, plannedById);
        int farmParticipating = participating.stream().mapToInt(Turbine::getAvailablePowerKw).sum();

        IdempotentRecord record = new IdempotentRecord();
        record.setRequestId(request.requestId());
        record.setBizType("PLAN_ORDER");
        record.setFingerprint(fingerprint);
        record.setCreatedAt(now);
        record.setResponseBody("");
        if (!idempotentRepository.tryInsert(record)) {
            return resolvePlanOrderIdempotent(
                    idempotentRepository.findByRequestId(request.requestId()), fingerprint);
        }

        // 目标不可实现（高于可参与出力，或低于按可参与出力计的可实现下限）：整单拒绝（422），
        // 事务回滚——上面的幂等占位随之回滚，失败不占键。旧未绑定模式仍维持 400 语义不变。
        List<Allocation> allocations;
        try {
            allocations = AllocationEngine.allocate(
                    participating, request.targetPowerKw(), 0L, 1);
        } catch (ApiBadRequestException ex) {
            throw new ApiUnprocessableException(ex.getMessage());
        }
        freezePlanBasis(allocations, turbines, plannedById);

        CurtailOrder order = new CurtailOrder();
        order.setOrderNo(generateOrderNo(now));
        order.setFarmCode(request.farmCode());
        order.setRequestId(request.requestId());
        order.setFingerprint(fingerprint);
        order.setTargetPowerKw(request.targetPowerKw());
        order.setStatus(OrderStatus.DISPATCHED);
        order.setCurrentCycleNo(1);
        order.setReceivedAt(now);
        order.setPlanVersionId(version.getId());
        order.setPlanVersionNo(version.getVersionNo());
        order.setPlanDate(request.planDate());
        order.setSegmentIndex(request.segmentIndex());
        long newId = dispatchRepository.insertOrder(order);
        allocations.forEach(a -> a.setOrderId(newId));

        if (active != null) {
            dispatchRepository.markSuperseded(active.getId(), newId);
        }
        dispatchRepository.insertCycle(buildCycle(newId, 1, now));
        dispatchRepository.insertAllocations(newId, 1, allocations);
        dispatchRepository.activateOrder(newId);

        Map<Long, Turbine> byId = new LinkedHashMap<>();
        turbines.forEach(t -> byId.put(t.getId(), t));
        List<AllocationItem> items = allocations.stream()
                .map(a -> toItem(a, byId.get(a.getTurbineId())))
                .toList();
        boolean noCurtailment = farmParticipating == request.targetPowerKw();
        PlanOrderReceiveResponse response = new PlanOrderReceiveResponse(newId, order.getOrderNo(),
                request.requestId(), request.farmCode(), request.targetPowerKw(),
                farmParticipating, farmParticipating - request.targetPowerKw(),
                OrderStatus.DISPATCHED.name(), 1, noCurtailment,
                version.getId(), version.getVersionNo(), request.planDate(),
                request.segmentIndex(), true, items, false);
        idempotentRepository.updateRefAndResponse(request.requestId(), newId, toJson(response));
        return response;
    }

    private PlanOrderReceiveResponse resolvePlanOrderIdempotent(
            IdempotentRecord existing, String fingerprint) {
        if (!existing.getFingerprint().equals(fingerprint)) {
            throw new ApiConflictException("相同 requestId 的业务参数与首次请求不一致");
        }
        if (existing.getResponseBody() != null && !existing.getResponseBody().isBlank()) {
            return fromJson(existing.getResponseBody(), PlanOrderReceiveResponse.class).asReplay();
        }
        throw new ApiConflictException("首次请求仍在处理中");
    }

    /**
     * 指令基数证据：返回绑定版本、UTC 时段及各轮每机组“核定计划/实时可用/参与出力/分摊”冻结值。
     * 未绑定计划的指令无此证据（404）；指令本身不存在亦 404。
     */
    @Transactional(readOnly = true)
    public OrderBasisResponse basis(String orderNo) {
        CurtailOrder order = dispatchRepository.findOrderByNo(orderNo);
        if (order == null) {
            throw new ApiNotFoundException("指令不存在：" + orderNo);
        }
        if (order.getPlanVersionId() == null) {
            throw new ApiNotFoundException("指令未绑定日前计划，无计划基数证据：" + orderNo);
        }
        PlanVersion version = planRepository.findVersionById(order.getPlanVersionId());
        List<Turbine> turbines = turbineRepository.findByFarm(order.getFarmCode());
        Map<Long, Turbine> turbineById = turbines.stream()
                .collect(Collectors.toMap(Turbine::getId, t -> t));

        Instant segmentStart = order.getPlanDate().atStartOfDay()
                .plusMinutes(15L * order.getSegmentIndex()).toInstant(ZoneOffset.UTC);
        List<DispatchCycle> cycles = dispatchRepository.findCycles(order.getId());
        List<Allocation> all = dispatchRepository.findAllAllocations(order.getId());
        List<OrderBasisResponse.CycleBasisView> cycleViews = new ArrayList<>();
        for (DispatchCycle c : cycles) {
            List<OrderBasisResponse.TurbineBasisItem> items = all.stream()
                    .filter(a -> a.getCycleNo() == c.getCycleNo())
                    .map(a -> {
                        Turbine t = turbineById.get(a.getTurbineId());
                        int planned = a.getPlannedPowerKw();
                        int realtime = a.getRealtimeAvailableKw();
                        return new OrderBasisResponse.TurbineBasisItem(
                                t.getTurbineCode(), planned, realtime,
                                Math.min(planned, realtime), t.getTechnicalMinKw(),
                                a.getTargetPowerKw(), a.getCurtailKw());
                    })
                    .toList();
            cycleViews.add(new OrderBasisResponse.CycleBasisView(
                    c.getCycleNo(), c.getDispatchedAt(), items));
        }

        return new OrderBasisResponse(order.getId(), order.getOrderNo(), order.getFarmCode(),
                order.getPlanVersionId(), order.getPlanVersionNo(), order.getPlanDate(),
                order.getSegmentIndex(), segmentStart, segmentStart.plusSeconds(15 * 60L),
                version != null && "Y".equals(version.getCurrentFlag()), cycleViews);
    }

    /** 计划绑定指令指纹：结构化 TLV，覆盖全部业务参数与精确受理时刻。 */
    private String fingerprintPlanOrder(PlanOrderReceiveRequest request, Instant receivedAt) {
        String canonical = Canonical.obj(
                "bizType", Canonical.str("PLAN_ORDER"),
                "requestId", Canonical.str(request.requestId()),
                "farmCode", Canonical.str(request.farmCode()),
                "targetPowerKw", Canonical.integer(request.targetPowerKw()),
                "planDate", Canonical.date(request.planDate()),
                "segmentIndex", Canonical.integer(request.segmentIndex()),
                "planVersionNo", Canonical.integer(request.planVersionNo()),
                "receivedAt", Canonical.instant(receivedAt));
        return Canonical.sha256(canonical);
    }

    private OrderReceiveResponse resolveOrderIdempotent(IdempotentRecord existing, String fingerprint) {        if (!existing.getFingerprint().equals(fingerprint)) {
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

        // 计划绑定指令：重分摊仍引用原计划，仅把各机组实时可用刷新为最新值，
        // 参与出力 = min(实时可用, 冻结核定计划)，核定计划与已受理结果不随后续新计划发布改变。
        if (order.getPlanVersionId() != null) {
            Map<Long, Integer> plannedById = dispatchRepository.findAllocations(order.getId(), 1)
                    .stream()
                    .collect(Collectors.toMap(Allocation::getTurbineId, Allocation::getPlannedPowerKw));
            List<Turbine> farmTurbines = turbineRepository.findByFarm(order.getFarmCode());
            List<Turbine> participating = applyPlanBasis(farmTurbines, plannedById);
            int farmParticipating = participating.stream().mapToInt(Turbine::getAvailablePowerKw).sum();
            if (farmParticipating <= order.getTargetPowerKw()) {
                return;
            }

            AlertEvent planAlert = new AlertEvent();
            planAlert.setOrderId(order.getId());
            planAlert.setCycleNo(cycleNo);
            planAlert.setType(AlertType.TARGET_NOT_REACHED);
            planAlert.setFiredAt(now);
            planAlert.setFarmActualKw(farmParticipating);
            dispatchRepository.insertAlert(planAlert);

            int newCycleNo = cycleNo + 1;
            List<Allocation> reallocations =
                    AllocationEngine.allocate(participating, order.getTargetPowerKw(),
                            order.getId(), newCycleNo);
            freezePlanBasis(reallocations, farmTurbines, plannedById);
            dispatchRepository.insertCycle(buildCycle(order.getId(), newCycleNo, now));
            dispatchRepository.insertAllocations(order.getId(), newCycleNo, reallocations);
            dispatchRepository.updateAlertReallocated(order.getId(), cycleNo, newCycleNo);
            dispatchRepository.updateCurrentCycle(order.getId(), newCycleNo);
            order.setCurrentCycleNo(newCycleNo);
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
        List<Allocation> reallocations =
                AllocationEngine.allocate(turbines, order.getTargetPowerKw(), order.getId(), newCycleNo);
        dispatchRepository.insertCycle(buildCycle(order.getId(), newCycleNo, now));
        dispatchRepository.insertAllocations(order.getId(), newCycleNo, reallocations);
        dispatchRepository.updateAlertReallocated(order.getId(), cycleNo, newCycleNo);
        dispatchRepository.updateCurrentCycle(order.getId(), newCycleNo);
        order.setCurrentCycleNo(newCycleNo);
    }

    /**
     * 以冻结核定计划裁剪实时可用：返回同序机组视图，其 availablePowerKw=min(实时, 计划)，
     * 作为最低保留与比例分摊的输入基数。
     */
    private List<Turbine> applyPlanBasis(List<Turbine> turbines, Map<Long, Integer> plannedById) {
        List<Turbine> views = new ArrayList<>(turbines.size());
        for (Turbine t : turbines) {
            Integer planned = plannedById.get(t.getId());
            if (planned == null) {
                // 不可达：受理时整版覆盖全场机组且版本不可变。
                throw new ApiBadRequestException("计划基数缺少机组核定值：" + t.getTurbineCode());
            }
            Turbine v = new Turbine();
            v.setId(t.getId());
            v.setFarmCode(t.getFarmCode());
            v.setTurbineCode(t.getTurbineCode());
            v.setRatedPowerKw(t.getRatedPowerKw());
            v.setTechnicalMinKw(t.getTechnicalMinKw());
            v.setUpdatedAt(t.getUpdatedAt());
            v.setAvailablePowerKw(Math.min(t.getAvailablePowerKw(), planned));
            views.add(v);
        }
        return views;
    }

    /** 将每轮分摊明细回填冻结的核定计划值与该轮实时可用值（参与出力取较小者）。 */
    private void freezePlanBasis(List<Allocation> allocations, List<Turbine> turbines,
                                 Map<Long, Integer> plannedById) {
        Map<Long, Integer> realtimeById = turbines.stream()
                .collect(Collectors.toMap(Turbine::getId, Turbine::getAvailablePowerKw));
        for (Allocation a : allocations) {
            int planned = plannedById.get(a.getTurbineId());
            int realtime = realtimeById.get(a.getTurbineId());
            a.setPlannedPowerKw(planned);
            a.setRealtimeAvailableKw(realtime);
            // 引擎已按 min(实时, 计划) 分摊；防御性校验参与出力一致。
            if (a.getBaseAvailableKw() != Math.min(realtime, planned)) {
                throw new ApiBadRequestException("计划参与出力基数不一致：turbineId=" + a.getTurbineId());
            }
        }
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
                a.getCurtailKw(),
                a.getPlannedPowerKw(),
                a.getRealtimeAvailableKw());
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
