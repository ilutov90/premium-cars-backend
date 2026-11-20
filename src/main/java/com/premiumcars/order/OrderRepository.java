package com.premiumcars.order;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.premiumcars.catalog.domain.CarSource;
import com.premiumcars.catalog.domain.CarStatus;
import com.premiumcars.catalog.repo.CarRepository;
import com.premiumcars.catalog.repo.JsonColumns;

/** All SQL for the {@code customer_order} table. */
@Repository
public class OrderRepository {

    /**
     * An order with what the list shows of its car now.
     *
     * @param carStatus      the car's status now; null when the car is gone
     * @param coverPath      the cover photo's storage path (null for a linked photo or none)
     * @param coverSourceUrl the cover photo's source URL (what a linked photo is shown from)
     */
    public record OrderRow(CustomerOrder order, CarStatus carStatus, String coverPath, String coverSourceUrl,
                           int documentCount) {
    }

    private static final String SELECT = """
            SELECT o.id, o.car_id, o.car_title, o.car_slug, o.car_make, o.car_model, o.car_year, o.car_mileage_km,
                   o.car_source, o.car_source_url, o.car_source_ref, o.car_price_eur,
                   o.customer_name, o.customer_phone, o.customer_email, o.customer_address, o.customer_id_number,
                   o.company_name, o.company_eik, o.vin, o.agreed_price_eur, o.deposit_eur, o.status,
                   o.container_number, o.contract_date, o.notes, o.created_at, o.updated_at, o.created_by, o.car_photos,
                   c.status AS car_status, p.storage_path AS cover_path, p.source_url AS cover_source_url,
                   (SELECT count(*) FROM order_document d WHERE d.order_id = o.id) AS document_count
            FROM customer_order o
            LEFT JOIN car c ON c.id = o.car_id
            LEFT JOIN LATERAL (SELECT i.storage_path, i.source_url FROM car_image i WHERE i.car_id = o.car_id
                               ORDER BY i.position, i.id LIMIT 1) p ON true
            """;

    private final JdbcClient jdbc;
    private final JsonColumns photos;
    private final RowMapper<OrderRow> mapper;

    public OrderRepository(JdbcClient jdbc, JsonColumns photos) {
        this.jdbc = jdbc;
        this.photos = photos;
        this.mapper = rowMapper();
    }

    private RowMapper<OrderRow> rowMapper() {
        return (rs, n) -> {
            String source = rs.getString("car_source");
            String carStatus = rs.getString("car_status");
            CustomerOrder order = new CustomerOrder(
                    rs.getLong("id"),
                    rs.getObject("car_id", Long.class),
                    new CarSnapshot(rs.getString("car_title"), rs.getString("car_slug"), rs.getString("car_make"),
                            rs.getString("car_model"), rs.getObject("car_year", Integer.class),
                            rs.getObject("car_mileage_km", Integer.class),
                            source == null ? null : CarSource.valueOf(source), rs.getString("car_source_url"),
                            rs.getString("car_source_ref"), rs.getBigDecimal("car_price_eur")),
                    new Customer(rs.getString("customer_name"), rs.getString("customer_phone"),
                            rs.getString("customer_email"), rs.getString("customer_address"),
                            rs.getString("customer_id_number"), rs.getString("company_name"),
                            rs.getString("company_eik")),
                    new OrderFields(rs.getString("vin"), rs.getBigDecimal("agreed_price_eur"),
                            rs.getBigDecimal("deposit_eur"), OrderStatus.valueOf(rs.getString("status")),
                            rs.getString("container_number"),
                            rs.getObject("contract_date", java.time.LocalDate.class), rs.getString("notes")),
                    rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                    rs.getObject("updated_at", OffsetDateTime.class).toInstant(),
                    rs.getString("created_by"),
                    photos.readStringList(rs.getString("car_photos")));
            return new OrderRow(order, carStatus == null ? null : CarStatus.valueOf(carStatus),
                    rs.getString("cover_path"), rs.getString("cover_source_url"), rs.getInt("document_count"));
        };
    }

