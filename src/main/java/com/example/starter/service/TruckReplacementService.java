package com.example.starter.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.starter.support.AppClock;
import com.example.starter.support.Fingerprints;
import com.example.starter.web.BusinessException;
import com.example.starter.web.ConflictException;
import com.example.starter.web.NotFoundException;
import com.example.starter.web.dto.CreateReplacementRequest;
import com.example.starter.web.dto.ReplacementArrivalRequest;
import com.example.starter.web.dto.ReplacementArrivalResponse;
import com.example.starter.web.dto.ReplacementTruckView;
import com.example.starter.web.dto.ReplacementView;
import com.example.starter.web.dto.SwitchConfirmRequest;
import com.example.starter.web.dto.SwitchConfirmResponse;
import com.example.starter.web.dto.TruckView;
import com.example.starter.web.dto.WithdrawReplacementRequest;
import com.example.starter.web.dto.WithdrawReplacementResponse;

/**
 * 热备接替服务：仅SUPPLYING调度单可申请，引用一辆供电旧车与1至3辆同馈线未占用新车，
 * 新车容量之和不少于旧车容量（恰等合法）；创建即全量预占新车，旧车继续供电，每辆旧车最多一个未结束接替；
 * 新车逐车登记到场，到齐后两个不同合成操作员确认切换，原子替换供电组合、转移车辆占用并留前后容量证据；
 * 未切换可撤回（释放新车保留旧车，旧到场与供电证据不覆盖）；复电或合并关闭未结束接替并释放预占车。
 * 接替单版本从1开始，修改携带 expectedVersion、成功加1、旧版本409；requestId 幂等，失败不占键。
 */
@Service
public class TruckReplacementService {

    /** 接替单内部行。 */
    record ReplacementRow(long id, long dispatchId, long workOrderId, long oldTruckId, int orderVersion,
            int dispatchVersion, int totalLoadKw, int oldCapacityKw, Integer newCapacityKw, String status,
            int version, LocalDateTime createdAt, LocalDateTime switchedAt) {
    }

    private static final RowMapper<ReplacementRow> ROW_MAPPER = (rs, rowNum) -> mapRow(rs);

    private final JdbcTemplate jdbc;
    private final AppClock clock;
    private final WorkOrderStore store;
    private final GeneratorTruckService trucks;
    private final GenIdempotency idem;
    private final TransactionTemplate tx;

    public TruckReplacementService(JdbcTemplate jdbc, AppClock clock, WorkOrderStore store,
            GeneratorTruckService trucks, GenIdempotency idem, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.store = store;
        this.trucks = trucks;
        this.idem = idem;
        this.tx = new TransactionTemplate(txManager);
    }

    private static ReplacementRow mapRow(ResultSet rs) throws SQLException {
        long newCapacity = rs.getLong("new_capacity_kw");
        Integer newCapacityKw = rs.wasNull() ? null : (int) newCapacity;
        Timestamp switched = rs.getTimestamp("switched_at");
        return new ReplacementRow(rs.getLong("id"), rs.getLong("dispatch_id"), rs.getLong("work_order_id"),
                rs.getLong("old_truck_id"), rs.getInt("order_version"), rs.getInt("dispatch_version"),
                rs.getInt("total_load_kw"), rs.getInt("old_capacity_kw"), newCapacityKw,
                rs.getString("status"), rs.getInt("version"), rs.getTimestamp("created_at").toLocalDateTime(),
                switched == null ? null : switched.toLocalDateTime());
    }

    /**
     * 创建热备接替单：仅SUPPLYING调度单；全量预占新车，旧车继续供电；任一新车冲突全单拒绝。
     */
    public ReplacementView create(CreateReplacementRequest req) {
        if (req.newTruckIds().stream().distinct().count() != req.newTruckIds().size()) {
            throw new IllegalArgumentException("车辆列表不可重复");
        }
        if (req.newTruckIds().contains(req.oldTruckId())) {
            throw new IllegalArgumentException("新车不可与旧车重复");
        }
        String fingerprint = Fingerprints.structured(req.requestId(), req.dispatchId(), req.expectedVersion(),
                req.oldTruckId(), req.newTruckIds(), req.createdAt().toString());
        try {
            return tx.execute(status -> doCreate(req, fingerprint));
        } catch (DuplicateKeyException e) {
            return tx.execute(status -> idem.findReplay(req.requestId(), fingerprint, ReplacementView.class)
                    .orElseThrow(() -> new BusinessException("热备预占并发冲突, 整单拒绝")));
        }
    }

