package com.example.starter.epidemic.service;

import com.example.starter.epidemic.api.CardView;
import com.example.starter.epidemic.api.ReviewView;
import com.example.starter.epidemic.api.SpecialCorrectionView;
import com.example.starter.epidemic.api.VersionView;
import com.example.starter.epidemic.repo.CardPo;
import com.example.starter.epidemic.repo.CardVersionPo;
import com.example.starter.epidemic.repo.CatalogRepository;
import com.example.starter.epidemic.repo.DiseasePo;
import com.example.starter.epidemic.repo.ReviewPo;
import com.example.starter.epidemic.repo.SpecialCorrectionPo;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PO 到查询视图的组装；时间统一按 UTC 输出，保证查询结果稳定可复算。
 */
@Component
public class CardViewAssembler {

    private final CatalogRepository catalogRepository;

    public CardViewAssembler(CatalogRepository catalogRepository) {
        this.catalogRepository = catalogRepository;
    }

    public CardView toCardView(CardPo card, List<CardVersionPo> versions, List<ReviewPo> reviews) {
        Map<String, String> diseaseNames = new HashMap<>();
        versions.stream().map(v -> v.diseaseCode).distinct()
                .forEach(code -> catalogRepository.findDisease(code)
                        .map(DiseasePo.class::cast)
                        .ifPresent(d -> diseaseNames.put(code, d.name)));
        List<VersionView> versionViews = versions.stream()
                .map(v -> toVersionView(v, diseaseNames.get(v.diseaseCode)))
                .toList();
        VersionView current = versionViews.stream()
                .filter(v -> v.versionNo() == card.currentVersion)
                .findFirst()
                .orElse(versionViews.isEmpty() ? null : versionViews.getLast());
        List<ReviewView> reviewViews = reviews.stream().map(this::toReviewView).toList();
        return new CardView(
                card.cardNumber, card.patientId, card.patientName,
                card.diseaseCode, card.onsetDate.toString(), card.status,
                card.currentVersion, utc(card.createdAt), utc(card.updatedAt),
                current, versionViews, reviewViews);
    }

    public VersionView toVersionView(CardVersionPo v, String diseaseName) {
        return new VersionView(
                v.versionNo, v.diseaseCode, diseaseName, v.diagnosisType,
                v.onsetDate.toString(), utc(v.diagnosisTime), utc(v.submittedAt), utc(v.deadline),
                v.timely, v.lateReason, v.facilityCode, v.reporter,
                v.changeKind, v.diseaseChanged, v.notes, utc(v.createdAt));
    }

    public ReviewView toReviewView(ReviewPo r) {
        return new ReviewView(r.versionNo, r.stage, r.reviewer, r.outcome,
                r.voided, r.opinion, utc(r.createdAt));
    }

    public SpecialCorrectionView toSpecialView(SpecialCorrectionPo po, String message) {
        boolean applied = "APPLIED".equals(po.status);
        return new SpecialCorrectionView(
                po.requestId, po.cardNumber, po.status, po.confirmer1, po.confirmer2,
                applied, po.appliedVersionNo, message);
    }

    private static java.time.OffsetDateTime utc(LocalDateTime time) {
        return time == null ? null : time.atOffset(ZoneOffset.UTC);
    }
}
