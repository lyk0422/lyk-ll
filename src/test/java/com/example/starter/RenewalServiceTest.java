package com.example.starter;

import java.time.LocalDate;

import com.example.starter.error.BusinessException;
import com.example.starter.repo.QualificationRepository;
import com.example.starter.service.RenewalService;
import com.example.starter.web.dto.QualificationDtos.QualificationResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 资格续期窗口测试：到期前 30 日内受理（恰第 30 日受理），更早或逾期拒绝。
 */
class RenewalServiceTest extends BaseIntegrationTest {

    @Autowired
    private RenewalService renewalService;

    @Autowired
    private QualificationRepository qualificationRepository;

    private long createQualification(LocalDate validTo) {
        long vehicleId = insertVehicle("PLATE-R-" + validTo + "-" + count("vehicle"), 30);
        jdbc.sql("INSERT INTO route (route_code, name, vehicle_id, station_spacing_m, road_grade,"
                        + " required_seats, status) VALUES (?, ?, ?, ?, ?, ?, ?)")
                .params("RC-R-" + validTo + "-" + count("route"), "合成线路", vehicleId, 800, 2,
                        30, "QUALIFIED")
                .update();
        long routeId = jdbc.sql("SELECT MAX(id) FROM route").query(Long.class).single();
        return qualificationRepository.insert(routeId, validTo.minusYears(1).plusDays(1),
                validTo, "INITIAL", newRequestId());
    }

    @Test
    void 恰第30日受理续期() {
        LocalDate validTo = TODAY.plusDays(RenewalService.RENEWAL_WINDOW_DAYS);
        long qualificationId = createQualification(validTo);

        QualificationResponse renewed = renewalService.renew(qualificationId, newRequestId());

        assertThat(renewed.validFrom()).isEqualTo(validTo.plusDays(1));
        assertThat(renewed.validTo()).isEqualTo(validTo.plusYears(1));
        assertThat(renewed.source()).isEqualTo("RENEWAL");
        assertThat(qualificationRepository.findById(qualificationId).orElseThrow().status())
                .isEqualTo("RENEWED");
    }

    @Test
    void 窗口内任意日受理续期() {
        long qualificationId = createQualification(TODAY.plusDays(10));
        QualificationResponse renewed = renewalService.renew(qualificationId, newRequestId());
        assertThat(renewed.status()).isEqualTo("ACTIVE");
    }

    @Test
    void 早于第30日拒绝续期() {
        long qualificationId = createQualification(
                TODAY.plusDays(RenewalService.RENEWAL_WINDOW_DAYS + 1));

        assertThatThrownBy(() -> renewalService.renew(qualificationId, newRequestId()))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.code()).isEqualTo("RENEWAL_WINDOW_VIOLATION");
                });
    }

    @Test
    void 逾期拒绝续期() {
        long qualificationId = createQualification(TODAY.minusDays(1));

        assertThatThrownBy(() -> renewalService.renew(qualificationId, newRequestId()))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.code()).isEqualTo("RENEWAL_WINDOW_VIOLATION");
                });
    }
}
