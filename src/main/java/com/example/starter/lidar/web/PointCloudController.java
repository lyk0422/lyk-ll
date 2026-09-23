package com.example.starter.lidar.web;

import com.example.starter.lidar.model.RebuildResponse;
import com.example.starter.lidar.model.SpatialQueryRequest;
import com.example.starter.lidar.model.SpatialQueryResponse;
import com.example.starter.lidar.model.WriteChunkRequest;
import com.example.starter.lidar.model.WriteChunkResponse;
import com.example.starter.lidar.service.PointCloudService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 点云块写入、空间查询与索引重建接口。
 */
@RestController
@RequestMapping("/api/lidar/chunks")
public class PointCloudController {

    private final PointCloudService service;

    public PointCloudController(PointCloudService service) {
        this.service = service;
    }

    /**
     * 写入块。同 ID 同内容重试返回 200（deduplicated=true）；同 ID 改参返回 409。
     */
    @PostMapping
    public ResponseEntity<WriteChunkResponse> write(@Valid @RequestBody WriteChunkRequest request) {
        WriteChunkResponse response = service.write(request);
        return response.deduplicated()
                ? ResponseEntity.ok(response)
                : ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /** 空间查询（BOX / RADIUS / CORRIDOR），闭区间，结果稳定排序。 */
    @PostMapping("/query")
    public SpatialQueryResponse query(@Valid @RequestBody SpatialQueryRequest request) {
        return service.query(request);
    }

    /** 从块文件重建索引；重建期间增量写入不遗漏。 */
    @PostMapping("/rebuild")
    public RebuildResponse rebuild() {
        return service.rebuild();
    }
}
