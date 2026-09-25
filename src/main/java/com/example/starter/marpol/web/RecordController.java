package com.example.starter.marpol.web;

import com.example.starter.marpol.model.EmissionRecord;
import com.example.starter.marpol.service.RecordService;
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

/**
 * 排放记录 API：提交、修正、按版本族查询（默认最新有效版本）。
 */
@RestController
@RequestMapping("/api/records")
public class RecordController {

    private final RecordService recordService;

    public RecordController(RecordService recordService) {
        this.recordService = recordService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RecordResponse submit(@Valid @RequestBody SubmitRecordRequest request) {
        EmissionRecord saved = recordService.submit(request);
        return recordService.toResponse(saved);
    }

    @PostMapping("/corrections")
    @ResponseStatus(HttpStatus.CREATED)
    public RecordResponse correct(@Valid @RequestBody CorrectRecordRequest request) {
        EmissionRecord saved = recordService.correct(request);
        return recordService.toResponse(saved);
    }

    @GetMapping("/{id}")
    public RecordResponse get(@PathVariable Long id,
                              @RequestParam(required = false) Integer version) {
        return recordService.toResponse(recordService.get(id, version));
    }
}
