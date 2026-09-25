package com.example.starter.flood.web;

import com.example.starter.flood.service.ReviewService;
import com.example.starter.flood.web.dto.ReviewRequest;
import com.example.starter.flood.web.dto.ReviewView;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 复盘归档入口：归档为终态，重复归档幂等成功。
 */
@RestController
@RequestMapping("/api/reviews")
public class ReviewController {

    private final ReviewService reviewService;

    public ReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @PostMapping("/archive")
    public ReviewView archive(@Valid @RequestBody ReviewRequest request) {
        return reviewService.archive(request);
    }
}
