package com.example.starter.web;

import com.example.starter.domain.PurgeBatch;
import com.example.starter.domain.PurgeManifestEntry;
import com.example.starter.service.PurgeService;
import com.example.starter.web.dto.ConfirmPurgeRequest;
import com.example.starter.web.dto.MarkPurgeRequest;
import com.example.starter.web.dto.PurgeBatchResponse;
import com.example.starter.web.dto.PurgeManifestResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 两阶段清除：标记、最终确认、批次与不可变清单查询 API。
 */
@RestController
@RequestMapping("/api/purges")
public class PurgeController {

    private final PurgeService purgeService;

    public PurgeController(PurgeService purgeService) {
        this.purgeService = purgeService;
    }

    @PostMapping("/mark")
    @ResponseStatus(HttpStatus.CREATED)
    public PurgeBatchResponse mark(@Valid @RequestBody MarkPurgeRequest request) {
        return toBatchResponse(purgeService.mark(request));
    }

    @PostMapping("/confirm")
    public PurgeManifestResponse confirm(@Valid @RequestBody ConfirmPurgeRequest request) {
        PurgeService.ConfirmResult result = purgeService.confirm(request.batchId());
        return toManifestResponse(result);
    }

    @GetMapping("/batches/{batchId}")
    public PurgeBatchResponse getBatch(@PathVariable String batchId) {
        return toBatchResponse(purgeService.getBatch(batchId));
    }

    @GetMapping("/batches/{batchId}/manifest")
    public PurgeManifestResponse getManifest(@PathVariable String batchId) {
        PurgeService.ConfirmResult result = new PurgeService.ConfirmResult(
                purgeService.getBatch(batchId), purgeService.getManifest(batchId));
        return toManifestResponse(result);
    }

    static PurgeBatchResponse toBatchResponse(PurgeBatch b) {
        return new PurgeBatchResponse(
                b.batchId(), b.markRequestId(), b.cutoffTime(), b.expectedVersion(),
                b.status().name(),
                b.items().stream()
                        .map(i -> new PurgeBatchResponse.Item(i.evidenceId(), i.rescued()))
                        .toList(),
                b.createdAt(), b.finalizedAt());
    }

    static PurgeManifestResponse toManifestResponse(PurgeService.ConfirmResult result) {
        PurgeBatch b = result.batch();
        return new PurgeManifestResponse(
                b.batchId(), b.status().name(), b.finalizedAt(),
                result.manifest().stream()
                        .map(m -> new PurgeManifestResponse.Entry(m.evidenceId(), m.sha256(), m.purgedAt()))
                        .toList());
    }
}
