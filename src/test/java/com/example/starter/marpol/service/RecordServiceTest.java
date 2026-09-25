package com.example.starter.marpol.service;

import com.example.starter.marpol.AbstractIntegrationTest;
import com.example.starter.marpol.TestFixtures;
import com.example.starter.marpol.domain.RecordVersionStatus;
import com.example.starter.marpol.error.ConflictException;
import com.example.starter.marpol.error.RuleViolationException;
import com.example.starter.marpol.model.EmissionRecord;
import com.example.starter.marpol.model.Ship;
import com.example.starter.marpol.model.Voyage;
import com.example.starter.marpol.support.GeoUtils;
import com.example.starter.marpol.web.CorrectRecordRequest;
import com.example.starter.marpol.web.SubmitRecordRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 记录服务集成测试：区域边界、12 海里口径、版本化修正、同键重放/异参、航次关闭。
 */
class RecordServiceTest extends AbstractIntegrationTest {

    @Autowired
    private RecordService recordService;
    @Autowired
    private ReferenceService referenceService;
    @Autowired
    private TestFixtures fixtures;

    private Ship ship;
    private Voyage voyage;
    private final Instant eventTime = Instant.parse("2026-09-25T08:00:00Z");

    @BeforeEach
    void setup() {
        ship = fixtures.ship("IMO-1000001");
        voyage = fixtures.voyage(ship.imo(), "V-1");
    }

    private SubmitRecordRequest submit(String requestId, String type, double lon, double lat) {
        return new SubmitRecordRequest(requestId, ship.imo(), voyage.voyageNo(), type,
                lon, lat, eventTime, new BigDecimal("1.500"), "TON");
    }

    @Test
    void oilInsideSpecialAreaIsRejected() {
        fixtures.squareSpecialArea();
        RuleViolationException ex = assertThrows(RuleViolationException.class,
                () -> recordService.submit(submit("REQ-OIL-IN", "OIL", 5, 5)));
        assertNotNull(ex.getMessage());
    }

    @Test
    void oilExactlyOnBoundaryIsRejected() {
        fixtures.squareSpecialArea();
        // 恰在边界线上，视为区域内。
        assertThrows(RuleViolationException.class,
                () -> recordService.submit(submit("REQ-OIL-EDGE", "OIL", 5, 0)));
        assertThrows(RuleViolationException.class,
                () -> recordService.submit(submit("REQ-OIL-EDGE2", "OIL", 10, 7)));
    }

    @Test
    void oilOutsideSpecialAreaIsAccepted() {
        fixtures.squareSpecialArea();
        EmissionRecord r = recordService.submit(submit("REQ-OIL-OUT", "OIL", -1, -1));
        assertEquals(1, r.versionNo());
        assertEquals(r.id(), r.rootId());
    }

    @Test
    void sewageInsideSpecialAreaIsRejectedButOutsideAccepted() {
        fixtures.squareSpecialArea();
        assertThrows(RuleViolationException.class,
                () -> recordService.submit(submit("REQ-SEW-IN", "SEWAGE", 1, 1)));
        assertNotNull(recordService.submit(submit("REQ-SEW-OUT", "SEWAGE", 12, 12)));
    }

    @Test
    void garbageCloserThanTwelveNauticalMilesIsRejected() {
        fixtures.originLand();
        double exact12Lat = Math.toDegrees(12.0 / GeoUtils.EARTH_RADIUS_NM);
        assertThrows(RuleViolationException.class,
                () -> recordService.submit(submit("REQ-GARB-NEAR", "GARBAGE", 0, exact12Lat - 0.001)));
    }

    @Test
    void garbageExactlyTwelveNauticalMilesIsLegal() {
        fixtures.originLand();
        double exact12Lat = Math.toDegrees(12.0 / GeoUtils.EARTH_RADIUS_NM);
        EmissionRecord r = recordService.submit(submit("REQ-GARB-EXACT", "GARBAGE", 0, exact12Lat));
        assertEquals(1, r.versionNo());
    }

    @Test
    void sameRequestIdSamePayloadReplaysFirstResult() {
        var req = submit("REQ-IDEM", "OIL", 20, 20);
        EmissionRecord first = recordService.submit(req);
        EmissionRecord replay = recordService.submit(req);
        assertEquals(first.id(), replay.id());
        assertEquals(first.requestId(), replay.requestId());
    }

