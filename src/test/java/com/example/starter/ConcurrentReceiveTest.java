package com.example.starter;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 并发接入测试: 同一股道同时刻只允许一列作业中列车, 并发接入唯一受理。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:test_concurrent;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConcurrentReceiveTest {

    private static final int THREADS = 4;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanTables() {
        jdbcTemplate.update("DELETE FROM idempotency_record");
        jdbcTemplate.update("DELETE FROM pressure_reading");
        jdbcTemplate.update("DELETE FROM brake_test");
        jdbcTemplate.update("DELETE FROM consist_check");
        jdbcTemplate.update("DELETE FROM operation_car");
        jdbcTemplate.update("DELETE FROM train_operation");
    }

    @AfterAll
    void releaseDatabase() {
        jdbcTemplate.execute("SHUTDOWN");
    }

    @Test
    void concurrentReceiveOnSameTrackAdmitsExactlyOne() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            final int index = i;
            futures.add(executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS), "并发起点等待超时");
                Map<String, Object> body = Map.of(
                        "requestId", "conc-" + index,
                        "trackNo", "TRACK-C1",
                        "trainNo", "K" + index,
                        "setPressureKpa", 500,
                        "cars", List.of(Map.of(
                                "carNo", "CAR-C" + index,
                                "lengthCoeff", "1.10",
                                "weightTons", "80.00",
                                "brakeCutout", false)));
                return mockMvc.perform(post("/api/operations")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(body)))
                        .andReturn().getResponse().getStatus();
            }));
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS), "线程就绪超时");
        start.countDown();

        int created = 0;
        int conflict = 0;
        for (Future<Integer> future : futures) {
            int status = future.get(15, TimeUnit.SECONDS);
            if (status == 201) {
                created++;
            } else if (status == 409) {
                conflict++;
            }
        }
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(1, created, "并发接入须唯一受理");
        assertEquals(THREADS - 1, conflict, "其余并发接入须返回 409");
        Integer active = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM train_operation WHERE active_track = 'TRACK-C1'",
                Integer.class);
        assertEquals(1, active, "股道上只允许一列作业中列车");
        Integer total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM train_operation WHERE track_no = 'TRACK-C1'",
                Integer.class);
        assertEquals(1, total, "未受理的并发请求不得留下作业记录");
    }
}
