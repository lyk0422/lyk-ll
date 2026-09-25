package com.example.starter.bonded.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 企业数据访问；frozen=1 表示企业因存在超时异常单而被冻结（新申请拒绝、在途单冻结）。
 */
@Repository
public class EnterpriseRepository {

    private final JdbcTemplate jdbc;

    public EnterpriseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 企业是否存在。
     */
    public boolean exists(String enterpriseId) {
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM enterprise WHERE id = ?", Integer.class, enterpriseId);
        return cnt != null && cnt > 0;
    }

    /**
     * 企业是否被冻结，不存在时返回 false。
     */
    public boolean isFrozen(String enterpriseId) {
        List<Integer> frozen = jdbc.queryForList(
                "SELECT frozen FROM enterprise WHERE id = ?", Integer.class, enterpriseId);
        return !frozen.isEmpty() && frozen.get(0) != null && frozen.get(0) == 1;
    }

    /**
     * 设置冻结标记，返回受影响行数。
     */
    public int setFrozen(String enterpriseId, boolean frozen) {
        return jdbc.update("UPDATE enterprise SET frozen = ? WHERE id = ?",
                frozen ? 1 : 0, enterpriseId);
    }
}
