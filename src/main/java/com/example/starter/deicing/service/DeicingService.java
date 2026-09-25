package com.example.starter.deicing.service;

import com.example.starter.deicing.db.AllocationRepository;
import com.example.starter.deicing.db.BayRepository;
import com.example.starter.deicing.db.DeicingAllocationPo;
import com.example.starter.deicing.db.DeicingBayPo;
import com.example.starter.deicing.db.DeicingRequestPo;
import com.example.starter.deicing.db.LatchRepository;
import com.example.starter.deicing.db.RequestRepository;
import com.example.starter.deicing.db.TimetableRepository;
import com.example.starter.deicing.domain.DeicingType;
import com.example.starter.deicing.domain.RequestStatus;
import com.example.starter.deicing.web.ApiException;
import com.example.starter.deicing.web.dto.ApplicationRequest;
import com.example.starter.deicing.web.dto.BayTimelineView;
import com.example.starter.deicing.web.dto.MutationResponse;
import com.example.starter.deicing.web.dto.QueueItemView;
import com.example.starter.deicing.web.dto.SnapshotResponse;
import com.example.starter.deicing.web.dto.TakeoffResponse;
import com.example.starter.deicing.web.dto.TimelineResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 除冰排队核心服务：申请幂等、先申请先服务/截止优先出队、半开区间车位分配、
 * 两步法 HOT 判定与终态幂等。
 */
@Service
public class DeicingService {

    private final RequestRepository requestRepository;
    private final AllocationRepository allocationRepository;
    private final BayRepository bayRepository;
    private final TimetableRepository timetableRepository;
    private final LatchRepository latchRepository;
    private final SchedulingCalculator calculator;
    private final ViewMapper viewMapper;
    private final TimeSource timeSource;

    public DeicingService(RequestRepository requestRepository,
                          AllocationRepository allocationRepository,
                          BayRepository bayRepository,
                          TimetableRepository timetableRepository,
                          LatchRepository latchRepository,
                          SchedulingCalculator calculator,
                          ViewMapper viewMapper,
                          TimeSource timeSource) {
        this.requestRepository = requestRepository;
        this.allocationRepository = allocationRepository;
        this.bayRepository = bayRepository;
        this.timetableRepository = timetableRepository;
        this.latchRepository = latchRepository;
        this.calculator = calculator;
        this.viewMapper = viewMapper;
        this.timeSource = timeSource;
    }

    /**
     * 除冰申请。requestId 幂等：同键同参返回首次结果，同键异参 409；
     * 同一航班仅允许一条进行中记录，并发申请由行锁 + 唯一约束唯一受理。
     */
    @Transactional
    public MutationResponse apply(ApplicationRequest request) {
        Instant now = timeSource.now();
        if (!request.targetTakeoffTime().isAfter(now)) {
            throw ApiException.badRequest("目标起飞时刻必须晚于当前时刻");
        }
        Instant requestDeadline = request.targetTakeoffTime()
                .minusSeconds(request.durationMinutes() * 60L);
        if (requestDeadline.isBefore(now)) {
            throw ApiException.badRequest("截止时刻（起飞时刻减除冰时长）已过");
        }

        String fingerprint = Fingerprints.sha256(request.requestId(), request.flightNumber(),
                request.standCode(), request.type(), request.requiredBays(),
                request.durationMinutes(), request.targetTakeoffTime());

        DeicingRequestPo existing = requestRepository.findByRequestId(request.requestId());
        if (existing != null) {
            return replayOrConflict(existing, fingerprint);
        }

        Integer hotMinutes = resolveHot(request.type(), request.flightNumber());

        try {
            // 航班闩锁串行化同一航班的并发申请
            latchRepository.lockFlight(request.flightNumber());
            existing = requestRepository.findByRequestId(request.requestId());
            if (existing != null) {
                return replayOrConflict(existing, fingerprint);
            }
            if (requestRepository.countActiveByFlight(request.flightNumber()) > 0) {
                throw ApiException.conflict("航班 " + request.flightNumber() + " 已存在进行中的除冰队列记录");
            }
            int activeBays = bayRepository.findActive().size();
            if (request.requiredBays() > activeBays) {
                throw ApiException.badRequest("所需车位数 " + request.requiredBays()
                        + " 超过可用车位数 " + activeBays);
            }

            DeicingRequestPo po = new DeicingRequestPo();
            po.setRequestId(request.requestId());
            po.setFlightNumber(request.flightNumber());
            po.setStandCode(request.standCode());
            po.setType(request.type());
            po.setRequiredBays(request.requiredBays());
            po.setDurationMinutes(request.durationMinutes());
            po.setTargetTakeoffTime(request.targetTakeoffTime());
            po.setDeadline(request.targetTakeoffTime().minusSeconds(request.durationMinutes() * 60L));
            po.setStatus(RequestStatus.QUEUED);
            po.setHotMinutes(hotMinutes);
            po.setFingerprint(fingerprint);
            po.setCreatedAt(now);
            po.setUpdatedAt(now);
            requestRepository.insert(po);
            DeicingRequestPo stored = requestRepository.findByRequestId(request.requestId());
            return MutationResponse.of(viewMapper.toView(stored), false, List.of());
        } catch (DuplicateKeyException e) {
            DeicingRequestPo raced = requestRepository.findByRequestId(request.requestId());
            if (raced != null) {
                return replayOrConflict(raced, fingerprint);
            }
            throw e;
        }
    }

