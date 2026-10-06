package com.example.starter.ledger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.starter.ledger.LedgerDtos.LedgerResponse;
import com.example.starter.ledger.LedgerDtos.SegmentLedger;
import com.example.starter.segment.SegmentRepository;
import com.example.starter.segment.SegmentRepository.SegmentRow;
import com.example.starter.task.PruningTaskRepository;
import com.example.starter.task.PruningTaskRepository.TaskRow;
import com.example.starter.tree.TreeRepository;
import com.example.starter.tree.TreeRepository.RiskCountRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 台账服务：每次查询从事务内实时复算各路段风险分布与修剪闭环率，
 * 重复查询结果稳定；查询前对逾期未闭环任务做幂等的升级督办标记。
 */
@Service
public class LedgerService {

    private final SegmentRepository segmentRepository;
    private final TreeRepository treeRepository;
    private final PruningTaskRepository taskRepository;
    private final Clock clock;

    public LedgerService(SegmentRepository segmentRepository, TreeRepository treeRepository,
            PruningTaskRepository taskRepository, Clock clock) {
        this.segmentRepository = segmentRepository;
        this.treeRepository = treeRepository;
        this.taskRepository = taskRepository;
        this.clock = clock;
    }

    /**
     * 复算台账：先幂等标记逾期任务，再聚合输出，按路段 id 升序保证稳定性。
     */
    @Transactional
    public LedgerResponse ledger() {
        taskRepository.escalateOverdue(LocalDate.now(clock));

        Map<Long, Map<String, Long>> distribution = new LinkedHashMap<>();
        for (RiskCountRow row : treeRepository.riskDistribution()) {
            distribution.computeIfAbsent(row.segmentId(), k -> new LinkedHashMap<>())
                    .merge(row.riskLevel() == null ? "UNEVALUATED" : String.valueOf(row.riskLevel()),
                            row.count(), Long::sum);
        }

        Map<Long, List<TaskRow>> tasksBySegment = new LinkedHashMap<>();
        for (TaskRow task : taskRepository.listAll()) {
            if ("CANCELLED".equals(task.status())) {
                continue;
            }
            tasksBySegment.computeIfAbsent(task.segmentId(), k -> new ArrayList<>()).add(task);
        }

        List<SegmentLedger> segments = new ArrayList<>();
        for (SegmentRow segment : segmentRepository.listAll()) {
            List<TaskRow> tasks = tasksBySegment.getOrDefault(segment.id(), List.of());
            long total = tasks.size();
            long closed = tasks.stream().filter(t -> "CLOSED".equals(t.status())).count();
            long onTime = tasks.stream()
                    .filter(t -> "CLOSED".equals(t.status()) && !t.escalated())
                    .count();
            long escalated = tasks.stream().filter(TaskRow::escalated).count();
            Double closureRate = total == 0 ? null
                    : BigDecimal.valueOf(closed)
                            .divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP)
                            .doubleValue();
            segments.add(new SegmentLedger(segment.id(), segment.name(),
                    distribution.getOrDefault(segment.id(), Map.of()),
                    total, total - closed, closed, onTime, escalated, closureRate));
        }
        return new LedgerResponse(segments);
    }
}
