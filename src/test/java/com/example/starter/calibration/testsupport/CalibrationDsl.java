package com.example.starter.calibration.testsupport;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import com.example.starter.calibration.domain.SensorType;
import com.example.starter.calibration.domain.payload.CalibrationPayload;
import com.example.starter.calibration.domain.payload.CameraIntrinsics;
import com.example.starter.calibration.domain.payload.Extrinsics;
import com.example.starter.calibration.web.dto.CanaryReleaseRequest;
import com.example.starter.calibration.web.dto.CreateVersionRequest;
import com.example.starter.calibration.web.dto.ResolveRequest;
import com.example.starter.calibration.web.dto.StateTransitionRequest;

/**
 * 测试请求与标定载荷构造工具，全部使用合成数据。
 */
public final class CalibrationDsl {

    public static final String KEY = "front-camera";
    public static final String SENSOR = "cam-front-01";

    private CalibrationDsl() {
    }

    public static Extrinsics identityExtrinsics() {
        return new Extrinsics(
                List.of(List.of(1.0, 0.0, 0.0), List.of(0.0, 1.0, 0.0), List.of(0.0, 0.0, 1.0)),
                List.of(0.1, 0.2, 1.5));
    }

    public static CalibrationPayload cameraPayload(double fx, double fy) {
        return new CalibrationPayload(
                new CameraIntrinsics(fx, fy, 640.0, 360.0, List.of(0.0, 0.0, 0.0, 0.0, 0.0)),
                identityExtrinsics(), null);
    }

    public static CalibrationPayload cameraPayload(double fx) {
        return cameraPayload(fx, fx);
    }

    public static CreateVersionRequest version(String version, boolean allVehicles, List<String> vehicles,
            Instant from, Instant to, Integer mapFrom, Integer mapTo) {
        return new CreateVersionRequest(
                KEY, SensorType.CAMERA, version, cameraPayload(1000.0),
                OffsetDateTime.ofInstant(from, ZoneOffset.UTC),
                to == null ? null : OffsetDateTime.ofInstant(to, ZoneOffset.UTC),
                allVehicles, vehicles, SENSOR, mapFrom, mapTo, "tester");
    }

    public static CreateVersionRequest allVehiclesVersion(String version, Instant from, Instant to) {
        return version(version, true, List.of(), from, to, null, null);
    }

    public static CreateVersionRequest vehiclesVersion(String version, List<String> vehicles,
            Instant from, Instant to) {
        return version(version, false, vehicles, from, to, null, null);
    }

    public static StateTransitionRequest transition(String operator) {
        return new StateTransitionRequest(operator, null);
    }

    public static StateTransitionRequest transition(String operator, String reason) {
        return new StateTransitionRequest(operator, reason);
    }

    public static CanaryReleaseRequest canary(String version, List<String> vehicles, String operator) {
        return new CanaryReleaseRequest(KEY, version, vehicles, operator);
    }

    public static ResolveRequest resolve(String vehicle, Integer mapVersion, Instant time) {
        return new ResolveRequest(KEY, SENSOR, vehicle, mapVersion,
                time == null ? null : OffsetDateTime.ofInstant(time, ZoneOffset.UTC));
    }
}
