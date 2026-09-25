package com.example.starter.cutover.repository;

import com.example.starter.cutover.domain.StepStatus;
import com.example.starter.cutover.po.StepPO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 割接步骤数据访问。
 */
@Repository
public class StepRepository {

    private static final RowMapper<StepPO> MAPPER = (rs, rowNum) -> {
        StepPO po = new StepPO();
        po.setId(rs.getLong("id"));
        po.setCutoverId(rs.getLong("cutover_id"));
        po.setSeq(rs.getInt("seq"));
        po.setName(rs.getString("name"));
        po.setStepType(rs.getString("step_type"));
        po.setStatus(rs.getString("status"));
        Timestamp started = rs.getTimestamp("started_at");
        po.setStartedAt(started == null ? null : started.toLocalDateTime());
        Timestamp completed = rs.getTimestamp("completed_at");
        po.setCompletedAt(completed == null ? null : completed.toLocalDateTime());
        return po;
    };

    private final JdbcTemplate jdbcTemplate;

    public StepRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long insert(StepPO po) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO cutover_step "
                            + "(cutover_id, seq, name, step_type, status, started_at, completed_at) "
                            + "VALUES (?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, po.getCutoverId());
            ps.setInt(2, po.getSeq());
            ps.setString(3, po.getName());
            ps.setString(4, po.getStepType());
            ps.setString(5, po.getStatus());
            ps.setTimestamp(6, po.getStartedAt() == null ? null : Timestamp.valueOf(po.getStartedAt()));
            ps.setTimestamp(7, po.getCompletedAt() == null ? null : Timestamp.valueOf(po.getCompletedAt()));
            return ps;
        }, keyHolder);
        long id = keyHolder.getKey().longValue();
        po.setId(id);
        return id;
    }

    public List<StepPO> findByCutoverId(long cutoverId) {
        return jdbcTemplate.query(
                "SELECT * FROM cutover_step WHERE cutover_id = ? ORDER BY seq",
                MAPPER, cutoverId);
    }

    /**
     * 条件更新单步骤状态，返回是否命中；用于回报与冻结之间的行级竞争。
     */
    public boolean compareAndUpdateStatus(long stepId, List<StepStatus> expected, StepStatus target,
                                          LocalDateTime startedAt, LocalDateTime completedAt) {
        String placeholders = String.join(",", expected.stream().map(s -> "?").toList());
        StringBuilder sql = new StringBuilder("UPDATE cutover_step SET status = ?");
        if (startedAt != null) {
            sql.append(", started_at = ?");
        }
        if (completedAt != null) {
            sql.append(", completed_at = ?");
        }
        sql.append(" WHERE id = ? AND status IN (").append(placeholders).append(")");
        List<Object> params = new java.util.ArrayList<>();
        params.add(target.name());
        if (startedAt != null) {
            params.add(Timestamp.valueOf(startedAt));
        }
        if (completedAt != null) {
            params.add(Timestamp.valueOf(completedAt));
        }
        params.add(stepId);
        expected.forEach(s -> params.add(s.name()));
        return jdbcTemplate.update(sql.toString(), params.toArray()) == 1;
    }

    /**
     * 将割接下全部未完成步骤（PENDING/RUNNING）冻结为 FROZEN，返回受影响行数。
     */
    public int freezeUnfinished(long cutoverId) {
        return jdbcTemplate.update(
                "UPDATE cutover_step SET status = 'FROZEN' "
                        + "WHERE cutover_id = ? AND status IN ('PENDING','RUNNING')",
                cutoverId);
    }
}
