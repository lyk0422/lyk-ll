package com.example.starter.repo;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 恢复复测记录数据访问。
 */
@Repository
public class RetestRepository {

    private final JdbcClient jdbc;

    public RetestRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 写入复测记录并返回主键。
     */
    public long insert(long zoneId, BigDecimal dissolvedOxygen, BigDecimal ammoniaNitrogen,
                       BigDecimal temperature, boolean qualified, LocalDateTime testedAt, String requestId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO retest_record (zone_id, dissolved_oxygen, ammonia_nitrogen, temperature,"
                        + " qualified, tested_at, request_id)"
                        + " VALUES (:zoneId, :do, :nh3n, :temp, :qualified, :testedAt, :requestId)")
                .param("zoneId", zoneId)
                .param("do", dissolvedOxygen)
                .param("nh3n", ammoniaNitrogen)
                .param("temp", temperature)
                .param("qualified", qualified ? 1 : 0)
                .param("testedAt", testedAt)
                .param("requestId", requestId)
                .update(keyHolder);
        return Objects.requireNonNull(keyHolder.getKey()).longValue();
    }

    /**
     * 统计某池复测记录数，供测试断言。
     */
    public int countByZone(long zoneId) {
        Long count = jdbc.sql("SELECT COUNT(*) FROM retest_record WHERE zone_id = :zoneId")
                .param("zoneId", zoneId)
                .query(Long.class)
                .single();
        return count.intValue();
    }
}
