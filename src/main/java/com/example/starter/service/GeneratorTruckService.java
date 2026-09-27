package com.example.starter.service;

import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.starter.support.AppClock;
import com.example.starter.support.Fingerprints;
import com.example.starter.web.ConflictException;
import com.example.starter.web.NotFoundException;
import com.example.starter.web.dto.RegisterTruckRequest;
import com.example.starter.web.dto.TruckLedgerEntry;
import com.example.starter.web.dto.TruckView;
import com.example.starter.web.dto.UpdateTruckAvailabilityRequest;

/**
 * 发电车池服务：合成车辆登记（正整数kW容量、服务馈线、可用状态）、可用性变更与车占用台账。
 * 所有写操作 requestId 幂等、携带 expectedVersion，版本从1开始、成功变更加1。
 */
@Service
public class GeneratorTruckService {

    private final JdbcTemplate jdbc;
    private final AppClock clock;
    private final GenIdempotency idem;
    private final TransactionTemplate tx;

    public GeneratorTruckService(JdbcTemplate jdbc, AppClock clock, GenIdempotency idem,
            PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.idem = idem;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * 登记发电车：同 requestId 同参重放首次结果，同键异参抛409；版本从1开始。
     */
    public TruckView register(RegisterTruckRequest req) {
        String fingerprint = Fingerprints.structured(req.requestId(), req.capacityKw(), req.feederId());
        try {
            return tx.execute(status -> doRegister(req, fingerprint));
        } catch (DuplicateKeyException e) {
            return tx.execute(status -> idem.findReplay(req.requestId(), fingerprint, TruckView.class)
                    .orElseThrow(() -> e));
        }
    }

    private TruckView doRegister(RegisterTruckRequest req, String fingerprint) {
        var replay = idem.findReplay(req.requestId(), fingerprint, TruckView.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO generator_truck (capacity_kw, feeder_id, available, version, created_at)"
                    + " VALUES (?,?,TRUE,1,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setInt(1, req.capacityKw());
            ps.setString(2, req.feederId());
            ps.setTimestamp(3, Timestamp.valueOf(clock.now()));
            return ps;
        }, keys);
        TruckView view = new TruckView(keys.getKey().longValue(), req.capacityKw(), req.feederId(), true, 1);
        idem.record(req.requestId(), fingerprint, "TRUCK_REGISTER", view.id(), view);
        return view;
    }

    /**
     * 可用性变更：expectedVersion 须等于当前版本（不符抛409），成功变更加1。
     */
    public TruckView updateAvailability(long truckId, UpdateTruckAvailabilityRequest req) {
        String fingerprint = Fingerprints.structured(req.requestId(), truckId, req.expectedVersion(),
                req.available());
        try {
            return tx.execute(status -> doUpdateAvailability(truckId, req, fingerprint));
        } catch (DuplicateKeyException e) {
            return tx.execute(status -> idem.findReplay(req.requestId(), fingerprint, TruckView.class)
                    .orElseThrow(() -> e));
        }
    }

    private TruckView doUpdateAvailability(long truckId, UpdateTruckAvailabilityRequest req, String fingerprint) {
        var replay = idem.findReplay(req.requestId(), fingerprint, TruckView.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        TruckView current = lockTruck(truckId);
        // 行锁串行化后复查幂等：并发同键请求在此重放首次冻结结果
        var replayAfterLock = idem.findReplay(req.requestId(), fingerprint, TruckView.class);
        if (replayAfterLock.isPresent()) {
            return replayAfterLock.get();
        }
        if (req.expectedVersion() != current.version()) {
            throw new ConflictException("车辆版本冲突: 期望" + req.expectedVersion() + ", 实际" + current.version());
        }
        jdbc.update("UPDATE generator_truck SET available = ?, version = version + 1 WHERE id = ?",
                req.available(), truckId);
        TruckView view = new TruckView(truckId, current.capacityKw(), current.feederId(),
                req.available(), current.version() + 1);
        idem.record(req.requestId(), fingerprint, "TRUCK_AVAILABILITY", truckId, view);
        return view;
    }

    /**
     * 锁定车辆行并返回当前视图；不存在抛404。
     */
    TruckView lockTruck(long truckId) {
        List<TruckView> rows = jdbc.query(
                "SELECT id, capacity_kw, feeder_id, available, version FROM generator_truck WHERE id = ? FOR UPDATE",
                (rs, rowNum) -> new TruckView(rs.getLong(1), rs.getInt(2), rs.getString(3),
                        rs.getBoolean(4), rs.getInt(5)),
                truckId);
        if (rows.isEmpty()) {
            throw new NotFoundException("发电车不存在: " + truckId);
        }
        return rows.get(0);
    }

    /**
     * 容量及车占用台账：全部车辆的容量、可用状态与当前未结束占用。
     */
    public List<TruckLedgerEntry> ledger() {
        return jdbc.query(
                "SELECT t.id, t.capacity_kw, t.feeder_id, t.available, t.version,"
                + " o.dispatch_id, d.work_order_id, o.occupied_at"
                + " FROM generator_truck t"
                + " LEFT JOIN active_occupation o ON o.truck_id = t.id"
                + " LEFT JOIN generator_dispatch d ON d.id = o.dispatch_id"
                + " ORDER BY t.id ASC",
                (rs, rowNum) -> {
                    long dispatchId = rs.getLong(6);
                    Long dispatch = rs.wasNull() ? null : dispatchId;
                    long workOrderId = rs.getLong(7);
                    Long workOrder = rs.wasNull() ? null : workOrderId;
                    Timestamp occupiedAt = rs.getTimestamp(8);
                    return new TruckLedgerEntry(rs.getLong(1), rs.getInt(2), rs.getString(3),
                            rs.getBoolean(4), rs.getInt(5), dispatch, workOrder,
                            occupiedAt == null ? null : occupiedAt.toLocalDateTime());
                });
    }
}
