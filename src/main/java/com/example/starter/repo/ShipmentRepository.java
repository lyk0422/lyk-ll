package com.example.starter.repo;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 分车出厂数据访问层：车单主表与头只预占台账，全部使用参数化 SQL，
 * 时间以 UTC ISO-8601 字符串存取。头只预占唯一约束由数据库保证，争车只一方成功。
 */
@Repository
public class ShipmentRepository {

    private final JdbcTemplate jdbc;

    public ShipmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 车单主表行。frozenHeads 为创建时冻结的头只标识 JSON 数组文本。 */
    public record VehicleRow(long id, long batchId, String vehicleNo, int version, String status,
                             int headCount, String frozenHeads, String createdAt,
                             String departedAt, String revokedAt) {
    }

    /** 头只预占台账行。 */
    public record ShipmentHeadRow(long id, long batchId, long vehicleId, String headNo, String status,
                                  String createdAt, String departedAt) {
    }

    private static final RowMapper<VehicleRow> VEHICLE_MAPPER = (rs, n) -> new VehicleRow(
            rs.getLong("id"), rs.getLong("batch_id"), rs.getString("vehicle_no"),
            rs.getInt("version"), rs.getString("status"), rs.getInt("head_count"),
            rs.getString("frozen_heads"), rs.getString("created_at"),
            rs.getString("departed_at"), rs.getString("revoked_at"));

    private static final RowMapper<ShipmentHeadRow> HEAD_MAPPER = (rs, n) -> new ShipmentHeadRow(
            rs.getLong("id"), rs.getLong("batch_id"), rs.getLong("vehicle_id"),
            rs.getString("head_no"), rs.getString("status"), rs.getString("created_at"),
            rs.getString("departed_at"));

    // ---------- 车单主表 ----------

    /** 创建车单（版本固定 1、状态 RESERVED），返回数据库生成的车单主键。 */
    public long insertVehicle(long batchId, String vehicleNo, int headCount, String frozenHeads,
                              String now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO shipment_vehicle(batch_id, vehicle_no, version, status, head_count,"
                            + " frozen_heads, created_at) VALUES (?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, batchId);
            ps.setString(2, vehicleNo);
            ps.setInt(3, 1);
            ps.setString(4, "RESERVED");
            ps.setInt(5, headCount);
            ps.setString(6, frozenHeads);
            ps.setString(7, now);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("车单主键生成失败");
        }
        return key.longValue();
    }

    public Optional<VehicleRow> findVehicleByNo(long batchId, String vehicleNo) {
        List<VehicleRow> rows = jdbc.query(
                "SELECT * FROM shipment_vehicle WHERE batch_id = ? AND vehicle_no = ?",
                VEHICLE_MAPPER, batchId, vehicleNo);
        return rows.stream().findFirst();
    }

    /** 行锁读取车单（须在事务内调用），用于撤销/发车与争车竞争互斥。 */
    public Optional<VehicleRow> findVehicleByNoForUpdate(long batchId, String vehicleNo) {
        List<VehicleRow> rows = jdbc.query(
                "SELECT * FROM shipment_vehicle WHERE batch_id = ? AND vehicle_no = ? FOR UPDATE",
                VEHICLE_MAPPER, batchId, vehicleNo);
        return rows.stream().findFirst();
    }

    public List<VehicleRow> findVehicles(long batchId) {
        return jdbc.query("SELECT * FROM shipment_vehicle WHERE batch_id = ? ORDER BY vehicle_no",
                VEHICLE_MAPPER, batchId);
    }

    /**
     * 乐观状态流转：仅当前版本等于 expectedVersion 且状态在 expectedStatus 内时生效，
     * 版本加 1，返回受影响行数（0 表示版本不符或状态已变，调用方返回 409）。
     */
    public int transitionVehicle(long vehicleId, String expectedStatus, int expectedVersion,
                                 String newStatus, int newVersion, String actionAtColumn,
                                 String actionAt) {
        return jdbc.update("UPDATE shipment_vehicle SET status = ?, version = ?,"
                        + " " + actionAtColumn + " = ? WHERE id = ? AND status = ? AND version = ?",
                newStatus, newVersion, actionAt, vehicleId, expectedStatus, expectedVersion);
    }

    // ---------- 头只预占台账 ----------

    /** 全量预占单头只；若该合格头只已被其他未撤销车占用，唯一约束抛出由调用方回滚整单。 */
    public void insertShipmentHead(long batchId, long vehicleId, String headNo, String now) {
        jdbc.update("INSERT INTO shipment_head(batch_id, vehicle_id, head_no, status, created_at)"
                        + " VALUES (?,?,?, 'RESERVED', ?)",
                batchId, vehicleId, headNo, now);
    }

    /** 撤销释放：删除该车全部预占行，头只随即可供其他车预占（仅未发车车单可调用）。 */
    public void deleteShipmentHeads(long batchId, long vehicleId) {
        jdbc.update("DELETE FROM shipment_head WHERE batch_id = ? AND vehicle_id = ?",
                batchId, vehicleId);
    }

    /** 逐车核销：把本车全部 RESERVED 预占头只一次性转 DEPARTED，返回受影响行数。 */
    public int markHeadsDeparted(long batchId, long vehicleId, String now) {
        return jdbc.update("UPDATE shipment_head SET status = 'DEPARTED', departed_at = ?"
                        + " WHERE batch_id = ? AND vehicle_id = ? AND status = 'RESERVED'",
                now, batchId, vehicleId);
    }

    public List<ShipmentHeadRow> findShipmentHeads(long batchId) {
        return jdbc.query("SELECT * FROM shipment_head WHERE batch_id = ? ORDER BY head_no",
                HEAD_MAPPER, batchId);
    }
}
