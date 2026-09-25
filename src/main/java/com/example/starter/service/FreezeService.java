package com.example.starter.service;

import java.time.Clock;
import java.time.Instant;

import com.example.starter.domain.FreezeReview;
import com.example.starter.domain.Mine;
import com.example.starter.dto.ReviewRequest;
import com.example.starter.dto.ReviewResponse;
import com.example.starter.error.ApiException;
import com.example.starter.repository.MineRepository;
import com.example.starter.repository.ReviewRepository;
import com.example.starter.util.Fingerprints;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 监管复核服务：解除冻结须复核确认，复核提交幂等。
 */
@Service
public class FreezeService {

    private final ReviewRepository reviews;
    private final MineRepository mines;
    private final Clock clock;

    public FreezeService(ReviewRepository reviews, MineRepository mines, Clock businessClock) {
        this.reviews = reviews;
        this.mines = mines;
        this.clock = businessClock;
    }

    /**
     * 复核解除冻结：同键同参返回首次结果，同键异参返回 409；矿区未冻结时拒绝。
     */
    @Transactional
    public ReviewResponse unfreeze(ReviewRequest request) {
        String fingerprint = Fingerprints.of(request.requestId(),
                String.valueOf(request.mineId()), request.reviewer());
        var existing = reviews.findByRequestId(request.requestId());
        if (existing.isPresent()) {
            FreezeReview found = existing.get();
            if (!found.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId 已存在且业务字段不一致");
            }
            return ReviewResponse.from(found, false);
        }

        Mine mine = mines.findById(request.mineId())
                .orElseThrow(() -> ApiException.notFound("矿区不存在: " + request.mineId()));
        if (!mine.frozen()) {
            throw ApiException.unprocessable("NOT_FROZEN", "矿区未处于冻结状态");
        }

        Instant now = Instant.now(clock);
        long id;
        try {
            id = reviews.insert(request.requestId(), fingerprint, mine.id(),
                    request.reviewer(), now);
        } catch (DuplicateKeyException e) {
            FreezeReview raced = reviews.findByRequestId(request.requestId())
                    .orElseThrow(() -> e);
            if (!raced.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId 已存在且业务字段不一致");
            }
            return ReviewResponse.from(raced, false);
        }
        mines.unfreeze(mine.id());
        return ReviewResponse.from(reviews.findByRequestId(request.requestId()).orElseThrow(), true);
    }
}
