package com.example.starter.water;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 幂等与并发集成测试（H2 MODE=MySQL）：同键同参回放首次结果、同键异参 409、
 * 并发下同键请求仅产生一次业务写入。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:waterquality_idem;"
        + "MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IdempotencyIntegrationTest extends AbstractWaterIntegrationTest {

    @AfterAll
    void releaseDatabase() {
        jdbcTemplate.execute("SHUTDOWN");
    }

    @Test
    void sameKeySameParamsReturnsFirstResult() throws Exception {
        String first = postSample(1L, "idem-1", 4.0, 0.3, 22.0)
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replay", "false"))
                .andReturn().getResponse().getContentAsString();

        postSample(1L, "idem-1", 4.0, 0.3, 22.0)
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andExpect(jsonPath("$.sampleId").value(extractSampleId(first)));

        assertEquals(1, countRows("water_sample"));
        assertEquals(1, countRows("idempotency_record"));
    }

    @Test
    void sameKeyDifferentParamsReturns409() throws Exception {
        postSample(1L, "idem-2", 4.0, 0.3, 22.0).andExpect(status().isOk());
        postSample(1L, "idem-2", 4.0, 0.3, 25.0).andExpect(status().isConflict());
        assertEquals(1, countRows("water_sample"));
    }

    @Test
    void differentKeysCreateIndependentWrites() throws Exception {
        postSample(1L, "idem-3a", 4.0, 0.3, 22.0).andExpect(status().isOk());
        postSample(1L, "idem-3b", 4.0, 0.3, 22.0).andExpect(status().isOk());
        assertEquals(2, countRows("water_sample"));
    }

    @Test
    void concurrentSameKeySameParamsProduceSingleWrite() throws Exception {
        List<ResponseSnapshot> responses = runConcurrent(8, index ->
                sampleJson("idem-c1", 4.0, 0.3, 22.0));

        Set<String> bodies = new HashSet<>();
        for (ResponseSnapshot response : responses) {
            assertEquals(200, response.status(), "并发同键同参应全部返回首次结果: " + response.body());
            bodies.add(response.body());
        }
        assertEquals(1, bodies.size(), "全部并发响应应回放同一首次结果");
        assertEquals(1, countRows("water_sample"));
        assertEquals(1, countRows("idempotency_record"));
    }

    @Test
    void concurrentSameKeyDifferentParamsHaveSingleWinner() throws Exception {
        List<ResponseSnapshot> responses = runConcurrent(8, index ->
                sampleJson("idem-c2", 4.0, 0.3, index < 4 ? 22.0 : 30.0));

        long okCount = responses.stream().filter(response -> response.status() == 200).count();
        long conflictCount = responses.stream().filter(response -> response.status() == 409).count();
        assertEquals(4, okCount, "胜出的参数组应全部拿到首次结果");
        assertEquals(4, conflictCount, "落败参数组应全部 409");

        Set<String> okBodies = new HashSet<>();
        responses.stream().filter(response -> response.status() == 200)
                .forEach(response -> okBodies.add(response.body()));
        assertEquals(1, okBodies.size());
        assertEquals(1, countRows("water_sample"));
        assertEquals(1, countRows("idempotency_record"));
    }

    private List<ResponseSnapshot> runConcurrent(int threads, RequestBodyFactory bodyFactory)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ResponseSnapshot>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int index = i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("并发起跑超时");
                }
                MvcResult result = mockMvc.perform(post("/api/tanks/{tankId}/samples", 1L)
                        .contentType("application/json")
                        .content(bodyFactory.body(index)))
                        .andReturn();
                return new ResponseSnapshot(result.getResponse().getStatus(),
                        result.getResponse().getContentAsString());
            }));
        }
        if (!ready.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("并发线程就绪超时");
        }
        start.countDown();
        List<ResponseSnapshot> responses = new ArrayList<>();
        for (Future<ResponseSnapshot> future : futures) {
            responses.add(future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdownNow();
        return responses;
    }

    private long extractSampleId(String body) {
        return Long.parseLong(body.replaceAll(".*\"sampleId\":(\\d+).*", "$1"));
    }

    @FunctionalInterface
    private interface RequestBodyFactory {
        String body(int index);
    }

    private record ResponseSnapshot(int status, String body) {
    }
}
