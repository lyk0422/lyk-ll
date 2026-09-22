package com.example.starter.service;

import com.example.starter.api.dto.FrameParseRequest;
import com.example.starter.api.dto.FrameParseResponse;
import com.example.starter.api.dto.SignalStateDto;
import com.example.starter.testsupport.AbstractIntegrationTest;
import com.example.starter.testsupport.TestDbcFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 帧解析并发边界 H2 测试：真实多线程下乱序帧不覆盖状态、同帧并发重放不重复产生事件。
 */
@SpringBootTest
class FrameParseConcurrencyIntegrationTest extends AbstractIntegrationTest {

    private static final long CAN_ID = 0x100L;
    private static final int THREADS = 8;

    @Autowired
    private DbcImportService importService;

    @Autowired
    private FrameParseService frameParseService;

    @BeforeEach
    void importDefinition() {
        importService.importVersion(TestDbcFactory.version("v1", "req-conc")
                .message(CAN_ID, "CounterMsg", 1)
                .signal("Counter", "LITTLE_ENDIAN", 0, 8, false, 1, 0, "")
                .endMessage()
                .build());
    }

    @Test
    void highestSeqFrameFirstThenConcurrentLowerFramesAllStale() throws Exception {
        // 最高序号帧先到达并接受
        FrameParseResponse first = frameParseService.parse(
                new FrameParseRequest("v1", CAN_ID, 1030L, 30L, "1E"));
        assertTrue(first.accepted());

        // 随后低序号帧 1..29 并发乱序到达，全部不得覆盖状态
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int seq = 1; seq <= 29; seq++) {
            int finalSeq = seq;
            futures.add(pool.submit(() -> {
                start.await();
                frameParseService.parse(new FrameParseRequest(
                        "v1", CAN_ID, 1000L + finalSeq, (long) finalSeq,
                        String.format("%02X", finalSeq)));
                return null;
            }));
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "并发帧处理必须在超时前完成");
        for (Future<?> future : futures) {
            future.get();
        }

        SignalStateDto state = frameParseService.listStates("v1", CAN_ID).get(0);
        assertEquals(30L, state.frameSeq());
        assertEquals(30L, state.rawValue());

        assertEquals(1L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM frame_audit WHERE accepted = TRUE", Long.class));
        assertEquals(29L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM frame_audit WHERE accepted = FALSE AND reject_reason = 'STALE_FRAME'",
                Long.class));
        assertEquals(30L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM frame_audit", Long.class));
        // 只有最高帧产生过一次事件
        assertEquals(1L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM state_change_event", Long.class));
    }

    @Test
    void concurrentFramesProcessedInSeqOrderAllAccepted() throws Exception {
        // 多个工作线程竞争处理，但每个序号等待前一序号已接受后再提交，
        // 从而在真实并发下确定性地验证：有序帧全部接受，事件不重不漏。
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int seq = 1; seq <= 30; seq++) {
            int finalSeq = seq;
            futures.add(pool.submit(() -> {
                start.await();
                awaitAcceptedSeq(finalSeq - 1);
                frameParseService.parse(new FrameParseRequest(
                        "v1", CAN_ID, 1000L + finalSeq, (long) finalSeq,
                        String.format("%02X", finalSeq)));
                return null;
            }));
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        for (Future<?> future : futures) {
            future.get();
        }

        SignalStateDto state = frameParseService.listStates("v1", CAN_ID).get(0);
        assertEquals(30L, state.frameSeq());
        assertEquals(30L, state.rawValue());
        assertEquals(1030L, state.timestampNs());

        assertEquals(30L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM frame_audit WHERE accepted = TRUE", Long.class));
        assertEquals(0L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM frame_audit WHERE reject_reason = 'STALE_FRAME'", Long.class));

        // Counter 每次原始值都不同，每个接受帧恰好一条事件
        assertEquals(30L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM state_change_event", Long.class));
        assertEquals(30L, jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT frame_seq) FROM state_change_event", Long.class));
    }

    @Test
    void concurrentReplaysOfSameFrameProduceSingleEvent() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<FrameParseResponse>> futures = new ArrayList<>();

        for (int i = 0; i < THREADS; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return frameParseService.parse(new FrameParseRequest(
                        "v1", CAN_ID, 1000L, 1L, "0A"));
            }));
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        int acceptedFresh = 0;
        int replayed = 0;
        for (Future<FrameParseResponse> future : futures) {
            FrameParseResponse response = future.get();
            assertTrue(response.accepted());
            if (response.replayed()) {
                replayed++;
            } else {
                acceptedFresh++;
            }
        }
        assertEquals(1, acceptedFresh, "仅有一个线程将帧作为新帧接受");
        assertEquals(THREADS - 1, replayed, "其余线程全部判定为相同帧重放");

        // 状态仅一行、事件仅一条
        assertEquals(1L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM signal_state", Long.class));
        assertEquals(1L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM state_change_event", Long.class));
        assertEquals(THREADS, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM frame_audit", Long.class), "重放帧也应留存审计");
    }

    private void awaitAcceptedSeq(long seq) throws InterruptedException {
        if (seq == 0) {
            return;
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            Long count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM frame_audit WHERE frame_seq = ? AND accepted = TRUE",
                    Long.class, seq);
            if (count != null && count > 0) {
                return;
            }
            Thread.sleep(2L);
        }
        throw new AssertionError("等待序号 " + seq + " 的帧被接受超时");
    }
}
