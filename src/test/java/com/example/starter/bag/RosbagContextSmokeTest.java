package com.example.starter.bag;

import com.example.starter.bag.repository.SegmentRepository;
import com.example.starter.bag.service.RosbagStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 最小 Spring + H2 冒烟测试：验证上下文启动、H2 建表脚本与仓储装配。
 */
@SpringBootTest
class RosbagContextSmokeTest {

    @Autowired
    private RosbagStorageService service;

    @Autowired
    private SegmentRepository segmentRepository;

    @Test
    void contextAndSchemaLoad() {
        assertNotNull(service);
        assertNotNull(segmentRepository);
    }
}
