package com.example.starter.web;

import com.example.starter.service.TailDeviceService;
import com.example.starter.web.dto.TailBindRequest;
import com.example.starter.web.dto.TailBindResponse;
import com.example.starter.web.dto.TailConfirmRequest;
import com.example.starter.web.dto.TailConfirmResponse;
import com.example.starter.web.dto.TailDeviceRegisterRequest;
import com.example.starter.web.dto.TailDeviceResponse;
import com.example.starter.web.dto.TailEvidenceResponse;
import com.example.starter.web.dto.TailUnbindRequest;
import com.example.starter.web.dto.TailUnbindResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 合成列尾装置控制 API：装置建档/证据查询，以及受控作业的绑定、双人一致性确认、发车前解绑。
 */
@RestController
public class TailDeviceController {

    private final TailDeviceService service;

    public TailDeviceController(TailDeviceService service) {
        this.service = service;
    }

    /** 合成列尾装置建档（编号唯一，初始版本 1）。 */
    @PostMapping("/api/tail-devices")
    @ResponseStatus(HttpStatus.CREATED)
    public TailDeviceResponse register(@Valid @RequestBody TailDeviceRegisterRequest request) {
        return service.register(request);
    }

    /** 证据查询：装置视图、全部绑定历史与共同确认记录。 */
    @GetMapping("/api/tail-devices")
    public TailEvidenceResponse getEvidence(@RequestParam String deviceNo) {
        return service.getEvidence(deviceNo);
    }

    /** 受控作业绑定空闲且自检合格的装置与当前编组尾车。 */
    @PostMapping("/api/jobs/{jobId}/tail-bindings")
    public TailBindResponse bind(@PathVariable long jobId, @Valid @RequestBody TailBindRequest request) {
        return service.bind(jobId, request);
    }

    /** 两名不同人员共同确认最新尾车及编组版本。 */
    @PostMapping("/api/jobs/{jobId}/tail-confirmations")
    public TailConfirmResponse confirm(@PathVariable long jobId, @Valid @RequestBody TailConfirmRequest request) {
        return service.confirm(jobId, request);
    }

    /** 发车前提前解绑，同时撤销一致性确认并释放装置。 */
    @PostMapping("/api/jobs/{jobId}/tail-unbindings")
    public TailUnbindResponse unbind(@PathVariable long jobId, @Valid @RequestBody TailUnbindRequest request) {
        return service.unbind(jobId, request);
    }
}
