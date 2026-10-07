package com.example.starter.repository;

import com.example.starter.domain.CoupledAdjustment;
import com.example.starter.domain.CoupledAdjustmentItem;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * 联调单及逐阀明细数据访问。
 */
@Repository
public class CoupledAdjustmentRepository {

    private static final RowMapper<CoupledAdjustment> MAPPER = (rs, n) -> new CoupledAdjustment(
            rs.getLong("id"), rs.getLong("group_id"), rs.getLong("station_id"),
            rs.getString("request_id"), rs.getString("fingerprint"),
            rs.getTimestamp("adjust_at").toInstant(),
            rs.getTimestamp("executed_at").toInstant(),
            rs.getInt("version"), rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<CoupledAdjustmentItem> ITEM_MAPPER = (rs, n) -> new CoupledAdjustmentItem(
            rs.getLong("id"), rs.getLong("coupled_id"), rs.getLong("valve_id"),
            rs.getInt("weight"), rs.getBigDecimal("old_opening"), rs.getBigDecimal("new_opening"),
            Optional.ofNullable(rs.getObject("unseal_id", Long.class)).orElse(null),
            Optional.ofNullable(rs.getObject("task_id", Long.class)).orElse(null));

    private static final String COLUMNS = "id, group_id, station_id, request_id, fingerprint,"
            + " adjust_at, executed_at, version, created_at";

    private final JdbcTemplate jdbc;

    public CoupledAdjustmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入联调单并返回生成的主键；与开度、版本、额度消费在同一事务内调用。
     */
    public long insert(CoupledAdjustment adjustment) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("INSERT INTO coupled_adjustment"
                            + " (group_id, station_id, request_id, fingerprint,"
                            + " adjust_at, executed_at, version, created_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, adjustment.groupId());
            ps.setLong(2, adjustment.stationId());
            ps.setString(3, adjustment.requestId());
            ps.setString(4, adjustment.fingerprint());
            ps.setTimestamp(5, Timestamp.from(adjustment.adjustAt()));
            ps.setTimestamp(6, Timestamp.from(adjustment.executedAt()));
            ps.setInt(7, adjustment.version());
            ps.setTimestamp(8, Timestamp.from(adjustment.createdAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按 ID 查询联调单。
     */
    public Optional<CoupledAdjustment> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM coupled_adjustment WHERE id = ?",
                MAPPER, id).stream().findFirst();
    }

    /**
     * 按幂等键查询联调单。
     */
    public Optional<CoupledAdjustment> findByRequestId(String requestId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM coupled_adjustment WHERE request_id = ?",
                MAPPER, requestId).stream().findFirst();
    }

    /**
     * 追加逐阀明细；与联调单在同一事务内调用。
     */
    public void insertItem(long coupledId, long valveId, int weight,
                           BigDecimal oldOpening, BigDecimal newOpening,
                           Long unsealId, Long taskId) {
        jdbc.update("INSERT INTO coupled_adjustment_item"
                        + " (coupled_id, valve_id, weight, old_opening, new_opening, unseal_id, task_id)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                coupledId, valveId, weight, oldOpening, newOpening, unsealId, taskId);
    }

    /**
     * 查询联调单全部逐阀明细，按阀门 ID 升序。
     */
    public List<CoupledAdjustmentItem> findItems(long coupledId) {
        return jdbc.query("SELECT id, coupled_id, valve_id, weight, old_opening, new_opening,"
                + " unseal_id, task_id FROM coupled_adjustment_item"
                + " WHERE coupled_id = ? ORDER BY valve_id", ITEM_MAPPER, coupledId);
    }

    /**
     * 查询联调组全部联调单，按主键升序。
     */
    public List<CoupledAdjustment> findByGroupId(long groupId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM coupled_adjustment"
                + " WHERE group_id = ? ORDER BY id", MAPPER, groupId);
    }
}
