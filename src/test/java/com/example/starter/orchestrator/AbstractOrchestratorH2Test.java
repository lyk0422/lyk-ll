package com.example.starter.orchestrator;

import com.example.starter.orchestrator.repo.OrchestratorRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * 编排器 H2（MODE=MySQL）集成测试基类：每个场景前清空数据避免顺序依赖。
 */
@SpringBootTest
@Import(SyncExecutorTestConfig.class)
abstract class AbstractOrchestratorH2Test {

    @Autowired
    protected OrchestratorRepository repo;

    @Autowired
    protected com.example.starter.orchestrator.service.LaunchPlanService service;

    @Autowired
    protected ObjectMapper objectMapper;

    @BeforeEach
    void cleanData() {
        repo.deleteAllData();
    }
}
