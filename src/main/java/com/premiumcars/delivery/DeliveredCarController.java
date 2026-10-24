package com.premiumcars.delivery;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.premiumcars.delivery.DeliveredCarDtos.AdminDeliveredCar;
import com.premiumcars.delivery.DeliveredCarDtos.DeliveredCarRequest;
import com.premiumcars.delivery.DeliveredCarDtos.PublicDeliveredCar;

import jakarta.validation.Valid;

/** "Доставени коли": the published ones for the site, all of them (with consent) for the admin panel. */
@RestController
public class DeliveredCarController {

    private final DeliveredCarService service;

    public DeliveredCarController(DeliveredCarService service) {
        this.service = service;
    }

    @GetMapping("/api/public/delivered-cars")
    public List<PublicDeliveredCar> listPublic(@RequestParam(defaultValue = "12") int limit) {
        return service.listPublic(limit);
    }

    @GetMapping("/api/admin/delivered-cars")
    public List<AdminDeliveredCar> list() {
        return service.listAdmin();
    }

    @PostMapping("/api/admin/delivered-cars")
    @ResponseStatus(HttpStatus.CREATED)
    public AdminDeliveredCar create(@Valid @RequestBody DeliveredCarRequest request) {
        return service.create(request);
    }

    @PutMapping("/api/admin/delivered-cars/{id}")
    public AdminDeliveredCar update(@PathVariable long id, @Valid @RequestBody DeliveredCarRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/api/admin/delivered-cars/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        service.delete(id);
    }

    /** The photo as the request body (Content-Type image/jpeg, image/png or image/webp); replaces the old one. */
    @PutMapping(value = "/api/admin/delivered-cars/{id}/photo",
            consumes = {MediaType.IMAGE_JPEG_VALUE, MediaType.IMAGE_PNG_VALUE, "image/webp",
                    MediaType.APPLICATION_OCTET_STREAM_VALUE})
    public AdminDeliveredCar setPhoto(@PathVariable long id, @RequestBody byte[] photo) {
        return service.setPhoto(id, photo);
    }

    @DeleteMapping("/api/admin/delivered-cars/{id}/photo")
    public AdminDeliveredCar deletePhoto(@PathVariable long id) {
        return service.deletePhoto(id);
    }
}
