package com.example.starter.web;

import com.example.starter.service.BatchReleaseService;
import com.example.starter.service.ServiceResponse;
import com.example.starter.web.dto.ApplicationRequest;
import com.example.starter.web.dto.CorrectionRequest;
import com.example.starter.web.dto.ObjectionRequest;
import com.example.starter.web.dto.SimpleRequest;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 批签发资料审查与公示服务接口。
 */
@RestController
@RequestMapping("/api/batch-release")
public class BatchReleaseController {

    private final BatchReleaseService service;

    public BatchReleaseController(BatchReleaseService service) {
        this.service = service;
    }

    @PostMapping("/applications")
    public ResponseEntity<String> apply(@Valid @RequestBody ApplicationRequest request) {
        return toEntity(service.apply(request));
    }

    @PostMapping("/batches/{batchNo}/corrections")
    public ResponseEntity<String> correct(@PathVariable String batchNo,
                                          @Valid @RequestBody CorrectionRequest request) {
        return toEntity(service.correct(batchNo, request));
    }

    @PostMapping("/batches/{batchNo}/evaluation")
    public ResponseEntity<String> evaluate(@PathVariable String batchNo,
                                           @Valid @RequestBody SimpleRequest request) {
        return toEntity(service.evaluate(batchNo, request));
    }

    @PostMapping("/batches/{batchNo}/objections")
    public ResponseEntity<String> object(@PathVariable String batchNo,
                                         @Valid @RequestBody ObjectionRequest request) {
        return toEntity(service.object(batchNo, request));
    }

    @PostMapping("/batches/{batchNo}/issuance")
    public ResponseEntity<String> issue(@PathVariable String batchNo,
                                        @Valid @RequestBody SimpleRequest request) {
        return toEntity(service.issue(batchNo, request));
    }

    @PostMapping("/batches/{batchNo}/archive")
    public ResponseEntity<String> archive(@PathVariable String batchNo,
                                          @Valid @RequestBody SimpleRequest request) {
        return toEntity(service.archive(batchNo, request));
    }

    @GetMapping("/batches/{batchNo}/checklist")
    public ResponseEntity<String> checklist(@PathVariable String batchNo) {
        return toEntity(service.checklist(batchNo));
    }

    @GetMapping("/batches/{batchNo}/inspection")
    public ResponseEntity<String> inspection(@PathVariable String batchNo) {
        return toEntity(service.inspection(batchNo));
    }

    @GetMapping("/batches/{batchNo}/publicity")
    public ResponseEntity<String> publicity(@PathVariable String batchNo) {
        return toEntity(service.publicity(batchNo));
    }

    private ResponseEntity<String> toEntity(ServiceResponse response) {
        return ResponseEntity.status(response.httpStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.jsonBody());
    }
}
