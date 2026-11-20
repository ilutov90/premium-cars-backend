package com.premiumcars.order;

import java.security.Principal;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.premiumcars.common.PageResponse;
import com.premiumcars.order.OrderDtos.AdminOrder;
import com.premiumcars.order.OrderDtos.CarLookup;
import com.premiumcars.order.OrderDtos.OrderRequest;
import com.premiumcars.order.OrderDtos.OrderSummary;

import jakarta.validation.Valid;

/** "Поръчки": the customers' orders of sold cars (admin only). */
@RestController
@RequestMapping("/api/admin/orders")
public class AdminOrderController {

    private final OrderService service;

    public AdminOrderController(OrderService service) {
        this.service = service;
    }

    /** Newest first; {@code q}: customer, company, car title, the car's number or link, VIN, container. */
    @GetMapping
    public PageResponse<OrderSummary> list(@RequestParam(required = false) OrderStatus status,
                                           @RequestParam(required = false) String q,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "20") int size) {
        return service.list(status, q, page, size);
    }

    /** The car for an order, by its number ("Обява № {id}") or a link to its page on our site; 404 when none. */
    @GetMapping("/car-lookup")
    public CarLookup carLookup(@RequestParam String q) {
        return service.lookupCar(q);
    }

    @GetMapping("/{id}")
    public AdminOrder get(@PathVariable long id) {
        return service.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AdminOrder create(@Valid @RequestBody OrderRequest request, Principal principal) {
        return service.create(request, principal == null ? null : principal.getName());
    }

    @PutMapping("/{id}")
    public AdminOrder update(@PathVariable long id, @Valid @RequestBody OrderRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        service.delete(id);
    }
}
