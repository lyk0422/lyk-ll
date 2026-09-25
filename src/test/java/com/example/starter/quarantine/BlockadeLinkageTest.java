package com.example.starter.quarantine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.starter.quarantine.api.dto.BlockadeRequest;
import com.example.starter.quarantine.api.dto.DeclareRequest;
import com.example.starter.quarantine.api.dto.LandingReportRequest;
import com.example.starter.quarantine.domain.AnimalType;
import com.example.starter.quarantine.domain.CertificateStatus;
import com.example.starter.quarantine.domain.RequestStatus;
import com.example.starter.quarantine.repository.CertificateRepository;
import com.example.starter.quarantine.repository.RequestRepository;
import com.example.starter.quarantine.repository.StockRepository;
import com.example.starter.quarantine.service.QuarantineService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 封锁三级联动：
 * <ol>
 *   <li>未出证申报一律驳回；</li>
 *   <li>已出证未启运的证自动作废、返还存栏、禁止启运；</li>
 *   <li>已在途允许完成落地，但强制隔离观察标记。</li>
 * </ol>
 */
class BlockadeLinkageTest extends AbstractIntegrationTest {

    @Autowired
    private QuarantineService service;
    @Autowired
    private RequestRepository requestRepository;
    @Autowired
    private CertificateRepository certificateRepository;
    @Autowired
    private StockRepository stockRepository;

    private DeclareRequest declaration(String requestId, AnimalType type, String tags, int quantity) {
        return new DeclareRequest(requestId, "FARM-001", "DEST-A", type, tags, quantity);
    }

    @Test
    void blockade_linksAllThreeLevelsInOnePublish() {
        // 一级：仅申报未出证。
        service.declare(declaration("REQ-PENDING", AnimalType.SHEEP, "TAG-S-0001-0050", 5));
        // 二级：已出证未启运。
        service.declare(declaration("REQ-CERT", AnimalType.CATTLE, "TAG-C-0001-0100", 10));
        service.issue("REQ-CERT");
        // 三级：已在途。
        service.declare(declaration("REQ-TRANSIT", AnimalType.PIG, "TAG-P-0001-0200", 20));
        service.issue("REQ-TRANSIT");
        service.depart("REQ-TRANSIT");

        var view = service.publishBlockade(new BlockadeRequest("BLK-LINK", "FARM-001", "口蹄疫疫点"));
        assertEquals(1, view.rejectedPendingCount());
        assertEquals(1, view.voidedCertifiedCount());
        assertEquals(1, view.flaggedInTransitCount());

        // 一级断言
        var pending = requestRepository.findById("REQ-PENDING");
        assertEquals(RequestStatus.REJECTED, pending.getStatus());
        assertNotNull(pending.getRejectReason());

        // 二级断言：单据 VOID、证 VOID 并释放 active_slot、存栏返还。
        assertEquals(RequestStatus.VOID, requestRepository.findById("REQ-CERT").getStatus());
        var voidCert = certificateRepository.findByRequestId("REQ-CERT");
        assertEquals(CertificateStatus.VOID, voidCert.getStatus());
        assertEquals(null, voidCert.getActiveSlot());
        assertEquals(100, stockRepository.findSlot(
                "FARM-001", AnimalType.CATTLE, "TAG-C-0001-0100").getAvailableCount());

        // 作废证禁止启运。
        assertThrows(com.example.starter.quarantine.service.BusinessRuleException.class,
                () -> service.depart("REQ-CERT"));

        // 三级断言：在途证保持 VALID，强制隔离观察标记；可继续落地直至结案。
        var inTransitCert = certificateRepository.findByRequestId("REQ-TRANSIT");
        assertEquals(CertificateStatus.VALID, inTransitCert.getStatus());
        assertTrue(inTransitCert.isQuarantineFlag());
        assertEquals(RequestStatus.IN_TRANSIT, requestRepository.findById("REQ-TRANSIT").getStatus());

        var landing = service.landing(new LandingReportRequest("REQ-TRANSIT", "R-1", 20));
        assertTrue(landing.accepted());
        assertTrue(landing.quarantineFlag());
        assertTrue(landing.closed());
        assertEquals(CertificateStatus.CLOSED,
                certificateRepository.findByRequestId("REQ-TRANSIT").getStatus());
    }

    @Test
    void blockedFarm_newDeclarationRejectedAndIssueRejectsPending() {
        service.publishBlockade(new BlockadeRequest("BLK-BEFORE", "FARM-001", "先封锁"));

        // 封锁后新申报直接驳回（不计为正常 SUBMITTED）。
        var declared = service.declare(declaration("REQ-AFTER", AnimalType.CATTLE,
                "TAG-C-0001-0100", 3));
        assertEquals(RequestStatus.REJECTED, declared.requestStatus());

        // 即便存在封锁前漏网的 SUBMITTED，出证时也在锁内再次判定封锁并驳回，不会出证。
        jdbcTemplate.update(
                "INSERT INTO quarantine_request (request_id, origin_farm, destination, animal_type,"
                        + " ear_tag_range, quantity, status, created_at, updated_at)"
                        + " VALUES ('REQ-GAP','FARM-001','DEST-A','CATTLE','TAG-C-0001-0100',3,"
                        + "'SUBMITTED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        var issued = service.issue("REQ-GAP");
        assertEquals(RequestStatus.REJECTED, issued.requestStatus());
        assertFalse(issued.idempotentReplay());
        assertEquals(null, certificateRepository.findByRequestId("REQ-GAP"));
        assertEquals(100, stockRepository.findSlot(
                "FARM-001", AnimalType.CATTLE, "TAG-C-0001-0100").getAvailableCount());
    }

    @Test
    void blockadeReplay_returnsFirstResult() {
        var first = service.publishBlockade(new BlockadeRequest("BLK-IDEM", "FARM-002", "禽流感疫点"));
        var replay = service.publishBlockade(new BlockadeRequest("BLK-IDEM", "FARM-002", "禽流感疫点"));
        assertTrue(replay.idempotentReplay());
        assertEquals(first.effectiveAt(), replay.effectiveAt());
    }
}
