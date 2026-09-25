package com.example.starter.irrigation.repo;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 实供计量与上报事件仓储。
 *
 * <p>去重边界：{@code irr_metering} 以“斗口＋轮次”为唯一键保存最后一次已确认累计读数；
 * {@code irr_meter_event} 以 {@code eventId} 去重，重复事件不重复累计。
 */
@Repository
public class MeteringRepository {

    /** 计量快照行，水量单位立方米。 */
    public record MeterRow(long turnNo, String groupCode, String outletCode,
                           long deliveredCubicMeter, int eventCount, Instant confirmedAt) {
    }

    /** 计量事件行。 */
    public record MeterEventRow(String eventId, long turnNo, String groupCode,
                                String outletCode, long deliveredCubicMeter) {
    }

    private final JdbcTemplate jdbc;

    public MeteringRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 对轮灌组主记录加行锁（SELECT ... FOR UPDATE）。计量、加购与封存事务均先取此锁，
     * 保证同组事务串行化，封存所见即为该轮最后一次已确认计量快照。
     */
    public void lockGroup(String groupCode) {
        jdbc.queryForObject("SELECT group_code FROM irr_group WHERE group_code = ? FOR UPDATE",
                String.class, groupCode);
    }

    /** 按事件幂等键查事件。 */
    public Optional<MeterEventRow> findEvent(String eventId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT event_id, turn_no, group_code, outlet_code, delivered_cubic_meter "
                            + "FROM irr_meter_event WHERE event_id = ?",
                    EVENT_MAPPER, eventId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** 登记计量事件（eventId 唯一索引兜底并发重复）。 */
    public void insertEvent(MeterEventRow event, Instant now) {
        jdbc.update("""
                INSERT INTO irr_meter_event
                    (event_id, turn_no, group_code, outlet_code, delivered_cubic_meter, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, event.eventId(), event.turnNo(), event.groupCode(), event.outletCode(),
                event.deliveredCubicMeter(), Timestamp.from(now));
    }

    /** 查斗口＋轮次计量快照。 */
    public Optional<MeterRow> findSnapshot(long turnNo, String outletCode) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT turn_no, group_code, outlet_code, delivered_cubic_meter, event_count, confirmed_at
                    FROM irr_metering WHERE turn_no = ? AND outlet_code = ?
                    """, METER_MAPPER, turnNo, outletCode));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** 查某轮某组全部斗口计量快照，按斗口编号升序。 */
    public List<MeterRow> findByTurnAndGroup(long turnNo, String groupCode) {
        return jdbc.query("""
                SELECT turn_no, group_code, outlet_code, delivered_cubic_meter, event_count, confirmed_at
                FROM irr_metering WHERE turn_no = ? AND group_code = ? ORDER BY outlet_code
                """, METER_MAPPER, turnNo, groupCode);
    }

    /** 首次确认：建立斗口＋轮次快照。 */
    public void insertSnapshot(long turnNo, String groupCode, String outletCode,
                               long deliveredCubicMeter, Instant now) {
        jdbc.update("""
                INSERT INTO irr_metering
                    (turn_no, group_code, outlet_code, delivered_cubic_meter,
                     event_count, confirmed_at, created_at)
                VALUES (?, ?, ?, ?, 1, ?, ?)
                """, turnNo, groupCode, outletCode, deliveredCubicMeter,
                Timestamp.from(now), Timestamp.from(now));
    }

    /** 后续确认：以新累计读数覆盖旧读数，事件计数加一。 */
    public void updateSnapshot(long turnNo, String outletCode, long deliveredCubicMeter, Instant now) {
        jdbc.update("""
                UPDATE irr_metering
                SET delivered_cubic_meter = ?, event_count = event_count + 1, confirmed_at = ?
                WHERE turn_no = ? AND outlet_code = ?
                """, deliveredCubicMeter, Timestamp.from(now), turnNo, outletCode);
    }

    /**
     * 加购补供落账：补供水视同已确认供水累加。若斗口本轮从未上报计量（无快照行），
     * 则建立仅有补供量的快照（计量事件计数为 0，因为加购不是计量上报事件）。
     */
    public void addSupplementalDelivery(long turnNo, String groupCode, String outletCode,
                                        long amount, Instant now) {
        int updated = jdbc.update("""
                UPDATE irr_metering
                SET delivered_cubic_meter = delivered_cubic_meter + ?, confirmed_at = ?
                WHERE turn_no = ? AND outlet_code = ?
                """, amount, Timestamp.from(now), turnNo, outletCode);
        if (updated == 0) {
            jdbc.update("""
                    INSERT INTO irr_metering
                        (turn_no, group_code, outlet_code, delivered_cubic_meter,
                         event_count, confirmed_at, created_at)
                    VALUES (?, ?, ?, ?, 0, ?, ?)
                    """, turnNo, groupCode, outletCode, amount,
                    Timestamp.from(now), Timestamp.from(now));
        }
    }

    /** 登记迟到计量事件（封存终态后到达，仅登记不改结转）。 */
    public void insertLateEvent(long turnNo, String groupCode, String outletCode,
                                String eventId, long deliveredCubicMeter, Instant now) {
        jdbc.update("""
                INSERT INTO irr_late_meter_event
                    (turn_no, group_code, outlet_code, event_id, delivered_cubic_meter, reason, received_at)
                VALUES (?, ?, ?, ?, ?, 'AFTER_SEALED', ?)
                """, turnNo, groupCode, outletCode, eventId, deliveredCubicMeter, Timestamp.from(now));
    }

    /** 迟到事件是否已登记（重复封存后迟到上报仍按事件幂等）。 */
    public boolean existsLateEvent(String eventId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM irr_late_meter_event WHERE event_id = ?", Integer.class, eventId);
        return count != null && count > 0;
    }

    private static final RowMapper<MeterRow> METER_MAPPER = (ResultSet rs, int rowNum) -> new MeterRow(
            rs.getLong("turn_no"),
            rs.getString("group_code"),
            rs.getString("outlet_code"),
            rs.getLong("delivered_cubic_meter"),
            rs.getInt("event_count"),
            rs.getTimestamp("confirmed_at").toInstant());

    private static final RowMapper<MeterEventRow> EVENT_MAPPER = (ResultSet rs, int rowNum) -> new MeterEventRow(
            rs.getString("event_id"),
            rs.getLong("turn_no"),
            rs.getString("group_code"),
            rs.getString("outlet_code"),
            rs.getLong("delivered_cubic_meter"));
}
