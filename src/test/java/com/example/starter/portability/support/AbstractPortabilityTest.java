package com.example.starter.portability.support;

import java.sql.Timestamp;
import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 携号转网测试基类：每个用例前清空业务表并重置可控时钟，避免用例间顺序依赖。
 */
@SpringBootTest
@Import(TestClockConfig.class)
public abstract class AbstractPortabilityTest {

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected MutableClock clock;

    @Autowired
    protected ObjectMapper objectMapper;

    @BeforeEach
    void resetState() {
        jdbc.sql("DELETE FROM portability_event").update();
        jdbc.sql("DELETE FROM portability_flow").update();
        jdbc.sql("DELETE FROM idempotency_record").update();
        jdbc.sql("DELETE FROM subscriber_profile").update();
        clock.setInstant(TestClockConfig.BASE_INSTANT);
    }

    /**
     * 写入合成用户资格档案。
     */
    protected void insertProfile(String msisdn, boolean hasArrears, boolean inContract, Instant lastPortAt) {
        jdbc.sql("INSERT INTO subscriber_profile (msisdn, has_arrears, in_contract, last_port_at, updated_at) "
                        + "VALUES (:msisdn, :arrears, :contract, :lastPortAt, CURRENT_TIMESTAMP)")
                .param("msisdn", msisdn)
                .param("arrears", hasArrears)
                .param("contract", inContract)
                .param("lastPortAt", lastPortAt == null ? null : Timestamp.from(lastPortAt))
                .update();
    }

    /**
     * 解析响应体 JSON。
     */
    protected JsonNode parseJson(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw new AssertionError("响应体不是合法 JSON：" + body, e);
        }
    }

    /**
     * 统计指定流程的某类事件数量。
     */
    protected int countEvents(long flowId, String eventType) {
        Integer count = jdbc.sql("SELECT COUNT(*) FROM portability_event "
                        + "WHERE flow_id = :flowId AND event_type = :type")
                .param("flowId", flowId)
                .param("type", eventType)
                .query(Integer.class)
                .single();
        return count;
    }

    /**
     * 查询流程当前状态字面值。
     */
    protected String flowStatus(long flowId) {
        return jdbc.sql("SELECT status FROM portability_flow WHERE id = :flowId")
                .param("flowId", flowId)
                .query(String.class)
                .single();
    }
}
