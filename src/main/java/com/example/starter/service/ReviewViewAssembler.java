package com.example.starter.service;

import com.example.starter.catalog.InspectionCatalog;
import com.example.starter.catalog.InspectionSpec;
import com.example.starter.domain.InspectionRecord;
import com.example.starter.domain.PublicityRecord;
import com.example.starter.domain.Review;
import com.example.starter.dto.InspectionView;
import com.example.starter.dto.PublicityView;
import com.example.starter.dto.ReviewView;
import com.example.starter.json.JsonSupport;
import com.example.starter.repo.ReviewRepository;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 将持久化对象装配为对外视图（资料核对表、检验链、公示记录均可据此复算）。
 */
@Component
public class ReviewViewAssembler {

    private final ReviewRepository reviewRepository;
    private final JsonSupport jsonSupport;

    public ReviewViewAssembler(ReviewRepository reviewRepository, JsonSupport jsonSupport) {
        this.reviewRepository = reviewRepository;
        this.jsonSupport = jsonSupport;
    }

    /** 装配完整审查视图。 */
    public ReviewView assemble(Review review) {
        List<InspectionRecord> records = reviewRepository.listInspections(review.getId());
        PublicityRecord publicityRecord = reviewRepository.findPublicity(review.getId());
        return assemble(review, records, publicityRecord);
    }

    /** 使用事务内已锁定/已读取的链数据装配，避免重复查询。 */
    public ReviewView assemble(Review review, List<InspectionRecord> records, PublicityRecord publicityRecord) {
        return new ReviewView(
                review.getId(),
                review.getEnterpriseName(),
                review.getProductName(),
                review.getBatchNo(),
                review.getStatus().name(),
                jsonSupport.readStringList(review.getDocumentsJson()),
                jsonSupport.readStringList(review.getMissingJson()),
                review.getDeclaredAt(),
                review.getCorrectionNoticeDate(),
                review.getCorrectionDeadline(),
                review.getCorrectedAt(),
                review.getPublicityStartUtc(),
                review.getPublicityEndUtc(),
                review.getConclusion(),
                review.getIssuedAt(),
                records.stream().map(ReviewViewAssembler::toView).toList(),
                publicityRecord == null ? null : toView(publicityRecord));
    }

    private static InspectionView toView(InspectionRecord record) {
        InspectionSpec spec = InspectionCatalog.find(record.getItemCode());
        String limitMin = spec == null || spec.limitMin() == null ? null : spec.limitMin().toPlainString();
        String limitMax = spec == null || spec.limitMax() == null ? null : spec.limitMax().toPlainString();
        return new InspectionView(
                record.getItemCode(),
                record.getItemName(),
                record.isCritical(),
                record.getMeasuredValue(),
                record.getComparison(),
                limitMin,
                limitMax,
                record.isQualified());
    }

    private static PublicityView toView(PublicityRecord record) {
        return new PublicityView(
                record.getStartUtcDate(),
                record.getEndUtcDate(),
                record.getStatus().name(),
                record.getObjectionAt(),
                record.getEffectiveAt());
    }
}
