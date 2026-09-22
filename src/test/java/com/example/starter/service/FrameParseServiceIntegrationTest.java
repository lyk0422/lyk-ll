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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 帧解析 H2 集成测试：大小端/符号缩放、多路复用、版本独立、
 * 非法帧可区分拒绝、乱序帧不覆盖状态、相同帧重放不重复产生事件。
 */
@SpringBootTest
class FrameParseServiceIntegrationTest extends AbstractIntegrationTest {

    private static final long ENGINE_ID = 0x100L;
    private static final long MUX_ID = 0x200L;

    @Autowired
    private DbcImportService importService;

    @Autowired
    private FrameParseService frameParseService;

    @BeforeEach
    void importDefinitions() {
        importService.importVersion(TestDbcFactory.version("v1", "req-v1")
                .message(ENGINE_ID, "EngineMsg", 4)
                .signal("Rpm", "LITTLE_ENDIAN", 0, 16, false, 0.25, 100, "rpm")
                .signal("Temp", "BIG_ENDIAN", 31, 8, true, 0.5, -40, "degC")
                .endMessage()
                .message(MUX_ID, "MuxMsg", 2)
                .signal("Mode", "LITTLE_ENDIAN", 0, 2, false, 1, 0, "", "MULTIPLEXOR", null, null)
                .signal("Gear", "LITTLE_ENDIAN", 2, 4, false, 1, 0, "",
                        "MULTIPLEXED", 1L, Map.of(1L, "D", 2L, "P", 3L, "N"))
                .signal("Brake", "LITTLE_ENDIAN", 2, 4, false, 1, 0, "",
                        "MULTIPLEXED", 2L, null)
                .endMessage()
                .build());

        // 相同 CAN ID 在另一版本中给出不同定义，用于验证版本间状态独立
        importService.importVersion(TestDbcFactory.version("v2", "req-v2")
                .message(ENGINE_ID, "EngineMsgV2", 1)
                .signal("Level", "LITTLE_ENDIAN", 0, 8, false, 1, 0, "lv")
                .endMessage()
                .build());
    }

    private FrameParseResponse parse(String version, long canId, long seq, long ts, String payloadHex) {
        return frameParseService.parse(new FrameParseRequest(version, canId, ts, seq, payloadHex));
    }

    @Test
    void decodesLittleEndianWithScaleOffsetAndEnum() {
        // Rpm: 0x0640 = 1600 -> 1600*0.25+100 = 500
        FrameParseResponse response = parse("v1", ENGINE_ID, 1, 1000, "40060000");
        assertTrue(response.accepted());
        FrameParseResponse.DecodedSignal rpm = signal(response, "Rpm");
        assertEquals(1600L, rpm.rawValue());
        assertEquals(500.0, rpm.physicalValue(), 1e-9);
        assertEquals("rpm", rpm.unit());
        assertNull(rpm.enumText());
    }

    @Test
    void decodesBigEndianSignedNegative() {
        // Temp 位于 byte3：0xF0 作为有符号 8 位 = -16 -> -16*0.5-40 = -48
        FrameParseResponse response = parse("v1", ENGINE_ID, 1, 1000, "400600F0");
        FrameParseResponse.DecodedSignal temp = signal(response, "Temp");
        assertEquals(-16L, temp.rawValue());
        assertEquals(-48.0, temp.physicalValue(), 1e-9);
    }

    @Test
    void enumTextDecodedForMultiplexedBranch() {
        // Mode=1 时 Gear 激活：byte0 = 0x01 | (3<<2) = 0x0D，Gear=3 -> "N"
        FrameParseResponse response = parse("v1", MUX_ID, 1, 1000, "0D00");
        assertTrue(response.accepted());
        FrameParseResponse.DecodedSignal mode = signal(response, "Mode");
        assertEquals(1L, mode.rawValue());
        FrameParseResponse.DecodedSignal gear = signal(response, "Gear");
        assertEquals(3L, gear.rawValue());
        assertEquals("N", gear.enumText());
        // 未激活的分支不参与解码
        assertTrue(response.signals().stream().noneMatch(s -> s.name().equals("Brake")));
    }

    @Test
    void muxBranchTwoDecodesOtherSignal() {
        // Mode=2 时 Brake 激活：byte0 = 0x02 | (5<<2) = 0x16
        FrameParseResponse response = parse("v1", MUX_ID, 1, 1000, "1600");
        FrameParseResponse.DecodedSignal brake = signal(response, "Brake");
        assertEquals(5L, brake.rawValue());
        assertTrue(response.signals().stream().noneMatch(s -> s.name().equals("Gear")));
    }

    @Test
    void unknownCanIdIsDistinguishable() {
        FrameParseResponse response = parse("v1", 0x999, 1, 1000, "00");
        assertFalse(response.accepted());
        assertEquals("UNKNOWN_ID", response.rejectReason());
    }

