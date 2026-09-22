package com.example.starter.perception.api;

import com.example.starter.perception.config.TimeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REST 入口测试：合成检测提交、轨迹快照查询、错误语义（400/404/409/422）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class TrackControllerTest {

    private static final Instant T0 = Instant.parse("2026-09-22T10:00:00Z");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private TimeService timeService;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM processed_detection");
        jdbcTemplate.update("DELETE FROM track_event");
        jdbcTemplate.update("DELETE FROM track");
        jdbcTemplate.update("ALTER SEQUENCE seq_track_no RESTART WITH 1");
        timeService.setClock(Clock.fixed(T0, ZoneOffset.UTC));
    }

    @AfterEach
    void resetClock() {
        timeService.resetClock();
    }

    @Test
    void associationFlowExposedOverHttp() throws Exception {
        mockMvc.perform(post("/api/tracks/associations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"detections":[
                                  {"detectionId":"h1","timestamp":"2026-09-22T10:00:00Z",
                                   "source":"camera_front","category":"pedestrian",
                                   "x":0.0,"y":0.0,"confidence":0.9},
                                  {"detectionId":"h2","timestamp":"2026-09-22T10:00:00.200Z",
                                   "source":"camera_front","category":"pedestrian",
                                   "x":0.5,"y":0.0,"confidence":0.8}
                                ]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acceptedCount", is(2)))
                .andExpect(jsonPath("$.results[0].trackId", is("TRK-00000001")))
                .andExpect(jsonPath("$.results[0].state", is("CREATED")))
                .andExpect(jsonPath("$.results[1].state", is("TRACKING")))
                .andExpect(jsonPath("$.results[1].version", is(2)));

        mockMvc.perform(get("/api/tracks/TRK-00000001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state", is("TRACKING")))
                .andExpect(jsonPath("$.source", is("LIVE")))
                .andExpect(jsonPath("$.version", is(2)));

        mockMvc.perform(get("/api/tracks/TRK-00000001/snapshot")
                        .param("at", "2026-09-22T10:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state", is("CREATED")))
                .andExpect(jsonPath("$.source", is("REPLAYED")))
                .andExpect(jsonPath("$.version", is(1)));

        mockMvc.perform(get("/api/tracks/snapshots")
                        .param("at", "2026-09-22T10:00:00.200Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].state", is("TRACKING")));
    }

    @Test
    void validationFailureReturns400() throws Exception {
        mockMvc.perform(post("/api/tracks/associations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"detections":[
                                  {"detectionId":"","timestamp":"2026-09-22T10:00:00Z",
                                   "source":"camera_front","category":"pedestrian",
                                   "x":0.0,"y":0.0,"confidence":1.5}
                                ]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason", is("VALIDATION_FAILED")));
    }

    @Test
    void unknownTrackReturns404() throws Exception {
        mockMvc.perform(get("/api/tracks/TRK-99999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.reason", is("TRACK_NOT_FOUND")));
    }

    @Test
    void staleExpectedVersionReturns409() throws Exception {
        mockMvc.perform(post("/api/tracks/associations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"detections":[
                                  {"detectionId":"c1","timestamp":"2026-09-22T10:00:00Z",
                                   "source":"camera_front","category":"pedestrian",
                                   "x":0.0,"y":0.0,"confidence":0.9}
                                ]}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/tracks/associations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"detections":[
                                  {"detectionId":"c2","timestamp":"2026-09-22T10:00:00.100Z",
                                   "source":"camera_front","category":"pedestrian",
                                   "x":0.2,"y":0.0,"confidence":0.9}
                                ],
                                 "expectedVersions":{"TRK-00000001":42}}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason", is("VERSION_CONFLICT")));
    }

    @Test
    void lowConfidenceRejectedOverHttpWithoutTrackChange() throws Exception {
        mockMvc.perform(post("/api/tracks/associations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"detections":[
                                  {"detectionId":"lc1","timestamp":"2026-09-22T10:00:00Z",
                                   "source":"lidar_top","category":"vehicle",
                                   "x":0.0,"y":0.0,"confidence":0.3}
                                ]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acceptedCount", is(0)))
                .andExpect(jsonPath("$.rejectedCount", is(1)))
                .andExpect(jsonPath("$.results[0].reason", is("LOW_CONFIDENCE")));

        mockMvc.perform(get("/api/tracks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void recycleFlowOverHttp() throws Exception {
        mockMvc.perform(post("/api/tracks/associations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"detections":[
                                  {"detectionId":"rc1","timestamp":"2026-09-22T10:00:00Z",
                                   "source":"camera_front","category":"pedestrian",
                                   "x":0.0,"y":0.0,"confidence":0.9}
                                ]}
                                """))
                .andExpect(status().isOk());

        // TRACKING 直接回收非法 -> 422
        mockMvc.perform(post("/api/tracks/TRK-00000001/recycle"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason", is("ILLEGAL_TRANSITION")));

        // 推进时间使轨迹超时进入 LOST（批末补账写入事件流）
        mockMvc.perform(post("/api/tracks/associations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"detections":[
                                  {"detectionId":"rc2","timestamp":"2026-09-22T10:00:03Z",
                                   "source":"camera_front","category":"pedestrian",
                                   "x":30.0,"y":0.0,"confidence":0.9}
                                ]}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/tracks/TRK-00000001/recycle")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state", is("RECYCLED")))
                .andExpect(jsonPath("$.version", is(4)));

        // 回收后重复回收 -> 422
        mockMvc.perform(post("/api/tracks/TRK-00000001/recycle"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void duplicateDetectionIdReturnsFirstResultOverHttp() throws Exception {
        String body = """
                {"detections":[
                  {"detectionId":"dup-http","timestamp":"2026-09-22T10:00:00Z",
                   "source":"camera_front","category":"pedestrian",
                   "x":0.0,"y":0.0,"confidence":0.9}
                ]}
                """;
        mockMvc.perform(post("/api/tracks/associations")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].trackId", is("TRK-00000001")));

        mockMvc.perform(post("/api/tracks/associations")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].reason", startsWith("DUPLICATE")))
                .andExpect(jsonPath("$.results[0].trackId", is("TRK-00000001")));

        mockMvc.perform(get("/api/tracks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }
}
