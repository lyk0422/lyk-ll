package com.example.starter.service;

import com.example.starter.dao.CrewExecutionDao;
import com.example.starter.dao.FaultOrderDao;
import com.example.starter.dao.FaultOrderDao.QueueRow;
import com.example.starter.dao.GeneratorTruckDao;
import com.example.starter.dao.LoadRecoveryDao;
import com.example.starter.dao.MasterDataDao;
import com.example.starter.dao.OrderEventDao;
import com.example.starter.dao.RestoreConfirmDao;
import com.example.starter.dao.RestoreConfirmDao.ConfirmRow;
import com.example.starter.domain.ActorType;
import com.example.starter.domain.EventType;
import com.example.starter.domain.Grade;
import com.example.starter.domain.OrderStatus;
import com.example.starter.po.FaultOrder;
import com.example.starter.po.OrderEvent;
import com.example.starter.support.BusinessClock;
import com.example.starter.support.BusinessException;
import com.example.starter.support.TimeParser;
import com.example.starter.web.AssessRequest;
import com.example.starter.web.DispatchRequest;
import com.example.starter.web.LateReportRequest;
import com.example.starter.web.MergeRequest;
import com.example.starter.web.OrderActionRequest;
import com.example.starter.web.RepairStartRequest;
import com.example.starter.web.ReportRequest;
import com.example.starter.web.RestoreConfirmRequest;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 配网故障抢修排序与重要用户保电核心服务。
 */
@Service
public class FaultRepairService {

    /** 同故障点重复接报归并时段，单位：分钟 */
    public static final long DEDUP_WINDOW_MINUTES = 30;
    /** 特级用户停电启动发电车流程阈值，单位：秒（恰满 5 分钟判定） */
    public static final long GENERATOR_THRESHOLD_SECONDS = 300;

    private static final DateTimeFormatter ORDER_NO_FMT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(BusinessClock.ZONE);

    private final FaultOrderDao orderDao;
    private final OrderEventDao eventDao;
    private final MasterDataDao masterDao;
    private final CrewExecutionDao crewExecutionDao;
    private final RestoreConfirmDao confirmDao;
    private final LoadRecoveryDao loadDao;
    private final GeneratorTruckDao generatorDao;
    private final IdempotentService idempotentService;
    private final BusinessClock clock;

    public FaultRepairService(FaultOrderDao orderDao, OrderEventDao eventDao, MasterDataDao masterDao,
                              CrewExecutionDao crewExecutionDao, RestoreConfirmDao confirmDao,
                              LoadRecoveryDao loadDao, GeneratorTruckDao generatorDao,
                              IdempotentService idempotentService, BusinessClock clock) {
        this.orderDao = orderDao;
        this.eventDao = eventDao;
        this.masterDao = masterDao;
        this.crewExecutionDao = crewExecutionDao;
        this.confirmDao = confirmDao;
        this.loadDao = loadDao;
        this.generatorDao = generatorDao;
        this.idempotentService = idempotentService;
        this.clock = clock;
    }

    // ------------------------------------------------------------------
    // 故障接报
    // ------------------------------------------------------------------

