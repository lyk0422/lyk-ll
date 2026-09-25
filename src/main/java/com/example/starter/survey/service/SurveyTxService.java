package com.example.starter.survey.service;

import com.example.starter.survey.api.Dtos;
import com.example.starter.survey.domain.CertificateRow;
import com.example.starter.survey.domain.DefectRow;
import com.example.starter.survey.domain.ExtensionRow;
import com.example.starter.survey.domain.ShipRow;
import com.example.starter.survey.domain.SurveyRow;
import com.example.starter.survey.domain.SurveyTypeRow;
import com.example.starter.survey.model.SurveyEnums.CertificateStatus;
import com.example.starter.survey.model.SurveyEnums.DefectStatus;
import com.example.starter.survey.model.SurveyEnums.ExtensionStatus;
import com.example.starter.survey.model.SurveyEnums.LedgerState;
import com.example.starter.survey.model.SurveyEnums.Severity;
import com.example.starter.survey.model.SurveyEnums.SurveyStatus;
import com.example.starter.survey.model.SurveyEnums.SurveyType;
import com.example.starter.survey.repo.CertificateRepo;
import com.example.starter.survey.repo.ExtensionRepo;
import com.example.starter.survey.repo.LedgerRepo;
import com.example.starter.survey.repo.ShipTypeRepo;
import com.example.starter.survey.repo.SurveyRepo;
import com.example.starter.survey.web.ApiException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/**
 * 检验业务事务原语：每个方法一个数据库事务，保证多表写入原子性。
 * 幂等占坑与结果固化在 {@link Idempotency}（自动提交）中完成，本类只负责事务内业务。
 */
@Service
public class SurveyTxService {

    private final ShipTypeRepo shipTypeRepo;
    private final SurveyRepo surveyRepo;
    private final CertificateRepo certificateRepo;
    private final ExtensionRepo extensionRepo;
    private final LedgerRepo ledgerRepo;
    private final ViewMapper viewMapper;
    private final Clock clock;

    public SurveyTxService(ShipTypeRepo shipTypeRepo, SurveyRepo surveyRepo,
                           CertificateRepo certificateRepo, ExtensionRepo extensionRepo,
                           LedgerRepo ledgerRepo, ViewMapper viewMapper, Clock clock) {
        this.shipTypeRepo = shipTypeRepo;
        this.surveyRepo = surveyRepo;
        this.certificateRepo = certificateRepo;
        this.extensionRepo = extensionRepo;
        this.ledgerRepo = ledgerRepo;
        this.viewMapper = viewMapper;
        this.clock = clock;
    }

    /** 申请检验：同船同类型同时刻仅一个进行中检验（uk_survey_active 并发兜底）。 */
    @Transactional
    public Dtos.SurveyView applySurvey(String requestId, ShipRow ship, SurveyTypeRow type) {
        LocalDate today = LocalDate.now(clock);
        LocalDate anniversary = null;
        LocalDate start = null;
        LocalDate end = null;
        int cycleYear = today.getYear();
        if (type.code().equals(SurveyType.ANNUAL.name())) {
            anniversary = SurveyDates.cycleAnniversary(ship.anniversaryDate(), today);
            start = SurveyDates.windowStart(anniversary);
            end = SurveyDates.windowEnd(anniversary);
            cycleYear = anniversary.getYear();
        }
        long surveyId;
        try {
            surveyId = surveyRepo.insertSurvey(requestId, ship.id(), type.code(),
                    anniversary, start, end);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("同一船舶同一检验类型已有进行中的检验");
        }
        surveyRepo.addEvent(surveyId, "APPLY", today, "申请检验 requestId=" + requestId);
        if (anniversary != null) {
            ledgerRepo.upsert(ship.id(), cycleYear, anniversary, start, end, null,
                    surveyId, null, LedgerState.PENDING);
        }
        return viewMapper.survey(surveyRepo.findSurvey(surveyId), ship.imo());
    }

