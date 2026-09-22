package com.example.starter.route;

import com.example.starter.error.ApiException;
import com.example.starter.error.ErrorCode;
import com.example.starter.map.MapVersionRepository;
import com.example.starter.map.MapVersionStatus;
import com.example.starter.match.MatchCache;
import com.example.starter.match.MatchResult;
import com.example.starter.match.MatchingService;
import com.example.starter.web.dto.RouteResponse;
import com.example.starter.web.dto.SubmitRouteRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;

/**
 * 路线请求提交（按 requestId 幂等）与查询。
 * 同参重放返回原请求与匹配结果；异参重放返回 409。
 * 匹配结果缓存以（地图版本， 输入摘要）为键。
 */
@Service
public class RouteService {

    private final RouteRequestRepository requestRepository;
    private final MapVersionRepository mapVersionRepository;
    private final MatchingService matchingService;
    private final MatchCache matchCache;
    private final ObjectMapper objectMapper;

    public RouteService(RouteRequestRepository requestRepository,
                        MapVersionRepository mapVersionRepository,
                        MatchingService matchingService,
                        MatchCache matchCache,
                        ObjectMapper objectMapper) {
        this.requestRepository = requestRepository;
        this.mapVersionRepository = mapVersionRepository;
        this.matchingService = matchingService;
        this.matchCache = matchCache;
        this.objectMapper = objectMapper;
    }

    /**
     * 提交路线请求；并发下同 requestId 仅一条落库，其余按重放处理。
     * 匹配失败同样落库（状态 FAILED + 错误码）后原样抛出，故业务异常不回滚事务。
     */
    @Transactional(noRollbackFor = ApiException.class)
    public RouteResponse submit(SubmitRouteRequest request) {
        MatchPoint origin = toPoint(request.origin());
        MatchPoint destination = toPoint(request.destination());

        Optional<RouteRequestRecord> existing = requestRepository.findByRequestId(request.requestId());
        if (existing.isPresent()) {
            return replay(existing.get(), request.mapVersionId(), origin, destination);
        }

        MapVersionStatus status = mapVersionRepository.findStatus(request.mapVersionId())
                .orElseThrow(() -> new ApiException(ErrorCode.MAP_VERSION_NOT_FOUND,
                        "地图版本不存在: " + request.mapVersionId()));
        if (status == MapVersionStatus.DEPRECATED) {
            throw new ApiException(ErrorCode.MAP_VERSION_DEPRECATED,
                    "地图版本已废弃，不接受新请求: " + request.mapVersionId());
        }

        String digest = inputDigest(request.mapVersionId(), origin, destination);
        MatchResult result = matchCache.get(request.mapVersionId(), digest).orElse(null);
        if (result == null) {
            try {
                result = matchingService.match(
                        mapVersionRepository.findSegments(request.mapVersionId()), origin, destination);
            } catch (ApiException failure) {
                try {
                    requestRepository.insert(new RouteRequestRecord(request.requestId(), request.mapVersionId(),
                            origin, destination, RouteRequestRecord.STATUS_FAILED,
                            failure.errorCode().name(), null, null));
                } catch (DuplicateKeyException e) {
                    // 并发下另一事务已落库同一 requestId，按重放语义处理
                    RouteRequestRecord stored = requestRepository.findByRequestId(request.requestId())
                            .orElseThrow(() -> e);
                    return replay(stored, request.mapVersionId(), origin, destination);
                }
                throw failure;
            }
            matchCache.put(request.mapVersionId(), digest, result);
        }

        RouteRequestRecord record = new RouteRequestRecord(request.requestId(), request.mapVersionId(),
                origin, destination, RouteRequestRecord.STATUS_SUCCESS, null,
                writeJson(result), null);
        try {
            requestRepository.insert(record);
        } catch (DuplicateKeyException e) {
            // 并发下另一事务已落库同一 requestId，按重放语义处理
            RouteRequestRecord stored = requestRepository.findByRequestId(request.requestId())
                    .orElseThrow(() -> e);
            return replay(stored, request.mapVersionId(), origin, destination);
        }
        return toResponse(record.requestId(), record.mapVersionId(),
                RouteRequestRecord.STATUS_SUCCESS, null, result);
    }

    /**
     * 查询请求快照；不存在时报 REQUEST_NOT_FOUND。
     */
    public RouteResponse get(String requestId) {
        RouteRequestRecord record = requestRepository.findByRequestId(requestId)
                .orElseThrow(() -> new ApiException(ErrorCode.REQUEST_NOT_FOUND,
                        "路线请求不存在: " + requestId));
        MatchResult result = record.resultJson() == null ? null : readJson(record.resultJson());
        return toResponse(record.requestId(), record.mapVersionId(),
                record.status(), record.errorCode(), result);
    }

    /**
     * 重放：参数一致返回原结果（失败请求重放抛出原错误码）；参数不一致返回 409。
     */
    private RouteResponse replay(RouteRequestRecord stored, String versionId,
                                 MatchPoint origin, MatchPoint destination) {
        if (!stored.sameParameters(versionId, origin, destination)) {
            throw new ApiException(ErrorCode.REQUEST_CONFLICT,
                    "requestId 已存在且参数不一致: " + stored.requestId());
        }
        if (RouteRequestRecord.STATUS_FAILED.equals(stored.status())) {
            throw new ApiException(ErrorCode.valueOf(stored.errorCode()),
                    "重放原失败请求: " + stored.requestId());
        }
        return toResponse(stored.requestId(), stored.mapVersionId(),
                stored.status(), null, readJson(stored.resultJson()));
    }

    private static MatchPoint toPoint(com.example.starter.web.dto.PointDto dto) {
        return new MatchPoint(dto.x(), dto.y(), dto.heading());
    }

    /**
     * 输入摘要：版本号 + 六个坐标/航向分量（定点 6 位小数）的 SHA-256。
     */
    static String inputDigest(String versionId, MatchPoint origin, MatchPoint destination) {
        String canonical = String.format(Locale.ROOT, "%s|%.6f|%.6f|%.6f|%.6f|%.6f|%.6f",
                versionId, origin.x(), origin.y(), origin.heading(),
                destination.x(), destination.y(), destination.heading());
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private RouteResponse toResponse(String requestId, String versionId,
                                     String status, String errorCode, MatchResult result) {
        return new RouteResponse(requestId, versionId, status, errorCode, result);
    }

    private String writeJson(MatchResult result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            throw new IllegalStateException("匹配结果序列化失败", e);
        }
    }

    private MatchResult readJson(String json) {
        try {
            return objectMapper.readValue(json, MatchResult.class);
        } catch (Exception e) {
            throw new IllegalStateException("匹配结果反序列化失败", e);
        }
    }
}
