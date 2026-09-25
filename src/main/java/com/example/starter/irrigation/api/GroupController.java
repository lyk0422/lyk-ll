package com.example.starter.irrigation.api;

import com.example.starter.irrigation.api.GroupDtos.CreateGroupRequest;
import com.example.starter.irrigation.api.GroupDtos.GroupResponse;
import com.example.starter.irrigation.service.GroupService;
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
 * 轮灌组建组接口。
 */
@RestController
@RequestMapping("/api/irrigation/groups")
public class GroupController {

    private final GroupService groupService;

    public GroupController(GroupService groupService) {
        this.groupService = groupService;
    }

    /** 编组：建档轮灌组、斗口合成数据与灌溉面积。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public GroupResponse create(@Valid @RequestBody CreateGroupRequest request) {
        return groupService.createGroup(request);
    }

    /** 查询组与成员。 */
    @GetMapping("/{groupCode}")
    public GroupResponse get(@PathVariable String groupCode) {
        return groupService.getGroup(groupCode);
    }
}
