package com.example.starter.irrigation.service;

import com.example.starter.irrigation.dto.GroupContracts.CreateGroupRequest;
import com.example.starter.irrigation.dto.GroupContracts.GroupView;
import com.example.starter.irrigation.dto.GroupContracts.OutletInput;
import com.example.starter.irrigation.dto.GroupContracts.OutletView;
import com.example.starter.irrigation.error.BizException;
import com.example.starter.irrigation.repo.GroupRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 轮灌组编组服务。
 */
@Service
public class GroupService {

    private final GroupRepository groupRepository;

    public GroupService(GroupRepository groupRepository) {
        this.groupRepository = groupRepository;
    }

    /**
     * 创建轮灌组及组内斗口；组编码全局唯一，斗口编号组内唯一。
     */
    @Transactional
    public GroupView create(CreateGroupRequest request) {
        long groupId;
        try {
            groupId = groupRepository.insertGroup(request.code(), request.name());
        } catch (DuplicateKeyException e) {
            throw BizException.conflict("轮灌组编码已存在: " + request.code());
        }
        List<OutletView> outlets = new ArrayList<>();
        for (OutletInput input : request.outlets()) {
            long outletId;
            try {
                outletId = groupRepository.insertOutlet(groupId, input.code(), input.name(), input.areaMu());
            } catch (DuplicateKeyException e) {
                throw BizException.conflict("组内斗口编号重复: " + input.code());
            }
            outlets.add(new OutletView(outletId, input.code(), input.name(), input.areaMu()));
        }
        outlets.sort((a, b) -> a.code().compareTo(b.code()));
        return new GroupView(groupId, request.code(), request.name(), outlets);
    }

    public GroupView get(long groupId) {
        GroupRepository.GroupRow group = groupRepository.findGroupById(groupId)
                .orElseThrow(() -> BizException.notFound("轮灌组不存在: " + groupId));
        List<OutletView> outlets = groupRepository.findOutletsByGroupId(groupId).stream()
                .map(o -> new OutletView(o.id(), o.code(), o.name(), o.areaMu()))
                .toList();
        return new GroupView(group.id(), group.code(), group.name(), outlets);
    }
}