    @Transactional
    public Map<String, Object> report(ReportRequest req) {
        List<String> parts = List.of(req.requestId(), nz(req.feederCode()), req.faultPoint(),
                nz(req.description()), String.valueOf(req.affectedUsers()), nz(req.outageTime()));
        return idempotentService.execute(req.requestId(), "REPORT", parts, () -> {
            Long feederId = resolveFeeder(req.feederCode());
            Instant now = clock.now();
            Instant outage = TimeParser.parse(req.outageTime());
            if (outage == null) {
                outage = now;
            }
            if (outage.isAfter(now)) {
                throw new IllegalArgumentException("停电起始时间不能晚于接报时间");
            }

            Instant windowStart = now.minusSeconds(DEDUP_WINDOW_MINUTES * 60);
            List<FaultOrder> samePoint = orderDao.findSamePointInWindow(req.faultPoint(), windowStart, now);
            if (!samePoint.isEmpty()) {
                FaultOrder first = samePoint.get(0);
                eventDao.append(first.getId(), EventType.DEDUP_MERGE, now, ActorType.CALLER,
                        "同故障点 " + DEDUP_WINDOW_MINUTES + " 分钟内重复接报，归并至工单 " + first.getOrderNo()
                                + "；影响户数 " + req.affectedUsers() + " 户不重复建档");
                Map<String, Object> view = orderView(first);
                view.put("dedup", true);
                view.put("dedupWindowMinutes", DEDUP_WINDOW_MINUTES);
                return new IdempotentService.Result(first.getOrderNo(), view);
            }

            FaultOrder o = new FaultOrder();
            o.setOrderNo(generateOrderNo(now));
            o.setFeederId(feederId);
            o.setFaultPoint(req.faultPoint());
            o.setDescription(req.description());
            o.setAffectedUsers(req.affectedUsers());
            o.setReportTime(now);
            o.setOutageTime(outage);
            o.setStatus(OrderStatus.REPORTED);
            orderDao.insert(o);
            eventDao.append(o.getId(), EventType.REPORT, now, ActorType.CALLER,
                    "故障接报：" + req.faultPoint() + "，影响 " + req.affectedUsers() + " 户");
            masterDao.updateFeederStatus(feederId, "FAULT");
            return new IdempotentService.Result(o.getOrderNo(), orderView(o));
        });
    }

    // ------------------------------------------------------------------
    // 研判
    // ------------------------------------------------------------------

    @Transactional
    public Map<String, Object> assess(String orderNo, AssessRequest req) {
        return idempotentService.execute(req.requestId(), "ASSESS",
                List.of(req.requestId(), orderNo), () -> {
                    FaultOrder o = requireOrder(orderNo);
                    if (o.getStatus() != OrderStatus.REPORTED) {
                        throw BusinessException.unprocessable(
                                "工单当前状态 " + o.getStatus() + " 不可研判（仅 REPORTED 可研判）");
                    }
                    Instant now = clock.now();
                    List<MasterDataDao.ImportantUserRow> users = masterDao.findUsersByFeeder(o.getFeederId());
                    Grade grade = users.stream().map(MasterDataDao.ImportantUserRow::grade)
                            .reduce(Grade.NORMAL, Grade::higher);
                    Long hitUserId = users.stream()
                            .filter(u -> u.grade() == grade && grade != Grade.NORMAL)
                            .map(MasterDataDao.ImportantUserRow::id)
                            .findFirst().orElse(null);
                    String hitUserName = users.stream()
                            .filter(u -> u.grade() == grade && grade != Grade.NORMAL)
                            .map(MasterDataDao.ImportantUserRow::userName)
                            .findFirst().orElse(null);
                    orderDao.markAssessed(o.getId(), grade, hitUserId);
                    eventDao.append(o.getId(), EventType.ASSESS, now, ActorType.SYSTEM,
                            "研判完成：最高保电等级 " + grade
                                    + (hitUserName != null ? "，命中用户 " + hitUserName : "，无重要用户")
                                    + "，影响 " + o.getAffectedUsers() + " 户，进入派单队列");
                    o.setStatus(OrderStatus.ASSESSED);
                    o.setUserGrade(grade);
                    o.setImportantUserId(hitUserId);
                    evaluateGenerators(now);
                    Map<String, Object> view = orderView(o);
                    view.put("hitImportantUserName", hitUserName);
                    return new IdempotentService.Result(orderNo, view);
                });
    }

    // ------------------------------------------------------------------
    // 派单
    // ------------------------------------------------------------------