    /**
     * 出队确认：仅队首（截止时刻最早、同刻按申请序号）可出队，禁止插队。
     * 排定成功返回车位与作业窗口；无法在截止前满足 HOT/时长时置 HOT_RISK，
     * 给出最早可重排时刻，不静默放行。
     */
    @Transactional
    public MutationResponse dequeue(String requestId) {
        DeicingRequestPo po = mustFind(requestId);
        if (po.getStatus() != RequestStatus.QUEUED && po.getStatus() != RequestStatus.HOT_RISK) {
            return mutationReplay(po);
        }

        // 全局调度闩锁：所有出队分配串行化，杜绝并发双分配
        latchRepository.lockSchedule();

        // QUEUED 与重排后待重新出队的 HOT_RISK 统一参与队首排序，禁止借重排插队
        List<DeicingRequestPo> queued = requestRepository.findAll().stream()
                .filter(r -> r.getStatus() == RequestStatus.QUEUED
                        || r.getStatus() == RequestStatus.HOT_RISK)
                .sorted(Comparator.comparing(DeicingRequestPo::getDeadline)
                        .thenComparingLong(DeicingRequestPo::getSeq))
                .toList();
        DeicingRequestPo head = queued.isEmpty() ? po : queued.get(0);
        if (!head.getRequestId().equals(requestId)) {
            throw ApiException.conflict("申请 " + requestId + " 不在队首，当前队首为 "
                    + head.getRequestId() + "，禁止插队");
        }

        return schedule(head);
    }

    private MutationResponse schedule(DeicingRequestPo po) {
        Instant now = timeSource.now();
        Duration duration = Duration.ofMinutes(po.getDurationMinutes());
        Instant deadline = po.getTargetTakeoffTime().minus(duration);

        List<String> activeBays = bayRepository.findActive().stream()
                .map(DeicingBayPo::getBayCode).toList();
        List<DeicingAllocationPo> occupied = allocationRepository.findAll().stream()
                .filter(a -> a.getEndTime().isAfter(now)).toList();

        // TWO_STEP：作业完成时刻须不早于 target-HOT，保证恰在 HOT 终点起飞仍有效
        Instant bandLower = now;
        if (po.getType() == DeicingType.TWO_STEP && po.getHotMinutes() != null) {
            Instant hotBound = po.getTargetTakeoffTime()
                    .minusSeconds(po.getHotMinutes() * 60L).minus(duration);
            if (hotBound.isAfter(bandLower)) {
                bandLower = hotBound;
            }
        }

        BaySelection selection = selectBays(activeBays, bandLower, duration, occupied,
                po.getRequiredBays());

        Instant now2 = timeSource.now();
        if (selection != null && !selection.start.isAfter(deadline)) {
            Instant start = selection.start;
            Instant end = start.plus(duration);
            // 数据库边界复核：半开 [start,end) 内部重叠才冲突，端点相接不冲突
            List<DeicingAllocationPo> clashes = allocationRepository.findOverlapping(
                    selection.bays, start, end);
            if (!clashes.isEmpty()) {
                throw ApiException.conflict("车位在 [" + start + ", " + end
                        + ") 已被占用，分配冲突");
            }
            for (String bay : selection.bays) {
                DeicingAllocationPo allocation = new DeicingAllocationPo();
                allocation.setRequestId(po.getRequestId());
                allocation.setFlightNumber(po.getFlightNumber());
                allocation.setBayCode(bay);
                allocation.setStartTime(start);
                allocation.setEndTime(end);
                allocation.setStatus(RequestStatus.SCHEDULED.name());
                allocation.setCreatedAt(now2);
                allocationRepository.insert(allocation);
            }
            po.setStatus(RequestStatus.SCHEDULED);
            po.setScheduledStart(start);
            po.setScheduledEnd(end);
            po.setHotRiskReason(null);
            po.setEarliestReassignTime(null);
            po.setUpdatedAt(now2);
            requestRepository.updateSchedule(po);
            DeicingRequestPo stored = requestRepository.findByRequestId(po.getRequestId());
            return MutationResponse.of(viewMapper.toView(stored), false, selection.bays);
        }

        // 无法在截止前满足：置 HOT 风险，给出最早可重排（最早可重新开始作业）时刻
        BaySelection earliest = selectBays(activeBays, now, duration, occupied,
                po.getRequiredBays());
        Instant earliestStart = earliest == null ? now : earliest.start;
        HotRiskReason reason = earliestStart.plus(duration).isAfter(po.getTargetTakeoffTime())
                ? HotRiskReason.LATE_COMPLETION
                : HotRiskReason.HOT_EXPIRY_RISK;
        po.setStatus(RequestStatus.HOT_RISK);
        po.setHotRiskReason(reason.name());
        po.setEarliestReassignTime(earliestStart);
        po.setUpdatedAt(now2);
        requestRepository.updateSchedule(po);
        DeicingRequestPo stored = requestRepository.findByRequestId(po.getRequestId());
        return MutationResponse.of(viewMapper.toView(stored), false, List.of());
    }

