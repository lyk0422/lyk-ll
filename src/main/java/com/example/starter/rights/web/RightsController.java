package com.example.starter.rights.web;

import com.example.starter.rights.dto.CompletePlaybackRequest;
import com.example.starter.rights.dto.CorrectLicenseRequest;
import com.example.starter.rights.dto.CreateLicenseRequest;
import com.example.starter.rights.dto.EvidenceView;
import com.example.starter.rights.dto.LicenseView;
import com.example.starter.rights.dto.PlayabilityView;
import com.example.starter.rights.dto.PlaybackReceipt;
import com.example.starter.rights.dto.RevokeLicenseRequest;
import com.example.starter.rights.dto.SubmissionView;
import com.example.starter.rights.dto.SubmitScheduleRequest;
import com.example.starter.rights.service.RightsService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 媒体版权窗口与播出授权 API。
 */
@RestController
@RequestMapping("/api/rights")
public class RightsController {

    private final RightsService service;

    public RightsController(RightsService service) {
        this.service = service;
    }

    @PostMapping("/licenses")
    public ResponseEntity<LicenseView> create(@Valid @RequestBody CreateLicenseRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createLicense(request));
    }

    @PostMapping("/licenses/{licenseId}/corrections")
    public LicenseView correct(@PathVariable String licenseId,
                               @Valid @RequestBody CorrectLicenseRequest request) {
        if (!licenseId.equals(request.licenseId())) {
            throw new com.example.starter.rights.ApiException(
                    HttpStatus.BAD_REQUEST, "PATH_BODY_MISMATCH", "路径与请求体的 licenseId 不一致");
        }
        return service.correctLicense(request);
    }

    @PostMapping("/licenses/{licenseId}/revocation")
    public LicenseView revoke(@PathVariable String licenseId,
                              @Valid @RequestBody RevokeLicenseRequest request) {
        if (!licenseId.equals(request.licenseId())) {
            throw new com.example.starter.rights.ApiException(
                    HttpStatus.BAD_REQUEST, "PATH_BODY_MISMATCH", "路径与请求体的 licenseId 不一致");
        }
        return service.revokeLicense(request);
    }

    @GetMapping("/licenses/{licenseId}/history")
    public List<LicenseView> history(@PathVariable String licenseId) {
        return service.getLicenseHistory(licenseId);
    }

    @PostMapping("/schedules")
    public ResponseEntity<SubmissionView> submit(@Valid @RequestBody SubmitScheduleRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.submitSchedule(request));
    }

    @GetMapping("/schedules/{submissionId}")
    public SubmissionView submission(@PathVariable String submissionId) {
        return service.getSubmission(submissionId);
    }

    @GetMapping("/schedules/{submissionId}/items/{itemIndex}/playability")
    public PlayabilityView playability(@PathVariable String submissionId,
                                       @PathVariable int itemIndex) {
        return service.checkPlayable(submissionId, itemIndex);
    }

    @PostMapping("/schedules/{submissionId}/items/{itemIndex}/playback")
    public PlaybackReceipt completePlayback(@PathVariable String submissionId,
                                            @PathVariable int itemIndex,
                                            @Valid @RequestBody CompletePlaybackRequest request) {
        return service.completePlayback(submissionId, itemIndex, request);
    }

    @GetMapping("/schedules/{submissionId}/evidence")
    public List<EvidenceView> evidenceBySubmission(@PathVariable String submissionId) {
        return service.getEvidenceBySubmission(submissionId);
    }

    @GetMapping("/evidence")
    public List<EvidenceView> evidenceByLicense(@RequestParam String licenseId) {
        return service.getEvidenceByLicense(licenseId);
    }
}