    @Transactional
    public Map<String, Object> dispatch(DispatchRequest req) {
        return idempotentService.execute(req.requestId(), "DISPATCH",
                List.of(req.requestId(), req.orderNo(), req.crewCode()), () -> {
                    FaultOrder o = requireOrder(req.orderNo());
                    long crewId = masterDao.findCrewIdByCode(req.crewCode())
                            .orElseThrow(() -> BusinessException.notFound("班组不存在: " + req.crewCode()));
                    if (o.getStatus() == OrderStatus.SEALED || o.getStatus() == OrderStatus.MERGED
                            || o.getStatus() == OrderStatus.RESTORED) {
                        throw BusinessException.unprocessable(
                                "工单当前状态 " + o.getStatus() + " 不可派单");
                    }
                    Instant now = clock.now();
                    evaluateGenerators(now);

                    Long currentOrderId = crewExecutionDao.currentOrderId(crewId);
                    if (currentOrderId != null) {
                        FaultOrder current = orderDao.findById(currentOrderId).orElse(null);
                        orderDao.markWaiting(o.getId(), crewId);
                        eventDao.append(o.getId(), EventType.PARK, now, ActorType.SYSTEM,
                                "班组 " + req.crewCode() + " 正在执行工单 "
                                        + (current != null ? current.getOrderNo() : currentOrderId)
                                        + "，资源冲突，工单挂单等待");
                        o.setStatus(OrderStatus.WAITING);
                        o.setWaitCrewId(crewId);
                        Map<String, Object> view = orderView(o);
                        view.put("parked", true);
                        view.put("waitCrewCode", req.crewCode());
                        return new IdempotentService.Result(req.orderNo(), view);
                    }
                    doDispatch(o, crewId, now, "调度派单");
                    return new IdempotentService.Result(req.orderNo(), orderView(o));
                });
    }

    private void doDispatch(FaultOrder o, long crewId, Instant now, String reason) {
        boolean occupied = crewExecutionDao.tryOccupy(crewId, o.getId(), now);
        if (!occupied) {
            orderDao.markWaiting(o.getId(), crewId);
            eventDao.append(o.getId(), EventType.PARK, now, ActorType.SYSTEM,
                    "班组占用冲突（并发派单），挂单等待");
            o.setStatus(OrderStatus.WAITING);
            o.setWaitCrewId(crewId);
            return;
        }
        orderDao.markDispatched(o.getId(), crewId, now);
        masterDao.updateCrewStatus(crewId, "BUSY");
        eventDao.append(o.getId(), EventType.DISPATCH, now, ActorType.SYSTEM,
                reason + "：派至班组 " + masterDao.getCrewCode(crewId));
        o.setStatus(OrderStatus.DISPATCHED);
        o.setCrewId(crewId);
        o.setAssignedTime(now);
        o.setWaitCrewId(null);
    }

    // ------------------------------------------------------------------
    // 抢修开始
    // ------------------------------------------------------------------

    @Transactional
    public Map<String, Object> repairStart(String orderNo, RepairStartRequest req) {
        return idempotentService.execute(req.requestId(), "REPAIR_START",
                List.of(req.requestId(), orderNo), () -> {
                    FaultOrder o = requireOrder(orderNo);
                    if (o.getStatus() != OrderStatus.DISPATCHED) {
                        throw BusinessException.unprocessable(
                                "工单当前状态 " + o.getStatus() + " 不可开始抢修（仅 DISPATCHED 可开始）");
                    }
                    Instant now = clock.now();
                    orderDao.markRepairing(o.getId(), now);
                    eventDao.append(o.getId(), EventType.REPAIR_START, now, ActorType.FIELD,
                            "现场班组开始抢修");
                    evaluateGenerators(now);
                    o.setStatus(OrderStatus.REPAIRING);
                    o.setRepairStartTime(now);
                    return new IdempotentService.Result(orderNo, orderView(o));
                });
    }

    // ------------------------------------------------------------------
    // 复电双确认
    // ------------------------------------------------------------------