    /**
     * 作业开始：仅 SCHEDULED 可触发，开始后车位占用保留，不可被插队。
     */
    @Transactional
    public MutationResponse startJob(String requestId) {
        DeicingRequestPo po = mustFind(requestId);
        if (po.getStatus() == RequestStatus.SCHEDULED) {
            Instant now = timeSource.now();
            requestRepository.updateActualStart(requestId, RequestStatus.IN_PROGRESS, now, now);
            allocationRepository.updateStatusByRequest(requestId, RequestStatus.IN_PROGRESS.name());
            DeicingRequestPo stored = mustFind(requestId);
            List<String> bays = allocationRepository.findByRequestId(requestId).stream()
                    .map(DeicingAllocationPo::getBayCode).toList();
            return MutationResponse.of(viewMapper.toView(stored), false, bays);
        }
        if (po.getStatus() == RequestStatus.QUEUED || po.getStatus() == RequestStatus.HOT_RISK) {
            throw ApiException.conflict("申请 " + requestId + " 尚未完成出队排定，不能开始作业");
        }
        return mutationReplay(po);
    }

    /**
     * 实际起飞回报。两步法：恰在 scheduledEnd+HOT 时刻起飞有效，晚于该时刻 HOT 失效，
     * 记录进入 HOT_EXPIRED 终态并要求重新除冰。已取消记录不会被复活。
     */
    @Transactional
    public TakeoffResponse reportTakeoff(String requestId, Instant actualTakeoffTime) {
        DeicingRequestPo po = mustFind(requestId);

        if (po.getStatus() == RequestStatus.CANCELLED) {
            // 迟到起飞回报：幂等返回，已取消队列项不得复活
            return new TakeoffResponse(viewMapper.toView(po), false, true,
                    viewMapper.hotDeadline(po));
        }
        if (po.getStatus() == RequestStatus.TAKEN_OFF
                || po.getStatus() == RequestStatus.HOT_EXPIRED) {
            boolean valid = po.getStatus() == RequestStatus.TAKEN_OFF;
            return new TakeoffResponse(viewMapper.toView(po), valid, true,
                    viewMapper.hotDeadline(po));
        }
        if (po.getStatus() == RequestStatus.QUEUED || po.getStatus() == RequestStatus.HOT_RISK
                || po.getStatus() == RequestStatus.SCHEDULED) {
            throw ApiException.conflict("申请 " + requestId
                    + " 除冰作业尚未开始/处于 HOT 风险，不得放行起飞");
        }
        if (po.getScheduledEnd() == null || actualTakeoffTime.isBefore(po.getScheduledEnd())) {
            throw ApiException.badRequest("实际起飞时刻早于除冰完成时刻");
        }

        boolean valid;
        RequestStatus nextStatus;
        if (po.getType() == DeicingType.TWO_STEP) {
            Instant hotDeadline = viewMapper.hotDeadline(po);
            // 半开 HOT 区间的对端取等号：恰在 HOT 终点起飞有效
            valid = !actualTakeoffTime.isAfter(hotDeadline);
            nextStatus = valid ? RequestStatus.TAKEN_OFF : RequestStatus.HOT_EXPIRED;
        } else {
            valid = true;
            nextStatus = RequestStatus.TAKEN_OFF;
        }

        Instant now = timeSource.now();
        requestRepository.updateTakeoffResult(requestId, nextStatus, actualTakeoffTime, now);
        allocationRepository.updateStatusByRequest(requestId, nextStatus.name());
        DeicingRequestPo stored = mustFind(requestId);
        return new TakeoffResponse(viewMapper.toView(stored), valid, false,
                viewMapper.hotDeadline(stored));
    }

