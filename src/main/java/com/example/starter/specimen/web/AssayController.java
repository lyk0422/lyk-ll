package com.example.starter.specimen.web;

import com.example.starter.specimen.service.AssayService;
import com.example.starter.specimen.web.dto.AssayCreateRequest;
import com.example.starter.specimen.web.dto.AssayResponse;
import com.example.starter.specimen.web.dto.ResultRequest;
import com.example.starter.specimen.web.dto.VersionedRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 检测接口：申请占用、开始、取消、结果回传与查询。
 */
@RestController
@RequestMapping("/api/assay-requests")
public class AssayController {

    private final AssayService service;

    public AssayController(AssayService service) {
        this.service = service;
    }

    /**
     * 申请占用：按 requestId 幂等，分装原子从 AVAILABLE 转为 RESERVED。
     */
    @PostMapping
    public AssayResponse create(@Valid @RequestBody AssayCreateRequest request) {
        return service.create(request);
    }

    /**
     * 查询检测申请当前状态。
     */
    @GetMapping("/{assayId}")
    public AssayResponse get(@PathVariable String assayId) {
        return service.get(assayId);
    }

    /**
     * 开始检测：预留转为已消耗。
     */
    @PostMapping("/{assayId}/start")
    public AssayResponse start(@PathVariable String assayId,
                               @Valid @RequestBody VersionedRequest request) {
        return service.start(assayId, request.expectedVersion());
    }

    /**
     * 取消：仅释放尚未开始的预留。
     */
    @PostMapping("/{assayId}/cancel")
    public AssayResponse cancel(@PathVariable String assayId,
                                @Valid @RequestBody VersionedRequest request) {
        return service.cancel(assayId, request.expectedVersion());
    }

    /**
     * 结果回传：取消后到达的结果返回 409。
     */
    @PostMapping("/{assayId}/result")
    public AssayResponse result(@PathVariable String assayId,
                                @Valid @RequestBody ResultRequest request) {
        return service.recordResult(assayId, request.expectedVersion(), request.result());
    }
}