    @Transactional
    public Map<String, Object> restoreConfirm(String orderNo, RestoreConfirmRequest req) {
        ActorType actor;
        try {
            actor = ActorType.valueOf(req.actor());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("actor 仅支持 DISPATCHER 或 FIELD");
        }
        if (actor != ActorType.DISPATCHER && actor != ActorType.FIELD) {
            throw new IllegalArgumentException("actor 仅支持 DISPATCHER 或 FIELD");
        }
        return idempotentService.execute(req.requestId(), "RESTORE_CONFIRM",
                List.of(req.requestId(), orderNo, actor.name(), nz(req.actorName()),
                        req.restoredLoadKw().stripTrailingZeros().toPlainString(),
                        String.valueOf(req.recoveredUsers())),
                () -> {
                    FaultOrder o = requireOrder(orderNo);
                    if (o.getStatus() == OrderStatus.SEALED) {
                        throw BusinessException.unprocessable("工单已封存，复电确认被拒绝");
                    }
                    if (o.getStatus() != OrderStatus.DISPATCHED && o.getStatus() != OrderStatus.REPAIRING) {
                        throw BusinessException.unprocessable(
                                "工单当前状态 " + o.getStatus() + " 不可复电确认");
                    }
                    if (loadDao.findByOrderId(o.getId()).isPresent()) {
                        throw BusinessException.unprocessable("工单已完成复电，不可重复确认");
                    }
                    Instant now = clock.now();
                    evaluateGenerators(now);
                    confirmDao.initRowIfAbsent(o.getId());
                    ConfirmRow before = confirmDao.get(o.getId()).orElseThrow();
                    boolean alreadyConfirmed =
                            (actor == ActorType.DISPATCHER && before.dispatcherConfirmed())
                                    || (actor == ActorType.FIELD && before.fieldConfirmed());
                    ConfirmRow row = confirmDao.confirm(o.getId(), actor.name(), now, req.actorName());
                    if (!alreadyConfirmed) {
                        eventDao.append(o.getId(), EventType.RESTORE_CONFIRM, now, actor,
                                (actor == ActorType.DISPATCHER ? "调度" : "现场")
                                        + "复电确认：" + req.actorName()
                                        + "（单方确认不生效）");
                    }

                    boolean restored = false;
                    if (row.bothConfirmed()) {
                        if (req.restoredLoadKw() == null || req.recoveredUsers() == null) {
                            throw new IllegalArgumentException("完成双确认时必须上送恢复负荷与恢复户数");
                        }
                        // 复电确认与负荷恢复记录在同一事务内完成
                        confirmDao.markCompleted(o.getId(), now);
                        orderDao.markRestored(o.getId(), now);
                        loadDao.insert(o.getId(), req.restoredLoadKw(), req.recoveredUsers(), now);
                        eventDao.append(o.getId(), EventType.RESTORED, now, ActorType.SYSTEM,
                                "调度与现场双确认齐备，复电完成；恢复负荷 "
                                        + req.restoredLoadKw().stripTrailingZeros().toPlainString()
                                        + " kW，恢复 " + req.recoveredUsers() + " 户");
                        if (generatorDao.findByOrder(o.getId()).isPresent()) {
                            generatorDao.markWithdrawn(o.getId());
                            eventDao.append(o.getId(), EventType.GENERATOR_STOP, now, ActorType.SYSTEM,
                                    "复电完成，发电车撤回");
                        }
                        long releaseCrewId = o.getCrewId();
                        crewExecutionDao.release(releaseCrewId);
                        masterDao.updateCrewStatus(releaseCrewId, "IDLE");
                        if (o.getFeederId() != null
                                && orderDao.countActiveByFeeder(o.getFeederId()) == 0) {
                            masterDao.updateFeederStatus(o.getFeederId(), "NORMAL");
                        }
                        promoteNextWaiting(releaseCrewId, now);
                        restored = true;
                    }
                    o.setStatus(restored ? OrderStatus.RESTORED : o.getStatus());
                    Map<String, Object> view = orderView(o);
                    view.put("dispatcherConfirmed", row.dispatcherConfirmed());
                    view.put("fieldConfirmed", row.fieldConfirmed());
                    view.put("restored", restored);
                    return new IdempotentService.Result(orderNo, view);
                });
    }

    /**
     * 班组释放后，按队列优先级自动承接挂单工单。
     */
    private void promoteNextWaiting(long crewId, Instant now) {
        List<FaultOrder> waiting = orderDao.findWaitingByCrew(crewId);
        if (waiting.isEmpty()) {
            return;
        }
        FaultOrder next = waiting.get(0);
        doDispatch(next, crewId, now, "班组空闲，挂单队列自动派单");
    }

    // ------------------------------------------------------------------
    // 工单合并
    // ------------------------------------------------------------------