    /**
     * 取消：释放车位占用，进入 CANCELLED 终态；终态记录上的重复取消幂等成功。
     */
    @Transactional
    public MutationResponse cancel(String requestId) {
        DeicingRequestPo po = mustFind(requestId);
        if (po.getStatus() == RequestStatus.CANCELLED) {
            return mutationReplay(po);
        }
        if (po.getStatus() == RequestStatus.TAKEN_OFF
                || po.getStatus() == RequestStatus.HOT_EXPIRED) {
            // 已进入其他终态，取消操作幂等返回但不改变终态
            return mutationReplay(po);
        }
        Instant now = timeSource.now();
        requestRepository.updateStatus(requestId, RequestStatus.CANCELLED, now);
        allocationRepository.deleteByRequest(requestId);
        return mutationReplay(mustFind(requestId));
    }

    /**
     * HOT 风险航班重排起飞时刻：新时刻必须允许在最早可重排时刻之后完成作业，
     * 重排后回到排队态等待重新出队。
     */
    @Transactional
    public MutationResponse reschedule(String requestId, Instant newTargetTakeoffTime) {
        DeicingRequestPo po = mustFind(requestId);
        if (po.getStatus() != RequestStatus.HOT_RISK) {
            throw ApiException.conflict("仅 HOT_RISK 状态的记录可重排起飞时刻");
        }
        Instant now = timeSource.now();
        if (!newTargetTakeoffTime.isAfter(now)) {
            throw ApiException.badRequest("新起飞时刻必须晚于当前时刻");
        }
        Instant earliestStart = po.getEarliestReassignTime() == null ? now
                : po.getEarliestReassignTime();
        Instant earliestCompletion = earliestStart.plusSeconds(po.getDurationMinutes() * 60L);
        if (newTargetTakeoffTime.isBefore(earliestCompletion)) {
            throw ApiException.conflict("新起飞时刻早于最早可完成时刻 " + earliestCompletion);
        }
        Instant newDeadline = newTargetTakeoffTime.minusSeconds(po.getDurationMinutes() * 60L);
        requestRepository.updateReschedule(requestId, newTargetTakeoffTime, newDeadline, now);
        return mutationReplay(mustFind(requestId));
    }

    /**
     * 队列快照：进行中记录在前（按截止时刻、申请序号），终态记录在后，重复查询稳定。
     */
    @Transactional(readOnly = true)
    public SnapshotResponse snapshot() {
        List<QueueItemView> items = requestRepository.findAll().stream()
                .sorted(snapshotOrder())
                .map(po -> viewMapper.toView(po))
                .toList();
        return new SnapshotResponse(timeSource.now(), items);
    }

    /**
     * 车位占用时间线：按车位与占用起点排序，可复算、重复查询稳定。
     */
    @Transactional(readOnly = true)
    public TimelineResponse timeline() {
        Map<String, List<BayTimelineView.OccupancyView>> byBay = new LinkedHashMap<>();
        for (DeicingBayPo bay : bayRepository.findActive()) {
            byBay.put(bay.getBayCode(), new ArrayList<>());
        }
        for (DeicingAllocationPo allocation : allocationRepository.findAll()) {
            byBay.computeIfAbsent(allocation.getBayCode(), k -> new ArrayList<>())
                    .add(viewMapper.toOccupancy(allocation));
        }
        List<BayTimelineView> bays = byBay.entrySet().stream()
                .map(e -> new BayTimelineView(e.getKey(),
                        e.getValue().stream()
                                .sorted(Comparator.comparing(BayTimelineView.OccupancyView::start)
                                        .thenComparing(BayTimelineView.OccupancyView::requestId))
                                .toList()))
                .toList();
        return new TimelineResponse(timeSource.now(), bays);
    }

