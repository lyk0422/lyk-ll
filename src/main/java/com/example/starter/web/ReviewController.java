package com.example.starter.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.service.ReviewService;

/**
 * 异常核查结论入口。
 */
@RestController
@RequestMapping("/api/manifests")
public class ReviewController {

    private final ReviewService reviewService;

    public ReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    /**
     * 核查请求体。conclusion 取值：DEVICE_ERROR 设备误差 / LOADING_DIFF 装载差异。
     */
    public record ReviewRequest(
            @NotBlank String requestId,
            @NotBlank String conclusion,
            @Size(max = 500) String note) {
    }

    @PostMapping("/{manifestId}/reviews")
    @ResponseStatus(HttpStatus.CREATED)
    public ReviewService.ReviewResult review(@PathVariable long manifestId,
                                             @Valid @RequestBody ReviewRequest request) {
        return reviewService.review(manifestId, request.requestId(), request.conclusion(), request.note());
    }
}