    @Test
    void badLengthIsDistinguishable() {
        FrameParseResponse response = parse("v1", ENGINE_ID, 1, 1000, "400600");
        assertFalse(response.accepted());
        assertEquals("BAD_LENGTH", response.rejectReason());
    }

    @Test
    void muxNotMatchedIsDistinguishable() {
        // Mode=0 没有对应的被选择分支
        FrameParseResponse response = parse("v1", MUX_ID, 1, 1000, "0000");
        assertFalse(response.accepted());
        assertEquals("MUX_NOT_MATCH", response.rejectReason());
    }

    @Test
    void staleFrameBySeqOrTimestampDoesNotOverrideState() {
        parse("v1", ENGINE_ID, 1, 1000, "40060010");
        parse("v1", ENGINE_ID, 2, 2000, "800600F0");

        // 低序号帧：乱序到达
        FrameParseResponse olderSeq = parse("v1", ENGINE_ID, 1, 500, "40060010");
        assertTrue(olderSeq.stale());
        assertEquals("STALE_FRAME", olderSeq.rejectReason());

        // 序号新但时戳旧：同样拒绝
        FrameParseResponse olderTs = parse("v1", ENGINE_ID, 3, 1500, "00000000");
        assertTrue(olderTs.stale());

        // 输出状态仍为 seq=2 的值
        List<SignalStateDto> states = frameParseService.listStates("v1", ENGINE_ID);
        SignalStateDto rpm = states.stream().filter(s -> s.signalName().equals("Rpm")).findFirst().orElseThrow();
        assertEquals(2L, rpm.frameSeq());
        assertEquals(0x0680L, rpm.rawValue());
        SignalStateDto temp = states.stream().filter(s -> s.signalName().equals("Temp")).findFirst().orElseThrow();
        assertEquals(-16L, temp.rawValue());

        // 乱序帧仍留存审计
        assertEquals(2L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM frame_audit WHERE accepted = FALSE AND reject_reason = 'STALE_FRAME'",
                Long.class));
    }

    @Test
    void identicalFrameReplayDoesNotDuplicateEventsOrStateChange() {
        parse("v1", ENGINE_ID, 1, 1000, "40060010");
        FrameParseResponse replay = parse("v1", ENGINE_ID, 1, 1000, "40060010");

        assertTrue(replay.accepted());
        assertTrue(replay.replayed());
        assertEquals(2L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM state_change_event", Long.class));
        // 相同帧的多次重放仍然幂等
        parse("v1", ENGINE_ID, 1, 1000, "40060010");
        assertEquals(2L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM state_change_event", Long.class));
        assertEquals(2L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM signal_state", Long.class));
    }

    @Test
    void eventsProducedOnlyWhenRawValueActuallyChanges() {
        parse("v1", ENGINE_ID, 1, 1000, "40060010");
        // Rpm 变化、Temp 不变：只产生 Rpm 事件
        parse("v1", ENGINE_ID, 2, 2000, "80060010");
        assertEquals(1L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM state_change_event WHERE frame_seq = 2", Long.class));
        // 相同值的更新帧不产生事件
        parse("v1", ENGINE_ID, 3, 3000, "80060010");
        assertEquals(3L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM state_change_event", Long.class));
    }

    @Test
    void sameSeqSameTsDifferentPayloadIsStaleNotReplay() {
        parse("v1", ENGINE_ID, 1, 1000, "40060010");
        FrameParseResponse response = parse("v1", ENGINE_ID, 1, 1000, "40060011");
        assertFalse(response.replayed());
        assertTrue(response.stale());
    }

    @Test
    void stateIsIndependentAcrossVersionsForSameCanId() {
        // v1 中 0x100 为 4 字节发动机帧
        parse("v1", ENGINE_ID, 1, 1000, "40060010");
        // v2 中同 CAN ID 为 1 字节独立定义
        FrameParseResponse v2Response = parse("v2", ENGINE_ID, 7, 7000, "2A");
        assertTrue(v2Response.accepted());
        SignalStateDto level = frameParseService.listStates("v2", ENGINE_ID).get(0);
        assertEquals("Level", level.signalName());
        assertEquals(42L, level.rawValue());
        assertEquals(7L, level.frameSeq());

        // v1 状态不受影响
        SignalStateDto rpm = frameParseService.listStates("v1", ENGINE_ID).stream()
                .filter(s -> s.signalName().equals("Rpm")).findFirst().orElseThrow();
        assertEquals(1600L, rpm.rawValue());
        assertEquals(1L, rpm.frameSeq());
    }

    private FrameParseResponse.DecodedSignal signal(FrameParseResponse response, String name) {
        return response.signals().stream()
                .filter(s -> s.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("解码结果中缺少信号: " + name));
    }
}
