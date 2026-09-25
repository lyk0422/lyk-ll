package com.example.starter.survey.service;

import com.example.starter.survey.api.Dtos;
import com.example.starter.survey.domain.CertificateRow;
import com.example.starter.survey.domain.DefectRow;
import com.example.starter.survey.domain.ExtensionRow;
import com.example.starter.survey.domain.LedgerRow;
import com.example.starter.survey.domain.ShipRow;
import com.example.starter.survey.domain.SurveyRow;
import org.springframework.stereotype.Component;

/**
 * 领域行 → API 视图转换。
 */
@Component
public class ViewMapper {

    public Dtos.ShipView ship(ShipRow row) {
        return new Dtos.ShipView(row.id(), row.imo(), row.name(), row.anniversaryDate());
    }

    public Dtos.SurveyView survey(SurveyRow row, String imo) {
        return new Dtos.SurveyView(
                row.id(), row.applyRequestId(), imo, row.typeCode(), row.status().name(),
                row.anniversaryDate(), row.windowStart(), row.windowEnd(),
                row.extensionMonths(), row.boardedAt(), row.issuedAt());
    }

    public Dtos.DefectView defect(DefectRow row) {
        return new Dtos.DefectView(
                row.id(), row.surveyId(), row.severity().name(), row.description(),
                row.status().name(), row.foundDate(), row.dueDate(), row.rectifiedDate(),
                row.recheckSubmitDate(), row.acceptedDate());
    }

    public Dtos.CertificateView certificate(CertificateRow row, String imo) {
        return new Dtos.CertificateView(
                row.id(), row.certNo(), imo, row.typeCode(), row.surveyId(), row.version(),
                row.status().name(), row.validFrom(), row.validUntil(), row.recheckDue(),
                row.issuedAt(), row.invalidatedAt());
    }

    public Dtos.ExtensionView extension(ExtensionRow row, String imo) {
        return new Dtos.ExtensionView(
                row.id(), row.requestId(), imo, row.typeCode(), row.certificateId(),
                row.months(), row.status().name(), row.oldValidUntil(), row.newValidUntil());
    }

    public Dtos.LedgerView ledger(LedgerRow row) {
        return new Dtos.LedgerView(
                row.shipId(), row.cycleYear(), row.anniversaryDate(),
                row.windowStart(), row.windowEnd(), row.extendedEnd(),
                row.surveyId(), row.boardedAt(), row.state().name());
    }
}
