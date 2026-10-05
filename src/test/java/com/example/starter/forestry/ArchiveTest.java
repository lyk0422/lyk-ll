package com.example.starter.forestry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.example.starter.forestry.dto.AcceptanceRequest;
import com.example.starter.forestry.dto.ApplyRequest;
import com.example.starter.forestry.dto.ConfirmRequest;
import com.example.starter.forestry.dto.IssueRequest;
import com.example.starter.forestry.dto.ReviewRequest;
import com.example.starter.forestry.web.ApiException;

/**
 * 归档终态：重复归档幂等成功；归档后迟到的审核/发证/确认/验收只记录迟到事件并返回冲突。
 */
class ArchiveTest extends AbstractForestryTest {

    @Test
    void repeatedArchiveIsIdempotentSuccess() {
        var app = service.apply(new ApplyRequest("arc-app", "OP-A", "SP-001",
                "PINUS_MASSONIANA", "MAIN", 20, new BigDecimal("10.00")));

        var first = service.archive(app.id());
        var second = service.archive(app.id());

        assertThat(first.status()).isEqualTo("ARCHIVED");
        assertThat(second.status()).isEqualTo("ARCHIVED");
        assertThat(second.archivedDate()).isEqualTo(first.archivedDate());
    }

    @Test
    void lateDataAfterArchiveOnlyRecordsEvent() {
        var app = service.apply(new ApplyRequest("arc-late-app", "OP-A2", "SP-001",
                "PINUS_MASSONIANA", "MAIN", 20, new BigDecimal("10.00")));
        service.archive(app.id());

        // 迟到审核：冲突 + 记录一条迟到事件
        assertThatThrownBy(() -> service.review(app.id(), new ReviewRequest(true, "late")))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getErrorCode()).isEqualTo("APPLICATION_ARCHIVED");
                });

        // 迟到发证
        assertThatThrownBy(() -> service.issue(new IssueRequest("arc-late-issue", app.id())))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo("APPLICATION_ARCHIVED"));

        Integer events = jdbc.queryForObject(
                "SELECT COUNT(*) FROM late_event WHERE application_id=?", Integer.class, app.id());
        assertThat(events).isEqualTo(2);
        // 业务数据未被迟到事件驱动
        assertThat(service.getApplication(app.id()).status()).isEqualTo("ARCHIVED");
    }

    @Test
    void lateConfirmAndAcceptanceAfterHarvestedArchiveRecordEvents() {
        var app = service.apply(new ApplyRequest("arc-h-app", "OP-A3", "SP-001",
                "PINUS_MASSONIANA", "MAIN", 20, new BigDecimal("10.00")));
        service.review(app.id(), new ReviewRequest(true, "OK"));
        var license = service.issue(new IssueRequest("arc-h-issue", app.id()));
        clock.setToday(LocalDate.of(2026, 4, 1));
        service.confirm(new ConfirmRequest("arc-h-confirm", license.id()));
        service.archive(app.id());

        assertThatThrownBy(() -> service.acceptance(
                new AcceptanceRequest("arc-h-acc", app.id(), 100, 100)))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo("APPLICATION_ARCHIVED"));

        Integer events = jdbc.queryForObject(
                "SELECT COUNT(*) FROM late_event WHERE application_id=? AND event_type='ACCEPTANCE'",
                Integer.class, app.id());
        assertThat(events).isOne();
        // 更新义务状态保持 PENDING，未被迟到验收驱动
        assertThat(service.getRenewal(app.id()).status()).isEqualTo("PENDING");
    }
}
