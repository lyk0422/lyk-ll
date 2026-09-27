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

import com.example.starter.api.dto.CollectionBatchView;
import com.example.starter.api.dto.CollectionCloseRequest;
import com.example.starter.api.dto.CollectionPacketView;
import com.example.starter.api.dto.CollectionSessionCreateRequest;
import com.example.starter.api.dto.CollectionSessionView;
import com.example.starter.api.dto.CollectionUploadRequest;
import com.example.starter.service.CollectionService;

/**
 * 本地工具采集协议 HTTP 入口：会话、批量采集上传、水位线与包证据查询。
 * 设备不联网，数据仅经本地 HTTP 提交；全部为合成数据。
 */
@RestController
@RequestMapping("/api/collection")
public class CollectionController {

    private final CollectionService collectionService;

    public CollectionController(CollectionService collectionService) {
        this.collectionService = collectionService;
    }

    /** 创建采集会话（绑定工序卡、工具日校验版本与紧固点清单）。 */
    @PostMapping("/sessions")
    public ResponseEntity<Object> createSession(@Valid @RequestBody CollectionSessionCreateRequest request) {
        return Responses.from(collectionService.createSession(request));
    }

    /** 批量上传采集包（至多 20 包，任一错误整批拒绝）。 */
    @PostMapping("/uploads")
    public ResponseEntity<Object> upload(@Valid @RequestBody CollectionUploadRequest request) {
        return Responses.from(collectionService.upload(request));
    }

    /** 关闭采集会话（须无缺号且全部缓冲已消费）。 */
    @PostMapping("/sessions/close")
    public ResponseEntity<Object> closeSession(@Valid @RequestBody CollectionCloseRequest request) {
        return Responses.from(collectionService.closeSession(request));
    }

    /** 查询会话（含水位线与版本）。 */
    @GetMapping("/sessions/{sessionNo}")
    public CollectionSessionView getSession(@PathVariable String sessionNo) {
        return collectionService.getSession(sessionNo);
    }

    /** 查询会话的原始包证据。 */
    @GetMapping("/sessions/{sessionNo}/packets")
    public List<CollectionPacketView> listPackets(@PathVariable String sessionNo) {
        return collectionService.listPackets(sessionNo);
    }

    /** 查询会话的上传批次台账。 */
    @GetMapping("/sessions/{sessionNo}/batches")
    public List<CollectionBatchView> listBatches(@PathVariable String sessionNo) {
        return collectionService.listBatches(sessionNo);
    }
}
