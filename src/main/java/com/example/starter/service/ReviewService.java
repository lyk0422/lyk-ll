package com.example.starter.service;

import com.example.starter.api.ApiException;
import com.example.starter.api.dto.ApiRequests.ReviewResolutionRequest;
import com.example.starter.api.dto.ApiResponses.ReviewResponse;
import com.example.starter.domain.ReviewStatus;
import com.example.starter.domain.ReviewType;
import com.example.starter.domain.VoyageStatus;
import com.example.starter.repo.QuotaDao;
import com.example.starter.repo.ReviewDao;
import com.example.starter.repo.ReviewDao.ReviewRow;
import com.example.starter.repo.VoyageDao;
import com.example.starter.repo.VoyageDao.VoyageRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * 人工复核：复核结论为最终口径；解除冻结与配额扣减在同一事务内完成，
 * 不会出现余额已扣而航次未结案的中间状态。
 */
@Service
public class ReviewService {

    private final ReviewDao reviewDao;
    private final VoyageDao voyageDao;
    private final QuotaDao quotaDao;
    private final SettlementService settlementService;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public ReviewService(ReviewDao reviewDao, VoyageDao voyageDao, QuotaDao quotaDao,
                         SettlementService settlementService, IdempotencyService idempotency,
                         Clock businessClock) {
        this.reviewDao = reviewDao;
        this.voyageDao = voyageDao;
        this.quotaDao = quotaDao;
        this.settlementService = settlementService;
        this.idempotency = idempotency;
        this.clock = businessClock;
    }

    /**
     * 复核办结：同一事务内办结案件、解除配额冻结；finalItems 非空时以其为最终口径
     * 同步完成配额扣减与航次核销流转。
     */
    @Transactional
    public ReviewResponse resolve(long reviewId, ReviewResolutionRequest req) {
        return idempotency.execute(req.requestId(), "REVIEW_RESOLUTION#" + reviewId, req,
                ReviewResponse.class, () -> doResolve(reviewId, req));
    }

    private ReviewResponse doResolve(long reviewId, ReviewResolutionRequest req) {
        ReviewRow review = reviewDao.findById(reviewId)
                .orElseThrow(() -> ApiException.notFound("审查案件不存在: " + reviewId));
        if (review.status() == ReviewStatus.RESOLVED) {
            throw ApiException.conflict("审查案件已复核办结，复核结论为最终口径");
        }
        VoyageRow voyage = voyageDao.findVoyage(review.voyageId())
                .orElseThrow(() -> ApiException.notFound("航次不存在: " + review.voyageId()));
        reviewDao.resolve(reviewId, req.conclusion(), Instant.now(clock));
        if (review.reviewType() == ReviewType.DEVIATION) {
            int quotaYear = voyage.departureTime().atZone(ZoneOffset.UTC).getYear();
            quotaDao.setFrozen(voyage.vesselId(), quotaYear, false);
        }
        if (req.finalItems() != null && !req.finalItems().isEmpty()) {
            if (voyage.status() != VoyageStatus.ARRIVED) {
                throw ApiException.unprocessable("INVALID_STATE",
                        "航次状态 " + voyage.status() + " 不允许随复核结论扣减配额");
            }
            if (reviewDao.hasOpenReview(voyage.id())) {
                throw ApiException.unprocessable("REVIEW_PENDING",
                        "航次仍存在其他待复核案件，不得扣减配额");
            }
            settlementService.deductInSameTx(voyage, req.requestId(), req.finalItems());
        }
        return new ReviewResponse(reviewId, ReviewStatus.RESOLVED.name(), req.conclusion());
    }
}
