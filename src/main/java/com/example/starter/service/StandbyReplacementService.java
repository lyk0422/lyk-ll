package com.example.starter.service;

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
import com.example.starter.web.dto.CreateReplacementRequest;
import com.example.starter.web.dto.ReplacementArrivalRequest;
import com.example.starter.web.dto.ReplacementArrivalResponse;
import com.example.starter.web.dto.ReplacementRevokeRequest;
import com.example.starter.web.dto.ReplacementRevokeResponse;
import com.example.starter.web.dto.ReplacementSwitchEvidence;
import com.example.starter.web.dto.ReplacementSwitchRequest;
import com.example.starter.web.dto.ReplacementSwitchResponse;
import com.example.starter.web.dto.ReplacementTruckView;
import com.example.starter.web.dto.ReplacementView;
import com.example.starter.web.dto.TruckView;

/**
 * 热备接替服务：仅 SUPPLYING 调度单可申请，引用一辆正在供电旧车与1至3辆未占用新车；
 * 新车同馈线且容量之和不少于旧车容量（恰等合法），创建即全量预占新车（STANDBY），旧车继续供电，
 * 任一新车冲突全单拒绝；每辆旧车最多一个未结束接替，负荷与原调度版本冻结。
 * 新车逐辆登记到场，到齐且两个不同合成操作员确认后原子替换供电组合、转移车辆占用并留前后容量证据；
 * 未切换可撤回，释放全部新车而保留旧车。复电或合并时与原取消规则同事务关闭未结束接替并释放预占车；
 * 行锁顺序统一为“调度单→接替单→车辆”，切换与复电竞争不留孤立占用、复电后不可再切入供电。
 */
@Service
public class StandbyReplacementService {

    /** 接替单行。 */
    record ReplacementRow(long id, long dispatchId, long workOrderId, long oldTruckId, String feederId,
            int frozenOrderVersion, int frozenLoadKw, int oldCapacityKw, int newCapacityKw,
            String status, int version, LocalDateTime createdAt) {
    }

    /** 调度单行（热备域所需字段）。 */
    record SupplyDispatch(long id, long workOrderId, int orderVersion, int totalLoadKw, String status,
            int version) {
    }

    private static final RowMapper<ReplacementRow> REPLACEMENT_MAPPER = (rs, rowNum) -> new ReplacementRow(
            rs.getLong("id"), rs.getLong("dispatch_id"), rs.getLong("work_order_id"),
            rs.getLong("old_truck_id"), rs.getString("feeder_id"), rs.getInt("frozen_order_version"),
            rs.getInt("frozen_load_kw"), rs.getInt("old_capacity_kw"), rs.getInt("new_capacity_kw"),
            rs.getString("status"), rs.getInt("version"),
            rs.getTimestamp("created_at").toLocalDateTime());

    private final JdbcTemplate jdbc;
    private final AppClock clock;
    private final WorkOrderStore store;
    private final GeneratorTruckService trucks;
    private final GenIdempotency idem;
    private final TransactionTemplate tx;

