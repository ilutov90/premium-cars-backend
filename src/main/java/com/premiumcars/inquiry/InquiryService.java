package com.premiumcars.inquiry;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.premiumcars.catalog.domain.Car;
import com.premiumcars.catalog.repo.CarRepository;
import com.premiumcars.common.BadRequestException;
import com.premiumcars.common.NotFoundException;
import com.premiumcars.common.PageResponse;
import com.premiumcars.inquiry.InquiryDtos.AdminInquiry;
import com.premiumcars.inquiry.InquiryDtos.InquiryCreated;
import com.premiumcars.inquiry.InquiryDtos.InquiryReport;
import com.premiumcars.inquiry.InquiryDtos.InquiryRequest;
import com.premiumcars.order.OrderDtos.AdminOrder;
import com.premiumcars.order.OrderRepository;
import com.premiumcars.order.OrderRepository.InquiryOrder;
import com.premiumcars.order.OrderService;

@Service
public class InquiryService {

    private static final Logger log = LoggerFactory.getLogger(InquiryService.class);

    private final InquiryRepository repo;
    private final CarRepository carRepo;
    private static final ZoneId SOFIA = ZoneId.of("Europe/Sofia");
    /** The longest period one report covers. */
    static final int MAX_REPORT_DAYS = 366;

    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final OrderService orderService;
    private final OrderRepository orders;

    public InquiryService(InquiryRepository repo, CarRepository carRepo, ApplicationEventPublisher events,
                          Clock clock, OrderService orderService, OrderRepository orders) {
        this.repo = repo;
        this.carRepo = carRepo;
        this.events = events;
        this.clock = clock;
        this.orderService = orderService;
        this.orders = orders;
    }

    @Transactional
    public InquiryCreated create(InquiryRequest r) {
        if (r.website() != null && !r.website().isBlank()) {
            log.info("Dropped inquiry caught by honeypot");
            return new InquiryCreated(null);
        }
        if (isBlank(r.phone()) && isBlank(r.email())) {
            throw new BadRequestException("Please enter a phone number or an e-mail");
        }
        Car car = null;
        if (r.carId() != null) {
            car = carRepo.findById(r.carId())
                    .filter(Car::isPubliclyVisible)
                    .orElseThrow(() -> new NotFoundException("Car not found: " + r.carId()));
        }
        Attribution attribution = r.attribution() == null ? Attribution.NONE : r.attribution();
        String name = r.name() == null ? "" : r.name().trim();
        long id = repo.insert(r.carId(), name, trimToNull(r.phone()), trimToNull(r.email()),
                trimToNull(r.message()), r.heardFrom(), attribution);
        log.info("New inquiry #{} for car {} (heard from {}, utm_source {})", id, r.carId(), r.heardFrom(),
                attribution.utmSource());
        // InquiryNotifier e-mails the admin after the commit, outside this transaction
        events.publishEvent(new InquiryCreatedEvent(id, name, trimToNull(r.phone()),
                trimToNull(r.email()), trimToNull(r.message()), car == null ? null : car.specs().title(),
                car == null ? null : car.slug(), r.heardFrom(), attribution));
        return new InquiryCreated(id);
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminInquiry> list(Inquiry.Status status, int page, int size) {
        int p = Math.max(page, 0);
        int s = Math.min(Math.max(size, 1), 100);
        List<Inquiry> rows = repo.list(status, s, p * s);
        Map<Long, InquiryOrder> made = orders.findByInquiries(rows.stream().map(Inquiry::id).toList());
        return PageResponse.of(rows, p, s, repo.count(status), i -> toDto(i, made.get(i.id())));
    }

    @Transactional
    public AdminInquiry changeStatus(long id, Inquiry.Status status) {
        return changeStatus(id, status, null);
    }

    /** "Свързахме се" (CONTACTED) also makes the inquiry's potential order, unless it has one. */
    @Transactional
    public AdminInquiry changeStatus(long id, Inquiry.Status status, String admin) {
        if (!repo.updateStatus(id, status)) {
            throw new NotFoundException("Inquiry not found: " + id);
        }
        if (status == Inquiry.Status.CONTACTED) {
            orderService.createFromInquiry(id, admin);
        }
        return find(id);
    }

    /** The inquiry's order: the one made from it before, else a new potential order. */
    @Transactional
    public AdminOrder order(long id, String admin) {
        return orderService.createFromInquiry(id, admin);
    }

    private AdminInquiry find(long id) {
        Inquiry i = repo.findById(id).orElseThrow(() -> new NotFoundException("Inquiry not found: " + id));
        return toDto(i, orders.findByInquiries(List.of(id)).get(id));
    }

    /**
     * Inquiries from {@code from} to {@code to} (both inclusive, Sofia days). Defaults: the last 30 days up to today.
     */
    @Transactional(readOnly = true)
    public InquiryReport report(LocalDate from, LocalDate to) {
        LocalDate end = to != null ? to : LocalDate.now(clock.withZone(SOFIA));
        LocalDate start = from != null ? from : end.minusDays(29);
        if (start.isAfter(end)) {
            throw new BadRequestException("\"from\" must not be after \"to\"");
        }
        if (start.plusDays(MAX_REPORT_DAYS).isBefore(end)) {
            throw new BadRequestException("A report covers at most " + MAX_REPORT_DAYS + " days");
        }
        return new InquiryReport(start, end, repo.countBetween(start, end), repo.countByStatus(start, end),
                repo.countByHeardFrom(start, end), repo.countByUtmSource(start, end),
                repo.countByUtmCampaign(start, end), repo.countByCar(start, end, 20), repo.countByWeek(start, end),
                repo.funnelByUtmSource(start, end), repo.funnelByUtmCampaign(start, end),
                repo.funnelByHeardFrom(start, end), repo.countLostReasons(start, end));
    }

    /** With its order and the same phone's other inquiries and orders (a known customer). */
    private AdminInquiry toDto(Inquiry i, InquiryOrder order) {
        String key = OrderRepository.phoneKey(i.phone());
        List<Long> otherInquiries = repo.findIdsByPhoneKey(key, i.id(), 10);
        List<Long> otherOrders = orders.findIdsByPhone(i.phone(), order == null ? null : order.id(), 10);
        return new AdminInquiry(i.id(), i.carId(), i.carTitle(), i.carSlug(), i.name(), i.phone(), i.email(),
                i.message(), i.status(), i.createdAt(), i.heardFrom(), i.attribution(),
                order == null ? null : order.id(), order == null ? null : order.status().name(), otherInquiries,
                otherOrders);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String trimToNull(String s) {
        return isBlank(s) ? null : s.trim();
    }
}
