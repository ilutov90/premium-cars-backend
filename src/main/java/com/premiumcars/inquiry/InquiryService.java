package com.premiumcars.inquiry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.premiumcars.catalog.domain.Car;
import com.premiumcars.catalog.repo.CarRepository;
import com.premiumcars.common.BadRequestException;
import com.premiumcars.common.NotFoundException;
import com.premiumcars.common.PageResponse;
import com.premiumcars.inquiry.InquiryDtos.AdminInquiry;
import com.premiumcars.inquiry.InquiryDtos.InquiryCreated;
import com.premiumcars.inquiry.InquiryDtos.InquiryRequest;

@Service
public class InquiryService {

    private static final Logger log = LoggerFactory.getLogger(InquiryService.class);

    private final InquiryRepository repo;
    private final CarRepository carRepo;

    public InquiryService(InquiryRepository repo, CarRepository carRepo) {
        this.repo = repo;
        this.carRepo = carRepo;
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
        if (r.carId() != null) {
            carRepo.findById(r.carId())
                    .filter(Car::isPubliclyVisible)
                    .orElseThrow(() -> new NotFoundException("Car not found: " + r.carId()));
        }
        long id = repo.insert(r.carId(), r.name().trim(), trimToNull(r.phone()), trimToNull(r.email()),
                trimToNull(r.message()));
        // Extension point: send an e-mail / Viber / Telegram notification here.
        log.info("New inquiry #{} for car {}", id, r.carId());
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

    private static AdminInquiry toDto(Inquiry i) {
        return new AdminInquiry(i.id(), i.carId(), i.carTitle(), i.carSlug(), i.name(), i.phone(), i.email(),
                i.message(), i.status(), i.createdAt());
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String trimToNull(String s) {
        return isBlank(s) ? null : s.trim();
    }
}
