package com.example.starter.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.service.GeneratorTruckService.TruckRow;
import com.example.starter.support.AppClock;
import com.example.starter.support.Fingerprints;
import com.example.starter.web.ConflictException;
import com.example.starter.web.NotFoundException;
import com.example.starter.web.UnprocessableException;
import com.example.starter.web.dto.DispatchLoadView;
import com.example.starter.web.dto.DispatchTruckView;
import com.example.starter.web.dto.GeneratorDispatchRequest;
import com.example.starter.web.dto.GeneratorDispatchView;
import com.example.starter.web.dto.ReassignRequest;
import com.example.starter.web.dto.TruckArrivalRequest;
import com.example.starter.web.dto.TruckArrivalResponse;

/**
 * 发电车组合调度服务：引用未复电工单并冻结重要用户负荷清单与工单版本，组合多车且总容量覆盖合计负荷；
 * 一车一个未结束占用，任一车冲突整单不派；下发满30分钟（恰满触发）未全到场转到达超时；
 * 到场逐车唯一记录，全部确认才供电；超时可整体改派（保留已到场车，其余释放换新车，全方案原子切换）；
 * 工单合并或正式复电后原子撤销未结束调度并释放车辆，保留供电证据。
 */
@Service
public class GeneratorDispatchService {

    /** 到达时限：下发后 30 分钟，恰满即判定超时。 */
    static final long ARRIVAL_LIMIT_MINUTES = 30L;

    private final JdbcTemplate jdbc;
    private final AppClock clock;
    private final GeneratorTruckService trucks;
    private final IdempotentExecutor idempotent;

