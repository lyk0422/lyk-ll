package com.example.starter.trajectory.persistence;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 校验请求与结果的幂等记录仓储。
 */
@Repository
public class ValidationRecordRepository {

    private static final RowMapper<StoredValidation> ROW_MAPPER = (rs, rowNum) -> new StoredValidation(
            rs.getString("request_id"),
            rs.getString("rule_version"),
            rs.getString("request_hash"),
            rs.getString("result_json"));

    private final JdbcTemplate jdbcTemplate;

    public ValidationRecordRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<StoredValidation> findByRequestId(String requestId) {
        try {
            StoredValidation record = jdbcTemplate.queryForObject(
                    "SELECT request_id, rule_version, request_hash, result_json "
                            + "FROM validation_record WHERE request_id = ?",
                    ROW_MAPPER, requestId);
            return Optional.ofNullable(record);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * 插入幂等记录；request_id 主键约束保证并发下只有一次插入成功。
     *
     * @return 是否插入成功（false 表示 requestId 已存在）
     */
    public boolean insert(StoredValidation record) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO validation_record (request_id, rule_version, request_hash, result_json) "
                            + "VALUES (?, ?, ?, ?)",
                    record.requestId(),
                    record.ruleVersion(),
                    record.requestHash(),
                    record.resultJson());
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }
}
