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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.starter.support.AppClock;
import com.example.starter.support.Fingerprints;
import com.example.starter.web.BusinessException;
import com.example.starter.web.ConflictException;
import com.example.starter.web.NotFoundException;
import com.example.starter.web.dto.ArrivalRequest;
import com.example.starter.web.dto.ArrivalResponse;
import com.example.starter.web.dto.CreateGeneratorDispatchRequest;
import com.example.starter.web.dto.DispatchTruckView;
import com.example.starter.web.dto.GeneratorDispatchView;
import com.example.starter.web.dto.LoadItem;
import com.example.starter.web.dto.ReassignRequest;
import com.example.starter.web.dto.ReassignResponse;
import com.example.starter.web.dto.TruckView;

/**
 * 发电车组合调度服务：调度单引用未复电工单并冻结负荷清单与工单版本，组合多车且总容量须覆盖合计负荷；
 * 一车仅一个未结束占用，任一车冲突整单不派；下发30分钟（恰满触发）未全到场转到达超时；
 * 到场逐车唯一记录，全部确认才供电；超时可整体改派（已到场车保留、其余释放换新车、全方案原子切换）；
 * 工单合并或正式复电后原子撤销未结束调度并释放车辆，供电证据保留。
 */
@Service
public class GeneratorDispatchService {

    /** 到达时限：下发后30分钟，恰满即判定超时。 */
    static final long ARRIVAL_LIMIT_MINUTES = 30L;

    /** 调度单内部行。 */
    record DispatchRow(long id, long workOrderId, int orderVersion, int totalLoadKw, String status,
            int version, LocalDateTime dispatchedAt, Long supersededById) {
    }

    private static final RowMapper<DispatchRow> ROW_MAPPER = (rs, rowNum) -> mapRow(rs);

    private final JdbcTemplate jdbc;
    private final AppClock clock;
    private final WorkOrderStore store;
    private final GeneratorTruckService trucks;
    private final GenIdempotency idem;
    private final TransactionTemplate tx;

    public GeneratorDispatchService(JdbcTemplate jdbc, AppClock clock, WorkOrderStore store,
            GeneratorTruckService trucks, GenIdempotency idem, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.store = store;
        this.trucks = trucks;
        this.idem = idem;
        this.tx = new TransactionTemplate(txManager);
    }

    private static DispatchRow mapRow(ResultSet rs) throws SQLException {
        long superseded = rs.getLong("superseded_by_id");
        Long supersededBy = rs.wasNull() ? null : superseded;
        return new DispatchRow(rs.getLong("id"), rs.getLong("work_order_id"), rs.getInt("order_version"),
                rs.getInt("total_load_kw"), rs.getString("status"), rs.getInt("version"),
                rs.getTimestamp("dispatched_at").toLocalDateTime(), supersededBy);
    }

    /**
     * 组合调度：原子冻结负荷清单与工单版本并占用全车；车总容量须覆盖合计负荷（恰等合法），
     * 任一车不存在/停用/已占用则整单不派。
     */
    public GeneratorDispatchView create(CreateGeneratorDispatchRequest req) {
        if (req.truckIds().stream().distinct().count() != req.truckIds().size()) {
            throw new IllegalArgumentException("车辆列表不可重复");
        }
        String fingerprint = Fingerprints.structured(req.requestId(), req.workOrderId(), req.expectedVersion(),
                req.loads().stream().map(l -> List.of(l.userName(), l.loadKw())).toList(),
                req.truckIds(), req.dispatchedAt().toString());
        try {
            return tx.execute(status -> doCreate(req, fingerprint));
        } catch (DuplicateKeyException e) {
            return tx.execute(status -> idem.findReplay(req.requestId(), fingerprint, GeneratorDispatchView.class)
                    .orElseThrow(() -> new BusinessException("车辆占用并发冲突, 整单不派")));
        }
    }

