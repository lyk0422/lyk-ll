package com.example.starter.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

import com.example.starter.support.AppClock;
import com.example.starter.support.Fingerprints;
import com.example.starter.web.ConflictException;
import com.example.starter.web.NotFoundException;
import com.example.starter.web.UnprocessableException;
import com.example.starter.web.dto.TruckLedgerEntry;
import com.example.starter.web.dto.TruckRegisterRequest;
import com.example.starter.web.dto.TruckUpdateRequest;
import com.example.starter.web.dto.TruckView;

/**
 * 发电车池服务：登记正数整数kW容量、服务馈线与可用状态；变更携带期望版本，成功变更加1；
 * 提供容量及车辆占用台账。
 */
@Service
public class GeneratorTruckService {

    private final JdbcTemplate jdbc;
    private final AppClock clock;
    private final IdempotentExecutor idempotent;

    public GeneratorTruckService(JdbcTemplate jdbc, AppClock clock, IdempotentExecutor idempotent) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.idempotent = idempotent;
    }

    /**
     * 登记发电车：同 requestId 同参重放首次结果，同键异参 409；编号重复 422。
     */
    public TruckView register(TruckRegisterRequest req) {
        if (req.capacityKw() == null || req.capacityKw() <= 0) {
            throw new IllegalArgumentException("容量必须为正整数kW: " + req.capacityKw());
        }
        boolean available = req.available() == null || req.available();
        String fingerprint = Fingerprints.structured(req.requestId(), req.truckCode(),
                req.capacityKw(), req.feederId(), available);
        return idempotent.execute(req.requestId(), fingerprint, TruckView.class,
                () -> doRegister(req, available));
    }

    private TruckView doRegister(TruckRegisterRequest req, boolean available) {
        if (!jdbc.query("SELECT id FROM generator_truck WHERE truck_code = ?",
                (rs, rowNum) -> rs.getLong(1), req.truckCode()).isEmpty()) {
            throw new UnprocessableException("发电车编号已存在: " + req.truckCode());
        }
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO generator_truck (truck_code, capacity_kw, feeder_id, available, version,"
                    + " created_at) VALUES (?,?,?,?,1,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, req.truckCode());
            ps.setInt(2, req.capacityKw());
            ps.setString(3, req.feederId());
            ps.setBoolean(4, available);
            ps.setTimestamp(5, Timestamp.valueOf(clock.now()));
            return ps;
        }, keys);
        long id = keys.getKey().longValue();
        return new TruckView(id, req.truckCode(), req.capacityKw(), req.feederId(), available, 1, false);
    }

    /**
     * 变更发电车容量/可用状态：期望版本不一致抛 409，成功变更加1。
     */
    public TruckView update(long truckId, TruckUpdateRequest req) {
        if (req.capacityKw() != null && req.capacityKw() <= 0) {
            throw new IllegalArgumentException("容量必须为正整数kW: " + req.capacityKw());
        }
        String fingerprint = Fingerprints.structured(req.requestId(), truckId, req.expectedVersion(),
                req.capacityKw(), req.available());
        return idempotent.execute(req.requestId(), fingerprint, TruckView.class,
                () -> doUpdate(truckId, req));
    }

    private TruckView doUpdate(long truckId, TruckUpdateRequest req) {
        TruckRow row = lockTruck(truckId);
        if (row.version() != req.expectedVersion()) {
            throw new ConflictException("车辆版本冲突: 期望" + req.expectedVersion() + ", 实际" + row.version());
        }
        int capacity = req.capacityKw() != null ? req.capacityKw() : row.capacityKw();
        boolean available = req.available() != null ? req.available() : row.available();
        jdbc.update("UPDATE generator_truck SET capacity_kw = ?, available = ?, version = version + 1"
                + " WHERE id = ?", capacity, available, truckId);
        return new TruckView(truckId, row.truckCode(), capacity, row.feederId(), available,
                row.version() + 1, false);
    }

    /**
     * 锁定车辆行并校验存在性；同事务内后续判断以此串行化。
     */
    TruckRow lockTruck(long truckId) {
        List<TruckRow> rows = jdbc.query(
                "SELECT * FROM generator_truck WHERE id = ? FOR UPDATE",
                (rs, rowNum) -> mapTruck(rs), truckId);
        if (rows.isEmpty()) {
            throw new NotFoundException("发电车不存在: " + truckId);
        }
        return rows.get(0);
    }

    private static TruckRow mapTruck(ResultSet rs) throws SQLException {
        return new TruckRow(rs.getLong("id"), rs.getString("truck_code"), rs.getInt("capacity_kw"),
                rs.getString("feeder_id"), rs.getBoolean("available"), rs.getInt("version"));
    }

    /**
     * 容量及车辆占用台账：feederId 为空时查全部；占用中车辆附调度单与工单信息。
     */
    public List<TruckLedgerEntry> ledger(String feederId) {
        String sql = "SELECT t.id, t.truck_code, t.capacity_kw, t.feeder_id, t.available, t.version,"
                + " d.id AS dispatch_id, d.work_order_id, d.dispatched_at"
                + " FROM generator_truck t"
                + " LEFT JOIN generator_dispatch_truck dt ON dt.active_occupation = t.id"
                + " LEFT JOIN generator_dispatch d ON d.id = dt.dispatch_id"
                + (feederId == null ? "" : " WHERE t.feeder_id = ?")
                + " ORDER BY t.id ASC";
        var mapper = (org.springframework.jdbc.core.RowMapper<TruckLedgerEntry>) (rs, rowNum) -> {
            long dispatchId = rs.getLong("dispatch_id");
            Long occupiedDispatch = rs.wasNull() ? null : dispatchId;
            long workOrderId = rs.getLong("work_order_id");
            Long occupiedOrder = rs.wasNull() ? null : workOrderId;
            Timestamp since = rs.getTimestamp("dispatched_at");
            return new TruckLedgerEntry(rs.getLong("id"), rs.getString("truck_code"),
                    rs.getInt("capacity_kw"), rs.getString("feeder_id"), rs.getBoolean("available"),
                    rs.getInt("version"), occupiedDispatch, occupiedOrder,
                    since == null ? null : since.toLocalDateTime());
        };
        return feederId == null ? jdbc.query(sql, mapper) : jdbc.query(sql, mapper, feederId);
    }

    /**
     * 发电车行记录。
     */
    record TruckRow(long id, String truckCode, int capacityKw, String feederId, boolean available,
            int version) {
    }
}