    public StandbyReplacementService(JdbcTemplate jdbc, AppClock clock, WorkOrderStore store,
            GeneratorTruckService trucks, GenIdempotency idem, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.store = store;
        this.trucks = trucks;
        this.idem = idem;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * 申请热备接替：全量预占新车，旧车继续供电；任一新车冲突整单拒绝且不留预占。
     */
    public ReplacementView create(long dispatchId, CreateReplacementRequest req) {
        List<Long> newTruckIds = req.newTruckIds().stream().distinct().sorted().toList();
        if (newTruckIds.size() != req.newTruckIds().size()) {
            throw new IllegalArgumentException("新车列表不可重复");
        }
        if (newTruckIds.contains(req.oldTruckId())) {
            throw new IllegalArgumentException("新车不可与被接替旧车相同");
        }
        String fingerprint = Fingerprints.structured(req.requestId(), dispatchId, req.expectedVersion(),
                req.oldTruckId(), newTruckIds, req.createdAt().toString());
        try {
            return tx.execute(status -> doCreate(dispatchId, req, newTruckIds, fingerprint));
        } catch (DuplicateKeyException e) {
            return tx.execute(status -> idem.findReplay(req.requestId(), fingerprint, ReplacementView.class)
                    .orElseThrow(() -> new BusinessException("新车预占并发冲突, 全单拒绝")));
        }
    }

    private ReplacementView doCreate(long dispatchId, CreateReplacementRequest req, List<Long> newTruckIds,
            String fingerprint) {
        var replay = idem.findReplay(req.requestId(), fingerprint, ReplacementView.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        // 行锁顺序：调度单→旧车→新车（ID升序）
        SupplyDispatch dispatch = lockDispatch(dispatchId);
        var replayAfterLock = idem.findReplay(req.requestId(), fingerprint, ReplacementView.class);
        if (replayAfterLock.isPresent()) {
            return replayAfterLock.get();
        }
        if (!"SUPPLYING".equals(dispatch.status())) {
            throw new BusinessException("仅供电中(SUPPLYING)调度单可申请热备接替: " + dispatch.status());
        }
        if (req.expectedVersion() != dispatch.version()) {
            throw new ConflictException("调度单版本冲突: 期望" + req.expectedVersion() + ", 实际"
                    + dispatch.version());
        }
        TruckView oldTruck = trucks.lockTruck(req.oldTruckId());
        Integer inComposition = jdbc.query(
                "SELECT 1 FROM dispatch_truck WHERE dispatch_id = ? AND truck_id = ? AND arrived_at IS NOT NULL",
                rs -> rs.next() ? 1 : null, dispatchId, req.oldTruckId());
        if (inComposition == null) {
            throw new BusinessException("旧车不在该调度单供电组合内或未到场: " + req.oldTruckId());
        }
        String occupation = jdbc.queryForObject(
                "SELECT occupation_type FROM active_occupation WHERE truck_id = ?",
                String.class, req.oldTruckId());
        if (!"SUPPLY".equals(occupation)) {
            throw new BusinessException("旧车未处于供电占用, 不可被接替: " + req.oldTruckId());
        }
        if (count("standby_replacement", "old_truck_id = ? AND status = 'WAITING'", req.oldTruckId()) > 0) {
            throw new BusinessException("该旧车已有未结束接替, 每辆旧车最多一个: " + req.oldTruckId());
        }
        int newCapacity = 0;
        for (long truckId : newTruckIds) {
            TruckView truck = trucks.lockTruck(truckId);
            if (!truck.available()) {
                throw new BusinessException("热备新车不可用: " + truckId);
            }
            if (!truck.feederId().equals(oldTruck.feederId())) {
                throw new BusinessException("新车须与旧车同馈线: 车辆" + truckId + "馈线" + truck.feederId()
                        + ", 旧车馈线" + oldTruck.feederId());
            }
            if (count("active_occupation", "truck_id = ?", truckId) > 0) {
                throw new BusinessException("新车存在未结束占用(供电或热备预占): " + truckId);
            }
            newCapacity += truck.capacityKw();
        }
        if (newCapacity < oldTruck.capacityKw()) {
            throw new BusinessException("新车容量之和" + newCapacity + "kW少于旧车容量"
                    + oldTruck.capacityKw() + "kW, 全单拒绝");
        }
        LocalDateTime createdAt = toLocal(req.createdAt());
        long replacementId = insertReplacement(dispatch, req.oldTruckId(), oldTruck.feederId(),
                oldTruck.capacityKw(), newCapacity, createdAt);
        for (long truckId : newTruckIds) {
            TruckView truck = trucks.lockTruck(truckId);
            jdbc.update("INSERT INTO replacement_truck (replacement_id, truck_id, capacity_kw, arrived_at)"
                    + " VALUES (?,?,?,NULL)", replacementId, truckId, truck.capacityKw());
            jdbc.update("INSERT INTO active_occupation (truck_id, dispatch_id, replacement_id,"
                    + " occupation_type, occupied_at) VALUES (?,?,?,?,?)",
                    truckId, null, replacementId, "STANDBY", Timestamp.valueOf(clock.now()));
        }
        store.addEvent(dispatch.workOrderId(), "STANDBY_REPLACEMENT_CREATED",
                "接替单" + replacementId + "预占热备车" + newTruckIds + "(合计" + newCapacity
                + "kW)替换旧车" + req.oldTruckId() + "(" + oldTruck.capacityKw() + "kW), 冻结负荷"
                + dispatch.totalLoadKw() + "kW, 旧车继续供电");
        ReplacementView view = viewOf(replacementId);
        idem.record(req.requestId(), fingerprint, "REPLACEMENT_CREATE", replacementId, view);
        return view;
    }

    /**
     * 新车逐辆登记到场，逐车唯一；到齐且双操作员已确认时由本次登记触发原子切换。
     */
    public ReplacementArrivalResponse arrive(long replacementId, ReplacementArrivalRequest req) {
        String fingerprint = Fingerprints.structured(req.requestId(), replacementId, req.expectedVersion(),
                req.truckId(), req.arrivedAt().toString());
        try {
            return tx.execute(status -> doArrive(replacementId, req, fingerprint));
        } catch (DuplicateKeyException e) {
            return tx.execute(status -> idem.findReplay(req.requestId(), fingerprint,
                    ReplacementArrivalResponse.class).orElseThrow(() -> e));
        }
    }

    private ReplacementArrivalResponse doArrive(long replacementId, ReplacementArrivalRequest req,
            String fingerprint) {
        var replay = idem.findReplay(req.requestId(), fingerprint, ReplacementArrivalResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        ReplacementRow row = lockReplacementThroughDispatch(replacementId);
        var replayAfterLock = idem.findReplay(req.requestId(), fingerprint, ReplacementArrivalResponse.class);
        if (replayAfterLock.isPresent()) {
            return replayAfterLock.get();
        }
        if (!"WAITING".equals(row.status())) {
            throw new BusinessException("接替单未处于待切换状态, 不可登记到场: " + row.status());
        }
        if (req.expectedVersion() != row.version()) {
            throw new ConflictException("接替单版本冲突: 期望" + req.expectedVersion() + ", 实际"
                    + row.version());
        }
        Boolean arrived = jdbc.query(
                "SELECT arrived_at IS NOT NULL FROM replacement_truck WHERE replacement_id = ? AND truck_id = ?",
                rs -> rs.next() ? rs.getBoolean(1) : null, replacementId, req.truckId());
        if (arrived == null) {
            throw new BusinessException("车辆不在接替单热备方案内: " + req.truckId());
        }
        if (arrived) {
            throw new BusinessException("热备新车已登记到场, 逐车唯一: " + req.truckId());
        }
        LocalDateTime arrivedAt = toLocal(req.arrivedAt());
        jdbc.update("UPDATE replacement_truck SET arrived_at = ? WHERE replacement_id = ? AND truck_id = ?",
                Timestamp.valueOf(arrivedAt), replacementId, req.truckId());
        int remaining = count("replacement_truck", "replacement_id = ? AND arrived_at IS NULL",
                replacementId);
        int confirmers = count("replacement_confirmation", "replacement_id = ?", replacementId);
        ReplacementArrivalResponse response;
        if (remaining == 0 && confirmers >= 2) {
            switchComposition(row, arrivedAt);
            response = new ReplacementArrivalResponse(replacementId, req.truckId(), "SWITCHED", "SWITCHED",
                    row.version() + 1, arrivedAt);
            idem.record(req.requestId(), fingerprint, "REPLACEMENT_ARRIVAL", replacementId, response);
            return response;
        }
        jdbc.update("UPDATE standby_replacement SET version = version + 1 WHERE id = ?", replacementId);
        String outcome = remaining == 0 ? "ALL_ARRIVED" : "ARRIVED";
        response = new ReplacementArrivalResponse(replacementId, req.truckId(), outcome, "WAITING",
                row.version() + 1, arrivedAt);
        idem.record(req.requestId(), fingerprint, "REPLACEMENT_ARRIVAL", replacementId, response);
        return response;
    }

    /**
     * 操作员确认切换：两个不同人员齐备且新车到齐才原子切换；缺车或单人不释放旧车。
     */
    public ReplacementSwitchResponse confirmSwitch(long replacementId, ReplacementSwitchRequest req) {
        String fingerprint = Fingerprints.structured(req.requestId(), replacementId, req.expectedVersion(),
                req.confirmer(), req.confirmedAt().toString());
        try {
            return tx.execute(status -> doConfirmSwitch(replacementId, req, fingerprint));
        } catch (DuplicateKeyException e) {
            return tx.execute(status -> idem.findReplay(req.requestId(), fingerprint,
                    ReplacementSwitchResponse.class)
                    .orElseThrow(() -> new ConflictException(
                            "同一操作员不可重复确认切换: " + req.confirmer())));
        }
    }

    private ReplacementSwitchResponse doConfirmSwitch(long replacementId, ReplacementSwitchRequest req,
            String fingerprint) {
        var replay = idem.findReplay(req.requestId(), fingerprint, ReplacementSwitchResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        ReplacementRow row = lockReplacementThroughDispatch(replacementId);
        var replayAfterLock = idem.findReplay(req.requestId(), fingerprint, ReplacementSwitchResponse.class);
        if (replayAfterLock.isPresent()) {
            return replayAfterLock.get();
        }
        if (!"WAITING".equals(row.status())) {
            throw new BusinessException("接替单未处于待切换状态, 不可确认: " + row.status());
        }
        if (req.expectedVersion() != row.version()) {
            throw new ConflictException("接替单版本冲突: 期望" + req.expectedVersion() + ", 实际"
                    + row.version());
        }
        if (count("replacement_confirmation", "replacement_id = ? AND confirmer = ?",
                replacementId, req.confirmer()) > 0) {
            throw new ConflictException("同一操作员不可重复确认切换: " + req.confirmer());
        }
        int existingConfirmers = count("replacement_confirmation", "replacement_id = ?", replacementId);
        if (existingConfirmers >= 2) {
            throw new BusinessException("两个不同操作员确认已齐备, 无需更多确认");
        }
        LocalDateTime confirmedAt = toLocal(req.confirmedAt());
        jdbc.update("INSERT INTO replacement_confirmation (replacement_id, request_id, confirmer,"
                + " confirmed_at) VALUES (?,?,?,?)",
                replacementId, req.requestId(), req.confirmer(), Timestamp.valueOf(confirmedAt));
        int confirmers = count("replacement_confirmation", "replacement_id = ?", replacementId);
        int remaining = count("replacement_truck", "replacement_id = ? AND arrived_at IS NULL",
                replacementId);
        if (confirmers >= 2 && remaining == 0) {
            switchComposition(row, confirmedAt);
            ReplacementSwitchResponse response = new ReplacementSwitchResponse(replacementId, true,
                    "SWITCHED", row.version() + 1, buildEvidence(replacementId));
            idem.record(req.requestId(), fingerprint, "REPLACEMENT_SWITCH", replacementId, response);
            return response;
        }
        // 缺人或缺车：仅登记确认，不释放旧车；确认属于修改，版本加1
        jdbc.update("UPDATE standby_replacement SET version = version + 1 WHERE id = ?", replacementId);
        ReplacementSwitchResponse response = new ReplacementSwitchResponse(replacementId, false, "WAITING",
                row.version() + 1, null);
        idem.record(req.requestId(), fingerprint, "REPLACEMENT_SWITCH", replacementId, response);
        return response;
    }

    /**
     * 未切换时撤回：释放全部新车预占而保留旧车供电，旧到场与供电证据不覆盖。
     */
    public ReplacementRevokeResponse revoke(long replacementId, ReplacementRevokeRequest req) {
        String fingerprint = Fingerprints.structured(req.requestId(), replacementId, req.expectedVersion());
        try {
            return tx.execute(status -> doRevoke(replacementId, req, fingerprint));
        } catch (DuplicateKeyException e) {
            return tx.execute(status -> idem.findReplay(req.requestId(), fingerprint,
                    ReplacementRevokeResponse.class).orElseThrow(() -> e));
        }
    }

    private ReplacementRevokeResponse doRevoke(long replacementId, ReplacementRevokeRequest req,
            String fingerprint) {
        var replay = idem.findReplay(req.requestId(), fingerprint, ReplacementRevokeResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        ReplacementRow row = lockReplacementThroughDispatch(replacementId);
        var replayAfterLock = idem.findReplay(req.requestId(), fingerprint, ReplacementRevokeResponse.class);
        if (replayAfterLock.isPresent()) {
            return replayAfterLock.get();
        }
        if (!"WAITING".equals(row.status())) {
            throw new BusinessException("仅待切换接替单可撤回: " + row.status());
        }
        if (req.expectedVersion() != row.version()) {
            throw new ConflictException("接替单版本冲突: 期望" + req.expectedVersion() + ", 实际"
                    + row.version());
        }
        List<Long> released = replacementTruckIds(replacementId);
        for (long truckId : released) {
            jdbc.update("DELETE FROM active_occupation WHERE truck_id = ? AND occupation_type = 'STANDBY'"
                    + " AND replacement_id = ?", truckId, replacementId);
        }
        jdbc.update("UPDATE standby_replacement SET status = 'REVOKED', version = version + 1 WHERE id = ?",
                replacementId);
        store.addEvent(row.workOrderId(), "STANDBY_REPLACEMENT_REVOKED",
                "接替单" + replacementId + "撤回, 释放热备车" + released + ", 旧车" + row.oldTruckId()
                        + "继续供电, 旧到场与供电证据保留");
        ReplacementRevokeResponse response = new ReplacementRevokeResponse(replacementId, released,
                row.oldTruckId(), "REVOKED", row.version() + 1);
        idem.record(req.requestId(), fingerprint, "REPLACEMENT_REVOKE", replacementId, response);
        return response;
    }

    /**
     * 接替单详情。
     */
    public ReplacementView view(long replacementId) {
        return tx.execute(status -> viewOf(replacementId));
    }

    /**
     * 调度单视角的接替单列表（按ID升序）。
     */
    public List<ReplacementView> listByDispatch(long dispatchId) {
        return tx.execute(status -> {
            lockDispatch(dispatchId);
            return jdbc.query("SELECT id FROM standby_replacement WHERE dispatch_id = ? ORDER BY id",
                    (rs, rowNum) -> rs.getLong(1), dispatchId).stream().map(this::viewOf).toList();
        });
    }

    /**
     * 正式复电或工单合并：在调度单行已被调用方锁定后，关闭其未结束接替（WAITING/SWITCHED）。
     * 仅释放热备预占（STANDBY）车辆；切换后的供电占用由调用方按调度单统一释放，避免重复删除。
     * 调用方须已在事务内持有调度单行锁；本方法再锁接替单行，保持“调度单→接替单→车辆”锁序。
     *
     * @return 关闭的接替单数
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public int cancelForLockedDispatch(long dispatchId, long workOrderId, String reason) {
        List<ReplacementRow> open = jdbc.query(
                "SELECT * FROM standby_replacement WHERE dispatch_id = ?"
                + " AND status IN ('WAITING','SWITCHED') FOR UPDATE",
                REPLACEMENT_MAPPER, dispatchId);
        for (ReplacementRow row : open) {
            List<Long> truckIds = replacementTruckIds(row.id());
            if ("WAITING".equals(row.status())) {
                // 未切换：新车仍是热备预占且不属于任何调度单，在此释放；旧车由调度取消统一释放
                for (long truckId : truckIds) {
                    jdbc.update("DELETE FROM active_occupation WHERE truck_id = ?"
                            + " AND occupation_type = 'STANDBY' AND replacement_id = ?",
                            truckId, row.id());
                }
            }
            jdbc.update("UPDATE standby_replacement SET status = 'CANCELLED', version = version + 1"
                    + " WHERE id = ?", row.id());
            store.addEvent(workOrderId, "STANDBY_REPLACEMENT_CANCELLED",
                    "接替单" + row.id() + "因" + reason + "关闭, 状态" + row.status() + ", 热备车"
                            + truckIds + "占用已释放");
        }
        return open.size();
    }

    /**
     * 原子切换供电组合：旧车退出并释放给后续工单，新车由热备预占转为供电占用，
     * 同步替换调度单组合，留切换前后容量证据；切换前后组合容量均须覆盖冻结负荷。
     * 调用方须已持有调度单与接替单行锁。
     */
    private void switchComposition(ReplacementRow row, LocalDateTime switchedAt) {
        SupplyDispatch dispatch = lockDispatch(row.dispatchId());
        if (!"SUPPLYING".equals(dispatch.status())) {
            // 复电/合并竞争胜出：拒绝切入，事务回滚不留孤立占用
            throw new BusinessException("调度单已不处于供电中, 禁止切换: " + dispatch.status());
        }
        List<Long> newTruckIds = jdbc.query(
                "SELECT truck_id FROM replacement_truck WHERE replacement_id = ? ORDER BY truck_id",
                (rs, rowNum) -> rs.getLong(1), row.id());
        List<long[]> beforeRows = jdbc.query(
                "SELECT t.id, t.capacity_kw FROM dispatch_truck dt JOIN generator_truck t"
                + " ON t.id = dt.truck_id WHERE dt.dispatch_id = ? ORDER BY t.id",
                (rs, rowNum) -> new long[] {rs.getLong(1), rs.getInt(2)}, row.dispatchId());
        List<Long> beforeTruckIds = beforeRows.stream().map(a -> a[0]).toList();
        int beforeCapacity = beforeRows.stream().mapToInt(a -> (int) a[1]).sum();
        if (!beforeTruckIds.contains(row.oldTruckId())) {
            throw new BusinessException("旧车已不在供电组合内, 切换拒绝: " + row.oldTruckId());
        }
        for (long truckId : newTruckIds) {
            String type = jdbc.queryForObject(
                    "SELECT occupation_type FROM active_occupation WHERE truck_id = ?",
                    String.class, truckId);
            if (!"STANDBY".equals(type)) {
                throw new BusinessException("热备新车预占已丢失, 切换拒绝: " + truckId);
            }
        }
        int afterCapacity = beforeCapacity - row.oldCapacityKw() + row.newCapacityKw();
        if (beforeCapacity < row.frozenLoadKw() || afterCapacity < row.frozenLoadKw()) {
            throw new BusinessException("切换前后容量均须覆盖冻结负荷" + row.frozenLoadKw() + "kW: 前"
                    + beforeCapacity + "kW, 后" + afterCapacity + "kW");
        }
        // 旧车退出供电组合并释放；新车预占转供电占用并入组合（到场时刻随车转移）
        jdbc.update("DELETE FROM dispatch_truck WHERE dispatch_id = ? AND truck_id = ?",
                row.dispatchId(), row.oldTruckId());
        jdbc.update("DELETE FROM active_occupation WHERE truck_id = ? AND occupation_type = 'SUPPLY'"
                + " AND dispatch_id = ?", row.oldTruckId(), row.dispatchId());
        for (long truckId : newTruckIds) {
            LocalDateTime arrivedAt = jdbc.queryForObject(
                    "SELECT arrived_at FROM replacement_truck WHERE replacement_id = ? AND truck_id = ?",
                    (rs, rowNum) -> rs.getTimestamp(1).toLocalDateTime(), row.id(), truckId);
            jdbc.update("INSERT INTO dispatch_truck (dispatch_id, truck_id, arrived_at) VALUES (?,?,?)",
                    row.dispatchId(), truckId, Timestamp.valueOf(arrivedAt));
            jdbc.update("UPDATE active_occupation SET occupation_type = 'SUPPLY', dispatch_id = ?,"
                    + " replacement_id = NULL WHERE truck_id = ?", row.dispatchId(), truckId);
        }
        jdbc.update("INSERT INTO replacement_switch_evidence (replacement_id, dispatch_id,"
                + " before_truck_ids, before_capacity_kw, after_truck_ids, after_capacity_kw,"
                + " frozen_load_kw, switched_at) VALUES (?,?,?,?,?,?,?,?)",
                row.id(), row.dispatchId(), joinIds(beforeTruckIds), beforeCapacity,
                joinIds(afterTruckIds(beforeTruckIds, row.oldTruckId(), newTruckIds)), afterCapacity,
                row.frozenLoadKw(), Timestamp.valueOf(switchedAt));
        jdbc.update("UPDATE standby_replacement SET status = 'SWITCHED', version = version + 1 WHERE id = ?",
                row.id());
        store.addEvent(row.workOrderId(), "STANDBY_REPLACEMENT_SWITCHED",
                "接替单" + row.id() + "原子切换: 旧车" + row.oldTruckId() + "退出, 热备车" + newTruckIds
                        + "转入供电; 容量" + beforeCapacity + "kW→" + afterCapacity + "kW, 均覆盖冻结负荷"
                        + row.frozenLoadKw() + "kW");
    }

    private ReplacementView viewOf(long replacementId) {
        ReplacementRow row = findReplacement(replacementId)
                .orElseThrow(() -> new NotFoundException("接替单不存在: " + replacementId));
        List<ReplacementTruckView> truckViews = jdbc.query(
                "SELECT rt.truck_id, rt.capacity_kw, rt.arrived_at FROM replacement_truck rt"
                + " WHERE rt.replacement_id = ? ORDER BY rt.truck_id",
                (rs, rowNum) -> {
                    Timestamp arrived = rs.getTimestamp(3);
                    return new ReplacementTruckView(rs.getLong(1), rs.getInt(2),
                            arrived == null ? null : arrived.toLocalDateTime());
                }, replacementId);
        List<String> confirmers = jdbc.query(
                "SELECT confirmer FROM replacement_confirmation WHERE replacement_id = ? ORDER BY id",
                (rs, rowNum) -> rs.getString(1), replacementId);
        List<ReplacementSwitchEvidence> evidences = jdbc.query(
                "SELECT before_truck_ids, before_capacity_kw, after_truck_ids, after_capacity_kw"
                + " FROM replacement_switch_evidence WHERE replacement_id = ?",
                (rs, rowNum) -> new ReplacementSwitchEvidence(splitIds(rs.getString(1)), rs.getInt(2),
                        splitIds(rs.getString(3)), rs.getInt(4)), replacementId);
        List<Timestamp> switchedAt = jdbc.query(
                "SELECT switched_at FROM replacement_switch_evidence WHERE replacement_id = ?",
                (rs, rowNum) -> rs.getTimestamp(1), replacementId);
        return new ReplacementView(row.id(), row.dispatchId(), row.workOrderId(), row.oldTruckId(),
                row.feederId(), row.status(), row.version(), row.frozenOrderVersion(), row.frozenLoadKw(),
                row.oldCapacityKw(), row.newCapacityKw(), row.createdAt(), truckViews, confirmers,
                evidences.isEmpty() ? null : evidences.get(0),
                switchedAt.isEmpty() ? null : switchedAt.get(0).toLocalDateTime());
    }

    private ReplacementSwitchEvidence buildEvidence(long replacementId) {
        return jdbc.queryForObject(
                "SELECT before_truck_ids, before_capacity_kw, after_truck_ids, after_capacity_kw"
                + " FROM replacement_switch_evidence WHERE replacement_id = ?",
                (rs, rowNum) -> new ReplacementSwitchEvidence(splitIds(rs.getString(1)), rs.getInt(2),
                        splitIds(rs.getString(3)), rs.getInt(4)), replacementId);
    }

    /** 先锁调度单再锁接替单：与复电/取消及切换的全局锁序一致，避免死锁。 */
    private ReplacementRow lockReplacementThroughDispatch(long replacementId) {
        ReplacementRow found = findReplacement(replacementId)
                .orElseThrow(() -> new NotFoundException("接替单不存在: " + replacementId));
        lockDispatch(found.dispatchId());
        List<ReplacementRow> rows = jdbc.query(
                "SELECT * FROM standby_replacement WHERE id = ? FOR UPDATE", REPLACEMENT_MAPPER,
                replacementId);
        return rows.get(0);
    }

    private Optional<ReplacementRow> findReplacement(long replacementId) {
        List<ReplacementRow> rows = jdbc.query(
                "SELECT * FROM standby_replacement WHERE id = ?", REPLACEMENT_MAPPER, replacementId);
        return rows.stream().findFirst();
    }

    private SupplyDispatch lockDispatch(long dispatchId) {
        List<SupplyDispatch> rows = jdbc.query(
                "SELECT id, work_order_id, order_version, total_load_kw, status, version"
                + " FROM generator_dispatch WHERE id = ? FOR UPDATE",
                (rs, rowNum) -> new SupplyDispatch(rs.getLong(1), rs.getLong(2), rs.getInt(3),
                        rs.getInt(4), rs.getString(5), rs.getInt(6)), dispatchId);
        if (rows.isEmpty()) {
            throw new NotFoundException("调度单不存在: " + dispatchId);
        }
        return rows.get(0);
    }

    private long insertReplacement(SupplyDispatch dispatch, long oldTruckId, String feederId,
            int oldCapacityKw, int newCapacityKw, LocalDateTime createdAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO standby_replacement (dispatch_id, work_order_id, old_truck_id, feeder_id,"
                    + " frozen_order_version, frozen_load_kw, old_capacity_kw, new_capacity_kw, status,"
                    + " version, created_at) VALUES (?,?,?,?,?,?,?,?,'WAITING',1,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, dispatch.id());
            ps.setLong(2, dispatch.workOrderId());
            ps.setLong(3, oldTruckId);
            ps.setString(4, feederId);
            ps.setInt(5, dispatch.orderVersion());
            ps.setInt(6, dispatch.totalLoadKw());
            ps.setInt(7, oldCapacityKw);
            ps.setInt(8, newCapacityKw);
            ps.setTimestamp(9, Timestamp.valueOf(createdAt));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    private List<Long> replacementTruckIds(long replacementId) {
        return jdbc.query("SELECT truck_id FROM replacement_truck WHERE replacement_id = ? ORDER BY truck_id",
                (rs, rowNum) -> rs.getLong(1), replacementId);
    }

    private List<Long> afterTruckIds(List<Long> before, long oldTruckId, List<Long> newTruckIds) {
        List<Long> after = new java.util.ArrayList<>(before.stream()
                .filter(id -> id != oldTruckId).toList());
        newTruckIds.stream().filter(id -> !after.contains(id)).forEach(after::add);
        return after.stream().sorted().toList();
    }

    private String joinIds(List<Long> ids) {
        return ids.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
    }

    private static List<Long> splitIds(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(text.split(",")).map(Long::valueOf).toList();
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
