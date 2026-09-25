package com.example.starter.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import com.example.starter.dto.GrainDtos.CreateFanRequest;
import com.example.starter.dto.GrainDtos.CreateSiloRequest;
import com.example.starter.dto.GrainDtos.FanView;
import com.example.starter.dto.GrainDtos.LateEventView;
import com.example.starter.dto.GrainDtos.ReadingRequest;
import com.example.starter.dto.GrainDtos.ReadingResponse;
import com.example.starter.dto.GrainDtos.SiloView;
import com.example.starter.dto.GrainDtos.TemperaturePoint;
import com.example.starter.error.ApiException;
import com.example.starter.model.SiloStatus;
import com.example.starter.util.Fingerprints;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;

/**
 * 粮仓与监测服务：粮仓/风机档案、温湿度上报与告警判定、温度曲线与迟到事件查询。
 */
@Service
public class SiloService {

    private static final RowMapper<SiloView> SILO_MAPPER = (rs, rowNum) -> new SiloView(
            rs.getLong("id"),
            rs.getString("code"),
            rs.getString("name"),
            rs.getBigDecimal("safe_temperature"),
            rs.getBigDecimal("equilibrium_humidity"),
            SiloStatus.valueOf(rs.getString("status")));

    private final JdbcTemplate jdbc;

    public SiloService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 创建粮仓（合成数据）。
     */
    @Transactional
    public SiloView createSilo(CreateSiloRequest request) {
        long id = insert("INSERT INTO silo (code, name, safe_temperature, equilibrium_humidity) VALUES (?, ?, ?, ?)",
                request.code(), request.name(), request.safeTemperature(), request.equilibriumHumidity());
        return getSilo(id);
    }

    /**
     * 创建风机（合成数据）。
     */
    @Transactional
    public FanView createFan(CreateFanRequest request) {
        long id = insert("INSERT INTO fan (code, name) VALUES (?, ?)", request.code(), request.name());
        return getFan(id);
    }

    /**
     * 按 ID 查询粮仓，不存在返回 404。
     */
    public SiloView getSilo(long siloId) {
        return findSilo(siloId).orElseThrow(
                () -> ApiException.notFound("SILO_NOT_FOUND", "粮仓不存在: " + siloId));
    }

    /**
     * 按 ID 查询风机，不存在返回 404。
     */
    public FanView getFan(long fanId) {
        List<FanView> fans = jdbc.query("SELECT * FROM fan WHERE id = ?", SiloService::mapFan, fanId);
        if (fans.isEmpty()) {
            throw ApiException.notFound("FAN_NOT_FOUND", "风机不存在: " + fanId);
        }
        return fans.get(0);
    }

    /**
     * 温湿度上报：任一点粮温超过安全温度即告警，恰等判正常；
     * 归档后迟到数据只记迟到事件；requestId 幂等，同键异参返回 409。
     */
    @Transactional
    public ReadingResponse ingestReading(long siloId, ReadingRequest request) {
        String fingerprint = Fingerprints.of(request.requestId(), siloId, request.pointNo(),
                request.temperature(), request.outsideHumidity(), request.measuredAt());

        Optional<ReadingRow> existing = findReadingByRequestId(request.requestId());
        if (existing.isPresent()) {
            ReadingRow row = existing.get();
            if (!row.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("IDEMPOTENCY_CONFLICT",
                        "requestId 已存在但业务字段不一致: " + request.requestId());
            }
            return new ReadingResponse(row.alarmId() != null ? "ALARM" : "NORMAL",
                    row.alarmId(), getSilo(siloId).status(), true);
        }

        Optional<LateEventView> late = findLateEvent(request.requestId());
        if (late.isPresent()) {
            return new ReadingResponse("LATE_RECORDED", null, SiloStatus.ARCHIVED, true);
        }

        SiloView silo = getSilo(siloId);
        if (silo.status() == SiloStatus.ARCHIVED) {
            try {
                recordLateEvent(siloId, request);
            } catch (DuplicateKeyException duplicate) {
                return new ReadingResponse("LATE_RECORDED", null, SiloStatus.ARCHIVED, true);
            }
            return new ReadingResponse("LATE_RECORDED", null, SiloStatus.ARCHIVED, false);
        }

        try {
            Long alarmId = null;
            String result = "NORMAL";
            if (request.temperature().compareTo(silo.safeTemperature()) > 0) {
                alarmId = insert(
                        "INSERT INTO alarm (silo_id, request_id, point_no, temperature, safe_temperature, fingerprint)"
                                + " VALUES (?, ?, ?, ?, ?, ?)",
                        siloId, request.requestId(), request.pointNo(), request.temperature(),
                        silo.safeTemperature(), fingerprint);
                result = "ALARM";
                if (silo.status() == SiloStatus.MONITORING) {
                    jdbc.update("UPDATE silo SET status = 'ALARMING' WHERE id = ?", siloId);
                }
            }
            jdbc.update("INSERT INTO sensor_reading (silo_id, request_id, point_no, temperature, outside_humidity,"
                            + " measured_at, alarm_id, fingerprint) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    siloId, request.requestId(), request.pointNo(), request.temperature(),
                    request.outsideHumidity(), request.measuredAt(), alarmId, fingerprint);
            SiloStatus current = getSilo(siloId).status();
            return new ReadingResponse(result, alarmId, current, false);
        } catch (DuplicateKeyException duplicate) {
            // 并发下同键请求落入唯一约束：按幂等重放或键冲突处理
            ReadingRow row = findReadingByRequestId(request.requestId()).orElseThrow(() -> duplicate);
            if (!row.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("IDEMPOTENCY_CONFLICT",
                        "requestId 已存在但业务字段不一致: " + request.requestId());
            }
            return new ReadingResponse(row.alarmId() != null ? "ALARM" : "NORMAL",
                    row.alarmId(), getSilo(siloId).status(), true);
        }
    }

