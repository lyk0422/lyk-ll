package com.example.starter.service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.starter.support.AppClock;
import com.example.starter.support.Fingerprints;
import com.example.starter.web.ConflictException;
import com.example.starter.web.NotFoundException;
import com.example.starter.web.UnprocessableException;
import com.example.starter.web.dto.GeneratorDispatchRequest;
import com.example.starter.web.dto.GeneratorDispatchResponse;
import com.example.starter.web.dto.GeneratorOrderView;
import com.example.starter.web.dto.GeneratorReassignRequest;
import com.example.starter.web.dto.GeneratorReassignResponse;
import com.example.starter.web.dto.LoadItemRequest;
import com.example.starter.web.dto.OccupationEntry;
import com.example.starter.web.dto.OrderVehicleView;
import com.example.starter.web.dto.VehicleArrivalRequest;
import com.example.starter.web.dto.VehicleArrivalResponse;
import com.example.starter.web.dto.VehicleRegisterRequest;
import com.example.starter.web.dto.VehicleView;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * 重要用户发电车组合调度服务（B 题）：发电车池登记、组合调度、逐车到场与全到场供电、
 * UTC 下发后 30 分钟到达超时与整体改派、工单合并/正式复电原子撤销、容量与占用台账、乐观版本与结构化幂等。
 */
@Service
public class GeneratorDispatchService {

    /** UTC 下发后到场时限：30 分钟，恰满未全到场即超时。 */
    static final long ARRIVAL_LIMIT_MINUTES = 30L;

