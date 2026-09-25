package com.example.starter.tailings.service;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.tailings.domain.CommandStatus;
import com.example.starter.tailings.domain.PlanStatus;
import com.example.starter.tailings.error.BusinessException;
import com.example.starter.tailings.idempotency.IdempotencyService;
import com.example.starter.tailings.repo.ArchiveRepository;
import com.example.starter.tailings.repo.CommandRepository;
import com.example.starter.tailings.repo.PlanRepository;

/**
 * 复盘归档：指令链完结后归档，归档为终态。
 * 重复归档返回幂等成功；归档后到达的读数只记迟到事件（见 ReadingService）。
 */
@Service
public class ReviewService {

    private final ArchiveRepository archiveRepository;
    private final PlanRepository planRepository;
    private final CommandRepository commandRepository;
    private final IdempotencyService idempotencyService;
    private final Clock clock;

    public ReviewService(ArchiveRepository archiveRepository, PlanRepository planRepository,
                         CommandRepository commandRepository, IdempotencyService idempotencyService,
                         Clock clock) {
        this.archiveRepository = archiveRepository;
        this.planRepository = planRepository;
        this.commandRepository = commandRepository;
        this.idempotencyService = idempotencyService;
        this.clock = clock;
    }

    /**
     * 归档结果。replayed=true 表示该尾矿库已归档，返回既有归档（幂等成功）。
     */
    public record ArchiveResult(long archiveId, long pondId, String status, boolean replayed,
                                LocalDateTime archivedAt) {
    }

    @Transactional
    public ArchiveResult archive(String requestId, long pondId) {
        var existing = archiveRepository.findByPond(pondId);
        if (existing.isPresent()) {
            ArchiveRepository.ArchiveRow row = existing.get();
            return new ArchiveResult(row.id(), row.pondId(), row.status(), true, row.archivedAt());
        }
        String canonical = FloodRoutingService.canonical("pondId=" + pondId);
        return idempotencyService.execute(requestId, canonical, ArchiveResult.class,
                () -> doArchive(requestId, pondId));
    }

    private ArchiveResult doArchive(String requestId, long pondId) {
        Long planId = planRepository.findActivePlanId(pondId)
                .orElseThrow(() -> BusinessException.conflict("该尾矿库不存在生效中的处置链，不能归档"));
        PlanRepository.PlanRow plan = planRepository.findPlan(planId)
                .orElseThrow(() -> BusinessException.notFound("泄流方案不存在: " + planId));
        CommandRepository.CommandRow command = commandRepository.findByPlanId(planId)
                .orElseThrow(() -> BusinessException.conflict("指令链未下达，不能归档"));
        if (!CommandStatus.RECEIPTED.name().equals(command.status())) {
            throw BusinessException.conflict("指令链未回执完结（当前 " + command.status() + "），不能归档");
        }
        LocalDateTime archivedAt = LocalDateTime.now(clock);
        long archiveId;
        try {
            archiveId = archiveRepository.insert(requestId, pondId, plan.calcId(), planId,
                    command.id(), archivedAt);
        } catch (DuplicateKeyException e) {
            // 并发归档：pond_id 唯一约束兜底，返回既有归档
            ArchiveRepository.ArchiveRow row = archiveRepository.findByPond(pondId).orElseThrow(() -> e);
            return new ArchiveResult(row.id(), row.pondId(), row.status(), true, row.archivedAt());
        }
        planRepository.updatePlanStatus(planId, PlanStatus.COMPLETED.name());
        planRepository.deleteActiveGuard(pondId);
        return new ArchiveResult(archiveId, pondId, "ARCHIVED", false, archivedAt);
    }

    @Transactional(readOnly = true)
    public ArchiveRepository.ArchiveRow getByPond(long pondId) {
        return archiveRepository.findByPond(pondId)
                .orElseThrow(() -> BusinessException.notFound("该尾矿库尚未归档: " + pondId));
    }
}