    /**
     * 温度曲线：按采样时间排序，可复算。
     */
    public List<TemperaturePoint> temperatureCurve(long siloId) {
        getSilo(siloId);
        return jdbc.query("SELECT point_no, temperature, measured_at FROM sensor_reading"
                        + " WHERE silo_id = ? ORDER BY measured_at, id",
                (rs, rowNum) -> new TemperaturePoint(
                        rs.getInt("point_no"),
                        rs.getBigDecimal("temperature"),
                        rs.getTimestamp("measured_at").toLocalDateTime()),
                siloId);
    }

    /**
     * 迟到事件列表。
     */
    public List<LateEventView> lateEvents(long siloId) {
        getSilo(siloId);
        return jdbc.query("SELECT * FROM late_event WHERE silo_id = ? ORDER BY id",
                SiloService::mapLateEvent, siloId);
    }

    Optional<SiloView> findSilo(long siloId) {
        List<SiloView> silos = jdbc.query("SELECT * FROM silo WHERE id = ?", SILO_MAPPER, siloId);
        return silos.stream().findFirst();
    }

    private void recordLateEvent(long siloId, ReadingRequest request) {
        String payload = "pointNo=" + request.pointNo() + ", temperature=" + request.temperature()
                + ", outsideHumidity=" + request.outsideHumidity() + ", measuredAt=" + request.measuredAt();
        jdbc.update("INSERT INTO late_event (silo_id, request_id, event_type, payload) VALUES (?, ?, ?, ?)",
                siloId, request.requestId(), "LATE_READING", payload);
    }

    private Optional<LateEventView> findLateEvent(String requestId) {
        List<LateEventView> events = jdbc.query("SELECT * FROM late_event WHERE request_id = ?",
                SiloService::mapLateEvent, requestId);
        return events.stream().findFirst();
    }

    private Optional<ReadingRow> findReadingByRequestId(String requestId) {
        List<ReadingRow> rows = jdbc.query(
                "SELECT request_id, fingerprint, alarm_id FROM sensor_reading WHERE request_id = ?",
                (rs, rowNum) -> new ReadingRow(rs.getString("request_id"), rs.getString("fingerprint"),
                        rs.getObject("alarm_id", Long.class)),
                requestId);
        return rows.stream().findFirst();
    }

    long insert(String sql, Object... args) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, new String[]{"id"});
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("未返回自增主键");
        }
        return key.longValue();
    }

    private static FanView mapFan(ResultSet rs, int rowNum) throws SQLException {
        return new FanView(rs.getLong("id"), rs.getString("code"), rs.getString("name"),
                rs.getString("status"), rs.getObject("locked_job_id", Long.class));
    }

    private static LateEventView mapLateEvent(ResultSet rs, int rowNum) throws SQLException {
        return new LateEventView(rs.getLong("id"), rs.getLong("silo_id"), rs.getString("request_id"),
                rs.getString("event_type"), rs.getString("payload"),
                rs.getTimestamp("recorded_at").toLocalDateTime());
    }

    private record ReadingRow(String requestId, String fingerprint, Long alarmId) {
    }
}
