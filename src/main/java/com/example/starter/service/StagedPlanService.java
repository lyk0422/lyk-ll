package com.example.starter.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.domain.CurtailOrder;
import com.example.starter.domain.IdempotentRecord;
import com.example.starter.domain.OrderStatus;
import com.example.starter.domain.StagedMinuteConfirm;
import com.example.starter.domain.StagedPlan;
import com.example.starter.domain.StagedPlanStatus;
import com.example.starter.domain.StagedPlanStep;
import com.example.starter.domain.StagedPlanTurbine;
import com.example.starter.domain.Turbine;
import com.example.starter.dto.StagedPlanCancelRequest;
import com.example.starter.dto.StagedPlanCancelResponse;
import com.example.starter.dto.StagedPlanDetailResponse;
import com.example.starter.dto.StagedPlanModifyRequest;
import com.example.starter.dto.StagedPlanModifyResponse;
import com.example.starter.dto.StagedReceiptItem;
import com.example.starter.dto.StagedReceiptRequest;
import com.example.starter.dto.StagedReceiptResponse;
import com.example.starter.dto.StagedSlopeInput;
import com.example.starter.dto.StagedTurbineView;
import com.example.starter.repo.DispatchRepository;
import com.example.starter.repo.IdempotentRepository;
import com.example.starter.repo.StagedPlanRepository;
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
 * 受控分段计划执行服务：分钟回执整分钟确认（缺一机组零更新）、
 * 每分钟事务内重查机组可用状态（失效则该分钟不执行、计划暂停，历史不回退）、
 * 暂停后取消释放占用、带 expectedVersion 的修改（成功版本加 1、旧版 409）。
 * 进度、功率、版本与幂等记录在同一事务提交。
 */
@Service
public class StagedPlanService {

    private final ServiceClock clock;
    private final TurbineRepository turbineRepository;
    private final DispatchRepository dispatchRepository;
    private final StagedPlanRepository stagedPlanRepository;
    private final IdempotentRepository idempotentRepository;
    private final DispatchService dispatchService;
    private final ObjectMapper objectMapper;

    public StagedPlanService(ServiceClock clock,
                             TurbineRepository turbineRepository,
                             DispatchRepository dispatchRepository,
                             StagedPlanRepository stagedPlanRepository,
                             IdempotentRepository idempotentRepository,
                             DispatchService dispatchService,
                             ObjectMapper objectMapper) {
        this.clock = clock;
        this.turbineRepository = turbineRepository;
        this.dispatchRepository = dispatchRepository;
        this.stagedPlanRepository = stagedPlanRepository;
        this.idempotentRepository = idempotentRepository;
        this.dispatchService = dispatchService;
        this.objectMapper = objectMapper;
    }

