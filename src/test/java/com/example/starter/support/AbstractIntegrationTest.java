package com.example.starter.support;

import com.example.starter.web.DeclareLotRequest;
import com.example.starter.web.FieldInspectionRequest;
import com.example.starter.web.LabInspectionRequest;
import com.example.starter.web.RecheckRequest;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;

/**
 * H2（MySQL 兼容模式）集成测试基类：每个用例前按外键逆序清表并复位自增序列与时钟。
 * 具体测试类自行声明 {@code @SpringBootTest}（含是否启用随机端口 Web 环境）。
 */
public abstract class AbstractIntegrationTest {

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected MutableClock clock;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM field_inspection");
        jdbcTemplate.update("DELETE FROM lab_inspection");
        jdbcTemplate.update("DELETE FROM certificate");
        jdbcTemplate.update("DELETE FROM recheck_application");
        jdbcTemplate.update("DELETE FROM idempotent_request");
        jdbcTemplate.update("DELETE FROM lot_event");
        jdbcTemplate.update("DELETE FROM seed_lot");
        jdbcTemplate.update("ALTER TABLE seed_lot ALTER COLUMN id RESTART WITH 1");
        clock.setInstant(TestClockConfig.BASE_TIME);
    }

    /** 统计指定表满足等值条件的行数。 */
    protected int count(String table, String whereClause, Object... args) {
        Integer value = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + whereClause, Integer.class, args);
        return value == null ? 0 : value;
    }

    public static DeclareLotRequest declareRequest(String requestId, String lotNo, int labels) {
        return new DeclareLotRequest(requestId, lotNo, "绿农种业有限公司", "郑单 958（合成）",
                "T-0001（合成地块）", new BigDecimal("120.50"), labels);
    }

    public static FieldInspectionRequest fieldRequest(String requestId, String lotNo, String purity) {
        return new FieldInspectionRequest(requestId, lotNo, new BigDecimal(purity),
                "田间检验员甲", null);
    }

    public static LabInspectionRequest labRequest(String requestId, String lotNo, String rate) {
        return new LabInspectionRequest(requestId, lotNo, new BigDecimal(rate),
                "室内检测员乙", null);
    }

    public static RecheckRequest recheckRequest(String requestId, String lotNo) {
        return new RecheckRequest(requestId, lotNo);
    }
}
