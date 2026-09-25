package com.example.starter.museum.repo;

import com.example.starter.museum.domain.AlertStatus;
import com.example.starter.museum.domain.ContractStatus;
import com.example.starter.museum.domain.HandoverType;
import com.example.starter.museum.domain.WorkOrderStatus;
import com.example.starter.museum.domain.po.ArtifactPo;
import com.example.starter.museum.domain.po.EnvironmentAlertPo;
import com.example.starter.museum.domain.po.EnvironmentReadingPo;
import com.example.starter.museum.domain.po.HandoverPo;
import com.example.starter.museum.domain.po.IdempotentRequestPo;
import com.example.starter.museum.domain.po.LoanContractPo;
import com.example.starter.museum.domain.po.LoanEventPo;
import com.example.starter.museum.domain.po.ProtectionWorkOrderPo;
import com.example.starter.museum.domain.po.ResumeReviewPo;
import com.example.starter.museum.domain.po.ShowcasePo;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 统一行映射：H2 TIMESTAMP 以本地时区解释，这里统一按 UTC 转回 Instant。
 */
final class RowMappers {

    private RowMappers() {
    }

    static Instant instant(Timestamp ts) {
        if (ts == null) {
            return null;
        }
        LocalDateTime ldt = ts.toLocalDateTime();
        return ldt.toInstant(ZoneOffset.UTC);
    }

    static final RowMapper<ArtifactPo> ARTIFACT = (rs, n) -> {
        ArtifactPo po = new ArtifactPo();
        po.id = rs.getLong("id");
        po.artifactNo = rs.getString("artifact_no");
        po.name = rs.getString("name");
        po.grade = rs.getInt("grade");
        po.material = rs.getString("material");
        po.createdAt = instant(rs.getTimestamp("created_at"));
        return po;
    };

    static final RowMapper<ShowcasePo> SHOWCASE = (rs, n) -> {
        ShowcasePo po = new ShowcasePo();
        po.id = rs.getLong("id");
        po.showcaseNo = rs.getString("showcase_no");
        po.hallId = rs.getLong("hall_id");
        po.material = rs.getString("material");
        po.tempMin = rs.getBigDecimal("temp_min");
        po.tempMax = rs.getBigDecimal("temp_max");
        po.humMin = rs.getBigDecimal("hum_min");
        po.humMax = rs.getBigDecimal("hum_max");
        po.frozen = rs.getBoolean("frozen");
        po.frozenReason = rs.getString("frozen_reason");
        po.createdAt = instant(rs.getTimestamp("created_at"));
        return po;
    };

    static final RowMapper<LoanContractPo> CONTRACT = (rs, n) -> {
        LoanContractPo po = new LoanContractPo();
        po.id = rs.getLong("id");
        po.contractNo = rs.getString("contract_no");
        po.artifactId = rs.getLong("artifact_id");
        po.borrower = rs.getString("borrower");
        po.startDate = rs.getDate("start_date").toLocalDate();
        po.endDate = rs.getDate("end_date").toLocalDate();
        po.originalDays = rs.getInt("original_days");
        po.status = ContractStatus.valueOf(rs.getString("status"));
        long showcaseId = rs.getLong("showcase_id");
        po.showcaseId = rs.wasNull() ? null : showcaseId;
        po.renewCount = rs.getInt("renew_count");
        po.suspendedAt = instant(rs.getTimestamp("suspended_at"));
        po.resumedAt = instant(rs.getTimestamp("resumed_at"));
        po.returnedAt = instant(rs.getTimestamp("returned_at"));
        po.closedAt = instant(rs.getTimestamp("closed_at"));
        po.version = rs.getInt("version");
        po.createdAt = instant(rs.getTimestamp("created_at"));
        return po;
    };

    static final RowMapper<HandoverPo> HANDOVER = (rs, n) -> {
        HandoverPo po = new HandoverPo();
        po.id = rs.getLong("id");
        po.contractId = rs.getLong("contract_id");
        po.type = HandoverType.valueOf(rs.getString("type"));
        po.lenderConfirmed = rs.getBoolean("lender_confirmed");
        po.borrowerConfirmed = rs.getBoolean("borrower_confirmed");
        po.completedAt = instant(rs.getTimestamp("completed_at"));
        po.createdAt = instant(rs.getTimestamp("created_at"));
        po.updatedAt = instant(rs.getTimestamp("updated_at"));
        return po;
    };

