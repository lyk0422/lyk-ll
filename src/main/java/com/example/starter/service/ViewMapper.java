package com.example.starter.service;

import java.time.Instant;

import com.example.starter.domain.Certificate;
import com.example.starter.domain.FieldInspectionRecord;
import com.example.starter.domain.LabInspectionRecord;
import com.example.starter.domain.SeedLot;
import com.example.starter.web.dto.CertificateView;
import com.example.starter.web.dto.InspectionView;
import com.example.starter.web.dto.LotView;

/**
 * 领域对象到 API 视图的转换。
 */
final class ViewMapper {

    static final String TYPE_FIELD = "FIELD";
    static final String TYPE_LAB = "LAB";

    private ViewMapper() {
    }

    static LotView lotView(SeedLot lot) {
        return new LotView(
                lot.lotNo(),
                lot.varietyCode(),
                lot.plotCode(),
                lot.declaredGrade().name(),
                lot.labelQuantity(),
                lot.availableLabels(),
                lot.status().name(),
                lot.declaredAt(),
                lot.revokedAt(),
                lot.revokeReason());
    }

    static CertificateView certificateView(Certificate certificate) {
        return new CertificateView(
                certificate.certificateNo(),
                certificate.lotNo(),
                certificate.grade().name(),
                certificate.deductedLabels(),
                certificate.state().name(),
                certificate.issuedAt(),
                certificate.revokedAt());
    }

    /**
     * 田间检验视图。
     *
     * @param retestable     是否仍在复检窗口（受理时刻或查询时刻的快照值）
     * @param retestDeadline 复检截止日（UTC），无可复检项为空
     */
    static InspectionView fieldView(FieldInspectionRecord record,
                                    boolean retestable, Instant retestDeadline) {
        return new InspectionView(
                TYPE_FIELD,
                record.round(),
                record.requestId(),
                record.purity(),
                record.verdict().name(),
                record.effective(),
                retestable,
                retestDeadline,
                record.inspector(),
                record.inspectedAt());
    }

    static InspectionView labView(LabInspectionRecord record,
                                  boolean retestable, Instant retestDeadline) {
        return new InspectionView(
                TYPE_LAB,
                record.round(),
                record.requestId(),
                record.germinationRate(),
                record.verdict().name(),
                record.effective(),
                retestable,
                retestDeadline,
                record.inspector(),
                record.inspectedAt());
    }
}
