package com.example.starter.fishery.db.support;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.sql.PreparedStatement;
import java.sql.Statement;

/**
 * 插入并取回数据库生成的自增主键（H2 2.x 无 IDENTITY() 函数，统一用 KeyHolder）。
 */
public final class GeneratedKeys {

    private GeneratedKeys() {
    }

    /** 执行插入并返回自增主键；失败抛 IllegalStateException。 */
    public static Number insertAndReturnKey(JdbcTemplate jdbc, String sql, Object... args) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("插入未返回自增主键: " + sql);
        }
        return key;
    }
}
