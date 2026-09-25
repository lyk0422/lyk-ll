package com.example.starter.tailings.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.tailings.repo.ArchiveRepository;
import com.example.starter.tailings.service.ReviewService;

/**
 * 复盘归档：归档为终态，重复归档返回幂等成功。
 */
@RestController
@RequestMapping("/api/reviews")
public class ReviewController {

    private final ReviewService reviewService;

    public ReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    /**
     * 归档请求。
     */
    public record ArchiveBody(@NotBlank String requestId, @NotNull Long pondId) {
    }

    @PostMapping("/archive")
    public ReviewService.ArchiveResult archive(@Valid @RequestBody ArchiveBody body) {
        return reviewService.archive(body.requestId(), body.pondId());
    }

    @GetMapping("/{pondId}")
    public ArchiveRepository.ArchiveRow get(@PathVariable long pondId) {
        return reviewService.getByPond(pondId);
    }
}
