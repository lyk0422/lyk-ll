package com.example.starter;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 使用默认（生产）application.yaml 启动上下文：嵌入式 H2、自动建表与合成数据、
 * 超时扫描定时任务均随上下文启动，验证无外部 MySQL/Docker 的本地启动入口。
 */
@SpringBootTest
class StarterApplicationContextTest {

    @Test
    void contextLoadsWithEmbeddedH2() {
        // 上下文成功启动即验证默认 H2 数据源、schema.sql/data.sql 初始化通过
    }
}