    /** 登轮检验完成：年度检验须在窗口闭区间内（首末日恰达有效）；可同时登记缺陷。 */
    @Transactional
    public Dtos.SurveyView boardSurvey(SurveyRow incoming, ShipRow ship, LocalDate boardDate,
                                       List<Dtos.DefectInput> defects) {
        SurveyRow survey = surveyRepo.lockSurvey(incoming.id());
        if (survey == null) {
            throw ApiException.notFound("检验单不存在：id=" + incoming.id());
        }
        if (survey.status() != SurveyStatus.APPLIED) {
            throw ApiException.badRequest("检验单当前状态不允许登轮：" + survey.status());
        }
        LocalDate anniversary = survey.anniversaryDate();
        LocalDate start = survey.windowStart();
        LocalDate end = survey.windowEnd();
        if (SurveyType.ANNUAL.name().equals(survey.typeCode())) {
            anniversary = SurveyDates.cycleAnniversary(ship.anniversaryDate(), boardDate);
            start = SurveyDates.windowStart(anniversary);
            end = SurveyDates.windowEnd(anniversary);
            if (!SurveyDates.withinWindow(boardDate, start, end)) {
                throw ApiException.badRequest("登轮完成日 " + boardDate
                        + " 不在年度检验窗口 [" + start + ", " + end + "] 内");
            }
        }

        boolean anyDefect = defects != null && !defects.isEmpty();
        for (Dtos.DefectInput input : defects) {
            Severity severity = parseSeverity(input.severity());
            LocalDate due = severity == Severity.GENERAL
                    ? SurveyDates.recheckDue(boardDate) : null;
            long defectId = surveyRepo.insertDefect(survey.id(), severity,
                    input.description(), DefectStatus.OPEN, boardDate, due);
            surveyRepo.addEvent(survey.id(), "DEFECT_FOUND", boardDate,
                    severity.name() + " 缺陷#" + defectId + "：" + input.description());
        }
        SurveyStatus newStatus = anyDefect ? SurveyStatus.RECTIFYING : SurveyStatus.BOARDING;
        surveyRepo.markBoarded(survey.id(), newStatus, boardDate, start, end);
        surveyRepo.addEvent(survey.id(), "BOARD", boardDate, "登轮检验完成");
        if (anniversary != null) {
            ledgerRepo.upsert(ship.id(), anniversary.getYear(), anniversary, start, end, null,
                    survey.id(), boardDate, LedgerState.IN_WINDOW);
        }
        return viewMapper.survey(surveyRepo.findSurvey(survey.id()), ship.imo());
    }

    /** 缺陷整改：重大缺陷整改即受理；一般缺陷整改后进入 30 日复查等待。 */
    @Transactional
    public Dtos.DefectView rectifyDefect(DefectRow incoming, SurveyRow surveyRow,
                                         LocalDate rectifiedDate) {
        SurveyRow survey = surveyRepo.lockSurvey(surveyRow.id());
        DefectRow defect = surveyRepo.lockDefect(incoming.id());
        if (defect.status() != DefectStatus.OPEN) {
            throw ApiException.badRequest("缺陷当前状态不允许整改上报：" + defect.status());
        }
        if (rectifiedDate.isBefore(defect.foundDate())) {
            throw ApiException.badRequest("整改完成日不得早于缺陷发现日");
        }
        if (defect.severity() == Severity.MAJOR) {
            surveyRepo.markRectified(defect.id(), DefectStatus.ACCEPTED,
                    rectifiedDate, rectifiedDate);
        } else {
            surveyRepo.markRectified(defect.id(), DefectStatus.RECTIFIED,
                    rectifiedDate, null);
        }
        surveyRepo.addEvent(survey.id(), "RECTIFY", rectifiedDate,
                "缺陷#" + defect.id() + " 完成整改");
        refreshSurveyStatus(survey.id());
        return viewMapper.defect(surveyRepo.findDefect(defect.id()));
    }

    /**
     * 一般缺陷复查提交：恰第 30 日（含）前受理；逾期则缺陷失效，
     * 若已发证书则证书在同一事务内失效。
     */
    @Transactional
    public Dtos.RecheckResult recheckDefect(DefectRow incoming, SurveyRow surveyRow, ShipRow ship,
                                            LocalDate submitDate) {
        SurveyRow survey = surveyRepo.lockSurvey(surveyRow.id());
        DefectRow defect = surveyRepo.lockDefect(incoming.id());
        if (defect.severity() != Severity.GENERAL) {
            throw ApiException.badRequest("重大缺陷整改即受理，无需复查提交");
        }
        if (defect.status() != DefectStatus.RECTIFIED) {
            throw ApiException.badRequest("缺陷当前状态不允许复查提交：" + defect.status());
        }
        if (submitDate.isBefore(defect.rectifiedDate())) {
            throw ApiException.badRequest("复查提交日不得早于整改完成日");
        }

        CertificateRow expiredCert = null;
        if (!submitDate.isAfter(defect.dueDate())) {
            surveyRepo.markRecheckAccepted(defect.id(), submitDate, submitDate);
            surveyRepo.addEvent(survey.id(), "RECHECK_ACCEPTED", submitDate,
                    "缺陷#" + defect.id() + " 复查受理（截止 " + defect.dueDate() + "）");
        } else {
            surveyRepo.markDefectExpired(defect.id());
            surveyRepo.addEvent(survey.id(), "RECHECK_OVERDUE", submitDate,
                    "缺陷#" + defect.id() + " 复查逾期（截止 " + defect.dueDate() + "）");
            CertificateRow validCert = certificateRepo.findValidCertificate(
                    ship.id(), survey.typeCode());
            if (validCert != null && validCert.surveyId() == survey.id()) {
                certificateRepo.markExpired(validCert.id(), submitDate);
                surveyRepo.addEvent(survey.id(), "CERT_EXPIRED", submitDate,
                        "证书 " + validCert.certNo() + " 因复查逾期失效");
                expiredCert = certificateRepo.findById(validCert.id());
            }
        }
        refreshSurveyStatus(survey.id());
        Dtos.DefectView defectView = viewMapper.defect(surveyRepo.findDefect(defect.id()));
        Dtos.CertificateView certView = expiredCert == null ? null
                : viewMapper.certificate(expiredCert, ship.imo());
        return new Dtos.RecheckResult(defectView, certView);
    }

