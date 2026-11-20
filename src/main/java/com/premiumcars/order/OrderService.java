package com.premiumcars.order;

import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.premiumcars.catalog.domain.Car;
import com.premiumcars.catalog.domain.CarImage;
import com.premiumcars.catalog.domain.CarStatus;
import com.premiumcars.catalog.repo.CarImageRepository;
import com.premiumcars.catalog.repo.CarRepository;
import com.premiumcars.catalog.service.CarMapper;
import com.premiumcars.common.BadRequestException;
import com.premiumcars.common.NotFoundException;
import com.premiumcars.common.PageResponse;
import com.premiumcars.order.OrderDtos.AdminOrder;
import com.premiumcars.order.OrderDtos.CarLookup;
import com.premiumcars.order.OrderDtos.DocumentView;
import com.premiumcars.order.OrderDtos.OrderRequest;
import com.premiumcars.order.OrderDtos.OrderSummary;
import com.premiumcars.order.OrderRepository.OrderRow;

/**
 * Customer orders ("Поръчки") for sold cars. A car has at most one order that is not cancelled. An order made for a
 * car (or given another one) keeps a snapshot of it and marks it SOLD; the car's linked photos are then copied into
 * our storage after the commit ({@link OrderPhotoKeeper}). Cancelling an order leaves the car's status to the admin.
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    static final int MAX_PAGE_SIZE = 100;

    private final OrderRepository orders;
    private final OrderDocumentRepository documents;
    private final OrderDocumentStorage documentStorage;
    private final CarRepository cars;
    private final CarImageRepository images;
    private final CarMapper carMapper;
    private final ApplicationEventPublisher events;

    public OrderService(OrderRepository orders, OrderDocumentRepository documents,
                        OrderDocumentStorage documentStorage, CarRepository cars, CarImageRepository images,
                        CarMapper carMapper, ApplicationEventPublisher events) {
        this.orders = orders;
        this.documents = documents;
        this.documentStorage = documentStorage;
        this.cars = cars;
        this.images = images;
        this.carMapper = carMapper;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public PageResponse<OrderSummary> list(OrderStatus status, String q, int page, int size) {
        int p = Math.max(page, 0);
        int s = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        List<OrderRow> rows = orders.search(status, q, p * s, s);
        return PageResponse.of(rows, p, s, orders.count(status, q), this::toSummary);
    }

    @Transactional(readOnly = true)
    public AdminOrder get(long id) {
        return toAdmin(find(id));
    }

    @Transactional
    public AdminOrder create(OrderRequest r, String admin) {
        if (r.carId() == null) {
            throw new BadRequestException("Choose the car of the order");
        }
        OrderFields fields = fields(r);
        Car car = car(r.carId());
        checkOneActive(car.id(), null, fields.status());
        long id;
        try {
            id = orders.insert(car.id(), CarSnapshot.of(car), customer(r), fields, admin);
        } catch (DuplicateKeyException e) {
            throw activeOrderExists(car.id(), null);
        }
        assigned(id, car, fields.status());
        log.info("Order {} for car {} created by {}", id, car.id(), admin);
        return get(id);
    }

    /**
     * Replaces the order's values. Another car takes a new snapshot and is marked SOLD (so is the car of a cancelled
     * order made active again); the same car keeps its snapshot; no car leaves the order with its snapshot.
     */
    @Transactional
    public AdminOrder update(long id, OrderRequest r) {
        CustomerOrder existing = find(id).order();
        OrderFields fields = fields(r);
        Long carId = r.carId();
        CarSnapshot snapshot = existing.car();
        Car newCar = null;
        if (carId != null && !carId.equals(existing.carId())) {
            newCar = car(carId);
            snapshot = CarSnapshot.of(newCar);
        }
        if (carId != null) {
            checkOneActive(carId, id, fields.status());
        }
        try {
            orders.update(id, carId, snapshot, customer(r), fields);
        } catch (DuplicateKeyException e) {
            throw activeOrderExists(Objects.requireNonNull(carId), null);
        }
        if (newCar == null && carId != null && existing.fields().status() == OrderStatus.CANCELLED) {
            // a cancelled order made active again takes its car again
            newCar = cars.findById(carId).orElse(null);
        }
        if (newCar != null) {
            assigned(id, newCar, fields.status());
        }
        return get(id);
    }

    /** The order and its documents' rows; then the documents' stored files. The car stays as it is. */
    @Transactional
    public void delete(long id) {
        find(id);
        List<OrderDocument> docs = documents.findByOrder(id);
        orders.delete(id);
        for (OrderDocument d : docs) {
            if (d.storageKey() == null) {
                continue;
            }
            try {
                documentStorage.delete(d.storageKey());
            } catch (RuntimeException e) {
                log.warn("Could not delete the file of document {} of order {}: {}", d.id(), id, e.toString());
            }
        }
    }

    /** The car an admin means by its number ("Обява № {id}", "#29538") or a link to its page on our site. */
    @Transactional(readOnly = true)
    public CarLookup lookupCar(String q) {
        Car car = cars.findByNumberOrLink(q == null ? null : q.strip())
                .orElseThrow(() -> new NotFoundException("No car for: " + q));
        List<CarImage> photos = images.findByCarId(car.id());
        return new CarLookup(car.id(), car.specs().title(), car.slug(), car.status(),
                photos.isEmpty() ? null : carMapper.url(photos.getFirst()), car.totalPriceEur(),
                orders.findActiveOrderOf(car.id(), null).orElse(null));
    }

    // ---------------------------------------------------------------- helpers

    private void assigned(long orderId, Car car, OrderStatus status) {
        if (status == OrderStatus.CANCELLED) {
            return;
        }
        if (car.status() != CarStatus.SOLD) {
            cars.updateStatus(car.id(), CarStatus.SOLD);
        }
        events.publishEvent(new OrderCarAssigned(orderId, car.id()));
    }

    private void checkOneActive(long carId, Long orderId, OrderStatus status) {
        if (status == OrderStatus.CANCELLED) {
            return;
        }
        Long other = orders.findActiveOrderOf(carId, orderId).orElse(null);
        if (other != null) {
            throw activeOrderExists(carId, other);
        }
    }

    /** @param other the car's other order, when known (not after a failed statement: the transaction is over) */
    private static BadRequestException activeOrderExists(long carId, Long other) {
        return new BadRequestException("Car " + carId + " already has an order that is not cancelled"
                + (other == null ? "" : " (order " + other + ")"));
    }

    private Car car(long carId) {
        return cars.findById(carId).orElseThrow(() -> new BadRequestException("Car not found: " + carId));
    }

    private OrderRow find(long id) {
        return orders.findById(id).orElseThrow(() -> new NotFoundException("Order not found: " + id));
    }

    private static Customer customer(OrderRequest r) {
        return r.customer() == null ? Customer.EMPTY : r.customer().normalized();
    }

    static OrderFields fields(OrderRequest r) {
        String vin = OrderText.clean(r.vin());
        return new OrderFields(vin == null ? null : vin.toUpperCase(java.util.Locale.ROOT), r.agreedPriceEur(),
                r.depositEur(), r.status() == null ? OrderStatus.CONTRACT_SIGNED : r.status(),
                OrderText.clean(r.containerNumber()), r.contractDate(), OrderText.clean(r.notes()));
    }

    private String coverUrl(OrderRow row) {
        if (row.coverPath() == null && row.coverSourceUrl() == null) {
            return null;
        }
        long carId = row.order().carId() == null ? 0 : row.order().carId();
        return carMapper.url(new CarImage(0, carId, row.coverPath(), row.coverSourceUrl(), 0));
    }

    private OrderSummary toSummary(OrderRow row) {
        CustomerOrder o = row.order();
        OrderFields f = o.fields();
        return new OrderSummary(o.id(), o.carId(), o.car(), row.carStatus(), coverUrl(row), o.customer(), f.vin(),
                f.agreedPriceEur(), f.depositEur(), f.status(), f.containerNumber(), f.contractDate(),
                row.documentCount(), o.createdAt(), o.updatedAt());
    }

    private AdminOrder toAdmin(OrderRow row) {
        CustomerOrder o = row.order();
        OrderFields f = o.fields();
        return new AdminOrder(o.id(), o.carId(), o.car(), row.carStatus(), coverUrl(row), o.customer(), f.vin(),
                f.agreedPriceEur(), f.depositEur(), f.status(), f.containerNumber(), f.contractDate(), f.notes(),
                o.createdAt(), o.updatedAt(), o.createdBy(),
                documents.findByOrder(o.id()).stream().map(DocumentView::of).toList());
    }
}