    public long insert(Long carId, CarSnapshot car, Customer customer, OrderFields fields, String createdBy) {
        Map<String, Object> p = params(carId, car, customer, fields);
        p.put("createdBy", createdBy);
        return jdbc.sql("""
                        INSERT INTO customer_order (car_id, car_title, car_slug, car_make, car_model, car_year,
                            car_mileage_km, car_source, car_source_url, car_source_ref, car_price_eur,
                            customer_name, customer_phone, customer_email, customer_address, customer_id_number,
                            company_name, company_eik, vin, agreed_price_eur, deposit_eur, status, container_number,
                            contract_date, notes, created_by)
                        VALUES (:carId, :carTitle, :carSlug, :carMake, :carModel, :carYear, :carMileageKm,
                            :carSource, :carSourceUrl, :carSourceRef, :carPriceEur, :customerName, :customerPhone,
                            :customerEmail, :customerAddress, :customerIdNumber, :companyName, :companyEik, :vin,
                            :agreedPriceEur, :depositEur, :status, :containerNumber, :contractDate, :notes,
                            :createdBy)
                        RETURNING id""")
                .params(p)
                .query(Long.class)
                .single();
    }

    /** Replaces every value but the creation's; returns false when the order does not exist. */
    public boolean update(long id, Long carId, CarSnapshot car, Customer customer, OrderFields fields) {
        Map<String, Object> p = params(carId, car, customer, fields);
        p.put("id", id);
        return jdbc.sql("""
                        UPDATE customer_order SET car_id = :carId, car_title = :carTitle, car_slug = :carSlug,
                            car_make = :carMake, car_model = :carModel, car_year = :carYear,
                            car_mileage_km = :carMileageKm, car_source = :carSource, car_source_url = :carSourceUrl,
                            car_source_ref = :carSourceRef, car_price_eur = :carPriceEur,
                            customer_name = :customerName, customer_phone = :customerPhone,
                            customer_email = :customerEmail, customer_address = :customerAddress,
                            customer_id_number = :customerIdNumber, company_name = :companyName,
                            company_eik = :companyEik, vin = :vin, agreed_price_eur = :agreedPriceEur,
                            deposit_eur = :depositEur, status = :status, container_number = :containerNumber,
                            contract_date = :contractDate, notes = :notes, updated_at = now()
                        WHERE id = :id""")
                .params(p)
                .update() == 1;
    }

    /** Its documents' rows go with it (ON DELETE CASCADE). */
    public boolean delete(long id) {
        return jdbc.sql("DELETE FROM customer_order WHERE id = :id").param("id", id).update() == 1;
    }

    /** Sets the stored photo paths (gallery order) of one order. */
    public void saveCarPhotos(long id, List<String> paths) {
        jdbc.sql("UPDATE customer_order SET car_photos = CAST(:paths AS jsonb) WHERE id = :id")
                .param("paths", photos.write(paths))
                .param("id", id)
                .update();
    }

    /** Sets the stored photo paths of every order of the car (the car is about to be deleted). */
    public void saveCarPhotosOfCar(long carId, List<String> paths) {
        jdbc.sql("UPDATE customer_order SET car_photos = CAST(:paths AS jsonb) WHERE car_id = :carId")
                .param("paths", photos.write(paths))
                .param("carId", carId)
                .update();
    }