    private GeneratorDispatchView doCreate(CreateGeneratorDispatchRequest req, String fingerprint) {
        Optional<GeneratorDispatchView> replay =
                idem.findReplay(req.requestId(), fingerprint, GeneratorDispatchView.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        OrderHead order = lockOrder(req.workOrderId());
        // 行锁串行化后复查幂等：并发同键请求在此重放首次冻结结果
        Optional<GeneratorDispatchView> replayAfterLock =
                idem.findReplay(req.requestId(), fingerprint, GeneratorDispatchView.class);
        if (replayAfterLock.isPresent()) {
            return replayAfterLock.get();
        }
        if (!"PENDING".equals(order.status()) && !"QUEUED".equals(order.status())
                && !"EXECUTING".equals(order.status())) {
            throw new BusinessException("工单状态不可发电车调度: " + order.status());
        }
        if (req.expectedVersion() != order.version()) {
            throw new ConflictException("工单版本冲突: 期望" + req.expectedVersion() + ", 实际" + order.version());
        }
        int totalLoad = req.loads().stream().mapToInt(LoadItem::loadKw).sum();
        int totalCapacity = 0;
        for (long truckId : req.truckIds()) {
            TruckView truck = trucks.lockTruck(truckId);
            if (!truck.available()) {
                throw new BusinessException("发电车不可用: " + truckId);
            }
            if (count("active_occupation", "truck_id = ?", truckId) > 0) {
                throw new BusinessException("发电车存在未结束占用: " + truckId);
            }
            totalCapacity += truck.capacityKw();
        }
        if (totalCapacity < totalLoad) {
            throw new BusinessException("车总容量" + totalCapacity + "kW不足覆盖合计负荷" + totalLoad + "kW");
        }
        LocalDateTime dispatchedAt = toLocal(req.dispatchedAt());
        long dispatchId = insertDispatch(req.workOrderId(), order.version(), totalLoad, "IN_PROGRESS",
                dispatchedAt);
        for (LoadItem load : req.loads()) {
            jdbc.update("INSERT INTO dispatch_load (dispatch_id, user_name, load_kw) VALUES (?,?,?)",
                    dispatchId, load.userName(), load.loadKw());
        }
        for (long truckId : req.truckIds()) {
            jdbc.update("INSERT INTO dispatch_truck (dispatch_id, truck_id, arrived_at) VALUES (?,?,NULL)",
                    dispatchId, truckId);
            jdbc.update("INSERT INTO active_occupation (truck_id, dispatch_id, occupied_at) VALUES (?,?,?)",
                    truckId, dispatchId, Timestamp.valueOf(clock.now()));
        }
        store.addEvent(req.workOrderId(), "GENERATOR_DISPATCH_CREATED",
                "发电车调度单" + dispatchId + "已下发, 合计负荷" + totalLoad + "kW, 车总容量" + totalCapacity + "kW");
        GeneratorDispatchView view = viewOf(dispatchId);
        idem.record(req.requestId(), fingerprint, "DISPATCH_CREATE", dispatchId, view);
        return view;
    }

    /**
     * 到场确认：逐车唯一记录；全部到场才转供电并入账供电证据；超时/改派/撤销后的迟到到场只记事件不占车。
     */
    public ArrivalResponse arrive(long dispatchId, ArrivalRequest req) {
        String fingerprint = Fingerprints.structured(req.requestId(), dispatchId, req.expectedVersion(),
                req.truckId(), req.arrivedAt().toString());
        try {
            return tx.execute(status -> doArrive(dispatchId, req, fingerprint));
        } catch (DuplicateKeyException e) {
            return tx.execute(status -> idem.findReplay(req.requestId(), fingerprint, ArrivalResponse.class)
                    .orElseThrow(() -> e));
        }
    }

    private ArrivalResponse doArrive(long dispatchId, ArrivalRequest req, String fingerprint) {
        Optional<ArrivalResponse> replay = idem.findReplay(req.requestId(), fingerprint, ArrivalResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        DispatchRow row = lockDispatch(dispatchId);
        row = transitionIfTimedOut(row);
        // 行锁串行化后复查幂等：并发同键请求在此重放首次冻结结果
        Optional<ArrivalResponse> replayAfterLock =
                idem.findReplay(req.requestId(), fingerprint, ArrivalResponse.class);
        if (replayAfterLock.isPresent()) {
            return replayAfterLock.get();
        }
        if (req.expectedVersion() != row.version()) {
            throw new ConflictException("调度单版本冲突: 期望" + req.expectedVersion() + ", 实际" + row.version());
        }
        LocalDateTime arrivedAt = toLocal(req.arrivedAt());
        ArrivalResponse response;
        if ("IN_PROGRESS".equals(row.status())) {
            Boolean arrived = jdbc.query(
                    "SELECT arrived_at IS NOT NULL FROM dispatch_truck WHERE dispatch_id = ? AND truck_id = ?",
                    rs -> rs.next() ? rs.getBoolean(1) : null, dispatchId, req.truckId());
            if (arrived == null) {
                throw new BusinessException("车辆不在调度单方案内: " + req.truckId());
            }
            if (arrived) {
                throw new BusinessException("车辆已记录到场, 逐车唯一: " + req.truckId());
            }
            jdbc.update("UPDATE dispatch_truck SET arrived_at = ? WHERE dispatch_id = ? AND truck_id = ?",
                    Timestamp.valueOf(arrivedAt), dispatchId, req.truckId());
            int remaining = count("dispatch_truck", "dispatch_id = ? AND arrived_at IS NULL", dispatchId);
            String newStatus = row.status();
            String outcome = "ARRIVED";
            if (remaining == 0) {
                newStatus = "SUPPLYING";
                outcome = "ALL_ARRIVED";
                jdbc.update("INSERT INTO supply_evidence (dispatch_id, work_order_id, total_load_kw, supplied_at)"
                        + " VALUES (?,?,?,?)", dispatchId, row.workOrderId(), row.totalLoadKw(),
                        Timestamp.valueOf(arrivedAt));
                store.addEvent(row.workOrderId(), "GENERATOR_SUPPLYING",
                        "调度单" + dispatchId + "全部到场, 供电" + row.totalLoadKw() + "kW");
            }
            jdbc.update("UPDATE generator_dispatch SET status = ?, version = version + 1 WHERE id = ?",
                    newStatus, dispatchId);
            response = new ArrivalResponse(dispatchId, req.truckId(), outcome, newStatus,
                    row.version() + 1, arrivedAt);
        } else if ("SUPPLYING".equals(row.status())) {
            throw new BusinessException("调度单已全部到场供电, 重复到场拒绝: " + req.truckId());
        } else {
            // 超时/改派/撤销后的迟到到场：只记事件，不再次占车、不变更版本
            jdbc.update("INSERT INTO late_report_event (work_order_id, event_type, detail, created_at)"
                    + " VALUES (?,?,?,?)",
                    row.workOrderId(), "LATE_TRUCK_ARRIVAL",
                    "调度单" + dispatchId + "状态" + row.status() + ", 车辆" + req.truckId() + "迟到到场仅记事件",
                    Timestamp.valueOf(clock.now()));
            response = new ArrivalResponse(dispatchId, req.truckId(), "LATE", row.status(), row.version(),
                    arrivedAt);
        }
        idem.record(req.requestId(), fingerprint, "DISPATCH_ARRIVAL", dispatchId, response);
        return response;
    }

    /**
     * 整体改派：仅到达超时的调度单可改派；已到场车保留在新方案，其余释放并换为新车，全方案原子切换。
     */
    public ReassignResponse reassign(long dispatchId, ReassignRequest req) {
        if (req.newTruckIds().stream().distinct().count() != req.newTruckIds().size()) {
            throw new IllegalArgumentException("车辆列表不可重复");
        }
        String fingerprint = Fingerprints.structured(req.requestId(), dispatchId, req.expectedVersion(),
                req.newTruckIds(), req.dispatchedAt().toString());
        try {
            return tx.execute(status -> doReassign(dispatchId, req, fingerprint));
        } catch (DuplicateKeyException e) {
            return tx.execute(status -> idem.findReplay(req.requestId(), fingerprint, ReassignResponse.class)
                    .orElseThrow(() -> new BusinessException("车辆占用并发冲突, 整单不改派")));
        }
    }

    private ReassignResponse doReassign(long dispatchId, ReassignRequest req, String fingerprint) {
        Optional<ReassignResponse> replay = idem.findReplay(req.requestId(), fingerprint, ReassignResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        DispatchRow snapshot = findDispatch(dispatchId)
                .orElseThrow(() -> new NotFoundException("调度单不存在: " + dispatchId));
        OrderHead order = lockOrder(snapshot.workOrderId());
        DispatchRow row = lockDispatch(dispatchId);
        row = transitionIfTimedOut(row);
        // 行锁串行化后复查幂等：并发同键请求在此重放首次冻结结果
        Optional<ReassignResponse> replayAfterLock =
                idem.findReplay(req.requestId(), fingerprint, ReassignResponse.class);
        if (replayAfterLock.isPresent()) {
            return replayAfterLock.get();
        }
        if (!"TIMED_OUT".equals(row.status())) {
            throw new BusinessException("仅到达超时的调度单可整体改派: " + row.status());
        }
        if (req.expectedVersion() != row.version()) {
            throw new ConflictException("调度单版本冲突: 期望" + req.expectedVersion() + ", 实际" + row.version());
        }
        List<Long> kept = jdbc.query(
                "SELECT truck_id FROM dispatch_truck WHERE dispatch_id = ? AND arrived_at IS NOT NULL"
                + " ORDER BY truck_id",
                (rs, rowNum) -> rs.getLong(1), dispatchId);
        List<Long> released = jdbc.query(
                "SELECT truck_id FROM dispatch_truck WHERE dispatch_id = ? AND arrived_at IS NULL"
                + " ORDER BY truck_id",
                (rs, rowNum) -> rs.getLong(1), dispatchId);
        if (req.newTruckIds().stream().anyMatch(kept::contains)) {
            throw new BusinessException("新增车辆不可与已到场保留车重复");
        }
        int capacity = 0;
        for (long truckId : kept) {
            capacity += trucks.lockTruck(truckId).capacityKw();
        }
        for (long truckId : req.newTruckIds()) {
            TruckView truck = trucks.lockTruck(truckId);
            if (!truck.available()) {
                throw new BusinessException("发电车不可用: " + truckId);
            }
            if (count("active_occupation", "truck_id = ?", truckId) > 0) {
                throw new BusinessException("发电车存在未结束占用: " + truckId);
            }
            capacity += truck.capacityKw();
        }
        if (capacity < row.totalLoadKw()) {
            throw new BusinessException("新方案车总容量" + capacity + "kW不足覆盖合计负荷" + row.totalLoadKw() + "kW");
        }
        LocalDateTime dispatchedAt = toLocal(req.dispatchedAt());
        long newId = insertDispatch(row.workOrderId(), order.version(), row.totalLoadKw(), "IN_PROGRESS",
                dispatchedAt);
        List<LoadItem> loads = jdbc.query(
                "SELECT user_name, load_kw FROM dispatch_load WHERE dispatch_id = ? ORDER BY id",
                (rs, rowNum) -> new LoadItem(rs.getString(1), rs.getInt(2)), dispatchId);
        for (LoadItem load : loads) {
            jdbc.update("INSERT INTO dispatch_load (dispatch_id, user_name, load_kw) VALUES (?,?,?)",
                    newId, load.userName(), load.loadKw());
        }
        for (long truckId : kept) {
            LocalDateTime arrivedAt = jdbc.queryForObject(
                    "SELECT arrived_at FROM dispatch_truck WHERE dispatch_id = ? AND truck_id = ?",
                    (rs, rowNum) -> rs.getTimestamp(1).toLocalDateTime(), dispatchId, truckId);
            jdbc.update("INSERT INTO dispatch_truck (dispatch_id, truck_id, arrived_at) VALUES (?,?,?)",
                    newId, truckId, Timestamp.valueOf(arrivedAt));
            jdbc.update("UPDATE active_occupation SET dispatch_id = ? WHERE truck_id = ?", newId, truckId);
        }
        for (long truckId : req.newTruckIds()) {
            jdbc.update("INSERT INTO dispatch_truck (dispatch_id, truck_id, arrived_at) VALUES (?,?,NULL)",
                    newId, truckId);
            jdbc.update("INSERT INTO active_occupation (truck_id, dispatch_id, occupied_at) VALUES (?,?,?)",
                    truckId, newId, Timestamp.valueOf(clock.now()));
        }
        for (long truckId : released) {
            jdbc.update("DELETE FROM active_occupation WHERE truck_id = ?", truckId);
        }
        jdbc.update("UPDATE generator_dispatch SET status = 'REASSIGNED', superseded_by_id = ?,"
                + " version = version + 1 WHERE id = ?", newId, dispatchId);
        store.addEvent(row.workOrderId(), "GENERATOR_DISPATCH_REASSIGNED",
                "调度单" + dispatchId + "整体改派为" + newId + ", 保留已到场车" + kept + ", 释放" + released);
        ReassignResponse response = new ReassignResponse(dispatchId, newId, kept, released,
                req.newTruckIds(), "IN_PROGRESS", 1);
        idem.record(req.requestId(), fingerprint, "DISPATCH_REASSIGN", newId, response);
        return response;
    }

    /**
     * 批量评估到达超时：下发满30分钟（恰满触发）未全到场的调度单转 TIMED_OUT，返回本次转换的调度单ID。
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public List<Long> evaluateTimeouts() {
        List<Long> candidates = jdbc.query(
                "SELECT id FROM generator_dispatch WHERE status = 'IN_PROGRESS' ORDER BY id",
                (rs, rowNum) -> rs.getLong(1));
        return candidates.stream().filter(this::evaluateTimeout).toList();
    }

    /**
     * 评估单个调度单是否到达超时；恰满30分钟未全到场即转换。
     *
     * @return true 表示本次调用发生了超时转换
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public boolean evaluateTimeout(long dispatchId) {
        Optional<DispatchRow> found = findDispatch(dispatchId);
        if (found.isEmpty() || !"IN_PROGRESS".equals(found.get().status())) {
            return false;
        }
        DispatchRow before = lockDispatch(dispatchId);
        return !transitionIfTimedOut(before).status().equals(before.status());
    }

    /**
     * 工单合并或正式复电后：原子撤销未结束调度并释放车辆，供电证据保留。返回撤销的调度单数。
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public int cancelForWorkOrder(long workOrderId, String reason) {
        List<DispatchRow> open = jdbc.query(
                "SELECT * FROM generator_dispatch WHERE work_order_id = ?"
                + " AND status IN ('IN_PROGRESS','SUPPLYING','TIMED_OUT') FOR UPDATE",
                ROW_MAPPER, workOrderId);
        for (DispatchRow row : open) {
            jdbc.update("UPDATE generator_dispatch SET status = 'CANCELLED', version = version + 1"
                    + " WHERE id = ?", row.id());
            jdbc.update("DELETE FROM active_occupation WHERE dispatch_id = ?", row.id());
            store.addEvent(workOrderId, "GENERATOR_DISPATCH_CANCELLED",
                    "调度单" + row.id() + "因" + reason + "撤销, 车辆已释放, 供电证据保留");
        }
        return open.size();
    }

    /**
     * 调度单详情：查询前评估到达超时。
     */
    public GeneratorDispatchView view(long dispatchId) {
        return tx.execute(status -> {
            evaluateTimeout(dispatchId);
            return viewOf(dispatchId);
        });
    }

    /**
     * 工单视角的调度单列表（按ID升序）；工单不存在抛404。
     */
    public List<GeneratorDispatchView> listByWorkOrder(long workOrderId) {
        return tx.execute(status -> {
            store.requireById(workOrderId);
            evaluateTimeouts();
            return jdbc.query("SELECT id FROM generator_dispatch WHERE work_order_id = ? ORDER BY id",
                    (rs, rowNum) -> rs.getLong(1), workOrderId).stream()
                    .map(this::viewOf).toList();
        });
    }

    private GeneratorDispatchView viewOf(long dispatchId) {
        DispatchRow row = findDispatch(dispatchId)
                .orElseThrow(() -> new NotFoundException("调度单不存在: " + dispatchId));
        List<LoadItem> loads = jdbc.query(
                "SELECT user_name, load_kw FROM dispatch_load WHERE dispatch_id = ? ORDER BY id",
                (rs, rowNum) -> new LoadItem(rs.getString(1), rs.getInt(2)), dispatchId);
        List<DispatchTruckView> truckViews = jdbc.query(
                "SELECT dt.truck_id, t.capacity_kw, dt.arrived_at FROM dispatch_truck dt"
                + " JOIN generator_truck t ON t.id = dt.truck_id WHERE dt.dispatch_id = ?"
                + " ORDER BY dt.truck_id",
                (rs, rowNum) -> {
                    Timestamp arrived = rs.getTimestamp(3);
                    return new DispatchTruckView(rs.getLong(1), rs.getInt(2),
                            arrived == null ? null : arrived.toLocalDateTime());
                }, dispatchId);
        List<Timestamp> supplied = jdbc.query(
                "SELECT supplied_at FROM supply_evidence WHERE dispatch_id = ?",
                (rs, rowNum) -> rs.getTimestamp(1), dispatchId);
        int totalCapacity = truckViews.stream().mapToInt(DispatchTruckView::capacityKw).sum();
        return new GeneratorDispatchView(row.id(), row.workOrderId(), row.orderVersion(), row.status(),
                row.version(), row.totalLoadKw(), totalCapacity, row.dispatchedAt(), loads, truckViews,
                supplied.isEmpty() ? null : supplied.get(0).toLocalDateTime(), row.supersededById());
    }

    /** 若已到达超时则转换状态并返回最新行，否则原样返回。调用方须已持有行锁。 */
    private DispatchRow transitionIfTimedOut(DispatchRow row) {
        if (!"IN_PROGRESS".equals(row.status())) {
            return row;
        }
        if (clock.now().isBefore(row.dispatchedAt().plusMinutes(ARRIVAL_LIMIT_MINUTES))) {
            return row;
        }
        jdbc.update("UPDATE generator_dispatch SET status = 'TIMED_OUT', version = version + 1 WHERE id = ?",
                row.id());
        store.addEvent(row.workOrderId(), "GENERATOR_DISPATCH_TIMEOUT",
                "调度单" + row.id() + "下发满30分钟未全到场, 转到达超时");
        return lockDispatch(row.id());
    }

    private DispatchRow lockDispatch(long dispatchId) {
        List<DispatchRow> rows = jdbc.query(
                "SELECT * FROM generator_dispatch WHERE id = ? FOR UPDATE", ROW_MAPPER, dispatchId);
        if (rows.isEmpty()) {
            throw new NotFoundException("调度单不存在: " + dispatchId);
        }
        return rows.get(0);
    }

    private Optional<DispatchRow> findDispatch(long dispatchId) {
        List<DispatchRow> rows = jdbc.query(
                "SELECT * FROM generator_dispatch WHERE id = ?", ROW_MAPPER, dispatchId);
        return rows.stream().findFirst();
    }

    /** 工单头：状态与版本。 */
    private record OrderHead(String status, int version) {
    }

    /** 锁定工单行并返回状态与版本；不存在抛404。 */
    private OrderHead lockOrder(long workOrderId) {
        List<OrderHead> rows = jdbc.query(
                "SELECT status, version FROM work_order WHERE id = ? FOR UPDATE",
                (rs, rowNum) -> new OrderHead(rs.getString(1), rs.getInt(2)), workOrderId);
        if (rows.isEmpty()) {
            throw new NotFoundException("工单不存在: " + workOrderId);
        }
        return rows.get(0);
    }

    private long insertDispatch(long workOrderId, int orderVersion, int totalLoadKw, String status,
            LocalDateTime dispatchedAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO generator_dispatch (work_order_id, order_version, total_load_kw, status,"
                    + " version, dispatched_at, created_at) VALUES (?,?,?,?,1,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, workOrderId);
            ps.setInt(2, orderVersion);
            ps.setInt(3, totalLoadKw);
            ps.setString(4, status);
            ps.setTimestamp(5, Timestamp.valueOf(dispatchedAt));
            ps.setTimestamp(6, Timestamp.valueOf(clock.now()));
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
