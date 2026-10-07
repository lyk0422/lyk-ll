package com.example.starter.web;

import com.example.starter.service.TruckService;
import com.example.starter.web.dto.TruckCreateRequest;
import com.example.starter.web.dto.TruckManifestActionRequest;
import com.example.starter.web.dto.TruckManifestResponse;
import com.example.starter.web.dto.TruckProgressView;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 分车出厂接口：创建车单（全量预占冻结）、整体撤销、逐车核销与分车进度查询。
 */
@RestController
@RequestMapping("/api/batches/{batchNo}/trucks")
public class TruckController {

    private final TruckService service;

    public TruckController(TruckService service) {
        this.service = service;
    }

    /** 创建分车清单（幂等）：一车一清单，全量预占并冻结合格头只，版本自 1 起。 */
    @PostMapping
    public TruckManifestResponse create(@PathVariable String batchNo,
                                        @Valid @RequestBody TruckCreateRequest request) {
        return service.createManifest(batchNo, request);
    }

    /** 整体撤销未发车清单（幂等）：释放全部头只预占，不能事后改单。 */
    @PostMapping("/{truckNo}/revoke")
    public TruckManifestResponse revoke(@PathVariable String batchNo, @PathVariable String truckNo,
                                        @Valid @RequestBody TruckManifestActionRequest request) {
        return service.revokeManifest(batchNo, truckNo, request);
    }

    /** 逐车发车核销（幂等）：门禁满足整车转已出厂，末车恰核销完批次转终态。 */
    @PostMapping("/{truckNo}/dispatch")
    public TruckManifestResponse dispatch(@PathVariable String batchNo, @PathVariable String truckNo,
                                          @Valid @RequestBody TruckManifestActionRequest request) {
        return service.dispatchManifest(batchNo, truckNo, request);
    }

    /** 分车进度查询：每车冻结清单、已出厂/未出厂头只及批次进度（按头只勾稽）。 */
    @GetMapping
    public TruckProgressView progress(@PathVariable String batchNo) {
        return service.getProgress(batchNo);
    }
}
