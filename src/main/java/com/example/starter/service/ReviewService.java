package com.example.starter.service;

import java.time.Clock;
import java.time.Instant;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.domain.ManifestStatus;
import com.example.starter.domain.ReviewConclusion;
import com.example.starter.repo.ManifestRepository;
import com.example.starter.support.BizException;
import com.example.starter.support.Fingerprints;

/**
 * 异常核查：对偏差超阈联单给出结论（设备误差 / 装载差异）并留痕闭环。
 */
@Service
public class ReviewService {

    private final ManifestRepository manifestRepository;
    private final Clock clock;

    public ReviewService(ManifestRepository manifestRepository, Clock clock) {
        this.manifestRepository = manifestRepository;
        this.clock = clock;
    }

    /**
     * 核查结果。
     *
     * @param replayed 是否为幂等重放
     */
    public record ReviewResult(
            long reviewId,
            String requestId,
            long manifestId,
            String conclusion,
            String note,
            boolean replayed,
            Instant reviewedAt) {
    }

    @Transactional
    public ReviewResult review(long manifestId, String requestId, String conclusion, String note) {
        ReviewConclusion parsed;
        try {
            parsed = ReviewConclusion.valueOf(conclusion);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw BizException.badRequest("CONCLUSION_INVALID",
                    "核查结论须为 DEVICE_ERROR（设备误差）或 LOADING_DIFF（装载差异）：" + conclusion);
        }
        String fingerprint = Fingerprints.of(
                requestId, String.valueOf(manifestId), parsed.name(), note == null ? "" : note);

        var existing = manifestRepository.findReviewByRequestId(requestId);
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), fingerprint);
        }

        var manifest = manifestRepository.findManifestById(manifestId)
                .orElseThrow(() -> BizException.notFound("MANIFEST_NOT_FOUND", "联单不存在：" + manifestId));
        if (!ManifestStatus.EXCEPTION.name().equals(manifest.status())) {
            throw BizException.conflict("MANIFEST_NOT_IN_EXCEPTION",
                    "联单当前状态不允许核查结论：" + manifest.status());
        }

        Instant now = clock.instant();
        long reviewId;
        try {
            reviewId = manifestRepository.insertReview(
                    requestId, fingerprint, manifestId, parsed.name(), note, now);
        } catch (DuplicateKeyException e) {
            var duplicated = manifestRepository.findReviewByRequestId(requestId);
            if (duplicated.isPresent()) {
                return replayOrConflict(duplicated.get(), fingerprint);
            }
            throw BizException.conflict("REVIEW_ALREADY_EXISTS", "该联单已存在核查结论：" + manifestId);
        }
        int updated = manifestRepository.transitionStatus(manifestId, ManifestStatus.EXCEPTION.name(),
                ManifestStatus.RESOLVED.name(), manifest.destinationVersion());
        if (updated != 1) {
            throw BizException.conflict("MANIFEST_NOT_IN_EXCEPTION", "联单状态已变化，核查失败：" + manifestId);
        }

        return new ReviewResult(reviewId, requestId, manifestId, parsed.name(), note, false, now);
    }

    private ReviewResult replayOrConflict(ManifestRepository.ReviewRow row, String fingerprint) {
        if (!row.fingerprint().equals(fingerprint)) {
            throw BizException.conflict("IDEMPOTENCY_CONFLICT",
                    "相同 requestId 提交了不同业务参数：" + row.requestId());
        }
        return new ReviewResult(row.id(), row.requestId(), row.manifestId(), row.conclusion(),
                row.note(), true, row.reviewedAt());
    }
}
