package com.example.starter.feed.repo;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * requestId 幂等仓储。首次请求落库（request_id 主键）；
 * 同键同参返回首次响应快照，同键异参由服务层判定 409。
 */
@Repository
public class IdempotentRepository {

    private final JdbcTemplate jdbc;

    public IdempotentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 幂等记录行。 */
    public record IdempotentRow(String requestId, String requestKind, String fingerprint,
                                int httpStatus, String responseJson) {
    }

    private static final RowMapper<IdempotentRow> MAPPER = (rs, n) -> new IdempotentRow(
            rs.getString("request_id"),
            rs.getString("request_kind"),
            rs.getString("fingerprint"),
            rs.getInt("http_status"),
            rs.getString("response_json")
    );

    public Optional<IdempotentRow> find(String requestId) {
        return jdbc.query("SELECT * FROM idempotent_request WHERE request_id = ?", MAPPER, requestId)
                .stream().findFirst();
    }

    /**
     * 落库首次结果；若并发下主键已存在则返回 false（由调用方重读返回首次结果）。
     */
    public boolean insertIfAbsent(String requestId, String requestKind, String fingerprint,
                                  int httpStatus, String responseJson) {
        try {
            jdbc.update("INSERT INTO idempotent_request (request_id, request_kind, fingerprint, http_status, response_json) "
                            + "VALUES (?,?,?,?,?)",
                    requestId, requestKind, fingerprint, httpStatus, responseJson);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }
}