    @Transactional(readOnly = true)
    public QueueItemView get(String requestId) {
        return viewMapper.toView(mustFind(requestId));
    }

    // ---- 内部辅助 ----------------------------------------------------------

    private Integer resolveHot(DeicingType type, String flightNumber) {
        if (type == DeicingType.ONE_STEP) {
            return null;
        }
        Integer hot = timetableRepository.findHotMinutes(flightNumber);
        if (hot == null || hot <= 0) {
            throw ApiException.badRequest("时刻表缺少航班 " + flightNumber
                    + " 的两步法防冰液保持时间（HOT）");
        }
        return hot;
    }

    private MutationResponse replayOrConflict(DeicingRequestPo existing, String fingerprint) {
        if (!existing.getFingerprint().equals(fingerprint)) {
            throw ApiException.conflict("requestId " + existing.getRequestId()
                    + " 已存在但业务参数不一致，拒绝覆盖");
        }
        return mutationReplay(existing);
    }

    private MutationResponse mutationReplay(DeicingRequestPo po) {
        List<String> bays = allocationRepository.findByRequestId(po.getRequestId()).stream()
                .map(DeicingAllocationPo::getBayCode).toList();
        return MutationResponse.of(viewMapper.toView(po), true, bays);
    }

    private DeicingRequestPo mustFind(String requestId) {
        DeicingRequestPo po = requestRepository.findByRequestId(requestId);
        if (po == null) {
            throw ApiException.notFound("申请不存在: " + requestId);
        }
        return po;
    }

    /**
     * 在 activeBays 中选择 requiredBays 个车位，使作业能在 earliest 之后最早开始；
     * 起点并列时优先共同空闲更久、车位编号更小的组合，保证结果确定。
     */
    private BaySelection selectBays(List<String> activeBays, Instant earliest, Duration duration,
                                    List<DeicingAllocationPo> occupied, int requiredBays) {
        record Candidate(String bay, Instant start, Instant end) {
        }
        Map<String, List<SchedulingCalculator.BayWindow>> windows = new LinkedHashMap<>();
        for (String bay : activeBays) {
            windows.put(bay, calculator.freeWindows(bay, earliest, duration, occupied));
        }

        List<Instant> starts = windows.values().stream().flatMap(List::stream)
                .map(SchedulingCalculator.BayWindow::windowStart)
                .distinct().sorted().toList();

        for (Instant start : starts) {
            List<Candidate> covering = new ArrayList<>();
            for (Map.Entry<String, List<SchedulingCalculator.BayWindow>> entry : windows.entrySet()) {
                SchedulingCalculator.BayWindow window = entry.getValue().stream()
                        .filter(w -> !start.isBefore(w.windowStart())
                                && !start.plus(duration).isAfter(w.windowEnd()))
                        .findFirst().orElse(null);
                if (window != null) {
                    covering.add(new Candidate(entry.getKey(), start, window.windowEnd()));
                }
            }
            if (covering.size() >= requiredBays) {
                covering.sort(Comparator.comparing(Candidate::end).reversed()
                        .thenComparing(Candidate::bay));
                List<String> chosen = covering.subList(0, requiredBays).stream()
                        .map(Candidate::bay).sorted().toList();
                return new BaySelection(start, chosen);
            }
        }
        return null;
    }

    private static Comparator<DeicingRequestPo> snapshotOrder() {
        return Comparator.comparingInt((DeicingRequestPo po) -> statusGroup(po.getStatus()))
                .thenComparing(DeicingRequestPo::getDeadline)
                .thenComparingLong(DeicingRequestPo::getSeq);
    }

    private static int statusGroup(RequestStatus status) {
        return switch (status) {
            case QUEUED, SCHEDULED, IN_PROGRESS, HOT_RISK -> 0;
            case TAKEN_OFF, HOT_EXPIRED, CANCELLED -> 1;
        };
    }

    private record BaySelection(Instant start, List<String> bays) {
    }
}
