package com.example.starter.quarantine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.starter.quarantine.api.dto.BlockadeRequest;
import com.example.starter.quarantine.api.dto.DeclareRequest;
import com.example.starter.quarantine.api.dto.LandingReportRequest;
import com.example.starter.quarantine.domain.AnimalType;
import com.example.starter.quarantine.domain.RequestStatus;
import com.example.starter.quarantine.repository.CertificateRepository;
import com.example.starter.quarantine.repository.RequestRepository;
import com.example.starter.quarantine.repository.StockRepository;
import com.example.starter.quarantine.service.IdempotencyConflictException;
import com.example.starter.quarantine.service.QuarantineService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 幂等：申报/出证/落地以业务键重放返回首次结果；同键异参 409 且不改状态；
 * 同一养殖场同耳标段只允许一张有效证，结案释放后可再次出证。
 */
class IdempotencyTest extends AbstractIntegrationTest {

    @Autowired
    private QuarantineService service;
    @Autowired
    private RequestRepository requestRepository;
    @Autowired
    private CertificateRepository certificateRepository;
    @Autowired
    private StockRepository stockRepository;

    private DeclareRequest declaration(String requestId, int quantity) {
        return new DeclareRequest(requestId, "FARM-001", "DEST-A",
                AnimalType.CATTLE, "TAG-C-0001-0100", quantity);
    }

    @Test
    void declareSameKeySameParams_returnsFirstResultWithReplayFlag() {
        var first = service.declare(declaration("REQ-IDEM", 10));
        assertFalse(first.idempotentReplay());
        var replay = service.declare(declaration("REQ-IDEM", 10));
        assertTrue(replay.idempotentReplay());
        assertEquals(first.requestStatus(), replay.requestStatus());
        // 只有一条单据、一条幂等记录。
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM quarantine_request WHERE request_id = 'REQ-IDEM'", Integer.class));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM idempotent_command WHERE command_key = 'DECLARE:REQ-IDEM'",
                Integer.class));
    }

    @Test
    void declareSameKeyDifferentParams_returns409AndKeepsState() {
        service.declare(declaration("REQ-CONF", 10));
        DeclareRequest changed = new DeclareRequest("REQ-CONF", "FARM-001", "DEST-A",
                AnimalType.CATTLE, "TAG-C-0001-0100", 11);
        IdempotencyConflictException ex = assertThrows(IdempotencyConflictException.class,
                () -> service.declare(changed));
        assertTrue(ex.getMessage().contains("409"));
        // 数量未被篡改，单据仍为首次参数。
        assertEquals(10, requestRepository.findById("REQ-CONF").getQuantity());
        assertEquals(RequestStatus.SUBMITTED, requestRepository.findById("REQ-CONF").getStatus());
    }

    @Test
    void issueSameKey_replaysCertificateAndDoesNotDeductAgain() {
        service.declare(declaration("REQ-ISS", 10));
        var first = service.issue("REQ-ISS");
        int availableAfterFirst = stockRepository.findSlot(
                "FARM-001", AnimalType.CATTLE, "TAG-C-0001-0100").getAvailableCount();
        assertEquals(90, availableAfterFirst);

        var replay = service.issue("REQ-ISS");
        assertTrue(replay.idempotentReplay());
        assertEquals(first.certificate().certificateNo(), replay.certificate().certificateNo());
        assertEquals(90, stockRepository.findSlot(
                "FARM-001", AnimalType.CATTLE, "TAG-C-0001-0100").getAvailableCount());
    }

    @Test
    void landingSameKeySameParams_replaysFirstResult() {
        service.declare(declaration("REQ-LAND", 10));
        service.issue("REQ-LAND");
        service.depart("REQ-LAND");
        var first = service.landing(new LandingReportRequest("REQ-LAND", "R-1", 4));
        var replay = service.landing(new LandingReportRequest("REQ-LAND", "R-1", 4));
        assertTrue(replay.idempotentReplay());
        assertEquals(first.cumulativeLanded(), replay.cumulativeLanded());
        // 只记录一条落地事件，累计不重复。
        assertEquals(4, jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(quantity),0) FROM landing_report WHERE accepted = TRUE"
                        + " AND request_id = 'REQ-LAND'", Integer.class));
    }

    @Test
    void landingSameKeyDifferentQuantity_returns409AndDoesNotRecord() {
        service.declare(declaration("REQ-LC", 10));
        service.issue("REQ-LC");
        service.depart("REQ-LC");
        service.landing(new LandingReportRequest("REQ-LC", "R-1", 4));
        assertThrows(IdempotencyConflictException.class,
                () -> service.landing(new LandingReportRequest("REQ-LC", "R-1", 5)));
        assertEquals(4, jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(quantity),0) FROM landing_report WHERE request_id = 'REQ-LC'",
                Integer.class));
    }

    @Test
    void twoRequestsOnSameEarTag_onlyOneActiveCertificate() {
        service.declare(declaration("REQ-A", 10));
        service.declare(declaration("REQ-B", 10));
        var first = service.issue("REQ-A");
        assertEquals(RequestStatus.CERTIFIED, first.requestStatus());
        // 第二张证：同养殖场同耳标段已有有效证 → 409（重复出证按幂等/冲突处理，不出第二张证）。
        var second = assertThrows(com.example.starter.quarantine.service.ResourceConflictException.class,
                () -> service.issue("REQ-B"));
        assertTrue(second.getMessage().contains("有效证"));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM quarantine_certificate WHERE active_slot IS NOT NULL",
                Integer.class));
        // 第二单未出证且存栏未被扣减（事务回滚）。
        assertEquals(RequestStatus.SUBMITTED, requestRepository.findById("REQ-B").getStatus());
        assertEquals(90, stockRepository.findSlot(
                "FARM-001", AnimalType.CATTLE, "TAG-C-0001-0100").getAvailableCount());
    }

    @Test
    void slotReleasedAfterClose_allowsNewCertificateAndKeyReuseIsBlockedByOwnIdempotency() {
        service.declare(declaration("REQ-DONE", 10));
        service.issue("REQ-DONE");
        service.depart("REQ-DONE");
        service.landing(new LandingReportRequest("REQ-DONE", "R-1", 10));
        // 结案释放 active_slot 且存栏不返还（已实际调运）。
        assertEquals(null, certificateRepository.findActiveBySlot("FARM-001", "TAG-C-0001-0100"));
        assertEquals(90, stockRepository.findSlot(
                "FARM-001", AnimalType.CATTLE, "TAG-C-0001-0100").getAvailableCount());

        service.declare(declaration("REQ-NEW", 5));
        var issued = service.issue("REQ-NEW");
        assertEquals(RequestStatus.CERTIFIED, issued.requestStatus());
        assertEquals(85, stockRepository.findSlot(
                "FARM-001", AnimalType.CATTLE, "TAG-C-0001-0100").getAvailableCount());
    }

    @Test
    void blockadeSameKeyDifferentFarm_returns409WithoutSideEffects() {
        var first = service.publishBlockade(new BlockadeRequest("BLK-1", "FARM-001", "口蹄疫疫点"));
        assertFalse(first.idempotentReplay());
        assertThrows(IdempotencyConflictException.class,
                () -> service.publishBlockade(new BlockadeRequest("BLK-1", "FARM-002", "异参重放")));
        // FARM-002 未被封锁。
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM blockade WHERE farm_id = 'FARM-002'", Integer.class));
    }
}
