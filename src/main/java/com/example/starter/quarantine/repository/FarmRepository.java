package com.example.starter.quarantine.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 养殖场仓储。{@link #lockFarm} 以 SELECT ... FOR UPDATE 持有农场行锁至事务结束，
 * 是同一养殖场上“出证/启运/落地/申报”与“封锁发布”之间的串行化点，保证封锁竞态唯一终态。
 */
@Repository
public class FarmRepository {

    private final JdbcTemplate jdbcTemplate;

    public FarmRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 锁定养殖场行并返回其是否存在。必须在事务内调用，锁持有到事务提交/回滚。
     */
    public boolean lockFarm(String farmId) {
        var rows = jdbcTemplate.queryForList(
                "SELECT farm_id FROM farm WHERE farm_id = ? FOR UPDATE", String.class, farmId);
        return !rows.isEmpty();
    }

    /** 取下一个证号序列值。 */
    public long nextCertificateSequence() {
        Long value = jdbcTemplate.queryForObject(
                "SELECT NEXT VALUE FOR cert_no_seq", Long.class);
        return value == null ? 0L : value;
    }
}
