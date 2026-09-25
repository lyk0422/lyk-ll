package com.example.starter.reinsurance.testsupport;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 测试数据清理：按外键依赖反序清空全部业务表，保证场景间无顺序依赖。
 */
public final class TestDataCleaner {

    private static final String[] TABLES_IN_DELETE_ORDER = {
            "bill_dispute",
            "bill_item",
            "bill",
            "account_ledger",
            "claim_event",
            "claim_recovery",
            "claim",
            "reinsurance_account",
            "policy",
            "treaty",
            "idempotency_record"
    };

    private TestDataCleaner() {
    }

    public static void clean(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY FALSE");
        try {
            for (String table : TABLES_IN_DELETE_ORDER) {
                jdbcTemplate.update("DELETE FROM " + table);
            }
        } finally {
            jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY TRUE");
        }
    }
}
