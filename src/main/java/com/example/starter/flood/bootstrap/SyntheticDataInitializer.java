package com.example.starter.flood.bootstrap;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 合成基础数据初始化：仅在库为空时写入一个示例尾矿库及库容曲线，
 * 保证本地启动后即可走通“预警→演算→方案→指令→归档”全流程。
 * 所有数据均为合成数据，随 H2 内存库在 JVM 退出后消失。
 */
@Component
public class SyntheticDataInitializer {

    /** 示例尾矿库编码（合成）。 */
    public static final String DEMO_POND_CODE = "TP-DEMO-01";

    private final JdbcTemplate jdbcTemplate;

    public SyntheticDataInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @jakarta.annotation.PostConstruct
    public void init() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM pond", Integer.class);
        if (count != null && count > 0) {
            return;
        }
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update(
                "INSERT INTO pond(pond_code, pond_name, downstream_safe_release, "
                        + "dry_beach_limit, phreatic_limit, created_at) VALUES (?,?,?,?,?,?)",
                DEMO_POND_CODE, "合成一号尾矿库", new BigDecimal("20.000"),
                new BigDecimal("100.000"), new BigDecimal("8.000"), now);
        Long pondId = jdbcTemplate.queryForObject(
                "SELECT id FROM pond WHERE pond_code = ?", Long.class, DEMO_POND_CODE);

        // 库容曲线（水位标高 m -> 蓄水量 m3），相邻点线性插值
        Object[][] curve = {
                {new BigDecimal("180.0000"), new BigDecimal("0.0000")},
                {new BigDecimal("190.0000"), new BigDecimal("300000.0000")},
                {new BigDecimal("200.0000"), new BigDecimal("600000.0000")},
                {new BigDecimal("210.0000"), new BigDecimal("1000000.0000")}
        };
        for (Object[] point : curve) {
            jdbcTemplate.update(
                    "INSERT INTO storage_curve_point(pond_id, water_level, storage) VALUES (?,?,?)",
                    pondId, point[0], point[1]);
        }
    }
}
