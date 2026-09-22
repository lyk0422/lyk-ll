package com.example.starter.ros;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * H2(MODE=MySQL)集成测试基类:使用独立命名内存库 ros_registry_test,
 * 每个测试方法在事务中清理业务表,避免顺序依赖。
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {
}
