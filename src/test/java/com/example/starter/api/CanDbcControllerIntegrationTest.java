package com.example.starter.api;

import com.example.starter.api.dto.DbcImportRequest;
import com.example.starter.api.dto.DbcImportResponse;
import com.example.starter.api.dto.FrameParseRequest;
import com.example.starter.api.dto.FrameParseResponse;
import com.example.starter.testsupport.AbstractIntegrationTest;
import com.example.starter.testsupport.TestDbcFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 HTTP 入口集成测试：状态码（200/400/404/409）与可区分的帧拒绝 JSON。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CanDbcControllerIntegrationTest extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private DbcImportRequest sampleImport() {
        return TestDbcFactory.version("http-v1", "http-req-1")
                .message(0x100, "EngineMsg", 2)
                .signal("Rpm", "LITTLE_ENDIAN", 0, 16, false, 0.25, 100, "rpm")
                .endMessage()
                .build();
    }

    @Test
    void importReplayAndParseOverHttp() {
        ResponseEntity<DbcImportResponse> created = restTemplate.postForEntity(
                url("/api/can/dbc/versions"), sampleImport(), DbcImportResponse.class);
        assertEquals(HttpStatus.OK, created.getStatusCode());
        assertNotNull(created.getBody());
        assertFalse(created.getBody().replayed());
        long versionId = created.getBody().versionId();

        ResponseEntity<DbcImportResponse> replay = restTemplate.postForEntity(
                url("/api/can/dbc/versions"), sampleImport(), DbcImportResponse.class);
        assertEquals(HttpStatus.OK, replay.getStatusCode());
        assertTrue(replay.getBody().replayed());
        assertEquals(versionId, replay.getBody().versionId());

        // 正常帧：0x0640=1600 → 物理值 500
        ResponseEntity<FrameParseResponse> parsed = restTemplate.postForEntity(
                url("/api/can/frames"),
                new FrameParseRequest("http-v1", 0x100L, 1000L, 1L,"4006"),
                FrameParseResponse.class);
        assertEquals(HttpStatus.OK, parsed.getStatusCode());
        assertTrue(parsed.getBody().accepted());
        FrameParseResponse.DecodedSignal rpm = parsed.getBody().signals().get(0);
        assertEquals("Rpm", rpm.name());
        assertEquals(1600L, rpm.rawValue());
        assertEquals(500.0, rpm.physicalValue(), 1e-9);
    }

    @Test
    void differentParamsWithSameRequestIdReturns409() {
        restTemplate.postForEntity(url("/api/can/dbc/versions"), sampleImport(), Object.class);

        DbcImportRequest changed = TestDbcFactory.version("http-v1", "http-req-1")
                .message(0x100, "EngineMsg", 2)
                .signal("Rpm", "LITTLE_ENDIAN", 0, 16, false, 0.5, 100, "rpm")
                .endMessage()
                .build();
        ResponseEntity<String> conflict = restTemplate.postForEntity(
                url("/api/can/dbc/versions"), changed, String.class);
        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode());
        assertTrue(conflict.getBody().contains("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void invalidDefinitionReturns400() {
        DbcImportRequest invalid = TestDbcFactory.version("http-bad", "http-req-bad")
                .message(0x100, "Msg", 1)
                .signal("S1", "LITTLE_ENDIAN", 4, 8, false, 1, 0, "")
                .endMessage()
                .build();
        ResponseEntity<String> bad = restTemplate.postForEntity(
                url("/api/can/dbc/versions"), invalid, String.class);
        assertEquals(HttpStatus.BAD_REQUEST, bad.getStatusCode());
    }

    @Test
    void beanValidationReturns400() {
        DbcImportRequest invalid = TestDbcFactory.version("", "req")
                .message(0x100, "Msg", 1)
                .signal("S1", "LITTLE_ENDIAN", 0, 4, false, 1, 0, "")
                .endMessage()
                .build();
        ResponseEntity<String> bad = restTemplate.postForEntity(
                url("/api/can/dbc/versions"), invalid, String.class);
        assertEquals(HttpStatus.BAD_REQUEST, bad.getStatusCode());
    }

    @Test
    void badLengthFrameReturnsDistinguishableResult() {
        restTemplate.postForEntity(url("/api/can/dbc/versions"), sampleImport(), Object.class);

        ResponseEntity<FrameParseResponse> response = restTemplate.postForEntity(
                url("/api/can/frames"),
                new FrameParseRequest("http-v1", 0x100L, 1000L, 1L,"40"),
                FrameParseResponse.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertFalse(response.getBody().accepted());
        assertEquals("BAD_LENGTH", response.getBody().rejectReason());
    }

    @Test
    void unknownIdAndUnknownVersionAreDistinguishable() {
        restTemplate.postForEntity(url("/api/can/dbc/versions"), sampleImport(), Object.class);

        // 版本存在但 CAN ID 未知：业务可区分结果
        ResponseEntity<FrameParseResponse> unknownId = restTemplate.postForEntity(
                url("/api/can/frames"),
                new FrameParseRequest("http-v1", 0x999L, 1000L, 1L, "0000"),
                FrameParseResponse.class);
        assertEquals(HttpStatus.OK, unknownId.getStatusCode());
        assertEquals("UNKNOWN_ID", unknownId.getBody().rejectReason());

        // 版本不存在：404
        ResponseEntity<String> unknownVersion = restTemplate.postForEntity(
                url("/api/can/frames"),
                new FrameParseRequest("missing-version", 0x100L, 1000L, 1L, "4006"),
                String.class);
        assertEquals(HttpStatus.NOT_FOUND, unknownVersion.getStatusCode());
    }

    @Test
    void malformedHexPayloadReturns400() {
        restTemplate.postForEntity(url("/api/can/dbc/versions"), sampleImport(), Object.class);

        ResponseEntity<String> bad = restTemplate.postForEntity(
                url("/api/can/frames"),
                new FrameParseRequest("http-v1", 0x100L, 1000L, 1L,"ZZZ"),
                String.class);
        assertEquals(HttpStatus.BAD_REQUEST, bad.getStatusCode());
    }
}