    private ReplacementView doCreate(CreateReplacementRequest req, String fingerprint) {
        Optional<ReplacementView> replay = idem.findReplay(req.requestId(), fingerprint, ReplacementView.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        DispatchHead dispatch = lockDispatch(req.dispatchId());
        // 行锁串行化后复查幂等：并发同键请求在此重放首次冻结结果
        Optional<ReplacementView> replayAfterLock =
                idem.findReplay(req.requestId(), fingerprint, ReplacementView.class);
        if (replayAfterLock.isPresent()) {
            return replayAfterLock.get();
        }
        if (!"SUPPLYING".equals(dispatch.status())) {
            throw new BusinessException("仅供电中的调度单可申请热备接替: " + dispatch.status());
        }
        if (req.expectedVersion() != dispatch.version()) {
            throw new ConflictException("调度单版本冲突: 期望" + req.expectedVersion() + ", 实际" + dispatch.version());
        }
        Boolean oldSupplying = jdbc.query(
                "SELECT arrived_at IS NOT NULL FROM dispatch_truck WHERE dispatch_id = ? AND truck_id = ?",
                rs -> rs.next() ? rs.getBoolean(1) : null, req.dispatchId(), req.oldTruckId());
        if (!Boolean.TRUE.equals(oldSupplying)) {
            throw new BusinessException("旧车不在供电组合内: " + req.oldTruckId());
        }
        TruckView oldTruck = trucks.lockTruck(req.oldTruckId());
        if (count("active_replacement", "old_truck_id = ?", req.oldTruckId()) > 0) {
            throw new BusinessException("旧车已存在未结束接替: " + req.oldTruckId());
        }
        int newCapacity = 0;
        for (long truckId : req.newTruckIds()) {
            TruckView truck = trucks.lockTruck(truckId);
            if (!truck.available()) {
                throw new BusinessException("发电车不可用: " + truckId);
            }
            if (!oldTruck.feederId().equals(truck.feederId())) {
                throw new BusinessException("新车与旧车不同馈线: " + truckId);
            }
            if (count("active_occupation", "truck_id = ?", truckId) > 0) {
                throw new BusinessException("发电车存在未结束占用: " + truckId);
            }
            if (count("replacement_reservation", "truck_id = ?", truckId) > 0) {
                throw new BusinessException("发电车存在热备预占: " + truckId);
            }
            newCapacity += truck.capacityKw();
        }
        if (newCapacity < oldTruck.capacityKw()) {
            throw new BusinessException(
                    "新车容量之和" + newCapacity + "kW不足覆盖旧车容量" + oldTruck.capacityKw() + "kW");
        }
        int orderVersion = jdbc.queryForObject(
                "SELECT version FROM work_order WHERE id = ?", Integer.class, dispatch.workOrderId());
        LocalDateTime createdAt = toLocal(req.createdAt());
        long replacementId = insertReplacement(req.dispatchId(), dispatch, req.oldTruckId(), orderVersion,
                oldTruck.capacityKw(), createdAt);
        for (long truckId : req.newTruckIds()) {
            jdbc.update("INSERT INTO replacement_truck (replacement_id, truck_id, arrived_at) VALUES (?,?,NULL)",
                    replacementId, truckId);
            jdbc.update("INSERT INTO replacement_reservation (truck_id, replacement_id, reserved_at)"
                    + " VALUES (?,?,?)", truckId, replacementId, Timestamp.valueOf(clock.now()));
        }
        jdbc.update("INSERT INTO active_replacement (old_truck_id, replacement_id, created_at) VALUES (?,?,?)",
                req.oldTruckId(), replacementId, Timestamp.valueOf(clock.now()));
        store.addEvent(dispatch.workOrderId(), "GENERATOR_REPLACEMENT_CREATED",
                "接替单" + replacementId + "已创建, 旧车" + req.oldTruckId() + "(" + oldTruck.capacityKw()
                + "kW)继续供电, 热备新车" + req.newTruckIds() + "合计" + newCapacity + "kW已预占");
        ReplacementView view = viewOf(replacementId);
        idem.record(req.requestId(), fingerprint, "REPLACEMENT_CREATE", replacementId, view);
        return view;
    }

    /**
     * 热备新车逐车登记到场：仅STANDBY可登记，逐车唯一；全部到齐后待双人确认切换。
     */
    public ReplacementArrivalResponse arrive(long replacementId, ReplacementArrivalRequest req) {
        String fingerprint = Fingerprints.structured(req.requestId(), replacementId, req.expectedVersion(),
                req.truckId(), req.arrivedAt().toString());
        try {
            return tx.execute(status -> doArrive(replacementId, req, fingerprint));
        } catch (DuplicateKeyException e) {
            return tx.execute(status -> idem
                    .findReplay(req.requestId(), fingerprint, ReplacementArrivalResponse.class)
                    .orElseThrow(() -> e));
        }
    }

    private ReplacementArrivalResponse doArrive(long replacementId, ReplacementArrivalRequest req,
            String fingerprint) {
        Optional<ReplacementArrivalResponse> replay =
                idem.findReplay(req.requestId(), fingerprint, ReplacementArrivalResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        ReplacementRow row = lockReplacement(replacementId);
        // 行锁串行化后复查幂等：并发同键请求在此重放首次冻结结果
        Optional<ReplacementArrivalResponse> replayAfterLock =
                idem.findReplay(req.requestId(), fingerprint, ReplacementArrivalResponse.class);
        if (replayAfterLock.isPresent()) {
            return replayAfterLock.get();
        }
        if (req.expectedVersion() != row.version()) {
            throw new ConflictException("接替单版本冲突: 期望" + req.expectedVersion() + ", 实际" + row.version());
        }
        if (!"STANDBY".equals(row.status())) {
            throw new BusinessException("接替单已结束, 不可登记到场: " + row.status());
        }
        Boolean arrived = jdbc.query(
                "SELECT arrived_at IS NOT NULL FROM replacement_truck WHERE replacement_id = ? AND truck_id = ?",
                rs -> rs.next() ? rs.getBoolean(1) : null, replacementId, req.truckId());
        if (arrived == null) {
            throw new BusinessException("车辆不在接替单热备方案内: " + req.truckId());
        }
        if (arrived) {
            throw new BusinessException("车辆已记录到场, 逐车唯一: " + req.truckId());
        }
        LocalDateTime arrivedAt = toLocal(req.arrivedAt());
        jdbc.update("UPDATE replacement_truck SET arrived_at = ? WHERE replacement_id = ? AND truck_id = ?",
                Timestamp.valueOf(arrivedAt), replacementId, req.truckId());
        int remaining = count("replacement_truck", "replacement_id = ? AND arrived_at IS NULL", replacementId);
        String outcome = remaining == 0 ? "ALL_ARRIVED" : "ARRIVED";
        jdbc.update("UPDATE truck_replacement SET version = version + 1 WHERE id = ?", replacementId);
        if (remaining == 0) {
            store.addEvent(row.workOrderId(), "GENERATOR_REPLACEMENT_READY",
                    "接替单" + replacementId + "热备新车全部到齐, 待双人确认切换");
        }
        ReplacementArrivalResponse response = new ReplacementArrivalResponse(replacementId, req.truckId(),
                outcome, row.status(), row.version() + 1, arrivedAt);
        idem.record(req.requestId(), fingerprint, "REPLACEMENT_ARRIVAL", replacementId, response);
        return response;
    }

    /**
     * 切换确认：新车到齐后由两个不同合成操作员确认；第二人确认时原子替换供电组合、
     * 转移车辆占用并留前后容量证据，切换前后均覆盖被冻结负荷。不足两人或缺车时不释放旧车。
     */
    public SwitchConfirmResponse confirmSwitch(long replacementId, SwitchConfirmRequest req) {
        String fingerprint = Fingerprints.structured(req.requestId(), replacementId, req.expectedVersion(),
                req.operator(), req.confirmedAt().toString());
        try {
            return tx.execute(status -> doConfirmSwitch(replacementId, req, fingerprint));
        } catch (DuplicateKeyException e) {
            return tx.execute(status -> idem.findReplay(req.requestId(), fingerprint, SwitchConfirmResponse.class)
                    .orElseThrow(() -> e));
        }
    }

    private SwitchConfirmResponse doConfirmSwitch(long replacementId, SwitchConfirmRequest req,
            String fingerprint) {
        Optional<SwitchConfirmResponse> replay =
                idem.findReplay(req.requestId(), fingerprint, SwitchConfirmResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        ReplacementRow snapshot = findReplacement(replacementId)
                .orElseThrow(() -> new NotFoundException("接替单不存在: " + replacementId));
        // 锁顺序与复电/合并撤销一致：先调度单后接替单，避免死锁
        DispatchHead dispatch = lockDispatch(snapshot.dispatchId());
        ReplacementRow row = lockReplacement(replacementId);
        // 行锁串行化后复查幂等：并发同键请求在此重放首次冻结结果
        Optional<SwitchConfirmResponse> replayAfterLock =
                idem.findReplay(req.requestId(), fingerprint, SwitchConfirmResponse.class);
        if (replayAfterLock.isPresent()) {
            return replayAfterLock.get();
        }
        if (req.expectedVersion() != row.version()) {
            throw new ConflictException("接替单版本冲突: 期望" + req.expectedVersion() + ", 实际" + row.version());
        }
        if (!"STANDBY".equals(row.status())) {
            throw new BusinessException("接替单已结束, 不可确认切换: " + row.status());
        }
        if (!"SUPPLYING".equals(dispatch.status())) {
            throw new BusinessException("原调度单已不在供电中, 不可切换: " + dispatch.status());
        }
        int missing = count("replacement_truck", "replacement_id = ? AND arrived_at IS NULL", replacementId);
        if (missing > 0) {
            throw new BusinessException("热备新车未到齐(缺" + missing + "辆), 不可确认切换");
        }
        if (count("replacement_confirmation", "replacement_id = ? AND operator = ?", replacementId,
                req.operator()) > 0) {
            throw new BusinessException("同一操作员不可重复确认: " + req.operator());
        }
        LocalDateTime confirmedAt = toLocal(req.confirmedAt());
        jdbc.update("INSERT INTO replacement_confirmation (replacement_id, operator, confirmed_at)"
                + " VALUES (?,?,?)", replacementId, req.operator(), Timestamp.valueOf(confirmedAt));
        int confirmed = count("replacement_confirmation", "replacement_id = ?", replacementId);
        SwitchConfirmResponse response;
        if (confirmed < 2) {
            jdbc.update("UPDATE truck_replacement SET version = version + 1 WHERE id = ?", replacementId);
            response = new SwitchConfirmResponse(replacementId, row.status(), row.version() + 1, false,
                    row.oldCapacityKw(), null, null);
        } else {
            response = doSwitch(row, req, confirmedAt);
        }
        idem.record(req.requestId(), fingerprint, "REPLACEMENT_SWITCH_CONFIRM", replacementId, response);
        return response;
    }

    /** 第二人确认时的原子切换：替换供电组合、转移占用、留前后容量证据。调用方须已持有调度单与接替单行锁。 */
    private SwitchConfirmResponse doSwitch(ReplacementRow row, SwitchConfirmRequest req,
            LocalDateTime switchedAt) {
        List<Long> newTruckIds = jdbc.query(
                "SELECT truck_id FROM replacement_truck WHERE replacement_id = ? ORDER BY truck_id",
                (rs, rowNum) -> rs.getLong(1), row.id());
        int newCapacity = 0;
        for (long truckId : newTruckIds) {
            newCapacity += trucks.lockTruck(truckId).capacityKw();
        }
        // 切换前后均覆盖被冻结负荷：其余车容量 + 新车容量之和 >= 冻结合计负荷
        Integer remainingCapacity = jdbc.queryForObject(
                "SELECT COALESCE(SUM(t.capacity_kw),0) FROM dispatch_truck dt"
                + " JOIN generator_truck t ON t.id = dt.truck_id"
                + " WHERE dt.dispatch_id = ? AND dt.truck_id <> ?",
                Integer.class, row.dispatchId(), row.oldTruckId());
        int newTotalCapacity = (remainingCapacity == null ? 0 : remainingCapacity) + newCapacity;
        if (newTotalCapacity < row.totalLoadKw()) {
            throw new IllegalStateException("切换后容量" + newTotalCapacity + "kW不足覆盖冻结负荷"
                    + row.totalLoadKw() + "kW");
        }
        // 原子替换供电组合：旧车移出，新车携到场记录入列
        jdbc.update("DELETE FROM dispatch_truck WHERE dispatch_id = ? AND truck_id = ?",
                row.dispatchId(), row.oldTruckId());
        for (long truckId : newTruckIds) {
            LocalDateTime arrivedAt = jdbc.queryForObject(
                    "SELECT arrived_at FROM replacement_truck WHERE replacement_id = ? AND truck_id = ?",
                    (rs, rowNum) -> rs.getTimestamp(1).toLocalDateTime(), row.id(), truckId);
            jdbc.update("INSERT INTO dispatch_truck (dispatch_id, truck_id, arrived_at) VALUES (?,?,?)",
                    row.dispatchId(), truckId, Timestamp.valueOf(arrivedAt));
        }
        // 转移车辆占用：旧车释放可为其他工单使用，新车由热备预占转供电占用
        jdbc.update("DELETE FROM active_occupation WHERE truck_id = ?", row.oldTruckId());
        for (long truckId : newTruckIds) {
            jdbc.update("DELETE FROM replacement_reservation WHERE truck_id = ?", truckId);
            jdbc.update("INSERT INTO active_occupation (truck_id, dispatch_id, occupied_at) VALUES (?,?,?)",
                    truckId, row.dispatchId(), Timestamp.valueOf(clock.now()));
        }
        jdbc.update("UPDATE generator_dispatch SET version = version + 1 WHERE id = ?", row.dispatchId());
        jdbc.update("UPDATE truck_replacement SET status = 'SWITCHED', new_capacity_kw = ?, switched_at = ?,"
                + " version = version + 1 WHERE id = ?", newCapacity, Timestamp.valueOf(switchedAt), row.id());
        jdbc.update("DELETE FROM active_replacement WHERE old_truck_id = ?", row.oldTruckId());
        store.addEvent(row.workOrderId(), "GENERATOR_REPLACEMENT_SWITCHED",
                "接替单" + row.id() + "双人确认切换, 旧车" + row.oldTruckId() + "(" + row.oldCapacityKw()
                + "kW)释放, 新车" + newTruckIds + "合计" + newCapacity + "kW切入供电, 操作员含" + req.operator());
        return new SwitchConfirmResponse(row.id(), "SWITCHED", row.version() + 1, true,
                row.oldCapacityKw(), newCapacity, switchedAt);
    }

    /**
     * 撤回未切换的接替单：释放全部新车预占，保留旧车供电；旧到场与供电证据不覆盖。
     */
    public WithdrawReplacementResponse withdraw(long replacementId, WithdrawReplacementRequest req) {
        String fingerprint = Fingerprints.structured(req.requestId(), replacementId, req.expectedVersion());
        try {
            return tx.execute(status -> doWithdraw(replacementId, req, fingerprint));
        } catch (DuplicateKeyException e) {
            return tx.execute(status -> idem
                    .findReplay(req.requestId(), fingerprint, WithdrawReplacementResponse.class)
                    .orElseThrow(() -> e));
        }
    }

    private WithdrawReplacementResponse doWithdraw(long replacementId, WithdrawReplacementRequest req,
            String fingerprint) {
        Optional<WithdrawReplacementResponse> replay =
                idem.findReplay(req.requestId(), fingerprint, WithdrawReplacementResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        ReplacementRow row = lockReplacement(replacementId);
        // 行锁串行化后复查幂等：并发同键请求在此重放首次冻结结果
        Optional<WithdrawReplacementResponse> replayAfterLock =
                idem.findReplay(req.requestId(), fingerprint, WithdrawReplacementResponse.class);
        if (replayAfterLock.isPresent()) {
            return replayAfterLock.get();
        }
        if (req.expectedVersion() != row.version()) {
            throw new ConflictException("接替单版本冲突: 期望" + req.expectedVersion() + ", 实际" + row.version());
        }
        if (!"STANDBY".equals(row.status())) {
            throw new BusinessException("仅未切换的接替单可撤回: " + row.status());
        }
        List<Long> released = releaseReservations(replacementId);
        jdbc.update("UPDATE truck_replacement SET status = 'WITHDRAWN', version = version + 1 WHERE id = ?",
                replacementId);
        jdbc.update("DELETE FROM active_replacement WHERE old_truck_id = ?", row.oldTruckId());
        store.addEvent(row.workOrderId(), "GENERATOR_REPLACEMENT_WITHDRAWN",
                "接替单" + replacementId + "已撤回, 释放热备新车" + released + ", 旧车" + row.oldTruckId()
                + "继续供电, 旧到场与供电证据保留");
        WithdrawReplacementResponse response = new WithdrawReplacementResponse(replacementId, "WITHDRAWN",
                row.version() + 1, released);
        idem.record(req.requestId(), fingerprint, "REPLACEMENT_WITHDRAW", replacementId, response);
        return response;
    }

    /**
     * 工单复电或合并时：关闭未结束接替并释放预占车（与原取消规则同事务），返回关闭的接替单数。
     */
    public int closeForWorkOrder(long workOrderId, String reason) {
        List<ReplacementRow> open = jdbc.query(
                "SELECT * FROM truck_replacement WHERE work_order_id = ? AND status = 'STANDBY' FOR UPDATE",
                ROW_MAPPER, workOrderId);
        for (ReplacementRow row : open) {
            List<Long> released = releaseReservations(row.id());
            jdbc.update("UPDATE truck_replacement SET status = 'CLOSED', version = version + 1 WHERE id = ?",
                    row.id());
            jdbc.update("DELETE FROM active_replacement WHERE old_truck_id = ?", row.oldTruckId());
            store.addEvent(workOrderId, "GENERATOR_REPLACEMENT_CLOSED",
                    "接替单" + row.id() + "因" + reason + "关闭, 释放热备新车" + released + ", 旧车"
                    + row.oldTruckId() + "随原调度单处理");
        }
        return open.size();
    }

    /** 释放接替单全部新车预占，返回被释放的车辆ID（按ID升序）。 */
    private List<Long> releaseReservations(long replacementId) {
        List<Long> released = jdbc.query(
                "SELECT truck_id FROM replacement_reservation WHERE replacement_id = ? ORDER BY truck_id",
                (rs, rowNum) -> rs.getLong(1), replacementId);
        jdbc.update("DELETE FROM replacement_reservation WHERE replacement_id = ?", replacementId);
        return released;
    }

    /**
     * 接替单详情。
     */
    public ReplacementView view(long replacementId) {
        return tx.execute(status -> viewOf(replacementId));
    }

    /**
     * 工单视角的接替单列表（按ID升序）；工单不存在抛404。
     */
    public List<ReplacementView> listByWorkOrder(long workOrderId) {
        return tx.execute(status -> {
            store.requireById(workOrderId);
            return jdbc.query("SELECT id FROM truck_replacement WHERE work_order_id = ? ORDER BY id",
                    (rs, rowNum) -> rs.getLong(1), workOrderId).stream()
                    .map(this::viewOf).toList();
        });
    }

    private ReplacementView viewOf(long replacementId) {
        ReplacementRow row = findReplacement(replacementId)
                .orElseThrow(() -> new NotFoundException("接替单不存在: " + replacementId));
        List<ReplacementTruckView> truckViews = jdbc.query(
                "SELECT rt.truck_id, t.capacity_kw, rt.arrived_at FROM replacement_truck rt"
                + " JOIN generator_truck t ON t.id = rt.truck_id WHERE rt.replacement_id = ?"
                + " ORDER BY rt.truck_id",
                (rs, rowNum) -> {
                    Timestamp arrived = rs.getTimestamp(3);
                    return new ReplacementTruckView(rs.getLong(1), rs.getInt(2),
                            arrived == null ? null : arrived.toLocalDateTime());
                }, replacementId);
        List<String> confirmations = jdbc.query(
                "SELECT operator FROM replacement_confirmation WHERE replacement_id = ?"
                + " ORDER BY confirmed_at ASC, id ASC",
                (rs, rowNum) -> rs.getString(1), replacementId);
        return new ReplacementView(row.id(), row.dispatchId(), row.workOrderId(), row.oldTruckId(),
                row.orderVersion(), row.dispatchVersion(), row.status(), row.version(), row.totalLoadKw(),
                row.oldCapacityKw(), row.newCapacityKw(), truckViews, confirmations, row.createdAt(),
                row.switchedAt());
    }

    /** 原调度单头：工单、状态、版本与冻结负荷。 */
    private record DispatchHead(long workOrderId, String status, int version, int totalLoadKw) {
    }

    /** 锁定原调度单行并返回状态与版本；不存在抛404。 */
    private DispatchHead lockDispatch(long dispatchId) {
        List<DispatchHead> rows = jdbc.query(
                "SELECT work_order_id, status, version, total_load_kw FROM generator_dispatch"
                + " WHERE id = ? FOR UPDATE",
                (rs, rowNum) -> new DispatchHead(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getInt(4)),
                dispatchId);
        if (rows.isEmpty()) {
            throw new NotFoundException("调度单不存在: " + dispatchId);
        }
        return rows.get(0);
    }

    ReplacementRow lockReplacement(long replacementId) {
        List<ReplacementRow> rows = jdbc.query(
                "SELECT * FROM truck_replacement WHERE id = ? FOR UPDATE", ROW_MAPPER, replacementId);
        if (rows.isEmpty()) {
            throw new NotFoundException("接替单不存在: " + replacementId);
        }
        return rows.get(0);
    }

    private Optional<ReplacementRow> findReplacement(long replacementId) {
        List<ReplacementRow> rows = jdbc.query(
                "SELECT * FROM truck_replacement WHERE id = ?", ROW_MAPPER, replacementId);
        return rows.stream().findFirst();
    }

    private long insertReplacement(long dispatchId, DispatchHead dispatch, long oldTruckId, int orderVersion,
            int oldCapacityKw, LocalDateTime createdAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO truck_replacement (dispatch_id, work_order_id, old_truck_id, order_version,"
                    + " dispatch_version, total_load_kw, old_capacity_kw, status, version, created_at)"
                    + " VALUES (?,?,?,?,?,?,?,'STANDBY',1,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, dispatchId);
            ps.setLong(2, dispatch.workOrderId());
            ps.setLong(3, oldTruckId);
            ps.setInt(4, orderVersion);
            ps.setInt(5, dispatch.version());
            ps.setInt(6, dispatch.totalLoadKw());
            ps.setInt(7, oldCapacityKw);
            ps.setTimestamp(8, Timestamp.valueOf(createdAt));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    private int count(String table, String where, Object... args) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + where,
                Integer.class, args);
        return n == null ? 0 : n;
    }

    private LocalDateTime toLocal(Instant instant) {
        return LocalDateTime.ofInstant(instant, AppClock.ZONE);
    }
}