    @Transactional
    public Map<String, Object> merge(MergeRequest req) {
        List<String> sources = new ArrayList<>(new LinkedHashSet<>(req.sourceOrderNos()));
        if (sources.size() < 2) {
            throw new IllegalArgumentException("合并至少需要 2 张不同的源工单");
        }
        return idempotentService.execute(req.requestId(), "MERGE",
                List.of(req.requestId(), String.join("|", sources), nz(req.description())), () -> {
                    Instant now = clock.now();
                    List<FaultOrder> orders = new ArrayList<>();
                    for (String no : sources) {
                        FaultOrder o = requireOrder(no);
                        if (o.getStatus() != OrderStatus.REPORTED && o.getStatus() != OrderStatus.ASSESSED) {
                            throw BusinessException.unprocessable(
                                    "工单 " + no + " 状态 " + o.getStatus()
                                            + " 不允许合并（仅未派单的 REPORTED/ASSESSED 工单可合并）");
                        }
                        orders.add(o);
                    }
                    Long commonFeeder = orders.get(0).getFeederId();
                    for (FaultOrder o : orders) {
                        if (!java.util.Objects.equals(o.getFeederId(), commonFeeder)) {
                            commonFeeder = null;
                            break;
                        }
                    }
                    int affectedSum = orders.stream().mapToInt(FaultOrder::getAffectedUsers).sum();
                    Instant earliestReport = orders.stream().map(FaultOrder::getReportTime)
                            .min(Instant::compareTo).orElse(now);
                    Instant earliestOutage = orders.stream().map(FaultOrder::getOutageTime)
                            .min(Instant::compareTo).orElse(now);
                    Grade topGrade = orders.stream().map(FaultOrder::getUserGrade)
                            .reduce(Grade.NORMAL, Grade::higher);

                    FaultOrder merged = new FaultOrder();
                    merged.setOrderNo(generateOrderNo(now));
                    merged.setFeederId(commonFeeder);
                    merged.setFaultPoint(orders.get(0).getFaultPoint());
                    merged.setDescription(req.description() != null && !req.description().isBlank()
                            ? req.description()
                            : "合并工单，来源：" + String.join("、", sources));
                    merged.setAffectedUsers(affectedSum);
                    merged.setReportTime(earliestReport);
                    merged.setOutageTime(earliestOutage);
                    merged.setStatus(OrderStatus.ASSESSED);
                    merged.setUserGrade(topGrade);
                    orderDao.insert(merged);
                    eventDao.append(merged.getId(), EventType.MERGE_IN, now, ActorType.SYSTEM,
                            "新单承接合并来源：" + String.join("、", sources) + "；合计影响 " + affectedSum + " 户");

                    for (FaultOrder o : orders) {
                        orderDao.markMerged(o.getId(), merged.getId(), merged.getOrderNo());
                        eventDao.append(o.getId(), EventType.MERGE_OUT, now, ActorType.SYSTEM,
                                "工单合并至新单 " + merged.getOrderNo(), merged.getId());
                    }
                    evaluateGenerators(now);
                    Map<String, Object> view = orderView(merged);
                    view.put("sourceOrderNos", sources);
                    return new IdempotentService.Result(merged.getOrderNo(), view);
                });
    }

    // ------------------------------------------------------------------
    // 日结封存
    // ------------------------------------------------------------------

    @Transactional
    public Map<String, Object> seal(String orderNo, OrderActionRequest req) {
        return idempotentService.execute(req.requestId(), "SEAL",
                List.of(req.requestId(), orderNo), () -> {
                    FaultOrder o = requireOrder(orderNo);
                    if (o.getStatus() == OrderStatus.SEALED) {
                        // 封存为终态：重复封存返回幂等成功，不改变状态
                        Map<String, Object> view = orderView(o);
                        view.put("alreadySealed", true);
                        return new IdempotentService.Result(orderNo, view);
                    }
                    if (o.getStatus() != OrderStatus.RESTORED) {
                        throw BusinessException.unprocessable(
                                "工单当前状态 " + o.getStatus() + "，未复电不可日结封存");
                    }
                    Instant now = clock.now();
                    orderDao.markSealed(o.getId(), now);
                    eventDao.append(o.getId(), EventType.SEAL, now, ActorType.SYSTEM,
                            "日结封存：工单终态锁定");
                    o.setStatus(OrderStatus.SEALED);
                    o.setSealedTime(now);
                    return new IdempotentService.Result(orderNo, orderView(o));
                });
    }

