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

import com.example.starter.api.dto.AcquisitionBatchRequest;
import com.example.starter.api.dto.AcquisitionBatchView;
import com.example.starter.api.dto.AcquisitionPacketView;
import com.example.starter.api.dto.AcquisitionSessionCloseRequest;
import com.example.starter.api.dto.AcquisitionSessionCreateRequest;
import com.example.starter.api.dto.AcquisitionSessionView;
import com.example.starter.api.dto.AcquisitionWatermarkView;
import com.example.starter.service.AcquisitionService;

/**
 * 本地力矩采集 HTTP 入口：设备不联网，数据仅经本地 HTTP 提交。
 */
@RestController
@RequestMapping("/api/acquisition")
public class AcquisitionController {

    private final AcquisitionService acquisitionService;

    public AcquisitionController(AcquisitionService acquisitionService) {
        this.acquisitionService = acquisitionService;
    }

    /** 创建采集会话（绑定工序卡、工具日校验版本与紧固点清单）。 */
    @PostMapping("/sessions")
    public ResponseEntity<Object> createSession(@Valid @RequestBody AcquisitionSessionCreateRequest request) {
        return Responses.from(acquisitionService.createSession(request));
    }

    /** 批量上传采集包（至多 20 包，任一错误整批拒绝）。 */
    @PostMapping("/sessions/{sessionNo}/batches")
    public ResponseEntity<Object> uploadBatch(@PathVariable String sessionNo,
                                              @Valid @RequestBody AcquisitionBatchRequest request) {
        return Responses.from(acquisitionService.uploadBatch(sessionNo, request));
    }

    /** 关闭采集会话（须无缺号且全部缓冲已消费）。 */
    @PostMapping("/sessions/{sessionNo}/close")
    public ResponseEntity<Object> closeSession(@PathVariable String sessionNo,
                                               @Valid @RequestBody AcquisitionSessionCloseRequest request) {
        return Responses.from(acquisitionService.closeSession(sessionNo, request));
    }

    /** 会话查询。 */
    @GetMapping("/sessions/{sessionNo}")
    public AcquisitionSessionView getSession(@PathVariable String sessionNo) {
        return acquisitionService.getSession(sessionNo);
    }

    /** 连续水位线查询。 */
    @GetMapping("/sessions/{sessionNo}/watermark")
    public AcquisitionWatermarkView getWatermark(@PathVariable String sessionNo) {
        return acquisitionService.getWatermark(sessionNo);
    }

    /** 原始包证据查询。 */
    @GetMapping("/sessions/{sessionNo}/packets")
    public List<AcquisitionPacketView> listPackets(@PathVariable String sessionNo) {
        return acquisitionService.listPackets(sessionNo);
    }

    /** 批次证据查询。 */
    @GetMapping("/sessions/{sessionNo}/batches")
    public List<AcquisitionBatchView> listBatches(@PathVariable String sessionNo) {
        return acquisitionService.listBatches(sessionNo);
    }
}
