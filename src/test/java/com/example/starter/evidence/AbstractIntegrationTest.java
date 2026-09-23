package com.example.starter.evidence;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.example.starter.evidence.service.EvidenceService;
import com.example.starter.evidence.service.Fingerprints;
import com.example.starter.evidence.service.HoldService;
import com.example.starter.evidence.service.PurgeService;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 集成测试基类：使用独立 H2 内存库，每个用例前清空全部业务表并重置领域版本。
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
    protected EvidenceService evidenceService;

    @Autowired
    protected HoldService holdService;

    @Autowired
    protected PurgeService purgeService;

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM purge_batch_item");
        jdbc.update("DELETE FROM purge_batch");
        jdbc.update("DELETE FROM legal_hold_item");
        jdbc.update("DELETE FROM legal_hold");
        jdbc.update("DELETE FROM custody_event");
        jdbc.update("DELETE FROM evidence");
        jdbc.update("UPDATE domain_state SET version = 0 WHERE id = 1");
    }

    /**
     * 生成合成证据摘要（非真实文件内容）。
     */
    protected String digestOf(String seed) {
        return Fingerprints.sha256("evidence-" + seed);
    }
}
