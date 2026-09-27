package com.example.starter.radio.repository;

import java.sql.PreparedStatement;
import java.util.Optional;

import com.example.starter.radio.model.IdempotentRequest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 幂等冻结记录数据访问。request_id 为主键，保证同一请求标识仅一条冻结结果；
 * 业务、版本与幂等结果在同一事务提交，业务失败整体回滚因而不占用 requestId。
 */
@Repository
public class IdempotentRequestRepository {

    private final JdbcTemplate jdbc;

    public IdempotentRequestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 写入冻结结果；并发同键时抛出唯一约束冲突，由调用方重读后按重放/冲突处理。
     */
    public void insert(IdempotentRequest request) {
        try {
            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO idempotent_request (request_id, fingerprint, operation,"
                                + " result_snapshot, created_at_epoch) VALUES (?, ?, ?, ?, ?)");
                ps.setString(1, request.requestId());
                ps.setString(2, request.fingerprint());
                ps.setString(3, request.operation());
                ps.setString(4, request.resultSnapshot());
                ps.setLong(5, request.createdAtEpoch());
                return ps;
            });
        } catch (DuplicateKeyException e) {
            throw e;
        }
    }

    public Optional<IdempotentRequest> findByRequestId(String requestId) {
        return jdbc.query("SELECT * FROM idempotent_request WHERE request_id = ?",
                (rs, i) -> new IdempotentRequest(
                        rs.getString("request_id"),
                        rs.getString("fingerprint"),
                        rs.getString("operation"),
                        rs.getString("result_snapshot"),
                        rs.getLong("created_at_epoch")),
                requestId).stream().findFirst();
    }
}
