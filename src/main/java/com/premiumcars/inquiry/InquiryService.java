package com.premiumcars.inquiry;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

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

    public InquiryService(InquiryRepository repo, CarRepository carRepo, ApplicationEventPublisher events,
                          Clock clock) {
        this.repo = repo;
        this.carRepo = carRepo;
        this.events = events;
        this.clock = clock;
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
        return PageResponse.of(repo.list(status, s, p * s), p, s, repo.count(status), InquiryService::toDto);
    }

    @Transactional
    public AdminInquiry changeStatus(long id, Inquiry.Status status) {
        if (!repo.updateStatus(id, status)) {
            throw new NotFoundException("Inquiry not found: " + id);
        }
        return repo.findById(id).map(InquiryService::toDto).orElseThrow();
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
                repo.countByUtmCampaign(start, end), repo.countByCar(start, end, 20), repo.countByWeek(start, end));
    }

    private static AdminInquiry toDto(Inquiry i) {
        return new AdminInquiry(i.id(), i.carId(), i.carTitle(), i.carSlug(), i.name(), i.phone(), i.email(),
                i.message(), i.status(), i.createdAt(), i.heardFrom(), i.attribution());
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String trimToNull(String s) {
        return isBlank(s) ? null : s.trim();
    }
}