    /**
     * 分钟回执：序号须为当前进度+1（不可提前执行或跳号），时刻为启动 UTC 时刻加序号分钟；
     * 回执须给齐全部参与机组且功率等于冻结该分钟阶梯，整分钟一起确认后更新实际功率与进度；
     * 缺一机组零更新。每分钟重查机组可用状态，任一失效则该分钟不执行、计划暂停（历史不回退）。
     * 最后一分钟确认完成指令。确认后复用旧超时判定：恰满 10 分钟未达标则告警重分摊并重排剩余阶梯。
     */
    @Transactional
    public StagedReceiptResponse confirmMinute(StagedReceiptRequest request) {
        Instant now = clock.now();
        String fingerprint = fingerprintReceipt(request);

        IdempotentRecord existing = idempotentRepository.findByRequestId(request.requestId());
        if (existing != null) {
            return resolveReceiptIdempotent(existing, fingerprint);
        }

        CurtailOrder order = dispatchRepository.findOrderByNo(request.orderNo());
        if (order == null) {
            throw new ApiNotFoundException("指令不存在：" + request.orderNo());
        }
        StagedPlan planRef = stagedPlanRepository.findPlanByOrderId(order.getId());
        if (planRef == null) {
            throw new ApiNotFoundException("指令未启用分段执行模式：" + request.orderNo());
        }

        // 锁指令行（与旧回报、告警重分摊串行化），再锁计划行——全库统一的加锁顺序。
        CurtailOrder locked = dispatchRepository.lockOrderById(order.getId());
        if (locked.getStatus() != OrderStatus.DISPATCHED) {
            throw new ApiConflictException(
                    "指令已非执行中状态（" + locked.getStatus() + "），分段回执不再受理");
        }
        order = locked;
        StagedPlan plan = stagedPlanRepository.lockPlanById(planRef.getId());

        // 持锁后二次确认幂等：并发同键请求在此串行化，后到者重放首次结果而非误判跳号。
        existing = idempotentRepository.findByRequestId(request.requestId());
        if (existing != null) {
            return resolveReceiptIdempotent(existing, fingerprint);
        }

        if (plan.getStatus() == StagedPlanStatus.PAUSED) {
            throw new ApiConflictException("分段计划已暂停，该分钟不执行：" + request.orderNo());
        }
        if (plan.getStatus() != StagedPlanStatus.EXECUTING) {
            throw new ApiConflictException(
                    "分段计划已终态（" + plan.getStatus() + "），回执不再受理");
        }

        // 序号校验：不可跳号，也不可重报已确认序号（同键重放由幂等处理，不走到这里）。
        if (request.minuteNo() != plan.getCurrentMinute() + 1) {
            throw new ApiConflictException(
                    "分钟序号须为 " + (plan.getCurrentMinute() + 1)
                            + "，不可提前执行或跳号，实际为 " + request.minuteNo());
        }
        // 时刻校验：分钟时刻 = 启动 UTC 时刻 + 序号分钟，不能提前执行。
        Instant minuteAt = plan.getStartAt().plus(request.minuteNo(), ChronoUnit.MINUTES);
        if (now.isBefore(minuteAt)) {
            throw new ApiConflictException(
                    "分钟 " + request.minuteNo() + " 未到执行时刻 " + minuteAt + "，不能提前执行");
        }

        List<StagedPlanTurbine> planTurbines = stagedPlanRepository.findTurbines(plan.getId());
        List<StagedPlanStep> steps = stagedPlanRepository.findStepsOfMinute(
                plan.getId(), plan.getPlanVersion(), request.minuteNo());
        Map<Long, Integer> stepByTurbine = steps.stream()
                .collect(Collectors.toMap(StagedPlanStep::getTurbineId, StagedPlanStep::getPowerKw));

        List<Turbine> farmTurbines = turbineRepository.findByFarm(order.getFarmCode());
        Map<String, Turbine> turbineByCode = farmTurbines.stream()
                .collect(Collectors.toMap(Turbine::getTurbineCode, t -> t));
        Map<Long, Turbine> turbineById = farmTurbines.stream()
                .collect(Collectors.toMap(Turbine::getId, t -> t));

        // 内容校验：恰好覆盖全部参与机组、无重复、功率等于冻结阶梯——任一不满足零更新。
        Map<Long, Integer> requestByTurbine = new LinkedHashMap<>();
        for (StagedReceiptItem item : request.items()) {
            Turbine t = turbineByCode.get(item.turbineCode());
            if (t == null) {
                throw new ApiBadRequestException("机组不存在：" + item.turbineCode());
            }
            if (!stepByTurbine.containsKey(t.getId())) {
                throw new ApiUnprocessableException(
                        "机组 " + item.turbineCode() + " 非本计划参与机组");
            }
            if (requestByTurbine.put(t.getId(), item.powerKw()) != null) {
                throw new ApiBadRequestException("机组重复回报：" + item.turbineCode());
            }
        }
        if (requestByTurbine.size() != planTurbines.size()) {
            throw new ApiUnprocessableException(
                    "回执须给齐全部 " + planTurbines.size() + " 台参与机组，缺一机组零更新");
        }
        for (Map.Entry<Long, Integer> e : requestByTurbine.entrySet()) {
            int frozen = stepByTurbine.get(e.getKey());
            if (e.getValue() != frozen) {
                throw new ApiUnprocessableException(
                        "机组 " + turbineById.get(e.getKey()).getTurbineCode() + " 第 "
                                + request.minuteNo() + " 分钟功率 " + e.getValue()
                                + " kW 不等于冻结阶梯 " + frozen + " kW");
            }
        }

        // 以上校验失败均抛异常回滚：失败不占键。
        IdempotentRecord record = newIdempotent(request.requestId(), "STAGED_RECEIPT", fingerprint, now);
        if (!idempotentRepository.tryInsert(record)) {
            return resolveReceiptIdempotent(
                    idempotentRepository.findByRequestId(request.requestId()), fingerprint);
        }

        // 每分钟事务内重查机组可用状态：任一失效则该分钟不执行、计划暂停，已执行历史不回退。
        List<String> unavailable = planTurbines.stream()
                .map(pt -> turbineById.get(pt.getTurbineId()))
                .filter(t -> t == null || !"AVAILABLE".equals(t.getStatus()))
                .map(t -> t == null ? "?" : t.getTurbineCode())
                .toList();
        if (!unavailable.isEmpty()) {
            stagedPlanRepository.updateStatus(plan.getId(), StagedPlanStatus.PAUSED, now);
            StagedReceiptResponse paused = new StagedReceiptResponse(request.requestId(),
                    order.getOrderNo(), plan.getId(), plan.getPlanVersion(), request.minuteNo(),
                    minuteAt, false, true, false, plan.getCurrentMinute(), List.of(), false);
            idempotentRepository.updateRefAndResponse(
                    request.requestId(), plan.getId(), toJson(paused));
            return paused;
        }

        // 整分钟一起确认：更新各机组实际功率与计划进度（同事务）。
        for (StagedPlanTurbine pt : planTurbines) {
            turbineRepository.updateAvailablePower(
                    pt.getTurbineId(), stepByTurbine.get(pt.getTurbineId()), now);
        }
        StagedMinuteConfirm confirm = new StagedMinuteConfirm();
        confirm.setPlanId(plan.getId());
        confirm.setPlanVersion(plan.getPlanVersion());
        confirm.setMinuteNo(request.minuteNo());
        confirm.setRequestId(request.requestId());
        confirm.setConfirmedAt(now);
        stagedPlanRepository.insertConfirm(confirm);
        stagedPlanRepository.updateProgress(plan.getId(), request.minuteNo(), now);

        // 最后一分钟才完成指令：释放机组占用，指令置 COMPLETED 终态。
        boolean completed = request.minuteNo() == plan.getDurationMinutes();
        if (completed) {
            stagedPlanRepository.updateStatus(plan.getId(), StagedPlanStatus.COMPLETED, now);
            stagedPlanRepository.releaseOccupations(plan.getId());
            dispatchRepository.markCompleted(order.getId());
        } else {
            // 旧超时判定：恰满 10 分钟未达标则告警重分摊，并重排守斜率的新剩余计划。
            dispatchService.maybeAlertAndReallocate(order, now);
        }

        List<StagedReceiptItem> applied = planTurbines.stream()
                .map(pt -> new StagedReceiptItem(
                        turbineById.get(pt.getTurbineId()).getTurbineCode(),
                        stepByTurbine.get(pt.getTurbineId())))
                .toList();
        StagedReceiptResponse response = new StagedReceiptResponse(request.requestId(),
                order.getOrderNo(), plan.getId(), plan.getPlanVersion(), request.minuteNo(),
                minuteAt, true, false, completed, request.minuteNo(), applied, false);
        idempotentRepository.updateRefAndResponse(request.requestId(), plan.getId(), toJson(response));
        return response;
    }

