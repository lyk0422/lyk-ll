package com.example.starter.fermentation.api;

import com.example.starter.fermentation.api.BatchDtos.CoolingRequest;
import com.example.starter.fermentation.api.BatchDtos.CreateBatchRequest;
import com.example.starter.fermentation.api.BatchDtos.DiacetylTestRequest;
import com.example.starter.fermentation.api.BatchDtos.ExtendMaturationRequest;
import com.example.starter.fermentation.api.BatchDtos.JudgeRequest;
import com.example.starter.fermentation.api.BatchDtos.StartPhaseRequest;
import com.example.starter.fermentation.api.BatchDtos.TemperatureReadingRequest;
import com.example.starter.fermentation.api.BatchDtos.TransferRequest;
import com.example.starter.fermentation.api.YeastDtos.ControlledStartRequest;
import com.example.starter.fermentation.domain.CurvePhase;
import com.example.starter.fermentation.service.BatchService;
import com.example.starter.fermentation.service.IdempotencyService.StoredResponse;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 发酵批次温控与成熟度判定 API。所有写接口以 requestId 幂等：
 * 同键同参返回首次结果，同键异参返回 409 且不改变业务状态。
 */
@RestController
@RequestMapping("/api/batches")
public class BatchController {

    private final BatchService batchService;

    public BatchController(BatchService batchService) {
        this.batchService = batchService;
    }

    @PostMapping
    public ResponseEntity<String> create(@Valid @RequestBody CreateBatchRequest request) {
        return respond(batchService.createBatch(request));
    }

    @PostMapping("/{id}/main-ferment/start")
    public ResponseEntity<String> startMainFerment(@PathVariable long id,
                                                   @Valid @RequestBody StartPhaseRequest request) {
        return respond(batchService.startMainFerment(id, request));
    }

    @PostMapping("/{id}/main-ferment/start-controlled")
    public ResponseEntity<String> startMainFermentControlled(
            @PathVariable long id, @Valid @RequestBody ControlledStartRequest request) {
        return respond(batchService.startMainFermentControlled(id, request));
    }

    @PostMapping("/{id}/temperature-readings")
    public ResponseEntity<String> addTemperatureReading(
            @PathVariable long id, @Valid @RequestBody TemperatureReadingRequest request) {
        return respond(batchService.addTemperatureReading(id, request));
    }

    @PostMapping("/{id}/diacetyl-tests")
    public ResponseEntity<String> addDiacetylTest(@PathVariable long id,
                                                  @Valid @RequestBody DiacetylTestRequest request) {
        return respond(batchService.addDiacetylTest(id, request));
    }

    @PostMapping("/{id}/cooling")
    public ResponseEntity<String> cooling(@PathVariable long id,
                                          @Valid @RequestBody CoolingRequest request) {
        return respond(batchService.cooling(id, request));
    }

    @PostMapping("/{id}/maturation/start")
    public ResponseEntity<String> startMaturation(@PathVariable long id,
                                                  @Valid @RequestBody StartPhaseRequest request) {
        return respond(batchService.startMaturation(id, request));
    }

    @PostMapping("/{id}/maturation/extend")
    public ResponseEntity<String> extendMaturation(@PathVariable long id,
                                                   @Valid @RequestBody ExtendMaturationRequest request) {
        return respond(batchService.extendMaturation(id, request));
    }

    @PostMapping("/{id}/judge")
    public ResponseEntity<String> judge(@PathVariable long id,
                                        @Valid @RequestBody JudgeRequest request) {
        return respond(batchService.judge(id, request));
    }

    @PostMapping("/{id}/transfer")
    public ResponseEntity<String> transfer(@PathVariable long id,
                                           @Valid @RequestBody TransferRequest request) {
        return respond(batchService.transfer(id, request));
    }

    @GetMapping("/{id}")
    public BatchDtos.BatchResponse get(@PathVariable long id) {
        return batchService.getBatch(id);
    }

    @GetMapping("/{id}/temperature-readings")
    public Object readings(@PathVariable long id, @RequestParam CurvePhase phase) {
        return batchService.getReadings(id, phase);
    }

    @GetMapping("/{id}/diacetyl-tests")
    public Object diacetylTests(@PathVariable long id) {
        return batchService.getDiacetylTests(id);
    }

    private ResponseEntity<String> respond(StoredResponse response) {
        return ResponseEntity.status(response.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.body());
    }
}