    private static final List<String> OPEN_STATUSES = List.of("DISPATCHED", "PARTIAL_ARRIVAL", "ARRIVAL_TIMEOUT");
    private static final List<String> ACTIVE_STATUSES = List.of("DISPATCHED", "PARTIAL_ARRIVAL");

    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());

    private final JdbcTemplate jdbc;
    private final AppClock clock;
    private final WorkOrderStore store;
    private final TransactionTemplate tx;

    public GeneratorDispatchService(JdbcTemplate jdbc, AppClock clock, WorkOrderStore store,
            PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.store = store;
        this.tx = new TransactionTemplate(txManager);
    }

    // ==================== 发电车池登记与台账 ====================

    /**
     * 登记发电车：正整数容量、服务馈线、可用状态；requestId 幂等，同键异参 409，失败不占键。
     */
    public VehicleView registerVehicle(VehicleRegisterRequest req) {
        boolean available = req.available() == null || req.available();
        String fingerprint = Fingerprints.structured("VEHICLE_REGISTER", req.requestId(),
                req.code(), req.capacityKw(), req.feederId(), available);
        try {
            return tx.execute(status -> doRegister(req, available, fingerprint));
        } catch (DuplicateKeyException e) {
            return tx.execute(status -> replay(req.requestId(), fingerprint, VehicleView.class)
                    .orElseThrow(() -> e));
        }
    }

    private VehicleView doRegister(VehicleRegisterRequest req, boolean available, String fingerprint) {
        Optional<VehicleView> replay = replay(req.requestId(), fingerprint, VehicleView.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (req.capacityKw() == null || req.capacityKw() <= 0) {
            throw new IllegalArgumentException("发电车容量必须为正整数kW");
        }
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM generator_vehicle WHERE code = ?", Integer.class, req.code());
        if (exists != null && exists > 0) {
            throw new UnprocessableException("发电车编号已登记: " + req.code());
        }
        jdbc.update("INSERT INTO generator_vehicle (request_id, code, capacity_kw, feeder_id, available, created_at)"
                + " VALUES (?,?,?,?,?,?)",
                req.requestId(), req.code(), req.capacityKw(), req.feederId(), available,
                Timestamp.valueOf(clock.nowUtc()));
        VehicleView view = loadVehicleByCode(req.code());
        saveIdem(req.requestId(), fingerprint, "VEHICLE_REGISTER", view);
        return view;
    }

    /**
     * 发电车池台账：容量、馈线、可用状态与当前占用；feederId 为空查全部。
     */
    @Transactional(readOnly = true)
    public List<VehicleView> listVehicles(String feederId) {
        String sql = "SELECT v.id, v.code, v.capacity_kw, v.feeder_id, v.available,"
                + " (SELECT o.order_id FROM generator_occupation o WHERE o.vehicle_id = v.id"
                + "  AND o.released_at IS NULL LIMIT 1) AS occupied_order_id"
                + " FROM generator_vehicle v"
                + (feederId == null ? "" : " WHERE v.feeder_id = ?")
                + " ORDER BY v.id ASC";
        var mapper = (org.springframework.jdbc.core.RowMapper<VehicleView>) (rs, rowNum) -> {
            long orderId = rs.getLong("occupied_order_id");
            Long occupiedOrder = rs.wasNull() ? null : orderId;
            return new VehicleView(rs.getLong("id"), rs.getString("code"), rs.getInt("capacity_kw"),
                    rs.getString("feeder_id"), rs.getBoolean("available"),
                    occupiedOrder != null, occupiedOrder);
        };
        return feederId == null ? jdbc.query(sql, mapper) : jdbc.query(sql, mapper, feederId);
    }

    // ==================== 组合调度下发 ====================

    /**
     * 组合调度下发：冻结负荷清单与工单版本，组合总容量须覆盖合计负荷（恰等合法），
     * 任一车不可用/被占用/馈线不符则整单不派；全车原子占用。
     */
    public GeneratorDispatchResponse dispatch(GeneratorDispatchRequest req) {
        List<Long> vehicleIds = distinctOrdered(req.vehicleIds(), "发电车");
        List<LoadItemRequest> loads = distinctLoads(req.loads());
        if (req.expectedVersion() == null || req.expectedVersion() <= 0) {
            throw new IllegalArgumentException("expectedVersion 必须为正整数，从1开始");
        }
        if (req.dispatchedAt() == null) {
            throw new IllegalArgumentException("dispatchedAt 精确UTC时刻不能为空");
        }
        String fingerprint = Fingerprints.structured("DISPATCH", req.requestId(), req.workOrderId(),
                req.expectedVersion(), vehicleIds, loads.stream().map(LoadItemRequest::userName).toList(),
                loads.stream().map(LoadItemRequest::loadKw).toList(), req.dispatchedAt());
        try {
            return tx.execute(status -> doDispatch(req, vehicleIds, loads, fingerprint));
        } catch (DuplicateKeyException e) {
            return tx.execute(status -> replay(req.requestId(), fingerprint, GeneratorDispatchResponse.class)
                    .orElseThrow(() -> e));
        }
    }

    private GeneratorDispatchResponse doDispatch(GeneratorDispatchRequest req, List<Long> vehicleIds,
            List<LoadItemRequest> loads, String fingerprint) {
        Optional<GeneratorDispatchResponse> replay = replay(req.requestId(), fingerprint,
                GeneratorDispatchResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        WorkOrderRow order = lockWorkOrder(req.workOrderId());
        if (!isUnrecovered(order.status())) {
            throw new UnprocessableException("工单已终结，不可发电车调度: " + order.status());
        }
        if (req.expectedVersion() != versionOf(order)) {
            throw new ConflictException("工单版本不匹配: expected=" + req.expectedVersion()
                    + " actual=" + versionOf(order));
        }
        Integer openChain = jdbc.queryForObject(
                "SELECT COUNT(*) FROM generator_dispatch_order WHERE work_order_id = ?"
                + " AND status IN ('DISPATCHED','PARTIAL_ARRIVAL','ARRIVAL_TIMEOUT','POWER_SUPPLIED')",
                Integer.class, order.id());
        if (openChain != null && openChain > 0) {
            throw new UnprocessableException("该工单已存在未结束发电车调度，请走整体改派或等待终结");
        }
        int totalLoad = validateLoads(order.feederId(), loads);
        Map<Long, VehicleRow> vehicles = lockAndValidateVehicles(vehicleIds, order.feederId());
        int totalCapacity = vehicles.values().stream().mapToInt(VehicleRow::capacityKw).sum();
        if (totalCapacity < totalLoad) {
            throw new UnprocessableException("组合总容量" + totalCapacity + "kW不能覆盖合计负荷"
                    + totalLoad + "kW");
        }
        long orderId = insertOrderRow(req.workOrderId(), order.status(), totalLoad, totalCapacity,
                "DISPATCHED", 1, req.dispatchedAt(), req.requestId(), fingerprint, null);
        int seq = 0;
        for (LoadItemRequest item : loads) {
            jdbc.update("INSERT INTO generator_load_item (order_id, seq, user_name, feeder_id, load_kw)"
                    + " VALUES (?,?,?,?,?)", orderId, seq++, item.userName(), order.feederId(), item.loadKw());
        }
        for (long vehicleId : vehicleIds) {
            jdbc.update("INSERT INTO generator_dispatch_vehicle (order_id, vehicle_id, carried_over, arrived_at)"
                    + " VALUES (?,?,?,NULL)", orderId, vehicleId, false);
            jdbc.update("INSERT INTO generator_occupation (vehicle_id, order_id, carried_over, occupied_at)"
                    + " VALUES (?,?,?,?)", vehicleId, orderId, false, Timestamp.valueOf(req.dispatchedAt()));
        }
        addOrderEvent(orderId, "DISPATCHED", null,
                "下发组合发电车" + vehicleIds + ", 总容量" + totalCapacity + "kW/合计负荷" + totalLoad + "kW",
                req.dispatchedAt());
        bumpWorkOrderVersion(order.id());
        GeneratorDispatchResponse resp = new GeneratorDispatchResponse(orderId, order.id(), 1, "DISPATCHED",
                totalLoad, totalCapacity, List.copyOf(vehicleIds), req.dispatchedAt(),
                req.dispatchedAt().plusMinutes(ARRIVAL_LIMIT_MINUTES), false);
        saveIdem(req.requestId(), fingerprint, "DISPATCH", resp);
        return resp;
    }

    // ==================== 逐车到场与全到场供电 ====================

    /**
     * 逐车到场唯一确认；30 分钟恰满未全到场先转超时，本次及之后到场只记 LATE_ARRIVAL 事件、
     * 不回写不到场、不再次占车；全部车到场才置 POWER_SUPPLIED 并保留供电证据。
     */
    public VehicleArrivalResponse arrive(VehicleArrivalRequest req) {
        if (req.arrivedAt() == null) {
            throw new IllegalArgumentException("arrivedAt 精确UTC时刻不能为空");
        }
        if (req.expectedVersion() == null || req.expectedVersion() <= 0) {
            throw new IllegalArgumentException("expectedVersion 必须为正整数，从1开始");
        }
        String fingerprint = Fingerprints.structured("ARRIVAL", req.requestId(), req.orderId(),
                req.expectedVersion(), req.vehicleId(), req.arrivedAt());
        try {
            return tx.execute(status -> doArrive(req, fingerprint));
        } catch (DuplicateKeyException e) {
            return tx.execute(status -> replay(req.requestId(), fingerprint, VehicleArrivalResponse.class)
                    .orElseThrow(() -> e));
        }
    }

    private VehicleArrivalResponse doArrive(VehicleArrivalRequest req, String fingerprint) {
        Optional<VehicleArrivalResponse> replay = replay(req.requestId(), fingerprint,
                VehicleArrivalResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        // 统一加锁顺序：先工单后调度单，保证到场/改派/复电撤销并发串行、结果唯一
        OrderRow preview = loadOrder(req.orderId());
        lockWorkOrder(preview.workOrderId);
        OrderRow order = lockOrder(req.orderId());
        boolean timedOutNow = applyTimeout(order, req.arrivedAt());
        if (timedOutNow || "ARRIVAL_TIMEOUT".equals(order.status)
                || "REDISPATCHED".equals(order.status) || "CANCELLED".equals(order.status)) {
            // 超时恰满当次、超时后或旧单终止后的迟到到场：只记事件，不占车、不回写到场
            addOrderEvent(order.id, "LATE_ARRIVAL", req.vehicleId(),
                    "迟到/超时到场仅记录事件, 不再次占用发电车", req.arrivedAt());
            int total = countVehicles(order.id);
            VehicleArrivalResponse late = new VehicleArrivalResponse(order.id, req.vehicleId(),
                    order.version, order.status, countArrived(order.id), total, false, null, true, false);
            saveIdem(req.requestId(), fingerprint, "ARRIVAL", late);
            return late;
        }
        if (!ACTIVE_STATUSES.contains(order.status)) {
            throw new UnprocessableException("调度单状态不可到场确认: " + order.status);
        }
        if (req.expectedVersion() != order.version) {
            throw new ConflictException("调度单版本不匹配: expected=" + req.expectedVersion()
                    + " actual=" + order.version);
        }
        LinkRow link = findLink(order.id, req.vehicleId())
                .orElseThrow(() -> new UnprocessableException(
                        "发电车不属于本调度单: orderId=" + order.id + " vehicleId=" + req.vehicleId()));
        if (link.arrivedAt() != null) {
            throw new UnprocessableException("发电车已到场，到场记录唯一: vehicleId=" + req.vehicleId());
        }
        if (req.arrivedAt().isBefore(order.dispatchedAt)) {
            throw new UnprocessableException("到场时刻早于下发时刻");
        }
        jdbc.update("UPDATE generator_dispatch_vehicle SET arrived_at = ? WHERE order_id = ? AND vehicle_id = ?",
                Timestamp.valueOf(req.arrivedAt()), order.id, req.vehicleId());
        addOrderEvent(order.id, "ARRIVED", req.vehicleId(), "发电车到场", req.arrivedAt());
        int arrived = countArrived(order.id);
        int total = countVehicles(order.id);
        boolean all = arrived == total;
        String newStatus;
        LocalDateTime suppliedAt = null;
        if (all) {
            newStatus = "POWER_SUPPLIED";
            suppliedAt = req.arrivedAt();
            jdbc.update("UPDATE generator_dispatch_order SET status = 'POWER_SUPPLIED',"
                    + " all_arrived_at = ?, supplied_at = ?, version = version + 1 WHERE id = ?",
                    Timestamp.valueOf(suppliedAt), Timestamp.valueOf(suppliedAt), order.id);
            jdbc.update("INSERT INTO generator_supply_record (order_id, total_load_kw, total_capacity_kw,"
                    + " supplied_at) VALUES (?,?,?,?)",
                    order.id, order.totalLoadKw, order.totalCapacityKw, Timestamp.valueOf(suppliedAt));
            addOrderEvent(order.id, "ALL_ARRIVED_SUPPLIED", null,
                    "全部" + total + "车到场确认, 开始临时供电(合计负荷" + order.totalLoadKw + "kW)",
                    suppliedAt);
        } else {
            newStatus = "PARTIAL_ARRIVAL";
            jdbc.update("UPDATE generator_dispatch_order SET status = 'PARTIAL_ARRIVAL', version = version + 1"
                    + " WHERE id = ?", order.id);
        }
        int newVersion = order.version + 1;
        VehicleArrivalResponse resp = new VehicleArrivalResponse(order.id, req.vehicleId(), newVersion,
                newStatus, arrived, total, all, suppliedAt, false, false);
        saveIdem(req.requestId(), fingerprint, "ARRIVAL", resp);
        return resp;
    }

    // ==================== 到达超时整体改派 ====================

    /**
     * 到达超时整体改派：旧单已到场车保留到新方案并结转占用，其余车释放；新车原子占用，
     * 新组合容量仍须覆盖冻结负荷；全方案同事务切换。
     */
    public GeneratorReassignResponse reassign(GeneratorReassignRequest req) {
        List<Long> newVehicleIds = distinctOrdered(req.vehicleIds(), "换入发电车");
        if (req.expectedVersion() == null || req.expectedVersion() <= 0) {
            throw new IllegalArgumentException("expectedVersion 必须为正整数，从1开始");
        }
        if (req.reassignedAt() == null) {
            throw new IllegalArgumentException("reassignedAt 精确UTC时刻不能为空");
        }
        String fingerprint = Fingerprints.structured("REASSIGN", req.requestId(), req.orderId(),
                req.expectedVersion(), newVehicleIds, req.reassignedAt());
        try {
            return tx.execute(status -> doReassign(req, newVehicleIds, fingerprint));
        } catch (DuplicateKeyException e) {
            return tx.execute(status -> replay(req.requestId(), fingerprint, GeneratorReassignResponse.class)
                    .orElseThrow(() -> e));
        }
    }

    private GeneratorReassignResponse doReassign(GeneratorReassignRequest req, List<Long> newVehicleIds,
            String fingerprint) {
        Optional<GeneratorReassignResponse> replay = replay(req.requestId(), fingerprint,
                GeneratorReassignResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        OrderRow preview = loadOrder(req.orderId());
        // 统一加锁顺序：先工单后调度单，再锁车辆，保证改派/到场/复电撤销并发串行
        WorkOrderRow workOrder = lockWorkOrder(preview.workOrderId);
        OrderRow old = lockOrder(req.orderId());
        applyTimeout(old, req.reassignedAt());
        if (!"ARRIVAL_TIMEOUT".equals(old.status)) {
            throw new UnprocessableException("仅到达超时调度单可整体改派: " + old.status);
        }
        if (req.expectedVersion() != old.version) {
            throw new ConflictException("调度单版本不匹配: expected=" + req.expectedVersion()
                    + " actual=" + old.version);
        }
        List<LinkRow> links = listLinks(old.id);
        List<Long> carried = links.stream().filter(l -> l.arrivedAt() != null)
                .map(LinkRow::vehicleId).toList();
        List<Long> released = links.stream().filter(l -> l.arrivedAt() == null)
                .map(LinkRow::vehicleId).toList();
        for (long oldId : links.stream().map(LinkRow::vehicleId).toList()) {
            if (newVehicleIds.contains(oldId)) {
                throw new UnprocessableException("换入车辆不得为旧方案车辆: vehicleId=" + oldId);
            }
        }
        if (!isUnrecovered(workOrder.status())) {
            throw new UnprocessableException("工单已终结，不可改派: " + workOrder.status());
        }
        // 新车锁行并校验空闲；结转车按旧单在场车保留
        Map<Long, VehicleRow> newVehicles = lockAndValidateVehicles(newVehicleIds, workOrder.feederId());
        Map<Long, VehicleRow> carriedVehicles = new LinkedHashMap<>();
        for (long vehicleId : carried) {
            carriedVehicles.put(vehicleId, requireVehicle(vehicleId));
        }
        int newCapacity = newVehicles.values().stream().mapToInt(VehicleRow::capacityKw).sum()
                + carriedVehicles.values().stream().mapToInt(VehicleRow::capacityKw).sum();
        if (newCapacity < old.totalLoadKw) {
            throw new UnprocessableException("改派后总容量" + newCapacity + "kW不能覆盖合计负荷"
                    + old.totalLoadKw + "kW");
        }

        String newStatus = carried.isEmpty() ? "DISPATCHED" : "PARTIAL_ARRIVAL";
        long newId = insertOrderRow(old.workOrderId, workOrder.status(), old.totalLoadKw, newCapacity,
                newStatus, 1, req.reassignedAt(), req.requestId(), fingerprint, null);
        // 冻结相同的重要用户负荷清单
        List<int[]> itemRows = jdbc.query(
                "SELECT seq, load_kw FROM generator_load_item WHERE order_id = ? ORDER BY seq ASC",
                (rs, rowNum) -> new int[] {rs.getInt(1), rs.getInt(2)}, old.id);
        List<String[]> itemUsers = jdbc.query(
                "SELECT user_name, feeder_id FROM generator_load_item WHERE order_id = ? ORDER BY seq ASC",
                (rs, rowNum) -> new String[] {rs.getString(1), rs.getString(2)}, old.id);
        for (int i = 0; i < itemRows.size(); i++) {
            jdbc.update("INSERT INTO generator_load_item (order_id, seq, user_name, feeder_id, load_kw)"
                    + " VALUES (?,?,?,?,?)", newId, itemRows.get(i)[0], itemUsers.get(i)[0],
                    itemUsers.get(i)[1], itemRows.get(i)[1]);
        }
        // 旧单：关闭全部车占用；仅已到场车保留进新方案，未到场车释放、不进入新单
        for (LinkRow link : links) {
            boolean kept = link.arrivedAt() != null;
            closeOccupation(link.vehicleId(), old.id,
                    kept ? "REDISPATCH_CARRIED" : "REDISPATCH_RELEASED", req.reassignedAt());
            if (kept) {
                jdbc.update("INSERT INTO generator_dispatch_vehicle (order_id, vehicle_id, carried_over, arrived_at)"
                        + " VALUES (?,?,?,?)", newId, link.vehicleId(), true,
                        Timestamp.valueOf(link.arrivedAt()));
                jdbc.update("INSERT INTO generator_occupation (vehicle_id, order_id, carried_over, occupied_at)"
                        + " VALUES (?,?,?,?)", link.vehicleId(), newId, true,
                        Timestamp.valueOf(req.reassignedAt()));
            }
        }
        // 新车进入新方案并占用
        for (long vehicleId : newVehicleIds) {
            jdbc.update("INSERT INTO generator_dispatch_vehicle (order_id, vehicle_id, carried_over, arrived_at)"
                    + " VALUES (?,?,?,NULL)", newId, vehicleId, false);
            jdbc.update("INSERT INTO generator_occupation (vehicle_id, order_id, carried_over, occupied_at)"
                    + " VALUES (?,?,?,?)", vehicleId, newId, false, Timestamp.valueOf(req.reassignedAt()));
        }
        jdbc.update("UPDATE generator_dispatch_order SET status = 'REDISPATCHED', replaced_by_id = ?,"
                + " version = version + 1 WHERE id = ?", newId, old.id);
        addOrderEvent(old.id, "REDISPATCHED", null,
                "整体改派至新调度单" + newId + ", 保留车" + carried + ", 释放车" + released,
                req.reassignedAt());
        addOrderEvent(newId, "DISPATCHED", null,
                "由超时旧单" + old.id + "改派生成, 保留到场车" + carried + ", 换入新车" + newVehicleIds
                        + ", 总容量" + newCapacity + "kW",
                req.reassignedAt());
        bumpWorkOrderVersion(workOrder.id());
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM generator_dispatch_order WHERE work_order_id = ? AND status = 'REDISPATCHED'",
                Integer.class, old.workOrderId);
        int reassignCount = count == null ? 0 : count;
        GeneratorReassignResponse resp = new GeneratorReassignResponse(newId, old.id, 1, newStatus,
                List.copyOf(carried), List.copyOf(newVehicleIds), reassignCount, req.reassignedAt(), false);
        saveIdem(req.requestId(), fingerprint, "REASSIGN", resp);
        return resp;
    }

    // ==================== 合并 / 正式复电原子撤销 ====================

    /**
     * 工单合并或正式复电钩子：原子撤销该工单全部未结束调度并释放车辆，
     * 已生成的临时供电证据（generator_supply_record）保留。
     *
     * @return 撤销的调度单数量
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public int cancelOpenOrdersForWorkOrder(long workOrderId, String reason) {
        // 先锁工单行，与到场/改派统一加锁顺序，保证并发串行、切换唯一
        List<Long> lockedOrder = jdbc.query("SELECT id FROM work_order WHERE id = ? FOR UPDATE",
                (rs, rowNum) -> rs.getLong(1), workOrderId);
        if (lockedOrder.isEmpty()) {
            throw new NotFoundException("工单不存在: " + workOrderId);
        }
        // POWER_SUPPLIED 仅为临时保电，正式复电/合并时同样撤销并释放车辆，供电证据另行保留
        List<Long> ids = jdbc.query(
                "SELECT id FROM generator_dispatch_order WHERE work_order_id = ?"
                + " AND status IN ('DISPATCHED','PARTIAL_ARRIVAL','ARRIVAL_TIMEOUT','POWER_SUPPLIED')"
                + " ORDER BY id ASC FOR UPDATE",
                (rs, rowNum) -> rs.getLong(1), workOrderId);
        LocalDateTime now = clock.nowUtc();
        for (long orderId : ids) {
            List<Long> vehicleIds = jdbc.query(
                    "SELECT vehicle_id FROM generator_dispatch_vehicle WHERE order_id = ?",
                    (rs, rowNum) -> rs.getLong(1), orderId);
            for (long vehicleId : vehicleIds) {
                closeOccupation(vehicleId, orderId, reason, now);
            }
            jdbc.update("UPDATE generator_dispatch_order SET status = 'CANCELLED', cancel_reason = ?,"
                    + " version = version + 1 WHERE id = ?", reason, orderId);
            addOrderEvent(orderId, "CANCELLED", null,
                    "MERGED".equals(reason) ? "工单合并，原子撤销临时保电调度并释放车辆"
                            : "正式复电，原子撤销临时保电调度并释放车辆", now);
            store.addEvent(workOrderId, "GENERATOR_DISPATCH_CANCELLED",
                    "发电车调度单" + orderId + "因" + reason + "撤销, 车辆释放, 供电证据保留");
        }
        return ids.size();
    }

    // ==================== 查询：详情复算与占用台账 ====================

    /**
     * 调度单详情：按当前 UTC 时刻复算 30 分钟到达超时，返回组合车辆到场与事件轨迹。
     */
    @Transactional
    public GeneratorOrderView viewOrder(long orderId) {
        OrderRow order = lockOrder(orderId);
        applyTimeout(order, clock.nowUtc());
        OrderRow current = loadOrder(orderId);
        List<OrderVehicleView> vehicles = new ArrayList<>();
        for (LinkRow link : listLinks(orderId)) {
            VehicleRow v = requireVehicle(link.vehicleId());
            vehicles.add(new OrderVehicleView(v.id(), v.code(), link.carriedOver(), link.arrivedAt()));
        }
        List<String> events = jdbc.query(
                "SELECT event_type, detail FROM generator_order_event WHERE order_id = ?"
                + " ORDER BY created_at ASC, id ASC",
                (rs, rowNum) -> {
                    String detail = rs.getString("detail");
                    return rs.getString("event_type") + (detail == null ? "" : ":" + detail);
                }, orderId);
        return new GeneratorOrderView(current.id, current.workOrderId, current.status, current.version,
                current.totalLoadKw, current.totalCapacityKw, current.dispatchedAt, current.deadlineAt,
                current.allArrivedAt, current.suppliedAt, current.replacedById, current.cancelReason,
                vehicles, events);
    }

    /**
     * 发电车占用台账：全部占用历史（含已释放），releasedAt 为空表示占用中；可按车过滤。
     */
    @Transactional(readOnly = true)
    public List<OccupationEntry> occupationLedger(Long vehicleId) {
        String sql = "SELECT o.id, o.vehicle_id, v.code, o.order_id, o.carried_over, o.occupied_at,"
                + " o.released_at, o.release_reason FROM generator_occupation o"
                + " JOIN generator_vehicle v ON v.id = o.vehicle_id"
                + (vehicleId == null ? "" : " WHERE o.vehicle_id = ?")
                + " ORDER BY o.id ASC";
        var mapper = (org.springframework.jdbc.core.RowMapper<OccupationEntry>) (rs, rowNum) -> {
            Timestamp released = rs.getTimestamp("released_at");
            return new OccupationEntry(rs.getLong("id"), rs.getLong("vehicle_id"), rs.getString("code"),
                    rs.getLong("order_id"), rs.getBoolean("carried_over"),
                    rs.getTimestamp("occupied_at").toLocalDateTime(),
                    released == null ? null : released.toLocalDateTime(),
                    rs.getString("release_reason"));
        };
        return vehicleId == null ? jdbc.query(sql, mapper) : jdbc.query(sql, mapper, vehicleId);
    }

    // ==================== 内部辅助 ====================

    /**
     * 若调度单在给定 UTC 时刻已到/过截止时刻且尚未全到场，则原子转为 ARRIVAL_TIMEOUT（恰满触发）。
     *
     * @return true 表示本次调用发生了超时状态转换
     */
    private boolean applyTimeout(OrderRow order, LocalDateTime atUtc) {
        if (!ACTIVE_STATUSES.contains(order.status)) {
            return false;
        }
        if (atUtc.isBefore(order.deadlineAt)) {
            return false;
        }
        // 恰满 30 分钟：只要尚未全到场即超时；本次拟到场车辆按迟到处理
        jdbc.update("UPDATE generator_dispatch_order SET status = 'ARRIVAL_TIMEOUT', version = version + 1"
                + " WHERE id = ? AND status IN ('DISPATCHED','PARTIAL_ARRIVAL')", order.id);
        addOrderEvent(order.id, "ARRIVAL_TIMEOUT", null,
                "UTC下发后满30分钟未全部到场，转到达超时，可整体改派", atUtc);
        order.status = "ARRIVAL_TIMEOUT";
        order.version = order.version + 1;
        return true;
    }

    private int validateLoads(String feederId, List<LoadItemRequest> loads) {
        int total = 0;
        for (LoadItemRequest item : loads) {
            if (item.loadKw() == null || item.loadKw() <= 0) {
                throw new IllegalArgumentException("保安负荷必须为正整数kW: " + item.userName());
            }
            List<String> feeders = jdbc.query(
                    "SELECT feeder_id FROM important_user WHERE user_name = ?",
                    (rs, rowNum) -> rs.getString(1), item.userName());
            if (feeders.isEmpty()) {
                throw new NotFoundException("重要用户不存在: " + item.userName());
            }
            if (!feederId.equals(feeders.get(0))) {
                throw new UnprocessableException("重要用户不属于工单馈线" + feederId + ": " + item.userName());
            }
            total += item.loadKw();
        }
        return total;
    }

    /**
     * 按ID升序锁定发电车行（避免交叉死锁），校验存在性、馈线、可用与空闲；返回按请求顺序的车辆。
     */
    private Map<Long, VehicleRow> lockAndValidateVehicles(List<Long> vehicleIds, String feederId) {
        // 统一按 ID 升序锁定行，避免并发组合交叉申请时死锁；再按请求顺序返回
        List<VehicleRow> locked = jdbc.query(
                "SELECT id, code, capacity_kw, feeder_id, available FROM generator_vehicle"
                + " WHERE id IN (" + placeholders(vehicleIds.size()) + ") ORDER BY id ASC FOR UPDATE",
                (rs, rowNum) -> new VehicleRow(rs.getLong(1), rs.getString(2), rs.getInt(3),
                        rs.getString(4), rs.getBoolean(5)),
                vehicleIds.toArray());
        Map<Long, VehicleRow> byId = new LinkedHashMap<>();
        Map<Long, VehicleRow> result = new LinkedHashMap<>();
        for (VehicleRow v : locked) {
            byId.put(v.id(), v);
        }
        for (long id : vehicleIds) {
            VehicleRow v = byId.get(id);
            if (v == null) {
                throw new NotFoundException("发电车不存在: " + id);
            }
            if (!feederId.equals(v.feederId())) {
                throw new UnprocessableException("发电车" + v.code() + "服务馈线" + v.feederId()
                        + "与工单馈线" + feederId + "不符");
            }
            if (!v.available()) {
                throw new UnprocessableException("发电车不可用: " + v.code());
            }
            Integer open = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM generator_occupation WHERE vehicle_id = ? AND released_at IS NULL",
                    Integer.class, id);
            if (open != null && open > 0) {
                throw new UnprocessableException("发电车已被未结束调度占用: " + v.code());
            }
            result.put(id, v);
        }
        return result;
    }

    private VehicleRow requireVehicle(long vehicleId) {
        List<VehicleRow> rows = jdbc.query(
                "SELECT id, code, capacity_kw, feeder_id, available FROM generator_vehicle WHERE id = ?",
                (rs, rowNum) -> new VehicleRow(rs.getLong(1), rs.getString(2), rs.getInt(3),
                        rs.getString(4), rs.getBoolean(5)), vehicleId);
        if (rows.isEmpty()) {
            throw new NotFoundException("发电车不存在: " + vehicleId);
        }
        return rows.get(0);
    }

    private VehicleView loadVehicleByCode(String code) {
        List<VehicleView> rows = jdbc.query(
                "SELECT id, code, capacity_kw, feeder_id, available FROM generator_vehicle WHERE code = ?",
                (rs, rowNum) -> new VehicleView(rs.getLong(1), rs.getString(2), rs.getInt(3),
                        rs.getString(4), rs.getBoolean(5), false, null), code);
        if (rows.isEmpty()) {
            throw new NotFoundException("发电车不存在: " + code);
        }
        return rows.get(0);
    }

    private long insertOrderRow(long workOrderId, String frozenStatus, int totalLoad, int totalCapacity,
            String status, int version, LocalDateTime dispatchedAt, String requestId, String fingerprint,
            Long replacedById) {
        jdbc.update("INSERT INTO generator_dispatch_order (request_id, fingerprint, work_order_id,"
                + " frozen_order_status, total_load_kw, total_capacity_kw, status, version, dispatched_at,"
                + " deadline_at, replaced_by_id, created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                requestId, fingerprint, workOrderId, frozenStatus, totalLoad, totalCapacity, status, version,
                Timestamp.valueOf(dispatchedAt),
                Timestamp.valueOf(dispatchedAt.plusMinutes(ARRIVAL_LIMIT_MINUTES)),
                replacedById, Timestamp.valueOf(clock.nowUtc()));
        Long id = jdbc.queryForObject("SELECT id FROM generator_dispatch_order WHERE request_id = ?",
                Long.class, requestId);
        return id == null ? 0L : id;
    }

    private void addOrderEvent(long orderId, String type, Long vehicleId, String detail, LocalDateTime at) {
        jdbc.update("INSERT INTO generator_order_event (order_id, event_type, vehicle_id, detail, created_at)"
                + " VALUES (?,?,?,?,?)", orderId, type, vehicleId, detail, Timestamp.valueOf(at));
    }

    private void closeOccupation(long vehicleId, long orderId, String reason, LocalDateTime at) {
        int updated = jdbc.update("UPDATE generator_occupation SET released_at = ?, release_reason = ?"
                + " WHERE vehicle_id = ? AND order_id = ? AND released_at IS NULL",
                Timestamp.valueOf(at), reason, vehicleId, orderId);
        if (updated == 0) {
            throw new IllegalStateException("占用记录不存在或已释放: vehicle=" + vehicleId + " order=" + orderId);
        }
    }

    private WorkOrderRow lockWorkOrder(long workOrderId) {
        List<WorkOrderRow> rows = jdbc.query(
                "SELECT * FROM work_order WHERE id = ? FOR UPDATE",
                (rs, rowNum) -> WorkOrderStore.mapRowPublic(rs), workOrderId);
        if (rows.isEmpty()) {
            throw new NotFoundException("工单不存在: " + workOrderId);
        }
        return rows.get(0);
    }

    private int versionOf(WorkOrderRow order) {
        Integer v = jdbc.queryForObject("SELECT version FROM work_order WHERE id = ?",
                Integer.class, order.id());
        return v == null ? 1 : v;
    }

    private void bumpWorkOrderVersion(long workOrderId) {
        jdbc.update("UPDATE work_order SET version = version + 1 WHERE id = ?", workOrderId);
    }

    private OrderRow lockOrder(long orderId) {
        List<OrderRow> rows = jdbc.query(
                "SELECT * FROM generator_dispatch_order WHERE id = ? FOR UPDATE",
                (rs, rowNum) -> mapOrder(rs), orderId);
        if (rows.isEmpty()) {
            throw new NotFoundException("发电车调度单不存在: " + orderId);
        }
        return rows.get(0);
    }

    private OrderRow loadOrder(long orderId) {
        List<OrderRow> rows = jdbc.query("SELECT * FROM generator_dispatch_order WHERE id = ?",
                (rs, rowNum) -> mapOrder(rs), orderId);
        if (rows.isEmpty()) {
            throw new NotFoundException("发电车调度单不存在: " + orderId);
        }
        return rows.get(0);
    }

    private static OrderRow mapOrder(java.sql.ResultSet rs) throws java.sql.SQLException {
        long replaced = rs.getLong("replaced_by_id");
        Long replacedById = rs.wasNull() ? null : replaced;
        Timestamp allArrived = rs.getTimestamp("all_arrived_at");
        Timestamp supplied = rs.getTimestamp("supplied_at");
        return new OrderRow(rs.getLong("id"), rs.getLong("work_order_id"), rs.getString("status"),
                rs.getInt("version"), rs.getInt("total_load_kw"), rs.getInt("total_capacity_kw"),
                rs.getTimestamp("dispatched_at").toLocalDateTime(),
                rs.getTimestamp("deadline_at").toLocalDateTime(),
                allArrived == null ? null : allArrived.toLocalDateTime(),
                supplied == null ? null : supplied.toLocalDateTime(),
                replacedById, rs.getString("cancel_reason"));
    }

    private Optional<LinkRow> findLink(long orderId, long vehicleId) {
        return listLinks(orderId).stream().filter(l -> l.vehicleId() == vehicleId).findFirst();
    }

    private List<LinkRow> listLinks(long orderId) {
        return jdbc.query(
                "SELECT vehicle_id, carried_over, arrived_at FROM generator_dispatch_vehicle"
                + " WHERE order_id = ? ORDER BY id ASC",
                (rs, rowNum) -> {
                    Timestamp arrived = rs.getTimestamp("arrived_at");
                    return new LinkRow(rs.getLong(1), rs.getBoolean(2),
                            arrived == null ? null : arrived.toLocalDateTime());
                }, orderId);
    }

    private int countVehicles(long orderId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM generator_dispatch_vehicle WHERE order_id = ?",
                Integer.class, orderId);
        return n == null ? 0 : n;
    }

    private int countArrived(long orderId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM generator_dispatch_vehicle WHERE order_id = ? AND arrived_at IS NOT NULL",
                Integer.class, orderId);
        return n == null ? 0 : n;
    }

    private boolean isUnrecovered(String status) {
        return "PENDING".equals(status) || "QUEUED".equals(status) || "EXECUTING".equals(status);
    }

    private List<Long> distinctOrdered(List<Long> source, String label) {
        if (source == null || source.isEmpty()) {
            throw new IllegalArgumentException(label + "集合不能为空");
        }
        if (source.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException(label + "集合不能含空元素");
        }
        List<Long> ordered = new ArrayList<>();
        for (Long id : source) {
            if (!ordered.contains(id)) {
                ordered.add(id);
            }
        }
        if (ordered.size() != source.size()) {
            throw new IllegalArgumentException(label + "集合不能重复");
        }
        return ordered;
    }

    private List<LoadItemRequest> distinctLoads(List<LoadItemRequest> source) {
        if (source == null || source.isEmpty()) {
            throw new IllegalArgumentException("重要用户负荷清单不能为空");
        }
        List<String> names = new ArrayList<>();
        for (LoadItemRequest item : source) {
            if (item == null || item.userName() == null || item.userName().isBlank()) {
                throw new IllegalArgumentException("负荷清单项用户名不能为空");
            }
            if (names.contains(item.userName())) {
                throw new IllegalArgumentException("负荷清单存在重复重要用户: " + item.userName());
            }
            names.add(item.userName());
        }
        return source;
    }

    private static String placeholders(int n) {
        return String.join(",", java.util.Collections.nCopies(n, "?"));
    }

    private void saveIdem(String requestId, String fingerprint, String operation, Object snapshot) {
        try {
            jdbc.update("INSERT INTO generator_idem_record (request_id, fingerprint, operation,"
                    + " response_snapshot, created_at) VALUES (?,?,?,?,?)",
                    requestId, fingerprint, operation, JSON.writeValueAsString(snapshot),
                    Timestamp.valueOf(clock.nowUtc()));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("幂等快照序列化失败", e);
        }
    }

    private <T> Optional<T> replay(String requestId, String fingerprint, Class<T> type) {
        List<Object[]> rows = jdbc.query(
                "SELECT fingerprint, response_snapshot FROM generator_idem_record WHERE request_id = ?",
                (rs, rowNum) -> new Object[] {rs.getString(1), rs.getString(2)}, requestId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Object[] row = rows.get(0);
        if (!row[0].equals(fingerprint)) {
            throw new ConflictException("同键异参幂等冲突: requestId=" + requestId);
        }
        try {
            var node = JSON.readTree((String) row[1]);
            if (node.has("replayed")) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) node).put("replayed", true);
            }
            return Optional.of(JSON.treeToValue(node, type));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("幂等快照反序列化失败", e);
        }
    }

    /** 发电车行。 */
    private record VehicleRow(long id, String code, int capacityKw, String feederId, boolean available) {
    }

    /** 调度单行（可变状态字段供事务内超时复算复用）。 */
    private static final class OrderRow {
        private final long id;
        private final long workOrderId;
        private String status;
        private int version;
        private final int totalLoadKw;
        private final int totalCapacityKw;
        private final LocalDateTime dispatchedAt;
        private final LocalDateTime deadlineAt;
        private final LocalDateTime allArrivedAt;
        private final LocalDateTime suppliedAt;
        private final Long replacedById;
        private final String cancelReason;

        private OrderRow(long id, long workOrderId, String status, int version, int totalLoadKw,
                int totalCapacityKw, LocalDateTime dispatchedAt, LocalDateTime deadlineAt,
                LocalDateTime allArrivedAt, LocalDateTime suppliedAt, Long replacedById,
                String cancelReason) {
            this.id = id;
            this.workOrderId = workOrderId;
            this.status = status;
            this.version = version;
            this.totalLoadKw = totalLoadKw;
            this.totalCapacityKw = totalCapacityKw;
            this.dispatchedAt = dispatchedAt;
            this.deadlineAt = deadlineAt;
            this.allArrivedAt = allArrivedAt;
            this.suppliedAt = suppliedAt;
            this.replacedById = replacedById;
            this.cancelReason = cancelReason;
        }
    }

    /** 调度单-车辆关联行。 */
    private record LinkRow(long vehicleId, boolean carriedOver, LocalDateTime arrivedAt) {
    }
}