    /**
     * 修改分段计划：带 expectedVersion（旧版 409）；从当前实际功率按新斜率重排剩余阶梯，
     * 冻结目标不变；成功版本加 1。仅执行中计划可修改；暂停计划须先取消。
     */
    @Transactional
    public StagedPlanModifyResponse modify(String orderNo, StagedPlanModifyRequest request) {
        Instant now = clock.now();
        String fingerprint = fingerprintModify(orderNo, request);

        IdempotentRecord existing = idempotentRepository.findByRequestId(request.requestId());
        if (existing != null) {
            return resolveModifyIdempotent(existing, fingerprint);
        }

        CurtailOrder order = dispatchRepository.findOrderByNo(orderNo);
        if (order == null) {
            throw new ApiNotFoundException("指令不存在：" + orderNo);
        }
        StagedPlan planRef = stagedPlanRepository.findPlanByOrderId(order.getId());
        if (planRef == null) {
            throw new ApiNotFoundException("指令未启用分段执行模式：" + orderNo);
        }

        dispatchRepository.lockOrderById(order.getId());
        StagedPlan plan = stagedPlanRepository.lockPlanById(planRef.getId());

        // 持锁后二次确认幂等：并发同键修改在此串行化，后到者重放首次结果。
        existing = idempotentRepository.findByRequestId(request.requestId());
        if (existing != null) {
            return resolveModifyIdempotent(existing, fingerprint);
        }

        if (plan.getStatus() != StagedPlanStatus.EXECUTING) {
            throw new ApiConflictException(
                    "仅执行中的分段计划可修改，当前状态：" + plan.getStatus());
        }
        if (request.expectedVersion() != plan.getPlanVersion()) {
            throw new ApiConflictException(
                    "expectedVersion=" + request.expectedVersion() + " 与当前计划版本 "
                            + plan.getPlanVersion() + " 不一致（旧版拒绝）");
        }

        List<StagedPlanTurbine> planTurbines = stagedPlanRepository.findTurbines(plan.getId());
        Map<Long, StagedPlanTurbine> ptByTurbine = planTurbines.stream()
                .collect(Collectors.toMap(StagedPlanTurbine::getTurbineId, pt -> pt));
        List<Turbine> farmTurbines = turbineRepository.findByFarm(order.getFarmCode());
        Map<String, Turbine> turbineByCode = farmTurbines.stream()
                .collect(Collectors.toMap(Turbine::getTurbineCode, t -> t));
        Map<Long, Turbine> turbineById = farmTurbines.stream()
                .collect(Collectors.toMap(Turbine::getId, t -> t));

        // 须恰好覆盖全部参与机组。
        Map<Long, StagedSlopeInput> slopeByTurbine = new LinkedHashMap<>();
        for (StagedSlopeInput in : request.turbines()) {
            Turbine t = turbineByCode.get(in.turbineCode());
            if (t == null) {
                throw new ApiBadRequestException("机组不存在：" + in.turbineCode());
            }
            if (!ptByTurbine.containsKey(t.getId())) {
                throw new ApiUnprocessableException(
                        "机组 " + in.turbineCode() + " 非本计划参与机组");
            }
            if (slopeByTurbine.put(t.getId(), in) != null) {
                throw new ApiBadRequestException("机组重复登记：" + in.turbineCode());
            }
        }
        if (slopeByTurbine.size() != planTurbines.size()) {
            throw new ApiUnprocessableException(
                    "修改须恰好覆盖全部 " + planTurbines.size() + " 台参与机组");
        }

        // 可行性：从当前实际功率按新斜率须能在期限内到达冻结目标，否则拒绝（版本不变、不占键）。
        int duration = 1;
        for (StagedPlanTurbine pt : planTurbines) {
            StagedSlopeInput in = slopeByTurbine.get(pt.getTurbineId());
            int start = turbineById.get(pt.getTurbineId()).getAvailablePowerKw();
            int needed = StaircaseEngine.neededMinutes(start, pt.getTargetPowerKw(), in.rampKwPerMin());
            if (needed > in.deadlineMinutes()) {
                throw new ApiUnprocessableException(
                        "机组 " + in.turbineCode() + " 期限内无法到达目标：需 " + needed
                                + " 分钟，超过期限 " + in.deadlineMinutes() + " 分钟，修改拒绝");
            }
            duration = Math.max(duration, in.deadlineMinutes());
        }

        IdempotentRecord record = newIdempotent(request.requestId(), "STAGED_MODIFY", fingerprint, now);
        if (!idempotentRepository.tryInsert(record)) {
            return resolveModifyIdempotent(
                    idempotentRepository.findByRequestId(request.requestId()), fingerprint);
        }

        int newVersion = plan.getPlanVersion() + 1;
        stagedPlanRepository.reformPlan(plan.getId(), newVersion, now, duration, now);
        List<StagedPlanTurbine> reformed = new ArrayList<>();
        for (StagedPlanTurbine pt : planTurbines) {
            StagedSlopeInput in = slopeByTurbine.get(pt.getTurbineId());
            int start = turbineById.get(pt.getTurbineId()).getAvailablePowerKw();
            stagedPlanRepository.updateTurbineSlope(plan.getId(), pt.getTurbineId(), start,
                    pt.getTargetPowerKw(), in.rampKwPerMin(), in.deadlineMinutes());
            StagedPlanTurbine v = new StagedPlanTurbine();
            v.setPlanId(plan.getId());
            v.setTurbineId(pt.getTurbineId());
            v.setStartPowerKw(start);
            v.setTargetPowerKw(pt.getTargetPowerKw());
            v.setRampKwPerMin(in.rampKwPerMin());
            v.setDeadlineMinutes(in.deadlineMinutes());
            reformed.add(v);
        }
        stagedPlanRepository.insertSteps(buildSteps(plan.getId(), newVersion, duration, reformed));

        List<StagedTurbineView> views = reformed.stream()
                .map(pt -> new StagedTurbineView(
                        turbineById.get(pt.getTurbineId()).getTurbineCode(),
                        pt.getStartPowerKw(), pt.getTargetPowerKw(), pt.getRampKwPerMin(),
                        pt.getDeadlineMinutes(),
                        StaircaseEngine.neededMinutes(pt.getStartPowerKw(), pt.getTargetPowerKw(),
                                pt.getRampKwPerMin())))
                .toList();
        StagedPlanModifyResponse response = new StagedPlanModifyResponse(request.requestId(),
                orderNo, plan.getId(), newVersion, StagedPlanStatus.EXECUTING.name(),
                now, duration, views, false);
        idempotentRepository.updateRefAndResponse(request.requestId(), plan.getId(), toJson(response));
        return response;
    }