    /**
     * 发证：重大缺陷未整改不得发证；一般缺陷已整改待复查可附条件发证（recheck_due）。
     * 旧证失效与新证签发在同一事务；同一检验单重复签发返回既有证书。
     */
    @Transactional
    public Dtos.CertificateView issueCertificate(SurveyRow incoming, ShipRow ship,
                                                 LocalDate issueDate) {
        SurveyRow survey = surveyRepo.lockSurvey(incoming.id());
        if (survey.status() == SurveyStatus.ISSUED) {
            CertificateRow existing = certificateRepo.findByShipType(ship.id(), survey.typeCode())
                    .stream()
                    .filter(c -> c.surveyId() == survey.id())
                    .reduce((first, second) -> second)
                    .orElseThrow(() -> ApiException.notFound("检验单关联证书不存在"));
            return viewMapper.certificate(existing, ship.imo());
        }
        if (survey.status() == SurveyStatus.APPLIED) {
            throw ApiException.badRequest("检验尚未登轮完成，不得发证");
        }

        List<DefectRow> defects = surveyRepo.findDefectsBySurvey(survey.id());
        for (DefectRow d : defects) {
            if (d.severity() == Severity.MAJOR && d.status() != DefectStatus.ACCEPTED) {
                throw ApiException.badRequest("重大缺陷#" + d.id() + " 未整改，不得发证");
            }
            if (d.status() == DefectStatus.OPEN || d.status() == DefectStatus.EXPIRED) {
                throw ApiException.badRequest("缺陷#" + d.id() + " 状态为 " + d.status()
                        + "，不得发证");
            }
        }
        LocalDate recheckDue = defects.stream()
                .filter(d -> d.status() == DefectStatus.RECTIFIED)
                .map(DefectRow::dueDate)
                .max(LocalDate::compareTo)
                .orElse(null);

        CertificateRow previous = certificateRepo.findValidCertificate(ship.id(), survey.typeCode());
        int version = certificateRepo.nextVersion(ship.id(), survey.typeCode());
        LocalDate validFrom = issueDate;
        LocalDate validUntil = SurveyType.ANNUAL.name().equals(survey.typeCode())
                ? SurveyDates.annualCertValidUntil(survey.anniversaryDate(), 0)
                : issueDate.plusYears(SurveyType.SPECIAL.name().equals(survey.typeCode()) ? 5 : 3);
        String certNo = ship.imo() + "-" + survey.typeCode() + "-V" + version;

        if (previous != null) {
            certificateRepo.invalidate(previous, CertificateStatus.INVALID, issueDate);
            // 旧证上待批准的展期随旧证作废，避免悬挂的展期申请。
            extensionRepo.findPending(ship.id(), survey.typeCode()).stream()
                    .filter(e -> e.certificateId() == previous.id())
                    .forEach(e -> extensionRepo.markRejected(e.id(),
                            "AUTO:旧证书被新证替代于 " + issueDate));
        }
        long certId;
        try {
            certId = certificateRepo.insertValid(certNo, ship.id(), survey.typeCode(),
                    survey.id(), version, validFrom, validUntil, recheckDue, issueDate);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("该船舶该检验类型已存在有效证书（并发发证冲突）");
        }
        surveyRepo.markIssued(survey.id(), issueDate);
        surveyRepo.addEvent(survey.id(), "ISSUE", issueDate,
                "签发证书 " + certNo + (recheckDue != null ? "，附复查截止 " + recheckDue : ""));
        return viewMapper.certificate(certificateRepo.findById(certId), ship.imo());
    }

