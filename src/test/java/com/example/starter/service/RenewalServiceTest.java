package com.example.starter.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import com.example.starter.support.BaseIntegrationTest;
import com.example.starter.support.BusinessException;
import com.example.starter.support.ConflictException;
import com.example.starter.support.NotFoundException;
import com.example.starter.web.dto.RenewalRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 资格续期测试：到期前 30 日窗口，恰第 30 日受理，更早或逾期拒绝，幂等回放。
 */
class RenewalServiceTest extends BaseIntegrationTest {

    private static final LocalDate VALID_TO = LocalDate.of(2026, 11, 5);

    @Autowired
    private QualificationService qualificationService;

    private void fixtures() {
        insertVehicle(1, 30);
        insertRoute(1, 1L, "RUNNING");
        insertQualification(1, VALID_TO.minusYears(1), VALID_TO, "ACTIVE");
    }

    @Test
    void acceptExactlyThirtiethDayBeforeExpiry() {
        fixtures();
        RenewalRequest.Result result = qualificationService.renew(1L,
                new RenewalRequest("ren-30", VALID_TO.minusDays(30)));
        assertThat(result.oldValidTo()).isEqualTo(VALID_TO);
        assertThat(result.newValidTo()).isEqualTo(VALID_TO.plusYears(1));
        assertThat(jdbc.queryForObject("SELECT valid_to FROM qualification WHERE route_id = 1",
                LocalDate.class)).isEqualTo(VALID_TO.plusYears(1));
        assertThat(count("qualification_renewal")).isEqualTo(1);
    }

    @Test
    void acceptOnExpiryDateItself() {
        fixtures();
        RenewalRequest.Result result = qualificationService.renew(1L,
                new RenewalRequest("ren-0", VALID_TO));
        assertThat(result.newValidTo()).isEqualTo(VALID_TO.plusYears(1));
    }

    @Test
    void rejectEarlierThanThirtyDays() {
        fixtures();
        assertThatThrownBy(() -> qualificationService.renew(1L,
                new RenewalRequest("ren-31", VALID_TO.minusDays(31))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("过早");
        assertThat(jdbc.queryForObject("SELECT valid_to FROM qualification WHERE route_id = 1",
                LocalDate.class)).isEqualTo(VALID_TO);
        assertThat(count("qualification_renewal")).isZero();
    }

    @Test
    void rejectAfterExpiry() {
        fixtures();
        assertThatThrownBy(() -> qualificationService.renew(1L,
                new RenewalRequest("ren-late", VALID_TO.plusDays(1))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("逾期");
    }

    @Test
    void rejectWhenQualificationExpired() {
        insertVehicle(1, 30);
        insertRoute(1, 1L, "STOPPED");
        insertQualification(1, VALID_TO.minusYears(1), VALID_TO, "EXPIRED");
        assertThatThrownBy(() -> qualificationService.renew(1L,
                new RenewalRequest("ren-expired", VALID_TO.minusDays(10))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("失效");
    }

    @Test
    void notFoundWhenNoQualification() {
        insertVehicle(1, 30);
        insertRoute(1, 1L, "APPLIED");
        assertThatThrownBy(() -> qualificationService.renew(1L,
                new RenewalRequest("ren-none", VALID_TO)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void idempotentReplayReturnsSameResult() {
        fixtures();
        RenewalRequest req = new RenewalRequest("ren-idem", VALID_TO.minusDays(5));
        RenewalRequest.Result first = qualificationService.renew(1L, req);
        RenewalRequest.Result replay = qualificationService.renew(1L, req);
        assertThat(replay).isEqualTo(first);
        assertThat(count("qualification_renewal")).isEqualTo(1);
        // 资格仅顺延一次
        assertThat(jdbc.queryForObject("SELECT valid_to FROM qualification WHERE route_id = 1",
                LocalDate.class)).isEqualTo(VALID_TO.plusYears(1));
    }

    @Test
    void sameKeyDifferentApplyDateConflicts() {
        fixtures();
        qualificationService.renew(1L, new RenewalRequest("ren-conflict", VALID_TO.minusDays(5)));
        assertThatThrownBy(() -> qualificationService.renew(1L,
                new RenewalRequest("ren-conflict", VALID_TO.minusDays(4))))
                .isInstanceOf(ConflictException.class);
    }
}
