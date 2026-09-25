package com.example.starter.crane.service;

import com.example.starter.crane.NotFoundException;
import com.example.starter.crane.api.BrakeView;
import com.example.starter.crane.api.CorrectionRequest;
import com.example.starter.crane.api.WarningView;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 越限预警/制动事件的只读查询与修正留痕。
 *
 * <p>预警与制动事件一经生成不可删除、不可直接改写；任何修正只向 event_correction 追加记录。</p>
 */
@Service
public class EventService {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public EventService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** 查询预警，可按塔机过滤；关联制动指令编号一并返回。 */
    public List<WarningView> listWarnings(String craneCode) {
        String sql = "SELECT w.*, b.command_code AS brake_command_code FROM intrusion_warning w "
                + "LEFT JOIN brake_command b ON w.brake_id = b.id "
                + (craneCode == null || craneCode.isBlank() ? "" : "WHERE w.crane_code = ? ")
                + "ORDER BY w.start_ts, w.id";
        if (craneCode == null || craneCode.isBlank()) {
            return jdbc.query(sql, (rs, n) -> mapWarning(rs));
        }
        return jdbc.query(sql, (rs, n) -> mapWarning(rs), craneCode);
    }

    /** 查询制动指令，可按塔机过滤。 */
    public List<BrakeView> listBrakes(String craneCode) {
        String sql = "SELECT * FROM brake_command "
                + (craneCode == null || craneCode.isBlank() ? "" : "WHERE crane_code = ? ")
                + "ORDER BY issued_at, id";
        if (craneCode == null || craneCode.isBlank()) {
            return jdbc.query(sql, (rs, n) -> mapBrake(rs));
        }
        return jdbc.query(sql, (rs, n) -> mapBrake(rs), craneCode);
    }

    /** 追加修正留痕；原事件保持不变，仅新增审计行。 */
    @Transactional
    public void appendCorrection(CorrectionRequest request) {
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(1) FROM intrusion_warning WHERE id = ?", Integer.class, request.warningId());
        if (exists == null || exists == 0) {
            throw new NotFoundException("越限预警不存在: " + request.warningId());
        }
        jdbc.update("INSERT INTO event_correction (warning_id, reason, detail, created_at) VALUES (?,?,?,?)",
                request.warningId(), request.reason(), request.detail(), Timestamp.from(clock.instant()));
    }

    /** 查询某预警的制动指令；尚未制动时返回 null（调用方可据此区分“未下发”与“已下发的首次指令”）。 */
    public BrakeView findBrakeByWarning(long warningId) {
        return jdbc.query("SELECT * FROM brake_command WHERE warning_id = ?",
                rs -> rs.next() ? mapBrake(rs) : null, warningId);
    }

    /** 查询某预警的修正留痕（供审计核对）。 */
    public List<String> listCorrections(long warningId) {
        return jdbc.queryForList(
                "SELECT reason || ': ' || detail FROM event_correction WHERE warning_id = ? ORDER BY id",
                String.class, warningId);
    }

    private WarningView mapWarning(ResultSet rs) throws SQLException {
        return new WarningView(
                rs.getLong("id"),
                rs.getString("warning_code"),
                rs.getString("crane_code"),
                rs.getString("zone_code"),
                rs.getTimestamp("start_ts").toInstant(),
                rs.getTimestamp("last_ts").toInstant(),
                rs.getTimestamp("exit_ts") == null ? null : rs.getTimestamp("exit_ts").toInstant(),
                rs.getString("status"),
                rs.getObject("brake_id", Long.class),
                rs.getString("brake_command_code"));
    }

    private BrakeView mapBrake(ResultSet rs) throws SQLException {
        return new BrakeView(
                rs.getLong("id"),
                rs.getString("command_code"),
                rs.getLong("warning_id"),
                rs.getString("crane_code"),
                rs.getString("zone_code"),
                rs.getTimestamp("issued_at").toInstant());
    }
}
