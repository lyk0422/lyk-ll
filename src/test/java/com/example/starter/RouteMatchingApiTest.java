package com.example.starter;

import com.example.starter.match.MatchCache;
import com.example.starter.route.RouteService;
import com.example.starter.web.dto.PointDto;
import com.example.starter.web.dto.RouteResponse;
import com.example.starter.web.dto.SubmitRouteRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 端到端集成测试：真实 H2（MODE=MySQL）库验证地图热更新、请求重放、
 * 缓存失效、多候选解释、路径断裂与并发幂等。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RouteMatchingApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MatchCache matchCache;

    @Autowired
    private RouteService routeService;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM route_request");
        jdbcTemplate.update("DELETE FROM map_segment");
        jdbcTemplate.update("DELETE FROM map_version");
        matchCache.clear();
    }

    private static String mapJson(String versionId, boolean a1Active) {
        return """
                {
                  "versionId": "%s",
                  "segments": [
                    {"segmentId":"A1","startNode":"n1","endNode":"n2","startX":0,"startY":0,"endX":100,"endY":0,"active":%s},
                    {"segmentId":"A2","startNode":"n1","endNode":"n2","startX":0,"startY":5,"endX":100,"endY":5,"active":true},
                    {"segmentId":"B1","startNode":"n2","endNode":"n3","startX":100,"startY":0,"endX":200,"endY":0,"active":true},
                    {"segmentId":"B2","startNode":"n2","endNode":"n3","startX":100,"startY":6,"endX":200,"endY":6,"active":true}
                  ]
                }
                """.formatted(versionId, a1Active);
    }

    private static String routeJson(String requestId, String versionId,
                                    double ox, double oy, double oh,
                                    double dx, double dy, double dh) {
        return """
                {"requestId":"%s","mapVersionId":"%s",
                 "origin":{"x":%s,"y":%s,"heading":%s},
                 "destination":{"x":%s,"y":%s,"heading":%s}}
                """.formatted(requestId, versionId, ox, oy, oh, dx, dy, dh);
    }

    private void publish(String versionId, boolean a1Active) throws Exception {
        mockMvc.perform(post("/api/map-versions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapJson(versionId, a1Active)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.versionId").value(versionId))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.segments.length()").value(4));
    }

    private void submitOk(String requestId, String versionId,
                          double ox, double oy, double oh,
                          double dx, double dy, double dh) throws Exception {
        mockMvc.perform(post("/api/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routeJson(requestId, versionId, ox, oy, oh, dx, dy, dh)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    @Test
    void 发布版本可查询且重复发布被拒绝() throws Exception {
        publish("v1", true);

        mockMvc.perform(get("/api/map-versions/v1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.segments[0].segmentId").value("A1"));

        mockMvc.perform(post("/api/map-versions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapJson("v1", true)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("MAP_VERSION_ALREADY_EXISTS"));

        mockMvc.perform(get("/api/map-versions/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("MAP_VERSION_NOT_FOUND"));
    }

    @Test
    void 路线匹配成功并输出多候选评分解释() throws Exception {
        publish("v1", true);

        mockMvc.perform(post("/api/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routeJson("r1", "v1", 50, 2, 0, 150, 3, 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value("r1"))
                .andExpect(jsonPath("$.mapVersionId").value("v1"))
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.errorCode").doesNotExist())
                .andExpect(jsonPath("$.result.origin.selectedSegmentId").value("A1"))
                .andExpect(jsonPath("$.result.origin.candidates.length()").value(2))
                .andExpect(jsonPath("$.result.origin.candidates[0].segmentId").value("A1"))
                .andExpect(jsonPath("$.result.origin.candidates[0].rank").value(1))
                .andExpect(jsonPath("$.result.origin.candidates[0].selected").value(true))
                .andExpect(jsonPath("$.result.origin.candidates[0].distanceMeters").value(2.0))
                .andExpect(jsonPath("$.result.origin.candidates[0].headingScore").value(1.0))
                .andExpect(jsonPath("$.result.origin.candidates[0].connectivityScore").value(1.0))
                .andExpect(jsonPath("$.result.origin.candidates[1].segmentId").value("A2"))
                .andExpect(jsonPath("$.result.origin.candidates[1].rank").value(2))
                .andExpect(jsonPath("$.result.origin.candidates[1].selected").value(false))
                .andExpect(jsonPath("$.result.origin.candidates[1].rejectionReason")
                        .value("SCORE_LOWER_THAN_SELECTED"))
                .andExpect(jsonPath("$.result.destination.selectedSegmentId").value("B1"))
                .andExpect(jsonPath("$.result.pathSegmentIds[0]").value("A1"))
                .andExpect(jsonPath("$.result.pathSegmentIds[1]").value("B1"));

        // 查询返回持久化快照
        mockMvc.perform(get("/api/routes/r1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.result.origin.selectedSegmentId").value("A1"));
    }

    @Test
    void 同参重放返回原结果异参重放返回409() throws Exception {
        publish("v1", true);
        submitOk("r1", "v1", 50, 2, 0, 150, 3, 0);

        // 同参重放：返回原请求与匹配结果
        mockMvc.perform(post("/api/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routeJson("r1", "v1", 50, 2, 0, 150, 3, 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.origin.selectedSegmentId").value("A1"));

        // 异参重放：409
        mockMvc.perform(post("/api/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routeJson("r1", "v1", 51, 2, 0, 150, 3, 0)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_CONFLICT"));

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM route_request WHERE request_id = 'r1'", Integer.class);
        assertEquals(1, count);
    }

    @Test
    void 地图热更新不影响已执行请求且缓存按版本隔离() throws Exception {
        publish("v1", true);
        submitOk("r1", "v1", 50, 2, 0, 150, 3, 0);
        assertEquals(1, matchCache.size());

        // 热更新：只能发布新版本 v2（A1 失效），v1 数据不可变
        publish("v2", false);

        // 新请求绑定 v2：A1 已失效，起点候选只剩 A2
        mockMvc.perform(post("/api/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routeJson("r2", "v2", 50, 2, 0, 150, 3, 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.origin.selectedSegmentId").value("A2"))
                .andExpect(jsonPath("$.result.origin.candidates.length()").value(1));

        // v1 与 v2 的缓存按键（版本+摘要）隔离共存，旧版本缓存不会命中新版本
        assertEquals(2, matchCache.size());

        // 旧请求重放仍返回 v1 的原结果（A1），不受新版本污染
        mockMvc.perform(post("/api/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routeJson("r1", "v1", 50, 2, 0, 150, 3, 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mapVersionId").value("v1"))
                .andExpect(jsonPath("$.result.origin.selectedSegmentId").value("A1"));

        // v1 快照不可变
        mockMvc.perform(get("/api/map-versions/v1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.segments[0].active").value(true));
    }

    @Test
    void 废弃版本拒绝新请求但旧请求可回放且缓存被驱逐() throws Exception {
        publish("v1", true);
        submitOk("r1", "v1", 50, 2, 0, 150, 3, 0);
        assertTrue(matchCache.size() > 0);

        mockMvc.perform(post("/api/map-versions/v1/deprecate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DEPRECATED"));

        // 版本废弃后缓存被驱逐
        assertEquals(0, matchCache.size());

        // 新请求被拒绝
        mockMvc.perform(post("/api/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routeJson("r2", "v1", 50, 2, 0, 150, 3, 0)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("MAP_VERSION_DEPRECATED"));

        // 旧请求同参回放仍返回原结果
        mockMvc.perform(post("/api/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routeJson("r1", "v1", 50, 2, 0, 150, 3, 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.origin.selectedSegmentId").value("A1"));
    }

    @Test
    void 版本不存在与参数校验错误() throws Exception {
        mockMvc.perform(post("/api/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routeJson("r1", "nope", 50, 2, 0, 150, 3, 0)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("MAP_VERSION_NOT_FOUND"));

        publish("v1", true);
        mockMvc.perform(post("/api/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routeJson("r2", "v1", 50, 2, 360, 150, 3, 0)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
    }

    @Test
    void 无候选与失败请求落库及重放() throws Exception {
        publish("v1", true);

        // 起点远离所有路段
        mockMvc.perform(post("/api/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routeJson("r1", "v1", 50, 500, 0, 150, 3, 0)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("NO_ORIGIN_CANDIDATE"));

        // 终点远离所有路段
        mockMvc.perform(post("/api/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routeJson("r2", "v1", 50, 2, 0, 150, 500, 0)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("NO_DESTINATION_CANDIDATE"));

        // 失败请求已落库，可查询
        mockMvc.perform(get("/api/routes/r1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.errorCode").value("NO_ORIGIN_CANDIDATE"));

        // 失败请求同参重放返回原错误码
        mockMvc.perform(post("/api/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routeJson("r1", "v1", 50, 500, 0, 150, 3, 0)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("NO_ORIGIN_CANDIDATE"));

        // 失败请求异参重放返回 409
        mockMvc.perform(post("/api/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routeJson("r1", "v1", 50, 501, 0, 150, 3, 0)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_CONFLICT"));
    }

    @Test
    void 路径断裂与路段失效错误码可区分() throws Exception {
        // 拓扑断裂：起终点各有候选但互不连通
        String disconnected = """
                {"versionId":"v-disc","segments":[
                  {"segmentId":"A1","startNode":"n1","endNode":"n2","startX":0,"startY":0,"endX":100,"endY":0,"active":true},
                  {"segmentId":"D1","startNode":"n5","endNode":"n6","startX":0,"startY":100,"endX":100,"endY":100,"active":true}
                ]}
                """;
        mockMvc.perform(post("/api/map-versions")
                        .contentType(MediaType.APPLICATION_JSON).content(disconnected))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routeJson("r1", "v-disc", 50, 2, 0, 50, 102, 0)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("PATH_DISCONNECTED"));

        // 起点附近仅存在失效路段
        String inactive = """
                {"versionId":"v-inact","segments":[
                  {"segmentId":"A1","startNode":"n1","endNode":"n2","startX":0,"startY":0,"endX":100,"endY":0,"active":false},
                  {"segmentId":"B1","startNode":"n2","endNode":"n3","startX":100,"startY":0,"endX":200,"endY":0,"active":true}
                ]}
                """;
        mockMvc.perform(post("/api/map-versions")
                        .contentType(MediaType.APPLICATION_JSON).content(inactive))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routeJson("r2", "v-inact", 50, 2, 0, 150, 3, 0)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("SEGMENT_INACTIVE"));
    }

    @Test
    void 并发同请求号同参提交幂等收敛() throws Exception {
        publish("v1", true);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<RouteResponse>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit((Callable<RouteResponse>) () -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return routeService.submit(new SubmitRouteRequest("r-conc", "v1",
                        new PointDto(50, 2, 0), new PointDto(150, 3, 0)));
            }));
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();

        List<RouteResponse> responses = new ArrayList<>();
        for (Future<RouteResponse> future : futures) {
            responses.add(future.get(60, TimeUnit.SECONDS));
        }
        pool.shutdownNow();

        // 全部成功且结果一致
        for (RouteResponse response : responses) {
            assertEquals("SUCCESS", response.status());
            assertEquals(responses.get(0).result(), response.result());
        }
        // 幂等：库中仅一条记录
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM route_request WHERE request_id = 'r-conc'", Integer.class);
        assertEquals(1, count);
    }
}
