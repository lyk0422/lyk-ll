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
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 联调记录及逐阀明细数据访问。
 */
@Repository
public class CoupledAdjustmentRepository {

    private static final RowMapper<CoupledAdjustment> MAPPER = (rs, n) -> new CoupledAdjustment(
            rs.getLong("id"), rs.getLong("group_id"), rs.getLong("station_id"),
            rs.getInt("group_version"), rs.getTimestamp("requested_at").toInstant(),
            rs.getString("request_id"), rs.getString("fingerprint"),
            rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<CoupledAdjustmentItem> ITEM_MAPPER = (rs, n) ->
            new CoupledAdjustmentItem(rs.getLong("id"), rs.getLong("coupled_id"),
                    rs.getLong("valve_id"),
                    Optional.ofNullable(rs.getObject("unseal_id", Long.class)).orElse(null),
                    Optional.ofNullable(rs.getObject("task_id", Long.class)).orElse(null),
                    rs.getInt("weight"), rs.getBigDecimal("old_opening"),
                    rs.getBigDecimal("new_opening"), rs.getTimestamp("created_at").toInstant());

    private static final String COLUMNS = "id, group_id, station_id, group_version, requested_at,"
            + " request_id, fingerprint, created_at";

    private static final String ITEM_COLUMNS = "id, coupled_id, valve_id, unseal_id, task_id,"
            + " weight, old_opening, new_opening, created_at";

    private final JdbcTemplate jdbc;

    public CoupledAdjustmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入联调记录并返回生成的主键。
     */
    public long insert(CoupledAdjustment coupled) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("INSERT INTO coupled_adjustment"
                            + " (group_id, station_id, group_version, requested_at,"
                            + " request_id, fingerprint, created_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, coupled.groupId());
            ps.setLong(2, coupled.stationId());
            ps.setInt(3, coupled.groupVersion());
            ps.setTimestamp(4, Timestamp.from(coupled.requestedAt()));
            ps.setString(5, coupled.requestId());
            ps.setString(6, coupled.fingerprint());
            ps.setTimestamp(7, Timestamp.from(coupled.createdAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 插入逐阀明细；必须与联调记录在同一事务内调用。
     */
    public void insertItem(long coupledId, long valveId, Long unsealId, Long taskId, int weight,
                           BigDecimal oldOpening, BigDecimal newOpening, Instant createdAt) {
        jdbc.update("INSERT INTO coupled_adjustment_item"
                        + " (coupled_id, valve_id, unseal_id, task_id, weight,"
                        + " old_opening, new_opening, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                coupledId, valveId, unsealId, taskId, weight,
                oldOpening, newOpening, Timestamp.from(createdAt));
    }

    /**
     * 按 ID 查询联调记录。
     */
    public Optional<CoupledAdjustment> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM coupled_adjustment WHERE id = ?",
                MAPPER, id).stream().findFirst();
    }

    /**
     * 按幂等键查询联调记录。
     */
    public Optional<CoupledAdjustment> findByRequestId(String requestId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM coupled_adjustment WHERE request_id = ?",
                MAPPER, requestId).stream().findFirst();
    }

    /**
     * 查询联调组的全部联调记录，按主键升序。
     */
    public List<CoupledAdjustment> findByGroupId(long groupId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM coupled_adjustment"
                + " WHERE group_id = ? ORDER BY id", MAPPER, groupId);
    }

    /**
     * 查询联调记录的逐阀明细，按阀门 ID 升序。
     */
    public List<CoupledAdjustmentItem> findItemsByCoupledId(long coupledId) {
        return jdbc.query("SELECT " + ITEM_COLUMNS + " FROM coupled_adjustment_item"
                + " WHERE coupled_id = ? ORDER BY valve_id", ITEM_MAPPER, coupledId);
    }
}
