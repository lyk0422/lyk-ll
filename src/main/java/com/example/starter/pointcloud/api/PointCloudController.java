package com.example.starter.pointcloud.api;

import com.example.starter.pointcloud.service.PointCloudService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * LiDAR 点云块写入、空间查询与索引重建接口。
 */
@RestController
@RequestMapping("/api/pointcloud")
public class PointCloudController {

    private final PointCloudService service;

    public PointCloudController(PointCloudService service) {
        this.service = service;
    }

    /**
     * 写入（或幂等重试）一个点云块。
     */
    @PostMapping("/chunks")
    @ResponseStatus(HttpStatus.CREATED)
    public WriteChunkResponse writeChunk(@Valid @RequestBody WriteChunkRequest request) {
        return service.writeChunk(request);
    }

    /**
     * 闭区间 Box 查询。
     */
    @PostMapping("/query/box")
    public QueryResponse queryBox(@Valid @RequestBody BoxQueryRequest request) {
        return service.queryBox(request);
    }

    /**
     * 球体 Radius 查询。
     */
    @PostMapping("/query/radius")
    public QueryResponse queryRadius(@Valid @RequestBody RadiusQueryRequest request) {
        return service.queryRadius(request);
    }

    /**
     * 折线轨迹走廊查询。
     */
    @PostMapping("/query/corridor")
    public QueryResponse queryCorridor(@Valid @RequestBody CorridorQueryRequest request) {
        return service.queryCorridor(request);
    }

    /**
     * 从块文件重建空间/时间索引。
     */
    @PostMapping("/reindex")
    public ReindexResponse reindex() {
        return service.reindex();
    }
}
