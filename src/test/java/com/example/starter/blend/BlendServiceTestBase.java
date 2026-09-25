package com.example.starter.blend;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;

/**
 * 调合服务测试基类：每个用例前清理业务数据并重建合成夹具，避免用例间顺序依赖。
 * 合成组分罐：1号(辛烷95/硫5/蒸气压60/余量1000)、2号(89/12/70/1000)、3号(97/3/55/500)；
 * 调合罐1、2；规格GAS92上限(92/10/65)，安全边际(0.3/0.5/1.0)。
 */
@Import(TestClockConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class BlendServiceTestBase {

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected BlendService service;

    @Autowired
    protected BlendRepository repository;

    @BeforeEach
    void resetData() {
        jdbc.update("DELETE FROM late_event");
        jdbc.update("DELETE FROM lab_sample");
        jdbc.update("DELETE FROM blend_batch_component");
        jdbc.update("DELETE FROM blend_batch");
        jdbc.update("DELETE FROM blend_tank_lock");
        jdbc.update("DELETE FROM blend_order");
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM component_tank");
        jdbc.update("DELETE FROM blend_tank");
        jdbc.update("DELETE FROM product_spec");
        jdbc.update("INSERT INTO component_tank (id, code, name, remaining_volume, octane, sulfur, "
                + "vapor_pressure) VALUES (1,'T-A','高辛烷组分罐',1000.000,95.000,5.000,60.000)");
        jdbc.update("INSERT INTO component_tank (id, code, name, remaining_volume, octane, sulfur, "
                + "vapor_pressure) VALUES (2,'T-B','常规组分罐',1000.000,89.000,12.000,70.000)");
        jdbc.update("INSERT INTO component_tank (id, code, name, remaining_volume, octane, sulfur, "
                + "vapor_pressure) VALUES (3,'T-C','低硫组分罐',500.000,97.000,3.000,55.000)");
        jdbc.update("INSERT INTO blend_tank (id, code, name) VALUES (1,'B1','1号调合罐')");
        jdbc.update("INSERT INTO blend_tank (id, code, name) VALUES (2,'B2','2号调合罐')");
        jdbc.update("INSERT INTO product_spec (id, product_code, octane_limit, sulfur_limit, "
                + "vapor_pressure_limit, octane_margin, sulfur_margin, vapor_pressure_margin) "
                + "VALUES (1,'GAS92',92.000,10.000,65.000,0.300,0.500,1.000)");
    }

    @AfterAll
    void releaseDatabase() {
        // 命名内存库开启延迟关闭，测试上下文结束后显式释放对应数据库
        jdbc.execute("DROP ALL OBJECTS");
    }

    protected BigDecimal remaining(long tankId) {
        return repository.componentRemainingVolume(tankId);
    }

    protected long countRows(String table) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return count == null ? 0 : count;
    }
}
