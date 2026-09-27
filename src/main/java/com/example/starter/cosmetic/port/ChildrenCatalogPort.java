package com.example.starter.cosmetic.port;

import com.example.starter.cosmetic.domain.ChildrenAgeBandRule;
import com.example.starter.cosmetic.domain.ChildrenBandLimit;
import com.example.starter.cosmetic.domain.ChildrenEfficacy;

import java.util.List;
import java.util.Optional;

/**
 * 儿童分龄规则与儿童功效合成目录端口。
 */
public interface ChildrenCatalogPort {

    /** 全部分龄档位（按下限月数升序）。 */
    List<ChildrenAgeBandRule> listBandRules();

    /** 按档位编码查询分龄规则。 */
    Optional<ChildrenAgeBandRule> findBandRule(String bandCode);

    /** 当前分龄规则版本（各档规则版本的最大值，受理时冻结）。 */
    int currentRuleVersion();

    /** 查询某档某限用成分的限值。 */
    Optional<ChildrenBandLimit> findBandLimit(String bandCode, String ingredientCode);

    /** 按编码查询儿童功效目录条目。 */
    Optional<ChildrenEfficacy> findChildrenEfficacy(String code);
}
