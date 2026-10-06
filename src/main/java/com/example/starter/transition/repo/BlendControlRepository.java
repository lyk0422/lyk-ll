package com.example.starter.transition.repo;

import com.example.starter.transition.model.BlendControlEvent;
import com.example.starter.transition.model.BlendControlState;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 掺配执行控制仓储：控制状态按订单唯一；失控/恢复事件留痕。
 */
@Repository
public class BlendControlRepository {

    private static final RowMapper<BlendControlState> STATE_MAPPER = (rs, n) -> {
        BlendControlState s = new BlendControlState();
        s.setOrderNo(rs.getString("order_no"));
        s.setControlStatus(rs.getString("control_status"));
        s.setCurrentVersionNo(rs.getInt("current_version_no"));
        s.setOutStreak(rs.getInt("out_streak"));
        s.setUpdatedAt(rs.getTimestamp("updated_at").toLocalDateTime());
        return s;
    };

    private static final RowMapper<BlendControlEvent> EVENT_MAPPER = (rs, n) -> {
        BlendControlEvent e = new BlendControlEvent();
        e.setId(rs.getLong("id"));
        e.setOrderNo(rs.getString("order_no"));
        e.setEventType(rs.getString("event_type"));
        e.setVersionNo(rs.getObject("version_no", Integer.class));
        e.setReviewer(rs.getString("reviewer"));
        e.setRequestId(rs.getString("request_id"));
        e.setEventTime(rs.getTimestamp("event_time").toLocalDateTime());
        return e;
    };

    private final JdbcTemplate jdbc;

    public BlendControlRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 初始化控制状态（申请携带比例时与初始版本同事务登记）。 */
    public void insertState(String orderNo, int currentVersionNo, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO blend_control_state
                            (order_no, control_status, current_version_no, out_streak, updated_at)
                        VALUES (?,?,?,0,?)
                        """,
                orderNo, BlendControlState.RUNNING, currentVersionNo, Timestamp.valueOf(now));
    }

    public Optional<BlendControlState> findState(String orderNo) {
        List<BlendControlState> list = jdbc.query(
                "SELECT * FROM blend_control_state WHERE order_no = ?", STATE_MAPPER, orderNo);
        return list.stream().findFirst();
    }

    /** 换版：当前版本号前进，连续超差计数清零。 */
    public void advanceVersion(String orderNo, int versionNo, LocalDateTime now) {
        jdbc.update("""
                        UPDATE blend_control_state
                        SET current_version_no = ?, out_streak = 0, updated_at = ?
                        WHERE order_no = ?
                        """,
                versionNo, Timestamp.valueOf(now), orderNo);
    }

    /** 更新连续超差计数（合格读数清零，超差读数 +1）。 */
    public void updateStreak(String orderNo, int outStreak, LocalDateTime now) {
        jdbc.update("""
                        UPDATE blend_control_state
                        SET out_streak = ?, updated_at = ?
                        WHERE order_no = ?
                        """,
                outStreak, Timestamp.valueOf(now), orderNo);
    }

    /** 失控暂停转人工。 */
    public void pause(String orderNo, LocalDateTime now) {
        jdbc.update("""
                        UPDATE blend_control_state
                        SET control_status = ?, updated_at = ?
                        WHERE order_no = ?
                        """,
                BlendControlState.PAUSED_MANUAL, Timestamp.valueOf(now), orderNo);
    }

    /** 复核确认恢复：状态回到执行中，连续超差计数清零。 */
    public void resume(String orderNo, LocalDateTime now) {
        jdbc.update("""
                        UPDATE blend_control_state
                        SET control_status = ?, out_streak = 0, updated_at = ?
                        WHERE order_no = ?
                        """,
                BlendControlState.RUNNING, Timestamp.valueOf(now), orderNo);
    }

    public void insertEvent(String orderNo, String eventType, Integer versionNo,
                            String reviewer, String requestId, LocalDateTime eventTime) {
        jdbc.update("""
                        INSERT INTO blend_control_event
                            (order_no, event_type, version_no, reviewer, request_id, event_time)
                        VALUES (?,?,?,?,?,?)
                        """,
                orderNo, eventType, versionNo, reviewer, requestId, Timestamp.valueOf(eventTime));
    }

    public List<BlendControlEvent> findEvents(String orderNo) {
        return jdbc.query(
                "SELECT * FROM blend_control_event WHERE order_no = ? ORDER BY id",
                EVENT_MAPPER, orderNo);
    }
}