    /**
     * 取消暂停中的分段计划：释放机组占用，不自动恢复机组原功率（保持最后确认值）。
     */
    @Transactional
    public StagedPlanCancelResponse cancel(String orderNo, StagedPlanCancelRequest request) {
        Instant now = clock.now();
        String fingerprint = fingerprintCancel(orderNo, request);

        IdempotentRecord existing = idempotentRepository.findByRequestId(request.requestId());
        if (existing != null) {
            return resolveCancelIdempotent(existing, fingerprint);
        }

        CurtailOrder order = dispatchRepository.findOrderByNo(orderNo);
        if (order == null) {
            throw new ApiNotFoundException("指令不存在：" + orderNo);
        }
        StagedPlan planRef = stagedPlanRepository.findPlanByOrderId(order.getId());
        if (planRef == null) {
            throw new ApiNotFoundException("指令未启用分段执行模式：" + orderNo);
        }

        dispatchRepository.lockOrderById(order.getId());
        StagedPlan plan = stagedPlanRepository.lockPlanById(planRef.getId());

        // 持锁后二次确认幂等：并发同键取消在此串行化，后到者重放首次结果。
        existing = idempotentRepository.findByRequestId(request.requestId());
        if (existing != null) {
            return resolveCancelIdempotent(existing, fingerprint);
        }

        if (plan.getStatus() != StagedPlanStatus.PAUSED) {
            throw new ApiConflictException(
                    "仅暂停中的分段计划可取消，当前状态：" + plan.getStatus());
        }

        IdempotentRecord record = newIdempotent(request.requestId(), "STAGED_CANCEL", fingerprint, now);
        if (!idempotentRepository.tryInsert(record)) {
            return resolveCancelIdempotent(
                    idempotentRepository.findByRequestId(request.requestId()), fingerprint);
        }

        stagedPlanRepository.updateStatus(plan.getId(), StagedPlanStatus.CANCELED, now);
        stagedPlanRepository.releaseOccupations(plan.getId());

        StagedPlanCancelResponse response = new StagedPlanCancelResponse(request.requestId(),
                orderNo, plan.getId(), plan.getPlanVersion(), StagedPlanStatus.CANCELED.name(), false);
        idempotentRepository.updateRefAndResponse(request.requestId(), plan.getId(), toJson(response));
        return response;
    }

