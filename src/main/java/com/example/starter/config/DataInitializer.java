package com.example.starter.config;

import com.example.starter.segment.SegmentDtos.CreateSegmentRequest;
import com.example.starter.segment.SegmentDtos.SegmentResponse;
import com.example.starter.segment.SegmentRepository;
import com.example.starter.segment.SegmentService;
import com.example.starter.tree.TreeDtos.RegisterTreeRequest;
import com.example.starter.tree.TreeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 合成数据初始化：仅在空库且 app.seed.enabled=true 时执行，
 * 覆盖一至四级风险树木，便于本地启动后直接演示评级到修剪闭环流程。
 */
@Component
@ConditionalOnProperty(name = "app.seed.enabled", havingValue = "true")
public class DataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final SegmentService segmentService;
    private final SegmentRepository segmentRepository;
    private final TreeService treeService;

    public DataInitializer(SegmentService segmentService, SegmentRepository segmentRepository,
            TreeService treeService) {
        this.segmentService = segmentService;
        this.segmentRepository = segmentRepository;
        this.treeService = treeService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!segmentRepository.listAll().isEmpty()) {
            return;
        }
        SegmentResponse seg1 = segmentService.create(new CreateSegmentRequest("seed-seg-1", "中山路"));
        SegmentResponse seg2 = segmentService.create(new CreateSegmentRequest("seed-seg-2", "解放大道"));

        // 一级风险：score = 32.5 + 18 + 6 + 15 = 71.5 -> 72，触发 7 日修剪任务
        treeService.register(new RegisterTreeRequest("seed-tree-1", seg1.id(), "ZSL-001", "悬铃木",
                60, 35, 18.0, true));
        // 三级风险：score = 20 + 10 + 4 = 34
        treeService.register(new RegisterTreeRequest("seed-tree-2", seg1.id(), "ZSL-002", "香樟",
                40, 60, 10.0, false));
        // 二级风险：score = 27.5 + 12 + 5 + 15 = 59.5 -> 60
        treeService.register(new RegisterTreeRequest("seed-tree-3", seg2.id(), "JFD-001", "银杏",
                50, 45, 12.0, true));
        // 四级风险：score = 5 + 2 + 1 = 8，不生成修剪任务
        treeService.register(new RegisterTreeRequest("seed-tree-4", seg2.id(), "JFD-002", "桂花",
                10, 90, 2.0, false));
        log.info("合成数据初始化完成：2 个路段、4 棵树木（一至四级风险各一）");
    }
}
