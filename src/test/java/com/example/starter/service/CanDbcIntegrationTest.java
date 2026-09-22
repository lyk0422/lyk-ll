package com.example.starter.service;

import com.example.starter.dbc.Endian;
import com.example.starter.dbc.MultiplexMode;
import com.example.starter.repo.FrameRepository;
import com.example.starter.web.dto.FrameRequest;
import com.example.starter.web.dto.FrameResult;
import com.example.starter.web.dto.ImportRequest;
import com.example.starter.web.dto.SignalDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 基于 H2(MODE=MySQL) 的端到端集成测试：真实建表、唯一约束、事务回滚与并发写入。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CanDbcIntegrationTest {

    @Autowired
    private DbcImportService importService;
    @Autowired
    private FrameParseService frameParseService;
    @Autowired
    private FrameRepository frameRepository;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM signal_state_events");
        jdbc.update("DELETE FROM signal_state");
        jdbc.update("DELETE FROM frame_audit");
        jdbc.update("DELETE FROM dbc_signal_enums");
        jdbc.update("DELETE FROM dbc_signals");
        jdbc.update("DELETE FROM dbc_messages");
        jdbc.update("DELETE FROM dbc_versions");
        jdbc.update("DELETE FROM dbc_import_requests");
    }

    // ---------- 导入 ----------

    @Test
    void importIsIdempotentForSameParamsAndConflictsForDifferentParams() {
        ImportRequest first = importV1();
        DbcImportService.ImportOutcome out1 = importService.importVersion(first);
        assertFalse(out1.replayed());

        // 同 requestId 同参重放：幂等
        DbcImportService.ImportOutcome out2 = importService.importVersion(first);
        assertTrue(out2.replayed());
        assertEquals(1, count("dbc_versions"));
        assertEquals(1, count("dbc_import_requests"));

        // 同 requestId 异参：409
        ImportRequest different = new ImportRequest("imp-v1", "v1", List.of(
                new ImportRequest.MessageDto(0x100L, "CHANGED", 8, List.of(
                        new SignalDto("EngineSpeed", 0, 16, Endian.LITTLE, false, 0.25, 0.0,
                                MultiplexMode.NONE, null, Map.of())))));
        assertThrowsConflict(() -> importService.importVersion(different));
    }

    @Test
    void failedImportDoesNotPolluteVersionAndCanBeRetried() {
        // 非法定义：信号位超出 dlc
        ImportRequest bad = new ImportRequest("imp-bad", "vx", List.of(
                new ImportRequest.MessageDto(0x200L, "BAD", 1, List.of(
                        new SignalDto("s", 0, 16, Endian.LITTLE, false, 1.0, 0.0,
                                MultiplexMode.NONE, null, Map.of())))));
        assertThrowsInvalid(() -> importService.importVersion(bad));
        assertEquals(0, count("dbc_versions"));
        assertEquals(0, count("dbc_import_requests"));

        // 用新 requestId 导入合法定义的同名版本应成功
        DbcImportService.ImportOutcome ok = importService.importVersion(
                new ImportRequest("imp-good", "vx", List.of(message0x100(0.25))));
        assertFalse(ok.replayed());
        assertEquals(1, count("dbc_versions"));
    }

    @Test
    void httpEndpointsReturnExpectedStatusesAndFrameStructure() throws Exception {
        ImportRequest req = importV1();
        mockMvc.perform(post("/api/dbc/imports")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(false));
        mockMvc.perform(post("/api/dbc/imports")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true));

        // Base64 负载 {0x30,0x01,0x01,0x64,0,0,0,0xFF} -> MAEBZAAAAP8=
        String frame = "{\"version\":\"v1\",\"canId\":256,\"timestampNs\":1000,\"seq\":1,"
                + "\"payload\":\"MAEBZAAAAP8=\"}";
        mockMvc.perform(post("/api/dbc/frames")
                        .contentType("application/json")
                        .content(frame))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.duplicate").value(false))
                .andExpect(jsonPath("$.reason").doesNotExist())
                .andExpect(jsonPath("$.signals[?(@.name=='EngineSpeed')].physicalValue").value(
                        org.hamcrest.Matchers.hasItem(76.0)));

        // 未知 ID 返回可区分 reason（HTTP 仍 200）
        String unknown = "{\"version\":\"v1\",\"canId\":999,\"timestampNs\":1000,\"seq\":2,"
                + "\"payload\":\"AAAAAAAAAAA=\"}";
        mockMvc.perform(post("/api/dbc/frames")
                        .contentType("application/json")
                        .content(unknown))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(false))
                .andExpect(jsonPath("$.reason").value("UNKNOWN_ID"));
    }

    // ---------- 解码主流程：大小端、有符号、缩放、枚举、多路复用 ----------

    @Test
    void decodesLittleBigEndianSignedScaledEnumAndMuxedSignals() {
        importService.importVersion(importV1());
        // b0=0x30：EngineSpeed 低字节，Gear 高 nibble=3(D)；b1=0x01 -> EngineSpeed raw=0x130=304 -> 76
        // b2=0x01：Mux=1 -> SpeedA 生效（b3=0x64=100），SpeedB 不出现；b7=0xFF -> Temp raw=-1 -> -41
        byte[] payload = {0x30, 0x01, 0x01, 0x64, 0, 0, 0, (byte) 0xFF};
        FrameResult result = frameParseService.parse(new FrameRequest("v1", 0x100L, 1000L, 1L, payload));

        assertTrue(result.accepted());
        assertSignal(result, "EngineSpeed", 304L, 76.0d, null);
        assertSignal(result, "Gear", 3L, 3.0d, "D");
        assertSignal(result, "Mux", 1L, 1.0d, null);
        assertSignal(result, "SpeedA", 100L, 100.0d, null);
        assertSignal(result, "Temp", -1L, -41.0d, null);
        assertNull(signal(result, "SpeedB"), "不满足多路复用条件的信号不应解码");
    }

    @Test
    void muxValueTwoSelectsOtherMuxedGroup() {
        importService.importVersion(importV1());
        // Mux=2 -> SpeedB（start24 len16 little，b3=0x78,b4=0x00 -> 120）生效，SpeedA 不出现
        byte[] payload = {0x30, 0x01, 0x02, 0x78, 0x00, 0, 0, (byte) 0xFF};
        FrameResult result = frameParseService.parse(new FrameRequest("v1", 0x100L, 1000L, 1L, payload));
        assertTrue(result.accepted());
        assertSignal(result, "SpeedB", 120L, 120.0d, null);
        assertNull(signal(result, "SpeedA"));
    }

    @Test
    void unmatchedMuxValueRejectsFrameAndDoesNotUpdateState() {
        importService.importVersion(importV1());
        byte[] payload = {0x30, 0x01, 0x09, 0x64, 0, 0, 0, (byte) 0xFF};
        FrameResult result = frameParseService.parse(new FrameRequest("v1", 0x100L, 1000L, 7L, payload));
        assertFalse(result.accepted());
        assertEquals("MUX_NOT_MATCHED", result.reason());
        assertNull(frameRepository.findState("v1", 0x100L, "EngineSpeed"));
    }

    // ---------- 非法帧 ----------

    @Test
    void invalidLengthAndUnknownIdReturnDistinguishableReasons() {
        importService.importVersion(importV1());

        FrameResult shortFrame = frameParseService.parse(
                new FrameRequest("v1", 0x100L, 1000L, 10L, new byte[]{0, 0, 0}));
        assertFalse(shortFrame.accepted());
        assertEquals("INVALID_LENGTH", shortFrame.reason());

        FrameResult nineBytes = frameParseService.parse(
                new FrameRequest("v1", 0x100L, 1000L, 11L, new byte[9]));
        assertFalse(nineBytes.accepted());
        assertEquals("INVALID_LENGTH", nineBytes.reason());

        FrameResult unknown = frameParseService.parse(
                new FrameRequest("v1", 0x999L, 1000L, 12L, new byte[8]));
        assertFalse(unknown.accepted());
        assertEquals("UNKNOWN_ID", unknown.reason());
    }

    // ---------- 版本切换：同 CAN ID 在不同版本独立解码、独立状态 ----------

    @Test
    void sameCanIdDecodesIndependentlyAcrossVersions() {
        importService.importVersion(importV1());
        // v2：EngineSpeed factor=0.5，其余与 v1 相同
        importService.importVersion(new ImportRequest("imp-v2", "v2", List.of(message0x100(0.5))));

        byte[] payload = {0x30, 0x01, 0x01, 0x64, 0, 0, 0, 0};
        FrameResult r1 = frameParseService.parse(new FrameRequest("v1", 0x100L, 1000L, 1L, payload));
        FrameResult r2 = frameParseService.parse(new FrameRequest("v2", 0x100L, 1000L, 1L, payload));

        assertEquals(76.0d, signal(r1, "EngineSpeed").physicalValue(), 1e-9);
        assertEquals(152.0d, signal(r2, "EngineSpeed").physicalValue(), 1e-9);

        FrameRepository.SignalStateRow s1 = frameRepository.findState("v1", 0x100L, "EngineSpeed");
        FrameRepository.SignalStateRow s2 = frameRepository.findState("v2", 0x100L, "EngineSpeed");
        assertNotNull(s1);
        assertNotNull(s2);
        assertEquals(76.0d, s1.physicalValue(), 1e-9);
        assertEquals(152.0d, s2.physicalValue(), 1e-9);
    }

    // ---------- 乱序/重放/事件 ----------

    @Test
    void oldTimestampOrLowerSeqNeverOverwritesStateAndReplayCreatesNoEvents() {
        importService.importVersion(importV1());

        // 第 1 帧（seq=10, ts=1000）EngineSpeed raw=0x0130(304)
        parseEngineFrame(10L, 1000L, 0x30, 0x01);
        // 第 2 帧（seq=2, ts=2000）时戳更新，raw=0x0230(560)
        parseEngineFrame(2L, 2000L, 0x30, 0x02);
        long eventsAfterTwo = frameRepository.countEvents("v1", 0x100L);
        FrameRepository.SignalStateRow current = frameRepository.findState("v1", 0x100L, "EngineSpeed");
        assertEquals(560L, current.rawValue());
        assertEquals(2L, current.seq());

        // 延迟到达的旧帧 ts=1500：不得覆盖、任何信号都不产生事件
        parseEngineFrame(3L, 1500L, 0x30, 0x09);
        FrameRepository.SignalStateRow afterOld = frameRepository.findState("v1", 0x100L, "EngineSpeed");
        assertEquals(560L, afterOld.rawValue(), "旧时戳帧不得覆盖状态");
        assertEquals(2L, afterOld.seq());
        assertEquals(eventsAfterTwo, frameRepository.countEvents("v1", 0x100L),
                "旧时戳帧不得产生状态变更事件");

        // 相同时戳、更低序号(seq=1 < 2)也不得覆盖
        parseEngineFrame(1L, 2000L, 0x30, 0x09);
        assertEquals(560L, frameRepository.findState("v1", 0x100L, "EngineSpeed").rawValue());

        // 相同帧重放：duplicate=true、不更新、不产生事件
        FrameResult replay = parseEngineFrame(2L, 2000L, 0x30, 0x02);
        assertTrue(replay.duplicate());
        assertFalse(replay.stateUpdated());
        assertEquals(eventsAfterTwo, frameRepository.countEvents("v1", 0x100L));
    }

    @Test
    void replayWithSameSeqButDifferentPayloadConflicts() {
        importService.importVersion(importV1());
        parseEngineFrame(5L, 1000L, 0x30, 0x01);
        assertThrowsConflict(() -> parseEngineFrame(5L, 1000L, 0x31, 0x01));
        // 同序号不同时戳同样冲突
        assertThrowsConflict(() -> parseEngineFrame(5L, 1001L, 0x30, 0x01));
    }

    // ---------- 并发 ----------

    @Test
    void concurrentFramesForOneSignalConvergeToNewestState() throws Exception {
        importService.importVersion(importV1());
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger failures = new AtomicInteger();
        List<Long> timestamps = new ArrayList<>();
        for (int i = 1; i <= threads; i++) {
            timestamps.add((long) (1000 + i));
        }
        // 打乱时戳与线程的对应顺序，制造真正的竞态
        java.util.Collections.shuffle(timestamps);
        for (int i = 0; i < threads; i++) {
            final long seq = i + 1L;
            final long ts = timestamps.get(i);
            pool.submit(() -> {
                try {
                    ready.countDown();
                    start.await();
                    parseEngineFrame(seq, ts, 0x30, (int) (seq & 0xFF));
                } catch (Exception ex) {
                    failures.incrementAndGet();
                }
            });
        }
        ready.await(5, TimeUnit.SECONDS);
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS));
        assertEquals(0, failures.get());

        FrameRepository.SignalStateRow state = frameRepository.findState("v1", 0x100L, "EngineSpeed");
        assertNotNull(state);
        // 最终状态必须来自时戳最大的帧 ts=1008，raw 高字节=0，低字节为该帧 seq
        assertEquals(1008L, state.timestampNs());
        assertEquals(0x30L + (state.seq() << 8), state.rawValue());
        // 审计不重不漏
        Long auditCount = jdbc.queryForObject(
                "SELECT COUNT(1) FROM frame_audit WHERE version='v1' AND can_id=256", Long.class);
        assertEquals(threads, auditCount.intValue());
    }

    // ---------- 辅助 ----------

    private FrameResult parseEngineFrame(long seq, long ts, int lowByte, int highByte) {
        byte[] payload = {
                (byte) lowByte, (byte) highByte, 0x01, 0x64, 0, 0, 0, 0
        };
        return frameParseService.parse(new FrameRequest("v1", 0x100L, ts, seq, payload));
    }

    private void assertSignal(FrameResult result, String name, long raw, double physical, String label) {
        com.example.starter.web.dto.SignalValueDto v = signal(result, name);
        assertNotNull(v, "缺少信号: " + name);
        assertEquals(raw, v.rawValue(), name + " raw");
        assertEquals(physical, v.physicalValue(), 1e-9, name + " physical");
        assertEquals(label, v.enumLabel(), name + " enum");
    }

    private com.example.starter.web.dto.SignalValueDto signal(FrameResult result, String name) {
        return result.signals().stream()
                .filter(s -> s.name().equals(name))
                .findFirst().orElse(null);
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(1) FROM " + table, Long.class);
    }

    private void assertThrowsConflict(Runnable action) {
        org.junit.jupiter.api.Assertions.assertThrows(com.example.starter.dbc.ConflictException.class,
                action::run);
    }

    private void assertThrowsInvalid(Runnable action) {
        org.junit.jupiter.api.Assertions.assertThrows(com.example.starter.dbc.InvalidDefinitionException.class,
                action::run);
    }

    private ImportRequest importV1() {
        return new ImportRequest("imp-v1", "v1", List.of(message0x100(0.25)));
    }

    /**
     * 0x100 / dlc8 测试报文：
     * EngineSpeed Intel 0/16 factor 可变；Gear Motorola start7 len4 枚举；
     * Mux Intel start16 len4；SpeedA mux=1 Intel 24/8；SpeedB mux=2 Intel 24/16；
     * Temp Motorola start63 len8 有符号 offset=-40。
     */
    private ImportRequest.MessageDto message0x100(double engineFactor) {
        return new ImportRequest.MessageDto(0x100L, "EngineData", 8, List.of(
                new SignalDto("EngineSpeed", 0, 16, Endian.LITTLE, false, engineFactor, 0.0,
                        MultiplexMode.NONE, null, Map.of()),
                new SignalDto("Gear", 7, 4, Endian.BIG, false, 1.0, 0.0,
                        MultiplexMode.NONE, null,
                        Map.of("0", "P", "1", "R", "2", "N", "3", "D")),
                new SignalDto("Mux", 16, 4, Endian.LITTLE, false, 1.0, 0.0,
                        MultiplexMode.MULTIPLEXOR, null, Map.of()),
                new SignalDto("SpeedA", 24, 8, Endian.LITTLE, false, 1.0, 0.0,
                        MultiplexMode.MUXED, 1L, Map.of()),
                new SignalDto("SpeedB", 24, 16, Endian.LITTLE, false, 1.0, 0.0,
                        MultiplexMode.MUXED, 2L, Map.of()),
                new SignalDto("Temp", 63, 8, Endian.BIG, true, 1.0, -40.0,
                        MultiplexMode.NONE, null, Map.of())));
    }
}
