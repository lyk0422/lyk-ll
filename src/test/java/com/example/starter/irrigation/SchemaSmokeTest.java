package com.example.starter.irrigation;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.irrigation.api.GroupDtos.CreateGroupRequest;
import com.example.starter.irrigation.api.GroupDtos.OutletMember;
import com.example.starter.irrigation.service.GroupService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 冒烟测试：H2 MODE=MySQL 下 schema.sql 全部建表语句可执行，编组主流程可用。
 */
class SchemaSmokeTest extends AbstractIntegrationTest {

    @Autowired
    private GroupService groupService;

    @Test
    void allBusinessTablesAreCreated() {
        List<String> tables = List.of(
                "irr_group", "irr_outlet", "irr_group_outlet", "irr_plan_version", "irr_plan_line",
                "irr_metering", "irr_meter_event", "irr_extra_purchase", "irr_extra_allocation",
                "irr_settlement", "irr_carryover", "irr_late_meter_event");
        for (String table : tables) {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(1) FROM INFORMATION_SCHEMA.TABLES WHERE LOWER(TABLE_NAME) = ?",
                    Integer.class, table);
            assertThat(count).as("表 %s 应已创建", table).isEqualTo(1);
        }
    }

    @Test
    void createGroupPersistsSyntheticOutletsAndAreas() {
        groupService.createGroup(new CreateGroupRequest("G-SMOKE", "冒烟一组", List.of(
                new OutletMember("D001", "东斗口", 600L),
                new OutletMember("D002", "西斗口", 400L))));

        assertThat(groupService.getGroup("G-SMOKE").outlets()).hasSize(2);
        Integer memberCount = jdbc.queryForObject(
                "SELECT COUNT(1) FROM irr_group_outlet WHERE group_code = 'G-SMOKE'", Integer.class);
        assertThat(memberCount).isEqualTo(2);
    }
}
