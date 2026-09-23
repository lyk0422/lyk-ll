package com.example.starter.trajectory.store;

import com.example.starter.trajectory.dto.ValidationResult;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.List;

/**
 * 基于 H2（MODE=MySQL）的校验请求/结果仓库。
 * 结果以规范化 JSON 文本存入 CLOB，重复读取可逐字节还原确定性结果。
 */
@Repository
public class JdbcValidationResultRepository implements ValidationResultRepository {

    private final JdbcTemplate jdbcTemplate;
    private final DeterministicHasher hasher;

    public JdbcValidationResultRepository(JdbcTemplate jdbcTemplate, DeterministicHasher hasher) {
        this.jdbcTemplate = jdbcTemplate;
        this.hasher = hasher;
    }

    @Override
    public StoredValidation findByRequestId(String requestId) {
        List<StoredValidation> rows = jdbcTemplate.query(
                "SELECT payload_hash, result_json FROM validation_record WHERE request_id = ?",
                (rs, rowNum) -> {
                    try {
                        ValidationResult result = hasher.fromResultJson(rs.getString("result_json"));
                        return new StoredValidation(rs.getString("payload_hash"), result);
                    } catch (IOException e) {
                        throw new IllegalStateException("已存结果 JSON 解析失败: " + requestId, e);
                    }
                },
                requestId);
        return rows.stream().findFirst().orElse(null);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean save(String requestId, String ruleVersion, String requestHash,
                        String requestJson, ValidationResult result) {
        try {
            jdbcTemplate.update("""
                            INSERT INTO validation_record
                                (request_id, rule_version, payload_hash, request_json, result_json)
                            VALUES (?, ?, ?, ?, ?)
                            """,
                    requestId, ruleVersion, requestHash, requestJson,
                    hasher.toResultJsonUnchecked(result));
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }
}
