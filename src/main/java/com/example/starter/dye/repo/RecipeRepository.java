package com.example.starter.dye.repo;

import com.example.starter.dye.domain.RecipeItemPO;
import com.example.starter.dye.domain.RecipePO;
import com.example.starter.dye.domain.RecipeStatus;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 配方版本与条目仓储。版本不可变（发布后冻结），修改通过新版本追加实现。
 */
@Repository
public class RecipeRepository {

    private final JdbcTemplate jdbc;

    public RecipeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<RecipePO> RECIPE_MAPPER = (rs, n) -> {
        RecipePO po = new RecipePO();
        po.setId(rs.getLong("id"));
        po.setRecipeCode(rs.getString("recipe_code"));
        po.setVersion(rs.getInt("version"));
        po.setName(rs.getString("name"));
        po.setLiquorRatio(rs.getInt("liquor_ratio"));
        po.setStatus(RecipeStatus.valueOf(rs.getString("status")));
        Timestamp pub = rs.getTimestamp("published_at");
        po.setPublishedAt(pub == null ? null : pub.toLocalDateTime());
        Timestamp created = rs.getTimestamp("created_at");
        po.setCreatedAt(created == null ? null : created.toLocalDateTime());
        return po;
    };

    private static final RowMapper<RecipeItemPO> ITEM_MAPPER = (rs, n) -> {
        RecipeItemPO po = new RecipeItemPO();
        po.setId(rs.getLong("id"));
        po.setRecipeId(rs.getLong("recipe_id"));
        po.setDyestuffCode(rs.getString("dyestuff_code"));
        po.setGramsPerLiter(rs.getBigDecimal("grams_per_liter"));
        po.setSeqNo(rs.getInt("seq_no"));
        return po;
    };

    public Optional<RecipePO> find(String recipeCode, int version) {
        try {
            return Optional.of(jdbc.queryForObject(
                    "select id, recipe_code, version, name, liquor_ratio, status, created_at, published_at "
                            + "from recipe where recipe_code = ? and version = ?",
                    RECIPE_MAPPER, recipeCode, version));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Optional<RecipePO> findLatestPublished(String recipeCode) {
        try {
            return Optional.of(jdbc.queryForObject(
                    "select id, recipe_code, version, name, liquor_ratio, status, created_at, published_at "
                            + "from recipe where recipe_code = ? and status = 'PUBLISHED' "
                            + "order by version desc limit 1",
                    RECIPE_MAPPER, recipeCode));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Optional<RecipePO> findLatestAny(String recipeCode) {
        try {
            return Optional.of(jdbc.queryForObject(
                    "select id, recipe_code, version, name, liquor_ratio, status, created_at, published_at "
                            + "from recipe where recipe_code = ? order by version desc limit 1",
                    RECIPE_MAPPER, recipeCode));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public int maxVersion(String recipeCode) {
        Integer v = jdbc.queryForObject("select coalesce(max(version), 0) from recipe where recipe_code = ?",
                Integer.class, recipeCode);
        return v == null ? 0 : v;
    }

    public long insert(String recipeCode, int version, String name, int liquorRatio,
                       RecipeStatus status, LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "insert into recipe (recipe_code, version, name, liquor_ratio, status, created_at, "
                            + "published_at) values (?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, recipeCode);
            ps.setInt(2, version);
            ps.setString(3, name);
            ps.setInt(4, liquorRatio);
            ps.setString(5, status.name());
            ps.setTimestamp(6, Timestamp.valueOf(now));
            ps.setTimestamp(7, status == RecipeStatus.PUBLISHED ? Timestamp.valueOf(now) : null);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public void publish(long recipeId, LocalDateTime now) {
        jdbc.update("update recipe set status = 'PUBLISHED', published_at = ? where id = ?",
                Timestamp.valueOf(now), recipeId);
    }

    public void insertItem(long recipeId, String dyestuffCode, java.math.BigDecimal gramsPerLiter, int seqNo) {
        jdbc.update("insert into recipe_item (recipe_id, dyestuff_code, grams_per_liter, seq_no) "
                + "values (?, ?, ?, ?)", recipeId, dyestuffCode, gramsPerLiter, seqNo);
    }

    public List<RecipeItemPO> findItems(long recipeId) {
        return jdbc.query("select id, recipe_id, dyestuff_code, grams_per_liter, seq_no from recipe_item "
                + "where recipe_id = ? order by seq_no", ITEM_MAPPER, recipeId);
    }

    public List<RecipePO> findAll() {
        return jdbc.query("select id, recipe_code, version, name, liquor_ratio, status, created_at, "
                + "published_at from recipe order by recipe_code, version", RECIPE_MAPPER);
    }
}
