package com.example.starter.repo;

import com.example.starter.domain.WaterSample;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 水质采样数据访问。
 */
@Repository
public class SampleRepository {

    private final JdbcClient jdbc;

    public SampleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 写入采样记录并返回主键。
     */
    public long insert(long zoneId, BigDecimal dissolvedOxygen, BigDecimal ammoniaNitrogen,
                       BigDecimal temperature, boolean outOfLimit, boolean evaluated,
                       LocalDateTime sampledAt, String requestId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO water_sample (zone_id, dissolved_oxygen, ammonia_nitrogen, temperature,"
                        + " out_of_limit, evaluated, sampled_at, request_id)"
                        + " VALUES (:zoneId, :do, :nh3n, :temp, :outOfLimit, :evaluated, :sampledAt, :requestId)")
                .param("zoneId", zoneId)
                .param("do", dissolvedOxygen)
                .param("nh3n", ammoniaNitrogen)
                .param("temp", temperature)
                .param("outOfLimit", outOfLimit ? 1 : 0)
                .param("evaluated", evaluated ? 1 : 0)
                .param("sampledAt", sampledAt)
                .param("requestId", requestId)
                .update(keyHolder);
        return Objects.requireNonNull(keyHolder.getKey()).longValue();
    }

    /**
     * 查询传感器缺失期间补录、尚未参与告警评估的采样，按采样时间升序。
     */
    public List<WaterSample> findUnevaluated(long zoneId) {
        return jdbc.sql("SELECT * FROM water_sample WHERE zone_id = :zoneId AND evaluated = 0"
                        + " ORDER BY sampled_at, id")
                .param("zoneId", zoneId)
                .query(WaterSample.class)
                .list();
    }

    /**
     * 将采样标记为已评估（补算完成后调用）。
     */
    public void markEvaluated(long id) {
        jdbc.sql("UPDATE water_sample SET evaluated = 1 WHERE id = :id")
                .param("id", id)
                .update();
    }
}
