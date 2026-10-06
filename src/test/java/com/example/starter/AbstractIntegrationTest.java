package com.example.starter;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.example.starter.repo.BreachRepository;
import com.example.starter.repo.IdempotencyRepository;
import com.example.starter.repo.LockRepository;
import com.example.starter.repo.PlanRepository;
import com.example.starter.repo.ResourceRepository;

/**
 * 集成测试基类：每个用例前清理业务数据并复位时钟，避免用例间顺序依赖。
 */
@SpringBootTest
@Import(TestInfraConfiguration.class)
public abstract class AbstractIntegrationTest {

    @Autowired
    protected MutableClock clock;

    @Autowired
    protected PlanRepository planRepository;

    @Autowired
    protected LockRepository lockRepository;

    @Autowired
    protected ResourceRepository resourceRepository;

    @Autowired
    protected BreachRepository breachRepository;

    @Autowired
    protected IdempotencyRepository idempotencyRepository;

    @BeforeEach
    void resetState() {
        idempotencyRepository.deleteAll();
        breachRepository.deleteAll();
        lockRepository.deleteAll();
        resourceRepository.deleteAllPlanTugs();
        planRepository.deleteAll();
        clock.set(TestInfraConfiguration.BASE_TIME);
    }
}
