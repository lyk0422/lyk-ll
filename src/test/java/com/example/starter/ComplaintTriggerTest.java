package com.example.starter;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 触发规则：30 分钟（UTC）窗口内 3 个不同监测站，恰第 3 个触发；恰满 30 分钟计入、超过不计。
 */
class ComplaintTriggerTest extends BaseIntegrationTest {

    private static final Instant T = TestClockConfiguration.T0;

    @Test
    void exactlyThirdDistinctStationTriggers() throws Exception {
        JsonNode first = postComplaint("req-t1", "ST-1", "98.100", T, "72.5", "EV-1");
        JsonNode second = postComplaint("req-t2", "ST-2", "98.100", T.plus(10, ChronoUnit.MINUTES), "73.0", "EV-2");
        assertFalse(first.get("eventTriggered").asBoolean());
        assertFalse(second.get("eventTriggered").asBoolean());
        assertEquals(0, countRows("interference_event"));

        JsonNode third = postComplaint("req-t3", "ST-3", "98.100", T.plus(20, ChronoUnit.MINUTES), "74.0", "EV-3");
        assertTrue(third.get("eventTriggered").asBoolean(), "恰第 3 个不同监测站触发");
        assertEquals(1, countRows("interference_event"));

        JsonNode fourth = postComplaint("req-t4", "ST-4", "98.100", T.plus(25, ChronoUnit.MINUTES), "75.0", "EV-4");
        assertFalse(fourth.get("eventTriggered").asBoolean(), "已有未结事件不重复触发");
        assertEquals(1, countRows("interference_event"));
    }

    @Test
    void exactlyThirtyMinutesGapCounts() throws Exception {
        postComplaint("req-e1", "ST-1", "98.100", T, "72.5", "EV-1");
        postComplaint("req-e2", "ST-2", "98.100", T.plus(10, ChronoUnit.MINUTES), "73.0", "EV-2");
        JsonNode third = postComplaint("req-e3", "ST-3", "98.100", T.plus(30, ChronoUnit.MINUTES), "74.0", "EV-3");

        assertTrue(third.get("eventTriggered").asBoolean(), "恰满 30 分钟计入窗口");
        assertEquals(1, countRows("interference_event"));
    }

    @Test
    void overThirtyMinutesGapExcluded() throws Exception {
        postComplaint("req-o1", "ST-1", "98.100", T, "72.5", "EV-1");
        postComplaint("req-o2", "ST-2", "98.100", T.plus(31, ChronoUnit.MINUTES), "73.0", "EV-2");
        JsonNode third = postComplaint("req-o3", "ST-3", "98.100", T.plus(40, ChronoUnit.MINUTES), "74.0", "EV-3");

        assertFalse(third.get("eventTriggered").asBoolean(), "超过 30 分钟的首条不计入，窗口内仅 2 站");
        assertEquals(0, countRows("interference_event"));

        JsonNode fourth = postComplaint("req-o4", "ST-4", "98.100", T.plus(45, ChronoUnit.MINUTES), "75.0", "EV-4");
        assertTrue(fourth.get("eventTriggered").asBoolean(), "窗口滑动后满 3 站触发");
        assertEquals(1, countRows("interference_event"));
    }

    @Test
    void sameStationRepeatedDoesNotCount() throws Exception {
        postComplaint("req-s1", "ST-1", "98.100", T, "72.5", "EV-1");
        postComplaint("req-s2", "ST-1", "98.100", T.plus(5, ChronoUnit.MINUTES), "73.0", "EV-2");
        JsonNode third = postComplaint("req-s3", "ST-2", "98.100", T.plus(10, ChronoUnit.MINUTES), "74.0", "EV-3");

        assertFalse(third.get("eventTriggered").asBoolean(), "同一监测站重复投诉只计一次");
        assertEquals(0, countRows("interference_event"));
    }

    @Test
    void differentFrequencyDoesNotShareWindow() throws Exception {
        postComplaint("req-f1", "ST-1", "98.100", T, "72.5", "EV-1");
        postComplaint("req-f2", "ST-2", "98.100", T.plus(5, ChronoUnit.MINUTES), "73.0", "EV-2");
        JsonNode third = postComplaint("req-f3", "ST-3", "101.700", T.plus(10, ChronoUnit.MINUTES), "74.0", "EV-3");

        assertFalse(third.get("eventTriggered").asBoolean(), "不同频率不合并计数");
        assertEquals(0, countRows("interference_event"));
    }
}