    /** 展期申请：仅年度检验可展期，须持有有效证书。 */
    @Transactional
    public Dtos.ExtensionView applyExtension(String requestId, ShipRow incoming,
                                             SurveyTypeRow type, int months) {
        ShipRow ship = shipTypeRepo.lockShip(incoming.id());
        if (!type.extendable()) {
            throw ApiException.badRequest(type.name() + " 不得展期（仅年度检验可展期）");
        }
        if (months < 1 || months > 3) {
            throw ApiException.badRequest("展期月数须在 1 至 3 个月之间，恰满 3 个月合法");
        }
        CertificateRow cert = certificateRepo.findValidCertificate(ship.id(), type.code());
        if (cert == null) {
            throw ApiException.badRequest("无有效年度检验证书，不能申请展期");
        }
        if (extensionRepo.findApprovedByCertificate(cert.id()) != null) {
            throw ApiException.conflict("该证书已批准展期一次，不得再次展期");
        }
        if (!extensionRepo.findPending(ship.id(), type.code()).isEmpty()) {
            throw ApiException.conflict("该船舶该检验类型已有待批准的展期申请");
        }
        long id = extensionRepo.insertPending(requestId, ship.id(), type.code(), cert.id(), months);
        surveyRepo.addEvent(cert.surveyId(), "EXTENSION_APPLY", LocalDate.now(clock),
                "申请展期 " + months + " 个月");
        return viewMapper.extension(extensionRepo.findById(id), ship.imo());
    }

    /** 展期批准：批准记录与证书有效期更新在同一事务，一证书限一次。 */
    @Transactional
    public Dtos.ExtensionView approveExtension(ExtensionRow incoming, ShipRow ship,
                                               String approveRequestId) {
        ExtensionRow extension = extensionRepo.lockById(incoming.id());
        if (extension.status() == ExtensionStatus.APPROVED) {
            return viewMapper.extension(extension, ship.imo());
        }
        if (extension.status() != ExtensionStatus.PENDING) {
            throw ApiException.badRequest("展期申请状态为 " + extension.status() + "，不可批准");
        }
        CertificateRow cert = certificateRepo.findById(extension.certificateId());
        if (cert == null || cert.validFlag() == null) {
            throw ApiException.badRequest("被展期证书已失效，无法批准展期");
        }
        LocalDate oldValidUntil = cert.validUntil();
        LocalDate newValidUntil = oldValidUntil.plusMonths(extension.months());
        try {
            extensionRepo.markApproved(extension.id(), approveRequestId,
                    oldValidUntil, newValidUntil);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("该证书展期已被批准（唯一展期约束）");
        }
        certificateRepo.extendValidUntil(cert.id(), newValidUntil);

        // 同步检验单与窗口台账的展期信息（可复算字段）。
        SurveyRow survey = surveyRepo.findSurvey(cert.surveyId());
        if (survey != null) {
            surveyRepo.updateExtensionMonths(survey.id(), extension.months());
            LocalDate extEnd = newValidUntil;
            ledgerRepo.upsert(ship.id(),
                    survey.anniversaryDate() == null ? newValidUntil.getYear()
                            : survey.anniversaryDate().getYear(),
                    survey.anniversaryDate() == null ? newValidUntil : survey.anniversaryDate(),
                    survey.windowStart() == null ? newValidUntil : survey.windowStart(),
                    survey.windowEnd() == null ? newValidUntil : survey.windowEnd(),
                    extEnd, survey.id(), survey.boardedAt(), LedgerState.IN_WINDOW);
        }
        surveyRepo.addEvent(cert.surveyId(), "EXTENSION_APPROVE", LocalDate.now(clock),
                "展期批准：有效期 " + oldValidUntil + " → " + newValidUntil);
        return viewMapper.extension(extensionRepo.findById(extension.id()), ship.imo());
    }

    /** 发证后的迟到检验记录：仅追加事件，不改变终态。 */
    @Transactional
    public Dtos.LateRecordResult lateRecord(SurveyRow survey, LocalDate recordDate, String detail) {
        boolean terminal = survey.status() == SurveyStatus.ISSUED;
        surveyRepo.addEvent(survey.id(), "LATE_RECORD", recordDate,
                (terminal ? "发证后迟到记录：" : "迟到记录：") + detail);
        return new Dtos.LateRecordResult(survey.id(), terminal, recordDate, detail);
    }

    /** 根据缺陷集合刷新检验单状态：全部受理回到 BOARDING，否则停留 RECTIFYING。 */
    private void refreshSurveyStatus(long surveyId) {
        SurveyRow survey = surveyRepo.findSurvey(surveyId);
        if (survey == null || survey.status() == SurveyStatus.ISSUED
                || survey.status() == SurveyStatus.APPLIED) {
            return;
        }
        boolean unfinished = surveyRepo.findDefectsBySurvey(surveyId).stream()
                .anyMatch(d -> d.status() == DefectStatus.OPEN
                        || d.status() == DefectStatus.RECTIFIED);
        surveyRepo.updateStatus(surveyId,
                unfinished ? SurveyStatus.RECTIFYING : SurveyStatus.BOARDING);
    }

    private Severity parseSeverity(String code) {
        try {
            return Severity.valueOf(code);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("未知缺陷严重程度：" + code);
        }
    }
}