    /** 分段计划详情：当前版本状态/进度/期限、机组斜率与逐分钟冻结阶梯（含确认情况），可复算。 */
    @Transactional(readOnly = true)
    public StagedPlanDetailResponse detail(String orderNo) {
        CurtailOrder order = dispatchRepository.findOrderByNo(orderNo);
        if (order == null) {
            throw new ApiNotFoundException("指令不存在：" + orderNo);
        }
        StagedPlan plan = stagedPlanRepository.findPlanByOrderId(order.getId());
        if (plan == null) {
            throw new ApiNotFoundException("指令未启用分段执行模式：" + orderNo);
        }

        List<StagedPlanTurbine> planTurbines = stagedPlanRepository.findTurbines(plan.getId());
        Map<Long, String> codeById = turbineRepository.findByFarm(order.getFarmCode()).stream()
                .collect(Collectors.toMap(Turbine::getId, Turbine::getTurbineCode));
        List<StagedPlanStep> steps =
                stagedPlanRepository.findStepsOfVersion(plan.getId(), plan.getPlanVersion());
        Map<Integer, List<StagedPlanStep>> stepsByMinute = steps.stream()
                .collect(Collectors.groupingBy(StagedPlanStep::getMinuteNo));
        Map<Integer, StagedMinuteConfirm> confirmByMinute = stagedPlanRepository
                .findConfirms(plan.getId()).stream()
                .filter(c -> c.getPlanVersion() == plan.getPlanVersion())
                .collect(Collectors.toMap(StagedMinuteConfirm::getMinuteNo, c -> c));

        List<StagedPlanDetailResponse.MinuteView> minutes = new ArrayList<>();
        for (int m = 1; m <= plan.getDurationMinutes(); m++) {
            List<StagedReceiptItem> items = stepsByMinute
                    .getOrDefault(m, List.of()).stream()
                    .map(s -> new StagedReceiptItem(
                            codeById.getOrDefault(s.getTurbineId(), String.valueOf(s.getTurbineId())),
                            s.getPowerKw()))
                    .toList();
            StagedMinuteConfirm confirm = confirmByMinute.get(m);
            minutes.add(new StagedPlanDetailResponse.MinuteView(m,
                    plan.getStartAt().plus(m, ChronoUnit.MINUTES),
                    confirm != null, confirm == null ? null : confirm.getConfirmedAt(), items));
        }

        List<StagedTurbineView> views = planTurbines.stream()
                .map(pt -> new StagedTurbineView(
                        codeById.getOrDefault(pt.getTurbineId(), String.valueOf(pt.getTurbineId())),
                        pt.getStartPowerKw(), pt.getTargetPowerKw(), pt.getRampKwPerMin(),
                        pt.getDeadlineMinutes(),
                        StaircaseEngine.neededMinutes(pt.getStartPowerKw(), pt.getTargetPowerKw(),
                                pt.getRampKwPerMin())))
                .toList();
        return new StagedPlanDetailResponse(orderNo, plan.getId(), plan.getPlanVersion(),
                plan.getStatus().name(), plan.getStartAt(), plan.getCurrentMinute(),
                plan.getDurationMinutes(), views, minutes);
    }

