package com.example.starter.irrigation.service;

import com.example.starter.irrigation.api.GroupDtos.CreateGroupRequest;
import com.example.starter.irrigation.api.GroupDtos.GroupResponse;
import com.example.starter.irrigation.api.GroupDtos.MemberResponse;
import com.example.starter.irrigation.api.GroupDtos.OutletMember;
import com.example.starter.irrigation.repo.GroupRepository;
import com.example.starter.irrigation.repo.GroupRepository.MemberRow;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 轮灌组建组服务：组、斗口合成数据与成员面积同一事务落库。
 */
@Service
public class GroupService {

    private final GroupRepository groupRepository;
    private final Clock clock;

    public GroupService(GroupRepository groupRepository, Clock clock) {
        this.groupRepository = groupRepository;
        this.clock = clock;
    }

    /** 编组：组编号已存在或斗口重复入组时返回 409。 */
    @Transactional
    public GroupResponse createGroup(CreateGroupRequest request) {
        if (groupRepository.existsGroup(request.groupCode())) {
            throw new ConflictException("轮灌组编号已存在: " + request.groupCode());
        }
        Set<String> uniqueOutlets = new HashSet<>();
        for (OutletMember outlet : request.outlets()) {
            if (!uniqueOutlets.add(outlet.outletCode())) {
                throw new BusinessRuleException("组内斗口编号重复: " + outlet.outletCode());
            }
        }
        Instant now = clock.instant();
        List<MemberRow> members = request.outlets().stream()
                .map(outlet -> new MemberRow(request.groupCode(), outlet.outletCode(),
                        outlet.outletName(), outlet.areaSquareMeter()))
                .toList();
        try {
            groupRepository.createGroup(request.groupCode(), request.groupName(), members, now);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 并发编组或斗口已编入其他组时，唯一索引兜底，映射 409。
            throw new ConflictException("轮灌组编号已存在或斗口已入组: " + request.groupCode());
        }
        return getGroup(request.groupCode());
    }

    /** 查询组与成员；组不存在返回 404。 */
    public GroupResponse getGroup(String groupCode) {
        String groupName = groupRepository.findGroupName(groupCode)
                .orElseThrow(() -> new NotFoundException("轮灌组不存在: " + groupCode));
        List<MemberRow> members = groupRepository.findMembers(groupCode);
        return new GroupResponse(groupCode, groupName,
                members.stream()
                        .map(member -> new MemberResponse(member.outletCode(), member.outletName(),
                                member.areaSquareMeter()))
                        .toList());
    }
}
