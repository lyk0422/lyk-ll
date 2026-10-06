package com.example.starter.reeval;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.example.starter.common.ApiException;
import com.example.starter.common.Fingerprints;
import com.example.starter.common.IdempotencyService;
import com.example.starter.reeval.ReevalDtos.ItemResult;
import com.example.starter.reeval.ReevalDtos.MetricItem;
import com.example.starter.reeval.ReevalDtos.ReevaluateRequest;
import com.example.starter.reeval.ReevalDtos.ReevaluateResponse;
import com.example.starter.risk.RiskEvaluator;
import com.example.starter.task.PruningTaskRepository;
import com.example.starter.task.PruningTaskRepository.TaskRow;
import com.example.starter.tree.TreeRepository;
import com.example.starter.tree.TreeRepository.TreeRow;
import org.springframework.stereotype.Service;

/**
 * 年度风险复评：按合成模型重算等级；等级变化触发修剪计划重排，
 * 重排不改变在途许可的既定时限（在途任务与其许可保持原截止日）。
 */
@Service
public class ReevaluationService {

    private final TreeRepository treeRepository;
    private final PruningTaskRepository taskRepository;
    private final RiskEvaluator riskEvaluator;
    private final IdempotencyService idempotencyService;
    private final Clock clock;

    public ReevaluationService(TreeRepository treeRepository, PruningTaskRepository taskRepository,
            RiskEvaluator riskEvaluator, IdempotencyService idempotencyService, Clock clock) {
        this.treeRepository = treeRepository;
        this.taskRepository = taskRepository;
        this.riskEvaluator = riskEvaluator;
        this.idempotencyService = idempotencyService;
        this.clock = clock;
    }

    /**
     * 幂等复评：同键同参返回首次结果，同键异参 409。
     */
    public ReevaluateResponse reevaluate(ReevaluateRequest request) {
        String fingerprint = Fingerprints.of(request.requestId(), request.items().stream()
                .map(i -> i.treeCode() + ":" + i.healthScore() + ":" + i.leanDegree() + ":" + i.age() + ":" + i.cavity())
                .toList());
        return idempotencyService.execute(request.requestId(), fingerprint, ReevaluateResponse.class,
                () -> doReevaluate(request.items()));
    }

    private ReevaluateResponse doReevaluate(List<MetricItem> items) {
        List<ItemResult> results = new ArrayList<>();
        for (MetricItem item : items) {
            TreeRow tree = treeRepository.findByCode(item.treeCode())
                    .orElseThrow(() -> ApiException.notFound("树木不存在：" + item.treeCode()));
            int oldLevel = tree.riskLevel() == null ? 0 : tree.riskLevel();
            int score = riskEvaluator.score(item.healthScore(), item.leanDegree(), item.age(), item.cavity());
            int newLevel = riskEvaluator.levelForScore(score);
            treeRepository.updateEvaluation(tree.id(), item.healthScore(), item.leanDegree(), item.age(),
                    item.cavity(), score, newLevel, LocalDateTime.now(clock));

            if (newLevel == oldLevel) {
                results.add(new ItemResult(item.treeCode(), oldLevel, newLevel, "NONE", null, null));
                continue;
            }
            results.add(replan(tree, oldLevel, newLevel));
        }
        return new ReevaluateResponse(results);
    }

    /**
     * 等级变化后的计划重排：无在途许可的待办任务可重排或取消；
     * 在途任务（已有执行中许可）保持既定时限不变。
     */
    private ItemResult replan(TreeRow tree, int oldLevel, int newLevel) {
        Optional<TaskRow> openTask = taskRepository.findOpenByTreeId(tree.id());
        LocalDate today = LocalDate.now(clock);

        if (!riskEvaluator.needsPruning(newLevel)) {
            if (openTask.isPresent() && "PENDING".equals(openTask.get().status())) {
                taskRepository.cancel(openTask.get().id());
                return new ItemResult(tree.treeCode(), oldLevel, newLevel, "CANCELLED", openTask.get().id(), null);
            }
            return new ItemResult(tree.treeCode(), oldLevel, newLevel,
                    openTask.isPresent() ? "KEPT_IN_FLIGHT" : "NONE",
                    openTask.map(TaskRow::id).orElse(null), openTask.map(TaskRow::deadlineDate).orElse(null));
        }

        LocalDate newDeadline = today.plusDays(riskEvaluator.deadlineDays(newLevel));
        if (openTask.isEmpty()) {
            long taskId = taskRepository.insert(tree.id(), tree.segmentId(), newLevel, "PENDING", today, newDeadline);
            return new ItemResult(tree.treeCode(), oldLevel, newLevel, "CREATED", taskId, newDeadline);
        }
        TaskRow task = openTask.get();
        if ("PENDING".equals(task.status())) {
            taskRepository.replan(task.id(), newLevel, newDeadline);
            return new ItemResult(tree.treeCode(), oldLevel, newLevel, "REPLANNED", task.id(), newDeadline);
        }
        // 在途：不改变任务与许可的既定时限
        return new ItemResult(tree.treeCode(), oldLevel, newLevel, "KEPT_IN_FLIGHT", task.id(), task.deadlineDate());
    }
}
