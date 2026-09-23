package com.example.starter.evidence.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 全局领域版本号数据访问，单行记录随任一变更递增。
 */
@Repository
public class RegistryRepository {

    private final JdbcTemplate jdbc;

    public RegistryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 读取当前领域版本号。
     */
    public long currentVersion() {
        Long version = jdbc.queryForObject("SELECT version FROM domain_state WHERE id = 1", Long.class);
        return version == null ? 0 : version;
    }

    /**
     * 递增领域版本号，应在变更事务内调用。
     */
    public void bump() {
        jdbc.update("UPDATE domain_state SET version = version + 1 WHERE id = 1");
    }
}
