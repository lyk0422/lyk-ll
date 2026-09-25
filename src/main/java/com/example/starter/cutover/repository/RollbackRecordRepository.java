package com.example.starter.cutover.repository;

import com.example.starter.cutover.po.RollbackRecordPO;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * 回退记录数据访问；cutover_id 上有唯一约束，保证同一割接最多一条回退指令。
 */
@Repository
public class RollbackRecordRepository {

    private static final RowMapper<RollbackRecordPO> MAPPER = (rs, rowNum) -> {
        RollbackRecordPO po = new RollbackRecordPO();
        po.setId(rs.getLong("id"));
        po.setCutoverId(rs.getLong("cutover_id"));
        po.setTriggerReason(rs.getString("trigger_reason"));
        po.setDetail(rs.getString("detail"));
        long stepId = rs.getLong("step_id");
        po.setStepId(rs.wasNull() ? null : stepId);
        po.setSnapshotJson(rs.getString("snapshot_json"));
        po.setRolledBackAt(rs.getTimestamp("rolled_back_at").toLocalDateTime());
        return po;
    };

    private final JdbcTemplate jdbcTemplate;

    public RollbackRecordRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 插入回退记录；唯一约束冲突时抛出 {@link DuplicateKeyException}，由业务层据此放弃回退。
     */
    public long insert(RollbackRecordPO po) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO rollback_record "
                            + "(cutover_id, trigger_reason, detail, step_id, snapshot_json, rolled_back_at) "
                            + "VALUES (?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, po.getCutoverId());
            ps.setString(2, po.getTriggerReason());
            ps.setString(3, po.getDetail());
            if (po.getStepId() == null) {
                ps.setNull(4, java.sql.Types.BIGINT);
            } else {
                ps.setLong(4, po.getStepId());
            }
            ps.setString(5, po.getSnapshotJson());
            ps.setTimestamp(6, Timestamp.valueOf(po.getRolledBackAt()));
            return ps;
        }, keyHolder);
        long id = keyHolder.getKey().longValue();
        po.setId(id);
        return id;
    }

    public Optional<RollbackRecordPO> findByCutoverId(long cutoverId) {
        List<RollbackRecordPO> list = jdbcTemplate.query(
                "SELECT * FROM rollback_record WHERE cutover_id = ?", MAPPER, cutoverId);
        return list.stream().findFirst();
    }
}
