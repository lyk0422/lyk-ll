package com.example.starter.portability.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.portability.FlowState;
import com.example.starter.portability.PortabilityFlow;

import java.sql.PreparedStatement;
import java.sql.Statement;

/**
 * 携号转网流程表数据访问，全部使用参数化 SQL。
 */
@Repository
public class PortabilityFlowRepository {

    private static final RowMapper<PortabilityFlow> MAPPER = PortabilityFlowRepository::mapRow;

    private final JdbcTemplate jdbc;

    public PortabilityFlowRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入在途流程；active_msisdn 唯一约束冲突时由调用方转换为 409。
     */
    public PortabilityFlow insert(PortabilityFlow flow, String activeMsisdn) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO portability_flow (request_id, msisdn, donor_operator, recipient_operator,"
                            + " state, auth_code, auth_issued_at, auth_expires_at, active_msisdn,"
                            + " created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, flow.requestId());
            ps.setString(2, flow.msisdn());
            ps.setString(3, flow.donorOperator());
            ps.setString(4, flow.recipientOperator());
            ps.setString(5, flow.state().name());
            ps.setString(6, flow.authCode());
            ps.setTimestamp(7, Timestamp.from(flow.authIssuedAt()));
            ps.setTimestamp(8, Timestamp.from(flow.authExpiresAt()));
            ps.setString(9, activeMsisdn);
            ps.setTimestamp(10, Timestamp.from(flow.createdAt()));
            ps.setTimestamp(11, Timestamp.from(flow.updatedAt()));
            return ps;
        }, keyHolder);
        long id = keyHolder.getKey().longValue();
        return findById(id).orElseThrow();
    }

    public Optional<PortabilityFlow> findById(long id) {
        return jdbc.query("SELECT * FROM portability_flow WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 行级排他锁读取，用于确认/广播/撤销的状态流转串行化。
     */
    public Optional<PortabilityFlow> findByIdForUpdate(long id) {
        return jdbc.query("SELECT * FROM portability_flow WHERE id = ? FOR UPDATE", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 条件更新状态：仅当当前状态匹配 expected 之一时生效，返回受影响行数（0 表示竞态失败）。
     * 进入终态（EFFECTIVE/CANCELLED）时同时清空在途占位 active_msisdn，释放号码。
     */
    public int transitionState(long id, FlowState target, Instant updatedAt, FlowState... expected) {
        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < expected.length; i++) {
            placeholders.append(i == 0 ? "?" : ",?");
        }
        Object[] args = new Object[expected.length + 4];
        args[0] = target.name();
        args[1] = Timestamp.from(updatedAt);
        args[2] = target.name();
        args[3] = id;
        for (int i = 0; i < expected.length; i++) {
            args[i + 4] = expected[i].name();
        }
        return jdbc.update("UPDATE portability_flow SET state = ?, updated_at = ?,"
                        + " active_msisdn = CASE WHEN ? IN ('EFFECTIVE','CANCELLED') THEN NULL ELSE active_msisdn END"
                        + " WHERE id = ? AND state IN (" + placeholders + ")",
                args);
    }

    private static PortabilityFlow mapRow(ResultSet rs, int rowNum) throws SQLException {
        Timestamp issued = rs.getTimestamp("auth_issued_at");
        Timestamp expires = rs.getTimestamp("auth_expires_at");
        return new PortabilityFlow(
                rs.getLong("id"),
                rs.getString("request_id"),
                rs.getString("msisdn"),
                rs.getString("donor_operator"),
                rs.getString("recipient_operator"),
                FlowState.valueOf(rs.getString("state")),
                rs.getString("auth_code"),
                issued == null ? null : issued.toInstant(),
                expires == null ? null : expires.toInstant(),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}
