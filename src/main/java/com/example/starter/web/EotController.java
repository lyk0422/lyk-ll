package com.example.starter.web;

import com.example.starter.service.EotService;
import com.example.starter.web.dto.EotBindRequest;
import com.example.starter.web.dto.EotBindingEvidence;
import com.example.starter.web.dto.EotBindingResponse;
import com.example.starter.web.dto.EotConfirmRequest;
import com.example.starter.web.dto.EotConfirmationResponse;
import com.example.starter.web.dto.EotDeviceEvidence;
import com.example.starter.web.dto.EotDeviceRegisterRequest;
import com.example.starter.web.dto.EotDeviceResponse;
import com.example.starter.web.dto.EotUnbindRequest;
import com.example.starter.web.dto.EotUnbindResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 列尾装置建档、绑定、双人一致性确认、提前解绑与证据查询 API。
 */
@RestController
@RequestMapping("/api")
public class EotController {

    private final EotService service;

    public EotController(EotService service) {
        this.service = service;
    }

    /**
     * 列尾装置建档（合成数据）。
     */
    @PostMapping("/eot-devices")
    @ResponseStatus(HttpStatus.CREATED)
    public EotDeviceResponse register(@Valid @RequestBody EotDeviceRegisterRequest request) {
        return service.register(request);
    }

    /**
     * 受控作业绑定空闲列尾装置并冻结当前编组尾车与版本。
     */
    @PostMapping("/jobs/{jobId}/eot-bindings")
    @ResponseStatus(HttpStatus.CREATED)
    public EotBindingResponse bind(@PathVariable long jobId,
                                   @Valid @RequestBody EotBindRequest request) {
        return service.bind(jobId, request);
    }

    /**
     * 两名不同人员共同确认列尾与最新编组尾车、版本一致。
     */
    @PostMapping("/jobs/{jobId}/eot-confirmations")
    public EotConfirmationResponse confirm(@PathVariable long jobId,
                                           @Valid @RequestBody EotConfirmRequest request) {
        return service.confirm(jobId, request);
    }

    /**
     * 发车前提前解绑：原子解除占用并撤销确认，历史保留。
     */
    @PostMapping("/jobs/{jobId}/eot-unbindings")
    public EotUnbindResponse unbind(@PathVariable long jobId,
                                    @Valid @RequestBody EotUnbindRequest request) {
        return service.unbind(jobId, request);
    }

    /**
     * 查询装置完整证据（档案、占用、全部绑定与双人确认历史）。
     */
    @GetMapping("/eot-devices/{deviceNo}")
    public EotDeviceEvidence getDeviceEvidence(@PathVariable String deviceNo) {
        return service.getDeviceEvidence(deviceNo);
    }

    /**
     * 按装置编号查询证据的查询参数形式（编号含分隔符时可用 ?deviceNo= 传值）。
     */
    @GetMapping("/eot-devices")
    public EotDeviceEvidence getDeviceEvidenceByParam(@RequestParam String deviceNo) {
        return service.getDeviceEvidence(deviceNo);
    }

    /**
     * 查询作业的列尾绑定与双人确认证据；未启用列尾控制的历史作业返回空列表。
     */
    @GetMapping("/jobs/{jobId}/eot-evidence")
    public List<EotBindingEvidence> getJobEvidence(@PathVariable long jobId) {
        return service.getJobEvidence(jobId);
    }
}
