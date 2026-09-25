package com.example.starter.api;

import com.example.starter.api.dto.ApiRequests.ReviewResolutionRequest;
import com.example.starter.api.dto.ApiResponses.ReviewResponse;
import com.example.starter.service.ReviewService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 人工复核接口。
 */
@RestController
@RequestMapping("/api/v1/reviews")
public class ReviewController {

    private final ReviewService reviewService;

    public ReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    /** 复核办结：结论为最终口径，解除冻结与扣减在同一事务内完成。 */
    @PostMapping("/{reviewId}/resolution")
    public ReviewResponse resolve(@PathVariable long reviewId,
                                  @Valid @RequestBody ReviewResolutionRequest req) {
        return reviewService.resolve(reviewId, req);
    }
}
