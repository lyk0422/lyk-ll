package com.example.starter.repo;

import java.math.BigDecimal;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 频率级并发守卫：在事务内对频率行 SELECT FOR UPDATE，
 * 串行化同一频率的干扰事件创建，保证恰第 3 个投诉只触发一个事件。
 */
@Repository
public class FrequencyGuardRepository {

    private final JdbcTemplate jdbc;

    public FrequencyGuardRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 在当前事务内锁定指定频率的守卫行（不存在则先创建）。
     *
     * @param frequencyMhz 频率，单位 MHz
     */
    public void lock(BigDecimal frequencyMhz) {
        try {
            jdbc.update("INSERT INTO frequency_guard (frequency_mhz) VALUES (?)", frequencyMhz);
        } catch (DuplicateKeyException ignored) {
            // 守卫行已存在，直接进入加锁
        }
        jdbc.queryForObject("SELECT frequency_mhz FROM frequency_guard WHERE frequency_mhz = ? FOR UPDATE",
                BigDecimal.class, frequencyMhz);
    }
}