    static final RowMapper<EnvironmentReadingPo> READING = (rs, n) -> {
        EnvironmentReadingPo po = new EnvironmentReadingPo();
        po.id = rs.getLong("id");
        po.showcaseId = rs.getLong("showcase_id");
        po.sampleTime = instant(rs.getTimestamp("sample_time"));
        po.temperature = rs.getBigDecimal("temperature");
        po.humidity = rs.getBigDecimal("humidity");
        po.receivedAt = instant(rs.getTimestamp("received_at"));
        po.late = rs.getBoolean("late");
        return po;
    };

    static final RowMapper<EnvironmentAlertPo> ALERT = (rs, n) -> {
        EnvironmentAlertPo po = new EnvironmentAlertPo();
        po.id = rs.getLong("id");
        po.alertNo = rs.getString("alert_no");
        po.showcaseId = rs.getLong("showcase_id");
        long cid = rs.getLong("contract_id");
        po.contractId = rs.wasNull() ? null : cid;
        long trid = rs.getLong("trigger_reading_id");
        po.triggerReadingId = rs.wasNull() ? null : trid;
        po.triggerTime = instant(rs.getTimestamp("trigger_time"));
        po.status = AlertStatus.valueOf(rs.getString("status"));
        po.createdAt = instant(rs.getTimestamp("created_at"));
        po.escalatedAt = instant(rs.getTimestamp("escalated_at"));
        po.recoveredAt = instant(rs.getTimestamp("recovered_at"));
        po.resolvedAt = instant(rs.getTimestamp("resolved_at"));
        return po;
    };

    static final RowMapper<ProtectionWorkOrderPo> WORK_ORDER = (rs, n) -> {
        ProtectionWorkOrderPo po = new ProtectionWorkOrderPo();
        po.id = rs.getLong("id");
        po.orderNo = rs.getString("order_no");
        po.alertId = rs.getLong("alert_id");
        po.showcaseId = rs.getLong("showcase_id");
        po.status = WorkOrderStatus.valueOf(rs.getString("status"));
        po.deadline = instant(rs.getTimestamp("deadline"));
        po.confirmedAt = instant(rs.getTimestamp("confirmed_at"));
        po.handler = rs.getString("handler");
        po.note = rs.getString("note");
        po.createdAt = instant(rs.getTimestamp("created_at"));
        return po;
    };

    static final RowMapper<ResumeReviewPo> REVIEW = (rs, n) -> {
        ResumeReviewPo po = new ResumeReviewPo();
        po.id = rs.getLong("id");
        po.contractId = rs.getLong("contract_id");
        po.alertId = rs.getLong("alert_id");
        po.lenderConfirmed = rs.getBoolean("lender_confirmed");
        po.borrowerConfirmed = rs.getBoolean("borrower_confirmed");
        po.completedAt = instant(rs.getTimestamp("completed_at"));
        po.createdAt = instant(rs.getTimestamp("created_at"));
        po.updatedAt = instant(rs.getTimestamp("updated_at"));
        return po;
    };

    static final RowMapper<LoanEventPo> EVENT = (rs, n) -> {
        LoanEventPo po = new LoanEventPo();
        po.id = rs.getLong("id");
        long cid = rs.getLong("contract_id");
        po.contractId = rs.wasNull() ? null : cid;
        long sid = rs.getLong("showcase_id");
        po.showcaseId = rs.wasNull() ? null : sid;
        po.type = rs.getString("type");
        po.detail = rs.getString("detail");
        po.createdAt = instant(rs.getTimestamp("created_at"));
        return po;
    };

    static final RowMapper<IdempotentRequestPo> IDEMPOTENT = (rs, n) -> {
        IdempotentRequestPo po = new IdempotentRequestPo();
        po.requestId = rs.getString("request_id");
        po.fingerprint = rs.getString("fingerprint");
        po.httpStatus = rs.getInt("http_status");
        po.responseJson = rs.getString("response_json");
        po.createdAt = instant(rs.getTimestamp("created_at"));
        return po;
    };
}
