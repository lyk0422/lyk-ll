package com.example.starter.flood.service;

import com.example.starter.flood.domain.ChainStatus;
import com.example.starter.flood.exception.FloodControlException;
import com.example.starter.flood.repo.OrderPO;
import com.example.starter.flood.repo.OrderRepository;
import com.example.starter.flood.repo.PlanPO;
import com.example.starter.flood.repo.PlanRepository;
import com.example.starter.flood.repo.ReviewRepository;
import com.example.starter.flood.repo.WarningPO;
import com.example.starter.flood.repo.WarningRepository;
import com.example.starter.flood.web.dto.ReviewRequest;
import com.example.starter.flood.web.dto.ReviewView;
import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 复盘归档服务。
 *
 * <p>归档为终态：处置链与方案置 ARCHIVED；重复归档返回首次归档结果（幂等成功）。
 * 归档后到达的读数由读数服务判定为迟到，仅记迟到事件。</p>
 */
@Service
public class ReviewService {

    private final WarningRepository warningRepository;
    private final WarningService warningService;
    private final PlanRepository planRepository;
    private final OrderRepository orderRepository;
    private final ReviewRepository reviewRepository;
    private final Clock clock;

    public ReviewService(WarningRepository warningRepository, WarningService warningService,
                         PlanRepository planRepository, OrderRepository orderRepository,
                         ReviewRepository reviewRepository, Clock floodClock) {
        this.warningRepository = warningRepository;
        this.warningService = warningService;
        this.planRepository = planRepository;
        this.orderRepository = orderRepository;
        this.reviewRepository = reviewRepository;
        this.clock = floodClock;
    }

    /**
     * 复盘归档（重复归档幂等返回首次结果）。
     */
    @Transactional
    public ReviewView archive(ReviewRequest request) {
        WarningPO warning = warningService.requireByNo(request.warningNo());
        if (ChainStatus.ARCHIVED.name().equals(warning.status)) {
            // 终态：重复归档幂等成功，返回首次归档时刻
            return new ReviewView(warning.warningNo, ChainStatus.ARCHIVED.name(),
                    reviewRepository.findNoteByWarningId(warning.id), true, warning.archivedAt);
        }

        LocalDateTime now = LocalDateTime.now(clock);
        PlanPO plan = planRepository.findByWarningId(warning.id);
        OrderPO order = plan == null ? null : orderRepository.findByPlanId(plan.id);
        if (plan != null && "EFFECTIVE".equals(plan.status)) {
            throw FloodControlException.business("PLAN_STILL_EFFECTIVE",
                    "方案 " + plan.planNo + " 仍在执行中（未回执闭环），不能归档");
        }

        int warningRows = warningRepository.markArchived(warning.id, now);
        if (warningRows == 0) {
            // 并发下已被其他事务归档：回查返回首次结果
            WarningPO archived = warningRepository.findByNo(request.warningNo());
            return new ReviewView(archived.warningNo, archived.status,
                    reviewRepository.findNoteByWarningId(archived.id), true, archived.archivedAt);
        }
        if (plan != null) {
            planRepository.markArchived(plan.id, now);
        }
        reviewRepository.insert(warning.id, plan == null ? null : plan.id,
                order == null ? null : order.id, request.note(), now);
        return new ReviewView(warning.warningNo, ChainStatus.ARCHIVED.name(),
                request.note(), false, now);
    }
}