    /** Another order keeps this photo file in its list. */
    public boolean photoKeptByOtherOrder(String path, long exceptOrderId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM customer_order WHERE id <> :id "
                        + "AND car_photos @> CAST(:path AS jsonb))")
                .param("id", exceptOrderId)
                .param("path", photos.write(List.of(path)))
                .query(Boolean.class)
                .single();
    }

    public Optional<OrderRow> findById(long id) {
        return jdbc.sql(SELECT + "WHERE o.id = :id").param("id", id).query(mapper).optional();
    }

    /** The car's order that is not cancelled, other than {@code exceptOrderId} (null = any). */
    public Optional<Long> findActiveOrderOf(long carId, Long exceptOrderId) {
        Map<String, Object> p = new HashMap<>();
        p.put("carId", carId);
        p.put("except", exceptOrderId == null ? -1L : exceptOrderId);
        return jdbc.sql("""
                        SELECT id FROM customer_order
                        WHERE car_id = :carId AND status <> 'CANCELLED' AND id <> :except
                        ORDER BY id DESC LIMIT 1""")
                .params(p)
                .query(Long.class)
                .optional();
    }

    /** The car's order for its editor: the one not cancelled, else the newest. */
    public Optional<Long> findOrderIdOfCar(long carId) {
        return jdbc.sql("""
                        SELECT id FROM customer_order WHERE car_id = :carId
                        ORDER BY (status = 'CANCELLED'), id DESC LIMIT 1""")
                .param("carId", carId)
                .query(Long.class)
                .optional();
    }

    /** The car has an order, in any status. */
    public boolean hasOrder(long carId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM customer_order WHERE car_id = :carId)")
                .param("carId", carId)
                .query(Boolean.class)
                .single();
    }

    /** Every car with an order, in any status. */
    public Set<Long> findOrderedCarIds() {
        return new HashSet<>(jdbc.sql("SELECT DISTINCT car_id FROM customer_order WHERE car_id IS NOT NULL")
                .query(Long.class)
                .list());
    }

    /** One page of the orders matching the status and text, newest first. */
    public List<OrderRow> search(OrderStatus status, String q, int offset, int limit) {
        Where w = where(status, q);
        Map<String, Object> p = new HashMap<>(w.params());
        p.put("offset", offset);
        p.put("limit", limit);
        return jdbc.sql(SELECT + w.sql() + " ORDER BY o.created_at DESC, o.id DESC LIMIT :limit OFFSET :offset")
                .params(p)
                .query(mapper)
                .list();
    }

    public long count(OrderStatus status, String q) {
        Where w = where(status, q);
        return jdbc.sql("SELECT count(*) FROM customer_order o " + w.sql())
                .params(w.params())
                .query(Long.class)
                .single();
    }

    record Where(String sql, Map<String, Object> params) {
    }

    /**
     * The status, and the text over the customer (name, phone also by its digits, e-mail, company), the car's title,
     * the VIN and the container; a car's number ("Обява № {id}") or a link to its page also finds its orders.
     * Fixed fragments with named parameters only.
     */
    static Where where(OrderStatus status, String q) {
        List<String> and = new ArrayList<>();
        Map<String, Object> p = new HashMap<>();
        if (status != null) {
            and.add("o.status = :status");
            p.put("status", status.name());
        }
        String text = OrderText.clean(q);
        if (text != null) {
            List<String> or = new ArrayList<>(List.of("o.customer_name ILIKE :q", "o.customer_phone ILIKE :q",
                    "o.customer_email ILIKE :q", "o.company_name ILIKE :q", "o.car_title ILIKE :q",
                    "o.vin ILIKE :q", "o.container_number ILIKE :q"));
            p.put("q", "%" + escapeLike(text) + "%");
            // the car page's "Обява № 29538" as copied
            Long number = CarRepository.carNumber(text.replaceFirst("(?iu)^обява\\s*", ""));
            if (number != null) {
                or.add("o.car_id = :carNumber");
                p.put("carNumber", number);
            }
            String slug = CarRepository.siteSlug(text);
            if (slug != null) {
                or.add("o.car_slug = :slug");
                p.put("slug", slug);
            }
            String digits = text.replaceAll("\\D", "");
            if (digits.length() >= 3 && text.matches("[+\\d\\s()./-]+")) {
                or.add("regexp_replace(o.customer_phone, '\\D', '', 'g') LIKE :phoneDigits");
                p.put("phoneDigits", "%" + digits + "%");
            }
            and.add("(" + String.join(" OR ", or) + ")");
        }
        return new Where(and.isEmpty() ? "" : "WHERE " + String.join(" AND ", and), p);
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static Map<String, Object> params(Long carId, CarSnapshot car, Customer customer, OrderFields f) {
        CarSnapshot s = car == null ? new CarSnapshot(null, null, null, null, null, null, null, null, null, null)
                : car;
        Customer c = customer == null ? Customer.EMPTY : customer;
        Map<String, Object> p = new HashMap<>();
        p.put("carId", carId);
        p.put("carTitle", s.title());
        p.put("carSlug", s.slug());
        p.put("carMake", s.make());
        p.put("carModel", s.model());
        p.put("carYear", s.year());
        p.put("carMileageKm", s.mileageKm());
        p.put("carSource", s.source() == null ? null : s.source().name());
        p.put("carSourceUrl", s.sourceUrl());
        p.put("carSourceRef", s.sourceRef());
        p.put("carPriceEur", s.priceEur());
        p.put("customerName", c.name());
        p.put("customerPhone", c.phone());
        p.put("customerEmail", c.email());
        p.put("customerAddress", c.address());
        p.put("customerIdNumber", c.idNumber());
        p.put("companyName", c.companyName());
        p.put("companyEik", c.companyEik());
        p.put("vin", f.vin());
        p.put("agreedPriceEur", f.agreedPriceEur());
        p.put("depositEur", f.depositEur());
        p.put("status", f.status().name());
        p.put("containerNumber", f.containerNumber());
        p.put("contractDate", f.contractDate());
        p.put("notes", f.notes());
        return p;
    }
}