    // ------------------------------------------------------------------
    // 封存后迟到回报
    // ------------------------------------------------------------------

    @Transactional
    public Map<String, Object> lateReport(String orderNo, LateReportRequest req) {
        return idempotentService.execute(req.requestId(), "LATE_REPORT",
                List.of(req.requestId(), orderNo, nz(req.actorName()), req.detail()), () -> {
                    FaultOrder o = requireOrder(orderNo);
                    Instant now = clock.now();
                    if (o.getStatus() != OrderStatus.SEALED) {
                        throw BusinessException.unprocessable(
                                "工单当前状态 " + o.getStatus() + "，非封存终态，不走迟到回报");
                    }
                    eventDao.append(o.getId(), EventType.LATE_AFTER_SEAL, now, ActorType.FIELD,
                            "封存后迟到回报[" + req.actorName() + "]：" + req.detail());
                    Map<String, Object> view = new LinkedHashMap<>();
                    view.put("orderNo", orderNo);
                    view.put("accepted", true);
                    view.put("stateChanged", false);
                    view.put("message", "工单已封存，迟到回报仅记录事件，不改变工单状态");
                    return new IdempotentService.Result(orderNo, view);
                });
    }

    // ------------------------------------------------------------------
    // 队列复算 / 详情 / 轨迹
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Map<String, Object>> queue() {
        Instant now = clock.now();
        List<QueueRow> rows = orderDao.findDispatchQueue();
        List<Map<String, Object>> result = new ArrayList<>();
        int rank = 1;
        for (QueueRow row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("rank", rank++);
            item.put("orderNo", orderNoOfId(row.id()));
            item.put("feederCode", masterDao.findFeederCodeById(row.feederId()).orElse(null));
            item.put("grade", row.grade().name());
            item.put("affectedUsers", row.affectedUsers());
            item.put("reportTime", row.reportTime().toString());
            item.put("status", row.status().name());
            item.put("waitCrewCode", row.waitCrewId() == null ? null
                    : safeCrewCode(row.waitCrewId()));
            item.put("generatorTriggerDue", row.grade() == Grade.SPECIAL);
            result.add(item);
        }
        return result;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(String orderNo) {
        FaultOrder o = requireOrder(orderNo);
        Map<String, Object> view = orderView(o);
        confirmDao.get(o.getId()).ifPresent(c -> {
            Map<String, Object> chain = new LinkedHashMap<>();
            chain.put("dispatcherConfirmed", c.dispatcherConfirmed());
            chain.put("dispatcherActor", c.dispatcherActor());
            chain.put("dispatcherTime", c.dispatcherTime() == null ? null : c.dispatcherTime().toString());
            chain.put("fieldConfirmed", c.fieldConfirmed());
            chain.put("fieldActor", c.fieldActor());
            chain.put("fieldTime", c.fieldTime() == null ? null : c.fieldTime().toString());
            chain.put("completedTime", c.completedTime() == null ? null : c.completedTime().toString());
            view.put("restoreConfirmChain", chain);
        });
        loadDao.findByOrderId(o.getId()).ifPresent(l -> {
            Map<String, Object> load = new LinkedHashMap<>();
            load.put("restoredLoadKw", l.restoredLoadKw());
            load.put("recoveredUsers", l.recoveredUsers());
            load.put("recordTime", l.recordTime().toString());
            view.put("loadRecovery", load);
        });
        generatorDao.findByOrder(o.getId()).ifPresent(g -> {
            Map<String, Object> truck = new LinkedHashMap<>();
            truck.put("status", g.status());
            truck.put("triggerTime", g.triggerTime().toString());
            truck.put("outageTime", g.outageTime().toString());
            truck.put("elapsedSecondsAtTrigger",
                    g.triggerTime().getEpochSecond() - g.outageTime().getEpochSecond());
            view.put("generatorTruck", truck);
        });
        if (o.getCrewId() != null) {
            view.put("crewCode", safeCrewCode(o.getCrewId()));
        }
        if (o.getWaitCrewId() != null) {
            view.put("waitCrewCode", safeCrewCode(o.getWaitCrewId()));
        }
        return view;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> events(String orderNo) {
        FaultOrder o = requireOrder(orderNo);
        List<Map<String, Object>> result = new ArrayList<>();
        for (OrderEvent e : eventDao.findByOrderId(o.getId())) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("seq", e.getId());
            item.put("eventType", e.getEventType().name());
            item.put("eventTime", e.getEventTime().toString());
            item.put("actor", e.getActor().name());
            item.put("detail", e.getDetail());
            if (e.getRelatedOrderId() != null) {
                item.put("relatedOrderNo", orderNoOfId(e.getRelatedOrderId()));
            }
            result.add(item);
        }
        return result;
    }

    // ------------------------------------------------------------------
    // 发电车流程复算：特级用户停电恰满 5 分钟（elapsed >= 300s）触发
    // ------------------------------------------------------------------

    @Transactional
    public int evaluateGenerators(Instant now) {
        int triggered = 0;
        Instant threshold = now.minusSeconds(GENERATOR_THRESHOLD_SECONDS);
        for (FaultOrder o : orderDao.findSpecialActiveOrders()) {
            if (generatorDao.existsByOrder(o.getId())) {
                continue;
            }
            // outageTime <= now-5min，即停电时长 >= 恰满 5 分钟
            if (!o.getOutageTime().isAfter(threshold)) {
                Instant triggerTime = o.getOutageTime().plusSeconds(GENERATOR_THRESHOLD_SECONDS);
                generatorDao.insertTriggered(o.getId(), Grade.SPECIAL, o.getOutageTime(),
                        triggerTime, "特级用户停电恰满 5 分钟，启动发电车流程");
                eventDao.append(o.getId(), EventType.GENERATOR_START, triggerTime, ActorType.SYSTEM,
                        "特级用户停电恰满 5 分钟（" + GENERATOR_THRESHOLD_SECONDS + " 秒），发电车流程启动");
                triggered++;
            }
        }
        return triggered;
    }

    // ------------------------------------------------------------------
    // 视图与辅助
    // ------------------------------------------------------------------

    private Map<String, Object> orderView(FaultOrder o) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("orderNo", o.getOrderNo());
        v.put("feederCode", masterDao.findFeederCodeById(o.getFeederId()).orElse(null));
        v.put("faultPoint", o.getFaultPoint());
        v.put("description", o.getDescription());
        v.put("affectedUsers", o.getAffectedUsers());
        v.put("reportTime", o.getReportTime() == null ? null : o.getReportTime().toString());
        v.put("outageTime", o.getOutageTime() == null ? null : o.getOutageTime().toString());
        v.put("status", o.getStatus().name());
        v.put("grade", o.getUserGrade() == null ? null : o.getUserGrade().name());
        v.put("assignedTime", o.getAssignedTime() == null ? null : o.getAssignedTime().toString());
        v.put("repairStartTime", o.getRepairStartTime() == null ? null : o.getRepairStartTime().toString());
        v.put("restoredTime", o.getRestoredTime() == null ? null : o.getRestoredTime().toString());
        v.put("sealedTime", o.getSealedTime() == null ? null : o.getSealedTime().toString());
        v.put("mergedIntoOrderNo", o.getMergedIntoNo());
        return v;
    }

    private FaultOrder requireOrder(String orderNo) {
        return orderDao.findByOrderNo(orderNo)
                .orElseThrow(() -> BusinessException.notFound("工单不存在: " + orderNo));
    }

    private Long resolveFeeder(String feederCode) {
        if (feederCode == null || feederCode.isBlank()) {
            return null;
        }
        return masterDao.findFeederIdByCode(feederCode)
                .orElseThrow(() -> BusinessException.notFound("馈线不存在: " + feederCode));
    }

    private String generateOrderNo(Instant now) {
        long seq = orderDao.nextOrderSequence();
        return "WO" + ORDER_NO_FMT.format(now) + String.format("%05d", seq);
    }

    private String orderNoOfId(long id) {
        return orderDao.findOrderNoById(id);
    }

    private String safeCrewCode(long crewId) {
        try {
            return masterDao.getCrewCode(crewId);
        } catch (Exception e) {
            return null;
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }
}
