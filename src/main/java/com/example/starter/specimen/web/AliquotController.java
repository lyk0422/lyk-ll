package com.example.starter.specimen.web;

import com.example.starter.specimen.service.AssayService;
import com.example.starter.specimen.web.dto.QuarantineRequest;
import com.example.starter.specimen.web.dto.QuarantineResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 分装接口：污染隔离。
 */
@RestController
@RequestMapping("/api/aliquots")
public class AliquotController {

    private final AssayService service;

    public AliquotController(AssayService service) {
        this.service = service;
    }

    /**
     * 污染隔离：分装转为 QUARANTINED，其未开始申请连带取消。
     */
    @PostMapping("/{aliquotId}/quarantine")
    public QuarantineResponse quarantine(@PathVariable String aliquotId,
                                         @Valid @RequestBody QuarantineRequest request) {
        return service.quarantine(aliquotId, request.expectedVersion());
    }
}
