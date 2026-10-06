package com.example.starter.seed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import com.example.starter.api.Requests.SegmentRegisterRequest;
import com.example.starter.api.Requests.TreeRegisterRequest;
import com.example.starter.repo.SegmentRepository;
import com.example.starter.service.SegmentService;
import com.example.starter.service.TreeService;

/**
 * 合成数据初始化：仅在空库时写入三段路段与四棵不同风险等级的行道树，
 * 其中 T-A-001 经合成评估模型为一级风险，会自动生成 7 日期限的修剪计划。
 */
@Component
public class DataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final SegmentRepository segmentRepository;
    private final SegmentService segmentService;
    private final TreeService treeService;

    public DataSeeder(SegmentRepository segmentRepository, SegmentService segmentService,
                      TreeService treeService) {
        this.segmentRepository = segmentRepository;
        this.segmentService = segmentService;
        this.treeService = treeService;
    }

    @Override
    public void run(String... args) {
        if (segmentRepository.count() > 0) {
            return;
        }
        segmentService.register(new SegmentRegisterRequest("seed-seg-1", "SEG-A", "人民路"));
        segmentService.register(new SegmentRegisterRequest("seed-seg-2", "SEG-B", "解放大道"));
        segmentService.register(new SegmentRegisterRequest("seed-seg-3", "SEG-C", "滨河路"));

        // 一级风险：倾斜30度、病害2级、树龄40年、暴露2级 -> 得分102
        treeService.register(new TreeRegisterRequest("seed-tree-1", "SEG-A", "T-A-001",
                30.0, 2, 40, 2));
        // 二级风险：得分45
        treeService.register(new TreeRegisterRequest("seed-tree-2", "SEG-A", "T-A-002",
                10.0, 1, 20, 1));
        // 三级风险：得分26
        treeService.register(new TreeRegisterRequest("seed-tree-3", "SEG-B", "T-B-001",
                5.0, 1, 10, 0));
        // 四级风险：得分4.9
        treeService.register(new TreeRegisterRequest("seed-tree-4", "SEG-C", "T-C-001",
                2.0, 0, 5, 0));
        log.info("合成数据初始化完成：3个路段、4棵行道树（T-A-001为一级风险）");
    }
}
