package com.example.starter.web;

import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import com.example.starter.service.MissedRideService;
import com.example.starter.support.NotFoundException;
import com.example.starter.web.dto.MissedResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 线路查询入口：线路详情（含资格）与漏乘名单。
 */
@RestController
@RequestMapping("/api/routes")
public class RouteController {

    private final JdbcTemplate jdbc;
    private final MissedRideService missedRideService;
    private final Clock clock;

    public RouteController(JdbcTemplate jdbc, MissedRideService missedRideService, Clock clock) {
        this.jdbc = jdbc;
        this.missedRideService = missedRideService;
        this.clock = clock;
    }

    /**
     * 线路详情：状态、车辆与运行资格。
     */
    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable long id) {
        Map<String, Object> route = jdbc.query(
                "SELECT id, code, vehicle_id, status FROM route WHERE id = ?",
                rs -> {
                    if (!rs.next()) {
                        throw new NotFoundException("线路不存在: " + id);
                    }
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("id", rs.getLong(1));
                    map.put("code", rs.getString(2));
                    map.put("vehicleId", rs.getObject(3));
                    map.put("status", rs.getString(4));
                    return map;
                },
                id);
        jdbc.query("SELECT valid_from, valid_to, status FROM qualification WHERE route_id = ?",
                rs -> {
                    Map<String, Object> qualification = new LinkedHashMap<>();
                    qualification.put("validFrom", rs.getDate(1).toLocalDate().toString());
                    qualification.put("validTo", rs.getDate(2).toLocalDate().toString());
                    qualification.put("status", rs.getString(3));
                    route.put("qualification", qualification);
                },
                id);
        return route;
    }

    /**
     * 漏乘名单：实时生成合成通知记录（幂等去重）。
     */
    @GetMapping("/{id}/missed")
    public MissedResult missed(@PathVariable long id,
                               @RequestParam(required = false) LocalDate date) {
        LocalDate effectiveDate = date != null ? date : LocalDate.now(clock);
        return missedRideService.missed(id, effectiveDate);
    }
}
