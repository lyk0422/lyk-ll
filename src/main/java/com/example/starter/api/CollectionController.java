package com.example.starter.api;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.api.dto.CollectionBatchRequest;
import com.example.starter.api.dto.CollectionCloseRequest;
import com.example.starter.api.dto.CollectionPacketView;
import com.example.starter.api.dto.CollectionSessionRequest;
import com.example.starter.api.dto.CollectionSessionView;
import com.example.starter.api.dto.CollectionWatermarkView;
import com.example.starter.service.CollectionService;

/**
 * 本地力矩采集会话 HTTP 入口（设备不联网，数据仅经本地 HTTP 提交）。
 */
@RestController
@RequestMapping("/api/collection")
public class CollectionController {

    private final CollectionService collectionService;

    public CollectionController(CollectionService collectionService) {
        this.collectionService = collectionService;
    }

    /** 创建采集会话（绑定工具日校验版本、工序卡与紧固点清单）。 */
    @PostMapping("/sessions")
    public ResponseEntity<Object> openSession(@Valid @RequestBody CollectionSessionRequest request) {
        return Responses.from(collectionService.openSession(request));
    }

    /** 批量采集上传（至多 20 包，整批原子）。 */
    @PostMapping("/sessions/{sessionNo}/packets")
    public ResponseEntity<Object> collect(@PathVariable String sessionNo,
                                          @Valid @RequestBody CollectionBatchRequest request) {
        return Responses.from(collectionService.collect(request));
    }

    /** 关闭采集会话。 */
    @PostMapping("/sessions/{sessionNo}/close")
    public ResponseEntity<Object> close(@PathVariable String sessionNo,
                                        @Valid @RequestBody CollectionCloseRequest request) {
        return Responses.from(collectionService.close(request));
    }

    /** 查询采集会话。 */
    @GetMapping("/sessions/{sessionNo}")
    public CollectionSessionView getSession(@PathVariable String sessionNo) {
        return collectionService.getSession(sessionNo);
    }

    /** 查询连续水位线。 */
    @GetMapping("/sessions/{sessionNo}/watermark")
    public CollectionWatermarkView watermark(@PathVariable String sessionNo) {
        return collectionService.getWatermark(sessionNo);
    }

    /** 查询会话全部原始包证据。 */
    @GetMapping("/sessions/{sessionNo}/packets")
    public List<CollectionPacketView> packets(@PathVariable String sessionNo) {
        return collectionService.listPackets(sessionNo);
    }
}
