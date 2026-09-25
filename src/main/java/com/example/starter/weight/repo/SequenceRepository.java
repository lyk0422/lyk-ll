package com.example.starter.weight.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 业务编号序列仓储：案件编号与文书编号。
 */
@Repository
public class SequenceRepository {

    private final JdbcTemplate jdbc;

    public SequenceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 生成案件编号，形如 AJ0000000001。
     */
    public String nextCaseNo() {
        Long value = jdbc.queryForObject("SELECT NEXT VALUE FOR seq_case_no", Long.class);
        return String.format("AJ%010d", value);
    }

    /**
     * 生成文书编号，形如 WS0000000001。
     */
    public String nextDocNo() {
        Long value = jdbc.queryForObject("SELECT NEXT VALUE FOR seq_doc_no", Long.class);
        return String.format("WS%010d", value);
    }
}