    /** 生成某版本完整阶梯：每分钟向目标移动不超过上限，最后恰到目标，到达后保持。 */
    private List<StagedPlanStep> buildSteps(long planId, int planVersion, int duration,
                                            List<StagedPlanTurbine> planTurbines) {
        List<StagedPlanStep> steps = new ArrayList<>();
        for (StagedPlanTurbine pt : planTurbines) {
            for (int m = 1; m <= duration; m++) {
                StagedPlanStep s = new StagedPlanStep();
                s.setPlanId(planId);
                s.setPlanVersion(planVersion);
                s.setMinuteNo(m);
                s.setTurbineId(pt.getTurbineId());
                s.setPowerKw(StaircaseEngine.powerAt(pt.getStartPowerKw(), pt.getTargetPowerKw(),
                        pt.getRampKwPerMin(), m));
                steps.add(s);
            }
        }
        return steps;
    }

    private String fingerprintReceipt(StagedReceiptRequest request) {
        List<String> itemTokens = request.items().stream()
                .sorted(Comparator.comparing(StagedReceiptItem::turbineCode))
                .map(i -> Canonical.obj(
                        "turbineCode", Canonical.str(i.turbineCode()),
                        "powerKw", Canonical.integer(i.powerKw())))
                .toList();
        return Canonical.sha256(Canonical.obj(
                "bizType", Canonical.str("STAGED_RECEIPT"),
                "requestId", Canonical.str(request.requestId()),
                "orderNo", Canonical.str(request.orderNo()),
                "minuteNo", Canonical.integer(request.minuteNo()),
                "items", Canonical.list(itemTokens)));
    }

