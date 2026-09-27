package com.example.starter;

import com.example.starter.dao.AlertRepository;
import com.example.starter.dao.DiveEventRepository;
import com.example.starter.dao.DiveRepository;
import com.example.starter.dao.PlanRepository;
import com.example.starter.dao.RecordRepository;
import com.example.starter.service.DiveService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 集成测试基类：每个用例前清空业务表（保留合成潜水员与减压表种子），消除顺序依赖。
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class AbstractIntegrationTest {

    @Autowired
    protected MockMvc mockMvc;
    @Autowired
    protected ObjectMapper objectMapper;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected DiveRepository diveRepository;
    @Autowired
    protected PlanRepository planRepository;
    @Autowired
    protected RecordRepository recordRepository;
    @Autowired
    protected AlertRepository alertRepository;
    @Autowired
    protected DiveEventRepository eventRepository;
    @Autowired
    protected DiveService diveService;
    @Autowired
    protected com.example.starter.service.ResidualService residualService;

    @BeforeEach
    void cleanBusinessTables() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM dive_event");
        jdbc.update("DELETE FROM speed_alert");
        jdbc.update("DELETE FROM bottom_time_ledger");
        jdbc.update("DELETE FROM dive_record");
        jdbc.update("DELETE FROM dive_plan");
        jdbc.update("DELETE FROM residual_point_movement");
        jdbc.update("DELETE FROM residual_plan_snapshot");
        jdbc.update("DELETE FROM diver_active_slot");
        jdbc.update("DELETE FROM residual_point_account");
        jdbc.update("DELETE FROM dive_record_correction");
        jdbc.update("DELETE FROM dive");
    }
}
