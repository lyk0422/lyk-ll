package com.example.starter.calibration.service;

import com.example.starter.calibration.api.CanaryReleaseRequest;
import com.example.starter.calibration.api.CreateParamRequest;
import com.example.starter.calibration.api.ResolveRequest;
import com.example.starter.calibration.domain.CalibrationKind;
import com.example.starter.calibration.domain.SensorType;
import com.example.starter.calibration.repo.CalibrationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 范围解析与发布边界的补充 H2 集成测试：
 * 地图版本范围、传感器范围、重复版本幂等、非法状态发布、声明范围外车辆、IMU/Radar 持久化。
 */
@SpringBootTest
class CalibrationScopingIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-09-23T00:00:00Z");

    @Autowired
    private CalibrationService service;
    @Autowired
    private CalibrationRepository repository;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM resolution_record");
        jdbc.update("DELETE FROM canary_vehicle");
        jdbc.update("DELETE FROM canary_release");
        jdbc.update("DELETE FROM param_status_log");
        jdbc.update("DELETE FROM calibration_param");
    }

    private Map<String, Object> intrinsic(double fx) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fx", fx);
        m.put("fy", 1048.5);
        m.put("cx", 960.0);
        m.put("cy", 540.0);
        return m;
    }

    private Map<String, Object> imu() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("gyro_bias", List.of(0.01, -0.02, 0.0));
        m.put("accel_bias", List.of(0.0, 0.0, 0.03));
        m.put("scale_factor", 1.002);
        return m;
    }

    private Map<String, Object> radarExtrinsic() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rotation", List.of(
                List.of(1.0, 0.0, 0.0),
                List.of(0.0, 1.0, 0.0),
                List.of(0.0, 0.0, 1.0)));
        m.put("translation", List.of(2.5, 0.0, 0.6));
        return m;
    }

    private void createActivate(String key, String version, Object content,
                                CalibrationKind kind, SensorType sensor,
                                List<String> sensors, String mapMin, String mapMax) {
        service.create(new CreateParamRequest(key, kind, sensor, version, content,
                null, null, null, sensors, mapMin, mapMax, "tester"));
        service.approve(key, version, "tester");
        service.activate(key, version, "tester");
    }

    @Test
    void resolvesByMapVersionRangeIncludingBounds() {
        String key = "cam.map";
        createActivate(key, "m1", intrinsic(1050.0), CalibrationKind.INTRINSIC,
                SensorType.CAMERA, null, "map-1", "map-5");
        createActivate(key, "m2", intrinsic(1060.0), CalibrationKind.INTRINSIC,
                SensorType.CAMERA, null, "map-6", null);

        assertEquals("m1", service.resolve(new ResolveRequest(key, "V1", null, "map-1", T0)).version());
        assertEquals("m1", service.resolve(new ResolveRequest(key, "V1", null, "map-5", T0)).version());
        assertEquals("m2", service.resolve(new ResolveRequest(key, "V1", null, "map-6", T0)).version());
        assertEquals("m2", service.resolve(new ResolveRequest(key, "V1", null, "map-10", T0)).version());
        // 自然序：map-10 落在 m2（下界 map-6）
        assertThrows(NotFoundException.class,
                () -> service.resolve(new ResolveRequest(key, "V1", null, "map-0", T0)));
    }

    @Test
    void resolvesBySensorScope() {
        String key = "cam.shared";
        createActivate(key, "base", intrinsic(1050.0), CalibrationKind.INTRINSIC,
                SensorType.CAMERA, null, null, null);
        // 传感器定向版本与全量版本时间相同：传感器命中定向版本
        service.create(new CreateParamRequest(key, CalibrationKind.INTRINSIC,
                SensorType.CAMERA, "sn-1", intrinsic(1099.0), null, null,
                null, List.of("SN-001"), null, null, "tester"));
        service.approve(key, "sn-1", "tester");
        service.activate(key, "sn-1", "tester");

        assertEquals("sn-1", service.resolve(new ResolveRequest(key, "V1", "SN-001", null, T0)).version());
        assertEquals("base", service.resolve(new ResolveRequest(key, "V1", "SN-OTHER", null, T0)).version());
    }

    @Test
    void rejectsDuplicateParamVersionIdempotently() {
        String key = "cam.dup";
        service.create(new CreateParamRequest(key, CalibrationKind.INTRINSIC,
                SensorType.CAMERA, "v1", intrinsic(1050.0), null, null,
                null, null, null, null, "tester"));
        ValidationException ex = assertThrows(ValidationException.class,
                () -> service.create(new CreateParamRequest(key, CalibrationKind.INTRINSIC,
                        SensorType.CAMERA, "v1", intrinsic(1060.0), null, null,
                        null, null, null, null, "tester")));
        assertTrue(ex.getMessage().contains("已存在版本"));
        assertEquals(1, repository.listByParamKey(key).size());
    }

    @Test
    void canaryOnNonApprovedVersionIsConflict() {
        String key = "cam.draft";
        service.create(new CreateParamRequest(key, CalibrationKind.INTRINSIC,
                SensorType.CAMERA, "v1", intrinsic(1050.0), null, null,
                null, null, null, null, "tester"));
        assertThrows(ConflictException.class, () -> service.canaryRelease(
                new CanaryReleaseRequest(key, "v1", List.of("V1"), "tester", null)));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM canary_release", Integer.class));
    }

    @Test
    void canaryRejectsVehicleOutsideDeclaredScope() {
        String key = "cam.scope";
        service.create(new CreateParamRequest(key, CalibrationKind.INTRINSIC,
                SensorType.CAMERA, "v1", intrinsic(1050.0), null, null,
                List.of("V001"), null, null, null, "tester"));
        service.approve(key, "v1", "tester");
        ValidationException ex = assertThrows(ValidationException.class,
                () -> service.canaryRelease(new CanaryReleaseRequest(
                        key, "v1", List.of("V001", "V002"), "tester", null)));
        assertTrue(ex.getMessage().contains("子集"));
        // 合法子集可以发布
        assertDoesNotThrow(() -> service.canaryRelease(new CanaryReleaseRequest(
                key, "v1", List.of("V001"), "tester", null)));
    }

    @Test
    void persistsAndResolvesImuAndRadarParams() {
        String imuKey = "imu.main.params";
        createActivate(imuKey, "i1", imu(), CalibrationKind.IMU_PARAMS, SensorType.IMU,
                null, null, null);
        var imuResolved = service.resolve(new ResolveRequest(imuKey, "V1", null, null, T0));
        assertEquals("i1", imuResolved.version());
        @SuppressWarnings("unchecked")
        Map<String, Object> content = (Map<String, Object>) imuResolved.content();
        assertEquals(1.002, ((Number) content.get("scale_factor")).doubleValue(), 1e-9);

        String radarKey = "radar.front.extrinsic";
        createActivate(radarKey, "r1", radarExtrinsic(), CalibrationKind.EXTRINSIC,
                SensorType.RADAR, null, null, null);
        assertEquals("r1", service.resolve(
                new ResolveRequest(radarKey, "V1", null, null, T0)).version());
    }

    @Test
    void rejectsInvalidTimeAndMapRangesAtCreation() {
        String key = "cam.range";
        assertThrows(ValidationException.class, () -> service.create(new CreateParamRequest(
                key, CalibrationKind.INTRINSIC, SensorType.CAMERA, "v1", intrinsic(1050.0),
                Instant.parse("2026-06-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"),
                null, null, null, null, "tester")));
        assertThrows(ValidationException.class, () -> service.create(new CreateParamRequest(
                key, CalibrationKind.INTRINSIC, SensorType.CAMERA, "v2", intrinsic(1050.0),
                null, null, null, null, "map-9", "map-1", "tester")));
        assertEquals(0, repository.listByParamKey(key).size());
    }
}
