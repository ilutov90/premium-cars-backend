package com.premiumcars.pricing;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** SQL for {@code pricing_profile} and its duty and VAT table {@code pricing_duty_bracket}. */
@Repository
public class PricingProfileRepository {

    private static final String COLUMNS = "id, sea_freight_eur, land_transport_eur, commission_eur, duty_vat_pct,"
            + " markup_low_eur, markup_high_eur, markup_threshold_eur, updated_at";

    private static final RowMapper<PricingProfile> MAPPER = (rs, i) -> new PricingProfile(
            PriceProfile.valueOf(rs.getString("id")),
            rs.getBigDecimal("sea_freight_eur"),
            rs.getBigDecimal("land_transport_eur"),
            rs.getBigDecimal("commission_eur"),
            rs.getBigDecimal("duty_vat_pct"),
            List.of(),
            rs.getBigDecimal("markup_low_eur"),
            rs.getBigDecimal("markup_high_eur"),
            rs.getBigDecimal("markup_threshold_eur"),
            rs.getObject("updated_at", OffsetDateTime.class).toInstant());

    private final JdbcClient jdbc;

    public PricingProfileRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<PricingProfile> findAll() {
        Map<String, List<DutyBracket>> brackets = brackets();
        return jdbc.sql("SELECT " + COLUMNS + " FROM pricing_profile ORDER BY id").query(MAPPER).list().stream()
                .map(p -> p.withBrackets(brackets.getOrDefault(p.id().name(), List.of())))
                .toList();
    }

    public Optional<PricingProfile> find(PriceProfile id) {
        return findAll().stream().filter(p -> p.id() == id).findFirst();
    }

    /** Returns false when the profile does not exist. The table is replaced by the given rows. */
    @Transactional
    public boolean update(PriceProfile id, PricingProfile p) {
        int rows = jdbc.sql("""
                        UPDATE pricing_profile SET
                            sea_freight_eur = :seaFreight, land_transport_eur = :landTransport,
                            commission_eur = :commission, duty_vat_pct = :dutyVatPct,
                            markup_low_eur = :markupLow, markup_high_eur = :markupHigh,
                            markup_threshold_eur = :markupThreshold,
                            updated_at = now()
                        WHERE id = :id""")
                .param("seaFreight", p.seaFreight())
                .param("landTransport", p.landTransport())
                .param("commission", p.commission())
                .param("dutyVatPct", p.dutyVatPct())
                .param("markupLow", p.markupLow())
                .param("markupHigh", p.markupHigh())
                .param("markupThreshold", p.markupThreshold())
                .param("id", id.name())
                .update();
        if (rows != 1) {
            return false;
        }
        jdbc.sql("DELETE FROM pricing_duty_bracket WHERE profile_id = :id").param("id", id.name()).update();
        for (DutyBracket b : p.dutyBrackets()) {
            jdbc.sql("INSERT INTO pricing_duty_bracket (profile_id, fob_up_to, duty_vat_eur) VALUES (:id, :upTo, :duty)")
                    .param("id", id.name())
                    .param("upTo", b.fobUpTo(), java.sql.Types.NUMERIC)
                    .param("duty", b.dutyVat())
                    .update();
        }
        return true;
    }

    /** profile id -> its table, ascending, the open row last. */
    private Map<String, List<DutyBracket>> brackets() {
        Map<String, List<DutyBracket>> out = new HashMap<>();
        jdbc.sql("""
                        SELECT profile_id, fob_up_to, duty_vat_eur FROM pricing_duty_bracket
                        ORDER BY profile_id, fob_up_to NULLS LAST""")
                .query((rs, n) -> {
                    out.computeIfAbsent(rs.getString("profile_id"), k -> new ArrayList<>())
                            .add(new DutyBracket(rs.getBigDecimal("fob_up_to"), rs.getBigDecimal("duty_vat_eur")));
                    return null;
                })
                .list();
        return out;
    }
}
