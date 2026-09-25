package com.example.starter.quarantine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.starter.quarantine.api.dto.DeclareRequest;
import com.example.starter.quarantine.api.dto.LandingReportRequest;
import com.example.starter.quarantine.api.dto.LandingResultView;
import com.example.starter.quarantine.domain.AnimalType;
import com.example.starter.quarantine.domain.CertificateStatus;
import com.example.starter.quarantine.domain.RequestStatus;
import com.example.starter.quarantine.repository.CertificateRepository;
import com.example.starter.quarantine.repository.RequestRepository;
import com.example.starter.quarantine.service.QuarantineService;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 主流程与证有效期边界：申报→出证→启运→分批落地→结案；24/48 小时终点边界与超时失效。
 */
class FlowAndValidityTest extends AbstractIntegrationTest {

    @Autowired
    private QuarantineService service;
    @Autowired
    private RequestRepository requestRepository;
    @Autowired
    private CertificateRepository certificateRepository;

    private DeclareRequest declaration(String requestId, AnimalType type, String tags, int quantity) {
        return new DeclareRequest(requestId, "FARM-001", "DEST-A", type, tags, quantity);
    }

    @Test
    void fullFlow_partialLanding_closesAtExactQuantity() {
        service.declare(declaration("REQ-FULL", AnimalType.CATTLE, "TAG-C-0001-0100", 10));
        var issued = service.issue("REQ-FULL");
        assertEquals(RequestStatus.CERTIFIED, issued.requestStatus());
        assertNotNull(issued.certificate());
        assertEquals(clock.instant(), issued.certificate().issuedAt());
        assertEquals(clock.instant().plus(Duration.ofHours(24)), issued.certificate().expiresAt());

        service.depart("REQ-FULL");
        assertEquals(RequestStatus.IN_TRANSIT, requestRepository.findById("REQ-FULL").getStatus());

        LandingResultView first = service.landing(new LandingReportRequest("REQ-FULL", "R-1", 4));
        assertTrue(first.accepted());
        assertEquals(4, first.cumulativeLanded());
        assertFalse(first.closed());
        assertEquals(RequestStatus.IN_TRANSIT, first.requestStatus());

        LandingResultView second = service.landing(new LandingReportRequest("REQ-FULL", "R-2", 6));
        assertTrue(second.accepted());
        assertEquals(10, second.cumulativeLanded());
        assertTrue(second.closed());
        assertEquals(RequestStatus.CLOSED, second.requestStatus());
        assertEquals(CertificateStatus.CLOSED, second.certificateStatus());

        // 结案为终态：重复/迟到报告返回首次结果或只记录不改状态。
        LandingResultView replay = service.landing(new LandingReportRequest("REQ-FULL", "R-2", 6));
        assertTrue(replay.idempotentReplay());
        assertEquals(10, replay.cumulativeLanded());

        LandingResultView late = service.landing(new LandingReportRequest("REQ-FULL", "R-LATE", 1));
        assertFalse(late.accepted());
        assertTrue(late.lateEvent());
        assertEquals(RequestStatus.CLOSED, late.requestStatus());
        assertEquals(10, service.landing(new LandingReportRequest("REQ-FULL", "R-LATE", 1)).cumulativeLanded());
    }

    @Test
    void cumulativeOverQuantity_rejectsWholeBatchAndKeepsState() {
        service.declare(declaration("REQ-OVER", AnimalType.CATTLE, "TAG-C-0001-0100", 10));
        service.issue("REQ-OVER");
        service.depart("REQ-OVER");

        assertTrue(service.landing(new LandingReportRequest("REQ-OVER", "R-1", 6)).accepted());
        LandingResultView overflow = service.landing(new LandingReportRequest("REQ-OVER", "R-2", 5));
        assertFalse(overflow.accepted());
        assertEquals(6, overflow.cumulativeLanded());
        assertEquals(RequestStatus.IN_TRANSIT, overflow.requestStatus());
        // 超量整批拒绝后仍可用不超量批次补齐结案。
        LandingResultView rest = service.landing(new LandingReportRequest("REQ-OVER", "R-3", 4));
        assertTrue(rest.accepted());
        assertTrue(rest.closed());
    }

    @Test
    void singleBatchOverCertificateQuantity_rejected() {
        service.declare(declaration("REQ-BIG", AnimalType.CATTLE, "TAG-C-0001-0100", 10));
        service.issue("REQ-BIG");
        service.depart("REQ-BIG");
        LandingResultView tooBig = service.landing(new LandingReportRequest("REQ-BIG", "R-1", 11));
        assertFalse(tooBig.accepted());
        assertEquals(0, tooBig.cumulativeLanded());
    }

    @Test
    void cattleReportExactlyAt24HourEndpoint_isValid() {
        assertBoundaryAccepted(AnimalType.CATTLE, "TAG-C-0001-0100", Duration.ofHours(24), "REQ-24");
    }

    @Test
    void poultryReportExactlyAt48HourEndpoint_isValid() {
        service.declare(new DeclareRequest("REQ-48", "FARM-002", "DEST-B",
                AnimalType.POULTRY, "TAG-B-0001-0500", 20));
        var issued = service.issue("REQ-48");
        assertEquals(clock.instant().plus(Duration.ofHours(48)), issued.certificate().expiresAt());
        service.depart("REQ-48");

        // 恰在有效期终点（不晚于终点）落地有效。
        clock.setInstant(issued.certificate().expiresAt());
        LandingResultView atEndpoint = service.landing(new LandingReportRequest("REQ-48", "R-1", 20));
        assertTrue(atEndpoint.accepted(), "恰在有效期终点应有效");
        assertTrue(atEndpoint.closed());
    }

    @Test
    void reportOneSecondAfter24HourEndpoint_certificateExpired() {
        service.declare(declaration("REQ-EXP", AnimalType.PIG, "TAG-P-0001-0200", 5));
        var issued = service.issue("REQ-EXP");
        service.depart("REQ-EXP");

        clock.setInstant(issued.certificate().expiresAt().plusSeconds(1));
        LandingResultView expired = service.landing(new LandingReportRequest("REQ-EXP", "R-1", 5));
        assertFalse(expired.accepted());
        assertEquals(RequestStatus.EXPIRED, expired.requestStatus());
        assertEquals(CertificateStatus.EXPIRED, expired.certificateStatus());
        assertEquals(RequestStatus.EXPIRED, requestRepository.findById("REQ-EXP").getStatus());
        assertEquals(CertificateStatus.EXPIRED,
                certificateRepository.findByRequestId("REQ-EXP").getStatus());
    }

    @Test
    void reportOneSecondBefore24HourEndpoint_isValid() {
        assertBoundaryAccepted(AnimalType.SHEEP, "TAG-S-0001-0050",
                Duration.ofHours(24).minusSeconds(1), "REQ-BEFORE");
    }

    private void assertBoundaryAccepted(AnimalType type, String tags, Duration advance, String requestId) {
        service.declare(declaration(requestId, type, tags, 8));
        var issued = service.issue(requestId);
        service.depart(requestId);
        clock.setInstant(issued.certificate().issuedAt().plus(advance));
        LandingResultView view = service.landing(new LandingReportRequest(requestId, "R-1", 8));
        assertTrue(view.accepted());
        assertEquals(RequestStatus.CLOSED, view.requestStatus());
    }
}
