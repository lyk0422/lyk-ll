package com.example.starter.cosmetic.child.port;

import com.example.starter.cosmetic.child.domain.AgeTier;
import com.example.starter.cosmetic.child.domain.ChildEfficacy;
import com.example.starter.cosmetic.child.domain.ChildIngredientLimit;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * 儿童分龄合成规则端口：分龄档位版本、儿童功效目录、分档成分限值。
 *
 * <p>受理时冻结 {@link #currentRuleVersion()} 返回的规则版本，规则升级不回溯在审件。</p>
 */
public interface ChildCatalogPort {

    /** 当前生效的分龄规则版本（合成初始化，唯一版本 V1）。 */
    String currentRuleVersion();

    /** 查询指定版本的分档定义（按档位顺序 0~36、37~144）。 */
    List<AgeTier> tiers(String ruleVersion);

    /** 按编码查询儿童功效目录条目。 */
    Optional<ChildEfficacy> findEfficacy(String code);

    /** 查询指定版本、指定分档、指定成分的限值；该档未列限值得空（该档不限制）。 */
    Optional<BigDecimal> findLimit(String ruleVersion, AgeTier tier, String ingredientCode);

    /** 列出指定版本全部分档成分限值（供分龄规则合成初始化查询）。 */
    List<ChildIngredientLimit> listLimits(String ruleVersion);
}
