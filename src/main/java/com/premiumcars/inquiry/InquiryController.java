package com.premiumcars.inquiry;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.premiumcars.common.PageResponse;
import com.premiumcars.inquiry.InquiryDtos.AdminInquiry;
import com.premiumcars.inquiry.InquiryDtos.InquiryCreated;
import com.premiumcars.inquiry.InquiryDtos.InquiryReport;
import com.premiumcars.inquiry.InquiryDtos.InquiryRequest;
import com.premiumcars.inquiry.InquiryDtos.InquiryStatusRequest;

import jakarta.validation.Valid;

@RestController
public class InquiryController {

    private final InquiryService service;

    public InquiryController(InquiryService service) {
        this.service = service;
    }

    @PostMapping("/api/public/inquiries")
    @ResponseStatus(HttpStatus.CREATED)
    public InquiryCreated create(@Valid @RequestBody InquiryRequest request) {
        return service.create(request);
    }

    @GetMapping("/api/admin/inquiries")
    public PageResponse<AdminInquiry> list(@RequestParam(required = false) Inquiry.Status status,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "50") int size) {
        return service.list(status, page, size);
    }

    @PostMapping("/api/admin/inquiries/{id}/status")
    public AdminInquiry changeStatus(@PathVariable long id, @Valid @RequestBody InquiryStatusRequest request) {
        return service.changeStatus(id, request.status());
    }

    /** The admin panel's report ("Отчет"): inquiries per channel, campaign, car and week. Dates: yyyy-MM-dd. */
    @GetMapping("/api/admin/reports/inquiries")
    public InquiryReport report(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.report(from, to);
    }
}