    private String fingerprintModify(String orderNo, StagedPlanModifyRequest request) {
        List<String> turbineTokens = request.turbines().stream()
                .sorted(Comparator.comparing(StagedSlopeInput::turbineCode))
                .map(t -> Canonical.obj(
                        "turbineCode", Canonical.str(t.turbineCode()),
                        "rampKwPerMin", Canonical.integer(t.rampKwPerMin()),
                        "deadlineMinutes", Canonical.integer(t.deadlineMinutes())))
                .toList();
        return Canonical.sha256(Canonical.obj(
                "bizType", Canonical.str("STAGED_MODIFY"),
                "requestId", Canonical.str(request.requestId()),
                "orderNo", Canonical.str(orderNo),
                "expectedVersion", Canonical.integer(request.expectedVersion()),
                "turbines", Canonical.list(turbineTokens)));
    }

    private String fingerprintCancel(String orderNo, StagedPlanCancelRequest request) {
        return Canonical.sha256(Canonical.obj(
                "bizType", Canonical.str("STAGED_CANCEL"),
                "requestId", Canonical.str(request.requestId()),
                "orderNo", Canonical.str(orderNo)));
    }

    private StagedReceiptResponse resolveReceiptIdempotent(
            IdempotentRecord existing, String fingerprint) {
        if (!existing.getFingerprint().equals(fingerprint)) {
            throw new ApiConflictException("相同 requestId 的业务参数与首次请求不一致");
        }
        if (existing.getResponseBody() != null && !existing.getResponseBody().isBlank()) {
            return fromJson(existing.getResponseBody(), StagedReceiptResponse.class).asReplay();
        }
        throw new ApiConflictException("首次请求仍在处理中");
    }

    private StagedPlanModifyResponse resolveModifyIdempotent(
            IdempotentRecord existing, String fingerprint) {
        if (!existing.getFingerprint().equals(fingerprint)) {
            throw new ApiConflictException("相同 requestId 的业务参数与首次请求不一致");
        }
        if (existing.getResponseBody() != null && !existing.getResponseBody().isBlank()) {
            return fromJson(existing.getResponseBody(), StagedPlanModifyResponse.class).asReplay();
        }
        throw new ApiConflictException("首次请求仍在处理中");
    }

    private StagedPlanCancelResponse resolveCancelIdempotent(
            IdempotentRecord existing, String fingerprint) {
        if (!existing.getFingerprint().equals(fingerprint)) {
            throw new ApiConflictException("相同 requestId 的业务参数与首次请求不一致");
        }
        if (existing.getResponseBody() != null && !existing.getResponseBody().isBlank()) {
            return fromJson(existing.getResponseBody(), StagedPlanCancelResponse.class).asReplay();
        }
        throw new ApiConflictException("首次请求仍在处理中");
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
