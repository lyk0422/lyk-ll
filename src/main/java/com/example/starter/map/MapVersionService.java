package com.example.starter.map;

import com.example.starter.error.ApiException;
import com.example.starter.error.ErrorCode;
import com.example.starter.match.MatchCache;
import com.example.starter.web.dto.PublishMapVersionRequest;
import com.example.starter.web.dto.SegmentDto;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 地图版本发布、查询与废弃；版本内容发布后不可变，热更新只能发布新版本。
 */
@Service
public class MapVersionService {

    private final MapVersionRepository repository;
    private final MatchCache matchCache;

    public MapVersionService(MapVersionRepository repository, MatchCache matchCache) {
        this.repository = repository;
        this.matchCache = matchCache;
    }

    /**
     * 发布新版本；versionId 重复时拒绝（409），版本与路段同事务写入。
     */
    @Transactional
    public MapVersionView publish(PublishMapVersionRequest request) {
        Set<String> seen = new HashSet<>();
        for (SegmentDto s : request.segments()) {
            if (!seen.add(s.segmentId())) {
                throw new ApiException(ErrorCode.MAP_VERSION_ALREADY_EXISTS,
                        "版本内路段标识重复: " + s.segmentId());
            }
        }
        try {
            repository.insertVersion(request.versionId(), MapVersionStatus.ACTIVE);
        } catch (DuplicateKeyException e) {
            throw new ApiException(ErrorCode.MAP_VERSION_ALREADY_EXISTS,
                    "地图版本已存在: " + request.versionId());
        }
        for (SegmentDto s : request.segments()) {
            repository.insertSegment(request.versionId(), new MapSegment(
                    s.segmentId(), s.startNode(), s.endNode(),
                    s.startX(), s.startY(), s.endX(), s.endY(), s.active()));
        }
        return get(request.versionId());
    }

    /**
     * 查询版本快照；不存在时报 MAP_VERSION_NOT_FOUND。
     */
    public MapVersionView get(String versionId) {
        MapVersionRepository.VersionMeta meta = repository.findMeta(versionId)
                .orElseThrow(() -> new ApiException(ErrorCode.MAP_VERSION_NOT_FOUND,
                        "地图版本不存在: " + versionId));
        List<MapSegment> segments = repository.findSegments(versionId);
        return new MapVersionView(versionId, meta.status(), segments, meta.createdAt());
    }

    /**
     * 废弃版本：不再接受新路线请求；驱逐该版本的查询缓存，已持久化的请求结果不受影响。
     */
    @Transactional
    public MapVersionView deprecate(String versionId) {
        MapVersionView current = get(versionId);
        if (current.status() == MapVersionStatus.ACTIVE) {
            repository.updateStatus(versionId, MapVersionStatus.DEPRECATED);
        }
        matchCache.evictVersion(versionId);
        return get(versionId);
    }
}
