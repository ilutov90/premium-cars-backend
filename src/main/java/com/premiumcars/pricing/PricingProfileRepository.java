package com.premiumcars.pricing;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL for {@code pricing_profile}. */
@Repository
public class PricingProfileRepository {

    private static final String COLUMNS =
            "id, base_costs_eur, threshold_eur, step_eur, step_cost_eur, profit_eur, updated_at";

    private static final RowMapper<PricingProfile> MAPPER = (rs, i) -> new PricingProfile(
            PriceProfile.valueOf(rs.getString("id")),
            rs.getBigDecimal("base_costs_eur"),
            rs.getBigDecimal("threshold_eur"),
            rs.getBigDecimal("step_eur"),
            rs.getBigDecimal("step_cost_eur"),
            rs.getBigDecimal("profit_eur"),
            rs.getObject("updated_at", OffsetDateTime.class).toInstant());

    private final JdbcClient jdbc;

    public PricingProfileRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<PricingProfile> findAll() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM pricing_profile ORDER BY id").query(MAPPER).list();
    }

    public Optional<PricingProfile> find(PriceProfile id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM pricing_profile WHERE id = :id")
                .param("id", id.name())
                .query(MAPPER)
                .optional();
    }

    /** Returns false when the profile does not exist. */
    public boolean update(PriceProfile id, PricingProfile p) {
        int rows = jdbc.sql("""
                        UPDATE pricing_profile SET
                            base_costs_eur = :baseCosts, threshold_eur = :threshold, step_eur = :step,
                            step_cost_eur = :stepCost, profit_eur = :profit,
                            updated_at = now()
                        WHERE id = :id""")
                .param("baseCosts", p.baseCosts())
                .param("threshold", p.threshold())
                .param("step", p.step())
                .param("stepCost", p.stepCost())
                .param("profit", p.profit())
                .param("id", id.name())
                .update();
        return rows == 1;
    }
}