    public GeneratorDispatchService(JdbcTemplate jdbc, AppClock clock, GeneratorTruckService trucks,
            IdempotentExecutor idempotent) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.trucks = trucks;
        this.idempotent = idempotent;
    }

    /**
     * 创建组合调度单：冻结负荷清单与工单版本并原子占用全车；同 requestId 同参重放，同键异参 409。
     */
    public GeneratorDispatchView create(GeneratorDispatchRequest req) {
        List<Long> truckIds = req.truckIds() == null ? List.of()
                : req.truckIds().stream().distinct().sorted().toList();
        if (truckIds.isEmpty()) {
            throw new IllegalArgumentException("组合车辆不能为空");
        }
        String fingerprint = Fingerprints.structured(req.requestId(), req.workOrderId(),
                req.expectedVersion(), truckIds);
        return idempotent.execute(req.requestId(), fingerprint, GeneratorDispatchView.class,
                () -> doCreate(req, truckIds));
    }

    private GeneratorDispatchView doCreate(GeneratorDispatchRequest req, List<Long> truckIds) {
        LockedOrder order = lockOrder(req.workOrderId());
        if (order.version() != req.expectedVersion()) {
            throw new ConflictException("工单版本冲突: 期望" + req.expectedVersion() + ", 实际" + order.version());
        }
        if (!Set.of("PENDING", "QUEUED", "EXECUTING").contains(order.status())) {
            throw new UnprocessableException("工单状态不可调度发电车: " + order.status());
        }
        List<DispatchLoadView> loads = loadList(order.feederId());
        int totalLoad = loads.stream().mapToInt(DispatchLoadView::loadKw).sum();
        if (totalLoad <= 0) {
            throw new UnprocessableException("馈线无重要用户保电负荷: " + order.feederId());
        }
        List<TruckRow> combined = lockAndCheckTrucks(truckIds, order.feederId());
        requireUnoccupied(truckIds);
        int capacity = combined.stream().mapToInt(TruckRow::capacityKw).sum();
        if (capacity < totalLoad) {
            throw new UnprocessableException(
                    "车总容量" + capacity + "kW不足覆盖合计负荷" + totalLoad + "kW");
        }
        long dispatchId = insertDispatch(order, totalLoad, null);
        for (DispatchLoadView load : loads) {
            jdbc.update("INSERT INTO generator_dispatch_load (dispatch_id, user_name, load_kw) VALUES (?,?,?)",
                    dispatchId, load.userName(), load.loadKw());
        }
        for (TruckRow truck : combined) {
            jdbc.update("INSERT INTO generator_dispatch_truck (dispatch_id, truck_id, capacity_kw,"
                    + " arrived_at, active_occupation) VALUES (?,?,?,?,?)",
                    dispatchId, truck.id(), truck.capacityKw(), null, truck.id());
        }
        return viewOf(dispatchId, false);
    }

    /**
     * 到场确认：逐车唯一记录，全部到场才供电；已超时/改派/撤销的调度单迟到到场只记事件，不再占车。
     */
    public TruckArrivalResponse arrive(long dispatchId, TruckArrivalRequest req) {
        String fingerprint = Fingerprints.structured(req.requestId(), dispatchId, req.expectedVersion(),
                req.truckId(), req.arrivedAt());
        return idempotent.execute(req.requestId(), fingerprint, TruckArrivalResponse.class,
                () -> doArrive(dispatchId, req));
    }

    private TruckArrivalResponse doArrive(long dispatchId, TruckArrivalRequest req) {
        DispatchRow dispatch = evaluateTimeoutLocked(lockDispatch(dispatchId));
        if (dispatch.version() != req.expectedVersion()) {
            throw new ConflictException(
                    "调度单版本冲突: 期望" + req.expectedVersion() + ", 实际" + dispatch.version());
        }
        LocalDateTime now = clock.now();
        if (req.arrivedAt().isAfter(now)) {
            throw new IllegalArgumentException("到场时刻不能晚于当前时间: " + req.arrivedAt());
        }
        PlanTruckRow plan = findPlanTruck(dispatchId, req.truckId())
                .orElseThrow(() -> new UnprocessableException("车辆不在调度方案内: " + req.truckId()));
        if (plan.arrivedAt() != null) {
            throw new UnprocessableException("车辆已到场，不可重复记录: " + req.truckId());
        }
        if (!"DISPATCHED".equals(dispatch.status())) {
            // 迟到到场：只记事件，不再占车，不改变调度单状态与版本
            addEvent(dispatchId, "LATE_ARRIVAL",
                    "车辆" + req.truckId() + "于" + req.arrivedAt() + "迟到到场, 调度单状态=" + dispatch.status());
            return new TruckArrivalResponse(dispatchId, req.truckId(), dispatch.status(),
                    allArrived(dispatchId), "SUPPLYING".equals(dispatch.status()), true,
                    dispatch.version(), false);
        }
        jdbc.update("UPDATE generator_dispatch_truck SET arrived_at = ? WHERE id = ?",
                Timestamp.valueOf(req.arrivedAt()), plan.id());
        boolean allArrived = allArrived(dispatchId);
        String status = "DISPATCHED";
        if (allArrived) {
            status = "SUPPLYING";
            jdbc.update("UPDATE generator_dispatch SET status = 'SUPPLYING', supplied_at = ?,"
                    + " version = version + 1 WHERE id = ?", Timestamp.valueOf(now), dispatchId);
            addEvent(dispatchId, "SUPPLYING", "全部车辆到场, 确认供电");
        } else {
            jdbc.update("UPDATE generator_dispatch SET version = version + 1 WHERE id = ?", dispatchId);
        }
        return new TruckArrivalResponse(dispatchId, req.truckId(), status, allArrived, allArrived,
                false, dispatch.version() + 1, false);
    }

    /**
     * 整体改派：仅到达超时的调度单可改派；已到场车保留在新方案，其余释放并换为新车，全方案原子切换。
     */
    public GeneratorDispatchView reassign(long dispatchId, ReassignRequest req) {
        List<Long> newTruckIds = req.newTruckIds() == null ? List.of()
                : req.newTruckIds().stream().distinct().sorted().toList();
        String fingerprint = Fingerprints.structured(req.requestId(), dispatchId, req.expectedVersion(),
                newTruckIds);
        return idempotent.execute(req.requestId(), fingerprint, GeneratorDispatchView.class,
                () -> doReassign(dispatchId, req.expectedVersion(), newTruckIds));
    }

    private GeneratorDispatchView doReassign(long dispatchId, long expectedVersion, List<Long> newTruckIds) {
        DispatchRow old = evaluateTimeoutLocked(lockDispatch(dispatchId));
        if (old.version() != expectedVersion) {
            throw new ConflictException("调度单版本冲突: 期望" + expectedVersion + ", 实际" + old.version());
        }
        if (!"ARRIVAL_TIMEOUT".equals(old.status())) {
            throw new UnprocessableException("仅到达超时的调度单可整体改派: " + old.status());
        }
        String feederId = jdbc.queryForObject("SELECT feeder_id FROM work_order WHERE id = ?",
                String.class, old.workOrderId());
        List<PlanTruckRow> plan = planTrucks(dispatchId);
        List<PlanTruckRow> kept = plan.stream().filter(p -> p.arrivedAt() != null).toList();
        for (Long newId : newTruckIds) {
            if (plan.stream().anyMatch(p -> p.truckId() == newId)) {
                throw new UnprocessableException("换入车辆已在原方案中: " + newId);
            }
        }
        List<TruckRow> fresh = lockAndCheckTrucks(newTruckIds, feederId);
        requireUnoccupied(newTruckIds);
        int capacity = kept.stream().mapToInt(PlanTruckRow::capacityKw).sum()
                + fresh.stream().mapToInt(TruckRow::capacityKw).sum();
        if (capacity < old.totalLoadKw()) {
            throw new UnprocessableException(
                    "新方案车总容量" + capacity + "kW不足覆盖合计负荷" + old.totalLoadKw() + "kW");
        }
        LocalDateTime now = clock.now();
        // 全方案原子切换：旧单关闭并释放全部占用，新单继承冻结负荷与已到场车
        jdbc.update("UPDATE generator_dispatch SET status = 'REASSIGNED', version = version + 1,"
                + " closed_at = ? WHERE id = ?", Timestamp.valueOf(now), dispatchId);
        addEvent(dispatchId, "REASSIGNED",
                "整体改派, 保留已到场车" + kept.stream().map(PlanTruckRow::truckId).toList()
                        + ", 换入新车" + newTruckIds);
        jdbc.update("UPDATE generator_dispatch_truck SET active_occupation = NULL"
                + " WHERE dispatch_id = ? AND active_occupation IS NOT NULL", dispatchId);
        long newDispatchId = insertDispatch(
                new LockedOrder(old.workOrderId(), feederId, "", old.workOrderVersion()),
                old.totalLoadKw(), dispatchId);
        jdbc.update("INSERT INTO generator_dispatch_load (dispatch_id, user_name, load_kw)"
                + " SELECT ?, user_name, load_kw FROM generator_dispatch_load WHERE dispatch_id = ?",
                newDispatchId, dispatchId);
        for (PlanTruckRow truck : kept) {
            jdbc.update("INSERT INTO generator_dispatch_truck (dispatch_id, truck_id, capacity_kw,"
                    + " arrived_at, active_occupation) VALUES (?,?,?,?,?)",
                    newDispatchId, truck.truckId(), truck.capacityKw(),
                    Timestamp.valueOf(truck.arrivedAt()), truck.truckId());
        }
        for (TruckRow truck : fresh) {
            jdbc.update("INSERT INTO generator_dispatch_truck (dispatch_id, truck_id, capacity_kw,"
                    + " arrived_at, active_occupation) VALUES (?,?,?,?,?)",
                    newDispatchId, truck.id(), truck.capacityKw(), null, truck.id());
        }
        if (fresh.isEmpty() && !kept.isEmpty()) {
            // 新方案全部车辆已到场（仅保留已到场车），直接确认供电
            jdbc.update("UPDATE generator_dispatch SET status = 'SUPPLYING', supplied_at = ?,"
                    + " version = version + 1 WHERE id = ?", Timestamp.valueOf(now), newDispatchId);
            addEvent(newDispatchId, "SUPPLYING", "改派方案全部车辆已到场, 确认供电");
        }
        return viewOf(newDispatchId, false);
    }

    /**
     * 工单合并或正式复电后，原子撤销未结束调度并释放车辆；到场与供电记录保留为证据。
     * 在调用方事务内执行（复电/合并与改派、到场并发唯一由行锁与版本保证）。
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void cancelForWorkOrder(long workOrderId, String reason) {
        List<Long> ids = jdbc.query(
                "SELECT id FROM generator_dispatch WHERE work_order_id = ?"
                + " AND status IN ('DISPATCHED','ARRIVAL_TIMEOUT','SUPPLYING') ORDER BY id ASC",
                (rs, rowNum) -> rs.getLong(1), workOrderId);
        for (long id : ids) {
            lockDispatch(id);
            jdbc.update("UPDATE generator_dispatch SET status = 'CANCELLED', version = version + 1,"
                    + " closed_at = ? WHERE id = ?", Timestamp.valueOf(clock.now()), id);
            jdbc.update("UPDATE generator_dispatch_truck SET active_occupation = NULL"
                    + " WHERE dispatch_id = ? AND active_occupation IS NOT NULL", id);
            addEvent(id, "CANCELLED", reason);
        }
    }

    /**
     * 调度单详情：查询前评估到达超时。
     */
    @Transactional
    public GeneratorDispatchView view(long dispatchId) {
        evaluateTimeoutLocked(lockDispatch(dispatchId));
        return viewOf(dispatchId, false);
    }

    /**
     * 工单的发电车调度单列表（按ID升序），查询前逐单评估到达超时。
     */
    @Transactional
    public List<GeneratorDispatchView> listForOrder(long workOrderId) {
        List<Long> order = jdbc.query("SELECT id FROM work_order WHERE id = ?",
                (rs, rowNum) -> rs.getLong(1), workOrderId);
        if (order.isEmpty()) {
            throw new NotFoundException("工单不存在: " + workOrderId);
        }
        List<Long> ids = jdbc.query("SELECT id FROM generator_dispatch WHERE work_order_id = ?"
                + " ORDER BY id ASC", (rs, rowNum) -> rs.getLong(1), workOrderId);
        List<GeneratorDispatchView> views = new ArrayList<>();
        for (long id : ids) {
            views.add(view(id));
        }
        return views;
    }

    /**
     * 批量评估到达超时：下发满30分钟（恰满触发）未全到场的调度单转到达超时，返回本次新转换的调度单ID。
     */
    @Transactional
    public List<Long> timeoutCheck() {
        List<Long> ids = jdbc.query("SELECT id FROM generator_dispatch WHERE status = 'DISPATCHED'"
                + " ORDER BY id ASC", (rs, rowNum) -> rs.getLong(1));
        List<Long> transitioned = new ArrayList<>();
        for (long id : ids) {
            DispatchRow before = lockDispatch(id);
            DispatchRow after = evaluateTimeoutLocked(before);
            if (!before.status().equals(after.status())) {
                transitioned.add(id);
            }
        }
        return transitioned;
    }

    /**
     * 评估到达超时：DISPATCHED 且当前时间不早于下发后30分钟（恰满触发）则转 ARRIVAL_TIMEOUT 并加版本。
     */
    private DispatchRow evaluateTimeoutLocked(DispatchRow dispatch) {
        if (!"DISPATCHED".equals(dispatch.status())) {
            return dispatch;
        }
        LocalDateTime deadline = dispatch.dispatchedAt().plusMinutes(ARRIVAL_LIMIT_MINUTES);
        if (clock.now().isBefore(deadline)) {
            return dispatch;
        }
        jdbc.update("UPDATE generator_dispatch SET status = 'ARRIVAL_TIMEOUT', version = version + 1"
                + " WHERE id = ?", dispatch.id());
        addEvent(dispatch.id(), "TIMEOUT", "下发满30分钟未全部到场, 转到达超时");
        return lockDispatch(dispatch.id());
    }

    private LockedOrder lockOrder(long workOrderId) {
        List<LockedOrder> rows = jdbc.query(
                "SELECT id, feeder_id, status, version FROM work_order WHERE id = ? FOR UPDATE",
                (rs, rowNum) -> new LockedOrder(rs.getLong("id"), rs.getString("feeder_id"),
                        rs.getString("status"), rs.getInt("version")),
                workOrderId);
        if (rows.isEmpty()) {
            throw new NotFoundException("工单不存在: " + workOrderId);
        }
        return rows.get(0);
    }

    private DispatchRow lockDispatch(long dispatchId) {
        List<DispatchRow> rows = jdbc.query(
                "SELECT * FROM generator_dispatch WHERE id = ? FOR UPDATE",
                (rs, rowNum) -> mapDispatch(rs), dispatchId);
        if (rows.isEmpty()) {
            throw new NotFoundException("调度单不存在: " + dispatchId);
        }
        return rows.get(0);
    }

    private List<TruckRow> lockAndCheckTrucks(List<Long> truckIds, String feederId) {
        List<TruckRow> result = new ArrayList<>();
        for (long truckId : truckIds) {
            TruckRow truck = trucks.lockTruck(truckId);
            if (!truck.available()) {
                throw new UnprocessableException("发电车不可用: " + truck.truckCode());
            }
            if (!feederId.equals(truck.feederId())) {
                throw new UnprocessableException(
                        "发电车" + truck.truckCode() + "服务馈线" + truck.feederId() + "与工单馈线" + feederId + "不一致");
            }
            result.add(truck);
        }
        return result;
    }

    /**
     * 预检车辆未结束占用；并发占用由 active_occupation 唯一索引兜底。
     */
    private void requireUnoccupied(List<Long> truckIds) {
        if (truckIds.isEmpty()) {
            return;
        }
        String placeholders = String.join(",", Collections.nCopies(truckIds.size(), "?"));
        List<Long> occupied = jdbc.query(
                "SELECT active_occupation FROM generator_dispatch_truck WHERE active_occupation IN ("
                + placeholders + ")",
                (rs, rowNum) -> rs.getLong(1), truckIds.toArray());
        if (!occupied.isEmpty()) {
            throw new UnprocessableException("发电车存在未结束占用，整单不派: " + occupied);
        }
    }

    private List<DispatchLoadView> loadList(String feederId) {
        return jdbc.query("SELECT user_name, load_kw FROM important_user WHERE feeder_id = ?"
                + " ORDER BY user_name ASC",
                (rs, rowNum) -> new DispatchLoadView(rs.getString("user_name"), rs.getInt("load_kw")),
                feederId);
    }

    private long insertDispatch(LockedOrder order, int totalLoad, Long predecessorId) {
        KeyHolder keys = new GeneratedKeyHolder();
        LocalDateTime now = clock.now();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO generator_dispatch (work_order_id, work_order_version, total_load_kw,"
                    + " status, version, predecessor_id, dispatched_at, created_at)"
                    + " VALUES (?,?,?,'DISPATCHED',1,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, order.id());
            ps.setInt(2, order.version());
            ps.setInt(3, totalLoad);
            if (predecessorId == null) {
                ps.setObject(4, null);
            } else {
                ps.setLong(4, predecessorId);
            }
            ps.setTimestamp(5, Timestamp.valueOf(now));
            ps.setTimestamp(6, Timestamp.valueOf(now));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    private List<PlanTruckRow> planTrucks(long dispatchId) {
        return jdbc.query("SELECT * FROM generator_dispatch_truck WHERE dispatch_id = ? ORDER BY id ASC",
                (rs, rowNum) -> mapPlanTruck(rs), dispatchId);
    }

    private Optional<PlanTruckRow> findPlanTruck(long dispatchId, long truckId) {
        return jdbc.query("SELECT * FROM generator_dispatch_truck WHERE dispatch_id = ? AND truck_id = ?",
                (rs, rowNum) -> mapPlanTruck(rs), dispatchId, truckId).stream().findFirst();
    }

    private boolean allArrived(long dispatchId) {
        Integer missing = jdbc.queryForObject(
                "SELECT COUNT(*) FROM generator_dispatch_truck WHERE dispatch_id = ?"
                + " AND arrived_at IS NULL", Integer.class, dispatchId);
        return missing != null && missing == 0;
    }

    private void addEvent(long dispatchId, String eventType, String detail) {
        jdbc.update("INSERT INTO generator_dispatch_event (dispatch_id, event_type, detail, created_at)"
                + " VALUES (?,?,?,?)",
                dispatchId, eventType, detail, Timestamp.valueOf(clock.now()));
    }

    private GeneratorDispatchView viewOf(long dispatchId, boolean replayed) {
        DispatchRow d = jdbc.query("SELECT * FROM generator_dispatch WHERE id = ?",
                (rs, rowNum) -> mapDispatch(rs), dispatchId).get(0);
        List<DispatchTruckView> truckViews = jdbc.query(
                "SELECT dt.truck_id, t.truck_code, dt.capacity_kw, dt.arrived_at"
                + " FROM generator_dispatch_truck dt JOIN generator_truck t ON t.id = dt.truck_id"
                + " WHERE dt.dispatch_id = ? ORDER BY dt.id ASC",
                (rs, rowNum) -> {
                    Timestamp arrived = rs.getTimestamp("arrived_at");
                    return new DispatchTruckView(rs.getLong("truck_id"), rs.getString("truck_code"),
                            rs.getInt("capacity_kw"), arrived == null ? null : arrived.toLocalDateTime());
                }, dispatchId);
        List<DispatchLoadView> loads = jdbc.query(
                "SELECT user_name, load_kw FROM generator_dispatch_load WHERE dispatch_id = ?"
                + " ORDER BY user_name ASC",
                (rs, rowNum) -> new DispatchLoadView(rs.getString("user_name"), rs.getInt("load_kw")),
                dispatchId);
        return new GeneratorDispatchView(d.id(), d.workOrderId(), d.workOrderVersion(), d.totalLoadKw(),
                d.status(), d.version(), d.predecessorId(), d.dispatchedAt(), d.suppliedAt(),
                truckViews, loads, replayed);
    }

    private static DispatchRow mapDispatch(ResultSet rs) throws SQLException {
        long predecessor = rs.getLong("predecessor_id");
        Long predecessorId = rs.wasNull() ? null : predecessor;
        Timestamp supplied = rs.getTimestamp("supplied_at");
        Timestamp closed = rs.getTimestamp("closed_at");
        return new DispatchRow(rs.getLong("id"), rs.getLong("work_order_id"),
                rs.getInt("work_order_version"), rs.getInt("total_load_kw"), rs.getString("status"),
                rs.getInt("version"), predecessorId,
                rs.getTimestamp("dispatched_at").toLocalDateTime(),
                supplied == null ? null : supplied.toLocalDateTime(),
                closed == null ? null : closed.toLocalDateTime());
    }

    private static PlanTruckRow mapPlanTruck(ResultSet rs) throws SQLException {
        Timestamp arrived = rs.getTimestamp("arrived_at");
        return new PlanTruckRow(rs.getLong("id"), rs.getLong("dispatch_id"), rs.getLong("truck_id"),
                rs.getInt("capacity_kw"), arrived == null ? null : arrived.toLocalDateTime());
    }

    /**
     * 锁定的工单摘要行。
     */
    private record LockedOrder(long id, String feederId, String status, int version) {
    }

    /**
     * 调度单行记录。
     */
    private record DispatchRow(long id, long workOrderId, int workOrderVersion, int totalLoadKw,
            String status, int version, Long predecessorId, LocalDateTime dispatchedAt,
            LocalDateTime suppliedAt, LocalDateTime closedAt) {
    }

    /**
     * 调度单车辆行记录。
     */
    private record PlanTruckRow(long id, long dispatchId, long truckId, int capacityKw,
            LocalDateTime arrivedAt) {
    }
}
