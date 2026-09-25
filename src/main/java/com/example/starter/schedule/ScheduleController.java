package com.example.starter.schedule;

import com.example.starter.common.ApiException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * 编排服务 REST 入口：申请、撤销、发布与版本查询。
 */
@RestController
@RequestMapping("/api/schedule")
public class ScheduleController {

    private final ScheduleService service;

    public ScheduleController(ScheduleService service) {
        this.service = service;
    }

    /**
     * 提交编排申请（requestId 幂等）。成功 200；冲突/冻结/幂等键冲突 409。
     */
    @PostMapping("/requests")
    public ResponseEntity<String> submit(@RequestBody ScheduleDtos.SubmitRequest request) {
        ScheduleService.StoredResponse response = service.submit(request);
        return ResponseEntity.status(response.httpStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.body());
    }

    /**
     * 撤销编排条目。已发布冻结日返回 409。
     */
    @PostMapping("/entries/{entryId}/cancel")
    public ScheduleDtos.EntryView cancel(@PathVariable long entryId) {
        return service.cancel(entryId);
    }

    /**
     * 按日发布编排表。重复发布返回幂等成功；有紧急插入草稿时发布新版本。
     */
    @PostMapping("/publications")
    public ScheduleDtos.PublishResult publish(@RequestBody ScheduleDtos.PublishRequest request) {
        if (request == null || request.day() == null) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "day 为必填（yyyy-MM-dd）");
        }
        return service.publish(parseDay(request.day()));
    }

    /**
     * 查询某日当前全部编排条目（含草稿与各状态）。
     */
    @GetMapping("/entries")
    public List<ScheduleDtos.EntryView> listEntries(@RequestParam String day) {
        return service.listEntries(parseDay(day));
    }

    /**
     * 查询某日全部发布版本。
     */
    @GetMapping("/publications")
    public List<ScheduleDtos.PublicationView> listPublications(@RequestParam String day) {
        return service.listPublications(parseDay(day));
    }

    /**
     * 查询某日指定版本快照，历史版本可查。
     */
    @GetMapping("/publications/{day}/{version}")
    public List<ScheduleDtos.EntryView> snapshot(@PathVariable String day, @PathVariable int version) {
        return service.snapshot(parseDay(day), version);
    }

    private LocalDate parseDay(String day) {
        try {
            return LocalDate.parse(day);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "day 格式应为 yyyy-MM-dd: " + day);
        }
    }
}
