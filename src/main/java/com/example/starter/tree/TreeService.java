package com.example.starter.tree;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;

import com.example.starter.common.ApiException;
import com.example.starter.common.Fingerprints;
import com.example.starter.common.IdempotencyService;
import com.example.starter.risk.RiskEvaluator;
import com.example.starter.segment.SegmentRepository;
import com.example.starter.task.PruningTaskRepository;
import com.example.starter.tree.TreeDtos.RegisterTreeRequest;
import com.example.starter.tree.TreeDtos.TreeResponse;
import org.springframework.stereotype.Service;

/**
 * 树木建档与风险评估：建档即按合成模型评级，一至三级自动生成修剪任务，
 * 一级风险任务截止日为创建日 +7 天（恰第 7 日闭环有效）。
 */
@Service
public class TreeService {

    private final TreeRepository treeRepository;
    private final SegmentRepository segmentRepository;
    private final PruningTaskRepository taskRepository;
    private final RiskEvaluator riskEvaluator;
    private final IdempotencyService idempotencyService;
    private final Clock clock;

    public TreeService(TreeRepository treeRepository, SegmentRepository segmentRepository,
            PruningTaskRepository taskRepository, RiskEvaluator riskEvaluator,
            IdempotencyService idempotencyService, Clock clock) {
        this.treeRepository = treeRepository;
        this.segmentRepository = segmentRepository;
        this.taskRepository = taskRepository;
        this.riskEvaluator = riskEvaluator;
        this.idempotencyService = idempotencyService;
        this.clock = clock;
    }

    /**
     * 幂等建档：同键同参返回首次结果，同键异参 409；树木编号全局唯一。
     */
    public TreeResponse register(RegisterTreeRequest request) {
        String fingerprint = Fingerprints.of(request.requestId(), request.segmentId(), request.treeCode(),
                request.species(), request.age(), request.healthScore(), request.leanDegree(), request.cavity());
        return idempotencyService.execute(request.requestId(), fingerprint, TreeResponse.class, () -> {
            if (segmentRepository.findById(request.segmentId()).isEmpty()) {
                throw ApiException.notFound("路段不存在：" + request.segmentId());
            }
            if (treeRepository.existsByCode(request.treeCode())) {
                throw ApiException.conflict("树木编号已存在：" + request.treeCode());
            }
            int score = riskEvaluator.score(request.healthScore(), request.leanDegree(),
                    request.age(), request.cavity());
            int level = riskEvaluator.levelForScore(score);
            LocalDateTime now = LocalDateTime.now(clock);
            long treeId = treeRepository.insert(request.segmentId(), request.treeCode(), request.species(),
                    request.age(), request.healthScore(), request.leanDegree(), request.cavity(),
                    score, level, now);

            Long taskId = null;
            LocalDate deadline = null;
            if (riskEvaluator.needsPruning(level)) {
                LocalDate today = LocalDate.now(clock);
                deadline = today.plusDays(riskEvaluator.deadlineDays(level));
                taskId = taskRepository.insert(treeId, request.segmentId(), level, "PENDING", today, deadline);
            }
            return new TreeResponse(treeId, request.segmentId(), request.treeCode(), score, level, taskId, deadline);
        });
    }
}
