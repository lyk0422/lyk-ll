package com.example.starter.flood.web;

import com.example.starter.flood.service.ReadingService;
import com.example.starter.flood.web.dto.RainfallRequest;
import com.example.starter.flood.web.dto.ReadingResult;
import com.example.starter.flood.web.dto.WaterReadingRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 降雨/水位读数上报：测点+采样时刻去重、乱序按时刻归位、归档后迟到只记事件。
 */
@RestController
@RequestMapping("/api/ponds/{pondCode}/readings")
public class ReadingController {

    private final ReadingService readingService;

    public ReadingController(ReadingService readingService) {
        this.readingService = readingService;
    }

    @PostMapping("/rainfall")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ReadingResult reportRainfall(@PathVariable String pondCode,
                                        @Valid @RequestBody RainfallRequest request) {
        return readingService.reportRainfall(pondCode, request);
    }

    @PostMapping("/water")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ReadingResult reportWater(@PathVariable String pondCode,
                                     @Valid @RequestBody WaterReadingRequest request) {
        return readingService.reportWater(pondCode, request);
    }
}
