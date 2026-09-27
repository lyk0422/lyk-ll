package com.example.starter.cosmetic.child.port;

import com.example.starter.cosmetic.child.domain.ChildAgeRule;
import com.example.starter.cosmetic.child.domain.ChildEfficacy;
import com.example.starter.cosmetic.child.domain.ChildRestrictedLimit;

import java.util.List;
import java.util.Optional;

/**
 * 儿童分龄规则与儿童功效合成目录端口。
 */
public interface ChildCatalogPort {

    /** 当前启用的分龄规则版本（含档位定义）。 */
    ChildAgeRule activeAgeRule();

    /** 按编码查询儿童功效目录条目。 */
    Optional<ChildEfficacy> findEfficacy(String code);

    /** 全部儿童功效目录。 */
    List<ChildEfficacy> listEfficacies();

    /** 指定规则版本下的全部儿童限用成分分档限值。 */
    List<ChildRestrictedLimit> listRestrictedLimits(String ruleVersion);
}
