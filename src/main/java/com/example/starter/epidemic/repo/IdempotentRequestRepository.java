package com.example.starter.epidemic.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * requestId 幂等记录仓储。并发同键由主键约束兜底。
 */
@Repository
public class IdempotentRequestRepository {

    private final JdbcTemplate jdbc;

    public IdempotentRequestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String COLUMNS =
            "request_id, request_type, fingerprint, result_kind, card_number, version_no, created_at";

    private static final RowMapper<IdempotentRequestPo> MAPPER = (rs, n) -> {
        IdempotentRequestPo po = new IdempotentRequestPo();
        po.requestId = rs.getString("request_id");
        po.requestType = rs.getString("request_type");
        po.fingerprint = rs.getString("fingerprint");
        po.resultKind = rs.getString("result_kind");
        po.cardNumber = rs.getString("card_number");
        po.versionNo = rs.getInt("version_no");
        po.createdAt = rs.getObject("created_at", java.time.LocalDateTime.class);
        return po;
    };

    public Optional<IdempotentRequestPo> find(String requestId) {
        List<IdempotentRequestPo> list = jdbc.query(
                "SELECT " + COLUMNS + " FROM idempotent_request WHERE request_id = ?",
                MAPPER, requestId);
        return list.stream().findFirst();
    }

    public void insert(IdempotentRequestPo po) {
        jdbc.update("INSERT INTO idempotent_request "
                        + "(request_id, request_type, fingerprint, result_kind, card_number, version_no, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                po.requestId, po.requestType, po.fingerprint, po.resultKind,
                po.cardNumber, po.versionNo, po.createdAt);
    }
}