    @Test
    void sameRequestIdDifferentPayloadConflictsWithoutNewVersion() {
        EmissionRecord first = recordService.submit(submit("REQ-CONFLICT", "OIL", 20, 20));
        SubmitRecordRequest changed = new SubmitRecordRequest("REQ-CONFLICT", ship.imo(), voyage.voyageNo(),
                "OIL", 21, 20, eventTime, new BigDecimal("1.500"), "TON");
        assertThrows(ConflictException.class, () -> recordService.submit(changed));
        // 仍只有一个版本行。
        assertEquals(1, recordService.get(first.id(), null).versionNo());
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM emission_record WHERE request_id = 'REQ-CONFLICT'", Integer.class);
        assertEquals(1, count);
    }

    @Test
    void correctionCreatesNewVersionAndKeepsOriginalQueryable() {
        EmissionRecord v1 = recordService.submit(submit("REQ-V1", "OIL", 20, 20));
        CorrectRecordRequest correction = new CorrectRecordRequest(
                v1.id(), "REQ-V2", "OIL", 22, 22, eventTime, new BigDecimal("2.000"), "TON");
        EmissionRecord v2 = recordService.correct(correction);

        assertEquals(2, v2.versionNo());
        assertEquals(v1.rootId(), v2.rootId());
        assertEquals(v1.id(), v2.supersedesId());
        assertEquals(RecordVersionStatus.ACTIVE, v2.status());

        // 默认查询返回最新版本。
        EmissionRecord latest = recordService.get(v1.id(), null);
        assertEquals(v2.id(), latest.id());
        assertEquals(0, new BigDecimal("22").compareTo(BigDecimal.valueOf(latest.longitude())));

        // 原版本保留可查且已标记 SUPERSEDED。
        EmissionRecord old = recordService.get(v1.id(), 1);
        assertEquals(v1.id(), old.id());
        assertEquals(RecordVersionStatus.SUPERSEDED, old.status());
    }

    @Test
    void correctionReplayWithSameRequestIdReturnsSameVersion() {
        EmissionRecord v1 = recordService.submit(submit("REQ-C1", "OIL", 20, 20));
        CorrectRecordRequest correction = new CorrectRecordRequest(
                v1.id(), "REQ-C2", "OIL", 23, 23, eventTime, new BigDecimal("3.000"), "TON");
        EmissionRecord v2 = recordService.correct(correction);
        EmissionRecord replay = recordService.correct(correction);
        assertEquals(v2.id(), replay.id());
    }

    @Test
    void correctionAfterVoyageClosedIsRejected() {
        EmissionRecord v1 = recordService.submit(submit("REQ-CLOSED", "OIL", 20, 20));
        referenceService.closeVoyage(ship.imo(), voyage.voyageNo());

        assertThrows(RuleViolationException.class, () -> recordService.submit(
                submit("REQ-AFTER-CLOSE", "OIL", 21, 21)));
        CorrectRecordRequest correction = new CorrectRecordRequest(
                v1.id(), "REQ-CORR-AFTER", "OIL", 24, 24, eventTime, new BigDecimal("4.000"), "TON");
        assertThrows(RuleViolationException.class, () -> recordService.correct(correction));
    }

    @Test
    void correctionIntoSpecialAreaIsRejected() {
        fixtures.squareSpecialArea();
        EmissionRecord v1 = recordService.submit(submit("REQ-MOVE", "OIL", 20, 20));
        CorrectRecordRequest intoArea = new CorrectRecordRequest(
                v1.id(), "REQ-MOVE-2", "OIL", 5, 5, eventTime, new BigDecimal("1.500"), "TON");
        assertThrows(RuleViolationException.class, () -> recordService.correct(intoArea));
        assertNotEquals(null, recordService.get(v1.id(), null));
        assertEquals(1, recordService.get(v1.id(), null).versionNo());
    }

    @Test
    void rootIdIsBoundToSelfForFirstVersion() {
        EmissionRecord r = recordService.submit(submit("REQ-ROOT", "OIL", 20, 20));
        assertNotNull(r.rootId());
        assertEquals(r.id(), r.rootId());
    }
}
