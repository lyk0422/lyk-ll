package com.example.starter.marpol.web;

import com.example.starter.marpol.service.EmissionService;
import jakarta.validation.Valid;
import java.util.List;
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
 * 船舶排放记录接口：提交（幂等）、修正（版本化）、查询。
 */
@RestController
@RequestMapping("/api/discharges")
public class DischargeController {

    private final EmissionService emissionService;

    public DischargeController(EmissionService emissionService) {
        this.emissionService = emissionService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RecordResponse submit(@Valid @RequestBody DischargeSubmitRequest request) {
        return emissionService.submit(request);
    }

    @PostMapping("/amend")
    @ResponseStatus(HttpStatus.CREATED)
    public RecordResponse amend(@Valid @RequestBody DischargeAmendRequest request) {
        return emissionService.amend(request);
    }

    @GetMapping("/{recordNo}")
    public RecordResponse getRecord(@PathVariable String recordNo,
                                    @RequestParam(required = false) Integer version) {
        return emissionService.getRecord(recordNo, version);
    }

    @GetMapping("/{recordNo}/versions")
    public List<RecordResponse> listVersions(@PathVariable String recordNo) {
        return emissionService.listVersions(recordNo);
    }

    @GetMapping
    public List<RecordResponse> listActive(@RequestParam(required = false) String shipId,
                                           @RequestParam(required = false) String voyageNo) {
        return emissionService.listActive(shipId, voyageNo);
    }
}
