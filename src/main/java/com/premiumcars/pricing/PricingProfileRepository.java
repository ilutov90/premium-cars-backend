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
            "id, sea_freight_eur, land_transport_eur, commission_eur, profit_pct, profit_min_eur, duty_vat_pct, updated_at";

    private static final RowMapper<PricingProfile> MAPPER = (rs, i) -> new PricingProfile(
            PriceProfile.valueOf(rs.getString("id")),
            rs.getBigDecimal("sea_freight_eur"),
            rs.getBigDecimal("land_transport_eur"),
            rs.getBigDecimal("commission_eur"),
            rs.getBigDecimal("profit_pct"),
            rs.getBigDecimal("profit_min_eur"),
            rs.getBigDecimal("duty_vat_pct"),
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
                            sea_freight_eur = :seaFreight, land_transport_eur = :landTransport,
                            commission_eur = :commission, profit_pct = :profitPct, profit_min_eur = :profitMin,
                            duty_vat_pct = :dutyVatPct,
                            updated_at = now()
                        WHERE id = :id""")
                .param("seaFreight", p.seaFreight())
                .param("landTransport", p.landTransport())
                .param("commission", p.commission())
                .param("profitPct", p.profitPct())
                .param("profitMin", p.profitMin())
                .param("dutyVatPct", p.dutyVatPct())
                .param("id", id.name())
                .update();
        return rows == 1;
    }
}
