package com.example.starter;

import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 投诉登记：证据校验、requestId 幂等与自然键去重。
 */
class ComplaintRegistrationTest extends BaseIntegrationTest {

    private static final Instant T = TestClockConfiguration.T0;

    @Test
    void missingEvidenceIsRejectedAndNotRegistered() throws Exception {
        JsonNode response = postComplaint("req-no-evidence", "ST-1", "98.100", T, "72.5", null);

        assertEquals(400, response.get("status").asInt());
        assertEquals(0, countRows("complaint"), "缺少证据不进入正式处置");
    }

    @Test
    void blankEvidenceIsRejected() throws Exception {
        JsonNode response = postComplaint("req-blank-evidence", "ST-1", "98.100", T, "72.5", "  ");

        assertEquals(400, response.get("status").asInt());
        assertEquals(0, countRows("complaint"));
    }

    @Test
    void registerSuccessWithoutEvent() throws Exception {
        JsonNode response = postComplaint("req-ok-1", "ST-1", "98.100", T, "72.5", "EV-1");

        assertTrue(response.get("complaintId").asLong() > 0);
        assertFalse(response.get("eventTriggered").asBoolean());
        assertFalse(response.get("idempotentReplay").asBoolean());
        assertEquals(1, countRows("complaint"));
    }

    @Test
    void sameRequestIdSameParamsReturnsFirstResult() throws Exception {
        JsonNode first = postComplaint("req-idem-1", "ST-1", "98.100", T, "72.5", "EV-1");
        JsonNode replay = postComplaint("req-idem-1", "ST-1", "98.100", T, "72.5", "EV-1");

        assertEquals(first.get("complaintId").asLong(), replay.get("complaintId").asLong());
        assertTrue(replay.get("idempotentReplay").asBoolean());
        assertEquals(1, countRows("complaint"), "同键同参不产生新记录");
    }

    @Test
    void sameRequestIdDifferentParamsConflict() throws Exception {
        postComplaint("req-idem-2", "ST-1", "98.100", T, "72.5", "EV-1");
        JsonNode conflict = postComplaint("req-idem-2", "ST-2", "98.100", T, "72.5", "EV-1");

        assertEquals(409, conflict.get("status").asInt());
        assertEquals(1, countRows("complaint"), "同键异参不产生新记录");
        assertEquals(0, countRows("interference_event"), "同键异参不产生新事件");
    }

    @Test
    void sameNaturalKeyRegisteredOnlyOnce() throws Exception {
        JsonNode first = postComplaint("req-nat-1", "ST-1", "98.100", T, "72.5", "EV-1");
        JsonNode duplicate = postComplaint("req-nat-2", "ST-1", "98.100", T, "80.0", "EV-2");

        assertEquals(first.get("complaintId").asLong(), duplicate.get("complaintId").asLong());
        assertTrue(duplicate.get("naturalDuplicate").asBoolean());
        assertEquals(1, countRows("complaint"), "同监测站+频率+采样时刻只登记一次");
    }
}
