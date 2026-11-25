package com.premiumcars.order;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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
import com.premiumcars.inquiry.Inquiry;
import com.premiumcars.inquiry.InquiryRepository;
import com.premiumcars.media.ImageStorage;
import com.premiumcars.order.OrderDtos.AdminOrder;
import com.premiumcars.order.OrderDtos.CarLookup;
import com.premiumcars.order.OrderDtos.CandidateView;
import com.premiumcars.order.OrderDtos.ContactRequest;
import com.premiumcars.order.OrderDtos.ContactView;
import com.premiumcars.order.OrderDtos.DocumentView;
import com.premiumcars.order.OrderDtos.InquiryInfo;
import com.premiumcars.order.OrderDtos.OrderRequest;
import com.premiumcars.order.OrderDtos.OrderSummary;
import com.premiumcars.order.OrderRepository.OrderRow;

/**
 * Customer orders ("Поръчки"). A potential order (LEAD, made by hand or from an inquiry marked "Свързахме се") holds
 * no car: the cars offered to the customer are its candidates, which stay on the site. An agreed order (any status
 * but LEAD and CANCELLED, {@link OrderStatus#holdsCar}) has its car: a car has at most one such order; the car gets a
 * snapshot in the order and is marked SOLD, and its linked photos are copied into our storage after the commit
 * ({@link OrderPhotoKeeper}). Cancelling an order leaves the car's status to the admin. An order agreed or cancelled
 * closes the inquiry it came from.
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    static final int MAX_PAGE_SIZE = 100;
    private static final ZoneId SOFIA = ZoneId.of("Europe/Sofia");
    /** How many other orders / inquiries of the same phone an order or inquiry lists. */
    static final int RELATED_LIMIT = 10;

    private final OrderRepository orders;
    private final OrderDocumentRepository documents;
    private final OrderDocumentStorage documentStorage;
    private final CarRepository cars;
    private final CarImageRepository images;
    private final CarMapper carMapper;
    private final ApplicationEventPublisher events;
    private final ImageStorage photoStorage;
    private final OrderCandidateRepository candidates;
    private final OrderContactRepository contacts;
    private final InquiryRepository inquiries;
    private final Clock clock;

    public OrderService(OrderRepository orders, OrderDocumentRepository documents,
                        OrderDocumentStorage documentStorage, CarRepository cars, CarImageRepository images,
                        CarMapper carMapper, ApplicationEventPublisher events, ImageStorage photoStorage,
                        OrderCandidateRepository candidates, OrderContactRepository contacts,
                        InquiryRepository inquiries, Clock clock) {
        this.photoStorage = photoStorage;
        this.candidates = candidates;
        this.contacts = contacts;
        this.inquiries = inquiries;
        this.clock = clock;
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
        return list(status, q, false, page, size);
    }

    /** @param followUp only the orders to contact today or earlier (not delivered or cancelled), earliest first */
    @Transactional(readOnly = true)
    public PageResponse<OrderSummary> list(OrderStatus status, String q, boolean followUp, int page, int size) {
        int p = Math.max(page, 0);
        int s = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        Instant until = followUp ? endOfToday() : null;
        List<OrderRow> rows = orders.search(status, q, until, p * s, s);
        return PageResponse.of(rows, p, s, orders.count(status, q, until), this::toSummary);
    }

    /** How many orders to contact today or earlier (the admin menu's badge). */
    @Transactional(readOnly = true)
    public long followUpCount() {
        return orders.countFollowUps(endOfToday());
    }

    /** The last instant of today in Sofia. */
    Instant endOfToday() {
        LocalDate today = LocalDate.now(clock.withZone(SOFIA));
        return today.plusDays(1).atStartOfDay(SOFIA).toInstant().minusMillis(1);
    }

    @Transactional(readOnly = true)
    public AdminOrder get(long id) {
        return toAdmin(find(id));
    }

    @Transactional
    public AdminOrder create(OrderRequest r, String admin) {
        OrderFields fields = fields(r);
        if (fields.status() == OrderStatus.LEAD) {
            long id = orders.insert(null, null, customer(r), fields, admin);
            if (r.carId() != null) {
                addCandidate(id, car(r.carId()), admin);
            }
            log.info("Potential order {} created by {}", id, admin);
            return get(id);
        }
        if (r.carId() == null) {
            throw new BadRequestException("Choose the car of the order");
        }
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
     * The order of an inquiry: the one made from it before, else a new potential order with the customer's name,
     * phone and e-mail, the inquiry's car as the first offered car (when it is still in the catalog) and the
     * customer's message as the first entry of the contacts.
     */
    @Transactional
    public AdminOrder createFromInquiry(long inquiryId, String admin) {
        Long existing = orders.findByInquiry(inquiryId).orElse(null);
        if (existing != null) {
            return get(existing);
        }
        Inquiry inquiry = inquiries.findById(inquiryId)
                .orElseThrow(() -> new NotFoundException("Inquiry not found: " + inquiryId));
        Customer customer = new Customer(inquiry.name(), inquiry.phone(), inquiry.email(), null, null, null, null)
                .normalized();
        OrderFields fields = new OrderFields(null, null, null, OrderStatus.LEAD, null, null, null, null, null);
        long id = orders.insert(null, null, customer, fields, admin, inquiryId);
        if (inquiry.carId() != null) {
            cars.findById(inquiry.carId()).ifPresent(car -> addCandidate(id, car, admin));
        }
        String message = OrderText.clean(inquiry.message());
        contacts.insert(id, ContactKind.INQUIRY, message == null ? "(без съобщение)" : message,
                inquiry.createdAt() == null ? clock.instant() : inquiry.createdAt(), null);
        log.info("Potential order {} made from inquiry {} by {}", id, inquiryId, admin);
        return get(id);
    }

    /**
     * Replaces the order's values. Another car takes a new snapshot and is marked SOLD (so is the car of a cancelled
     * order made active again); the same car keeps its snapshot; no car leaves the order with its snapshot.
     */
    @Transactional
    public AdminOrder update(long id, OrderRequest r) {
        return update(id, r, null);
    }

    /**
     * @param admin who changes it (noted on an offered car added because the order became potential with its car)
     */
    @Transactional
    public AdminOrder update(long id, OrderRequest r, String admin) {
        CustomerOrder existing = find(id).order();
        OrderFields fields = fields(r);
        Long carId = r.carId();
        CarSnapshot snapshot = existing.car();
        if (fields.status() == OrderStatus.LEAD && carId != null) {
            // a potential order holds no car: its car becomes an offered one (the car's status stays as it is)
            cars.findById(carId).ifPresent(car -> addCandidate(id, car, admin));
            carId = null;
            snapshot = null;
        }
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
        if (newCar == null && carId != null && !existing.fields().status().holdsCar()) {
            // a cancelled or potential order agreed now takes its car
            newCar = cars.findById(carId).orElse(null);
        }
        if (newCar != null) {
            assigned(id, newCar, fields.status());
        }
        closeInquiry(existing.inquiryId(), fields.status());
        return get(id);
    }

    // ---------------------------------------------------------------- offered cars

    /** Offers the customer the car with this number or site link. */
    @Transactional
    public AdminOrder addCandidate(long orderId, String q, String admin) {
        find(orderId);
        Car car = findCar(q);
        if (!addCandidate(orderId, car, admin)) {
            throw new BadRequestException("This car is already offered to the customer");
        }
        return get(orderId);
    }

    /** @return false when the order already has this car */
    private boolean addCandidate(long orderId, Car car, String admin) {
        List<CarImage> photos = images.findByCarId(car.id());
        return candidates.insert(orderId, car.id(), car.specs().title(), car.slug(), car.totalPriceEur(),
                photos.isEmpty() ? null : carMapper.url(photos.getFirst()), admin).isPresent();
    }

    @Transactional
    public AdminOrder removeCandidate(long orderId, long candidateId) {
        if (!candidates.delete(orderId, candidateId)) {
            throw new NotFoundException("Offered car not found: " + candidateId);
        }
        return get(orderId);
    }

    /**
     * The customer chose this offered car: it becomes the order's car with a new snapshot, the order is agreed
     * ({@code status}, null = CONTRACT_SIGNED) and the car is marked SOLD. The other offered cars stay listed.
     */
    @Transactional
    public AdminOrder chooseCandidate(long orderId, long candidateId, OrderStatus status) {
        OrderStatus newStatus = status == null ? OrderStatus.CONTRACT_SIGNED : status;
        if (!newStatus.holdsCar()) {
            throw new BadRequestException("Choosing a car agrees the order: pick a status after the contract");
        }
        CustomerOrder order = find(orderId).order();
        OrderCandidateRepository.Candidate candidate = candidates.find(orderId, candidateId)
                .orElseThrow(() -> new NotFoundException("Offered car not found: " + candidateId));
        if (candidate.carId() == null) {
            throw new BadRequestException("This car is no longer in the catalog");
        }
        Car car = car(candidate.carId());
        checkOneActive(car.id(), orderId, newStatus);
        try {
            orders.update(orderId, car.id(), CarSnapshot.of(car), order.customer(),
                    order.fields().withStatus(newStatus));
        } catch (DuplicateKeyException e) {
            throw activeOrderExists(car.id(), null);
        }
        assigned(orderId, car, newStatus);
        closeInquiry(order.inquiryId(), newStatus);
        log.info("Order {}: the customer chose car {}", orderId, car.id());
        return get(orderId);
    }

    /**
     * The customer changed their mind after the contract: the agreed order takes another car (its number or site
     * link), which is marked SOLD and whose snapshot and photos the order keeps from now on. The previous car stays
     * among the offered cars for the history and, with {@code republishOld}, goes back on the site when it is still
     * in the catalog and marked SOLD. The status, the customer, the prices and the documents stay as they are; a
     * "Комуникация" note records the change.
     */
    @Transactional
    public AdminOrder changeCar(long orderId, String q, boolean republishOld, String admin) {
        CustomerOrder order = find(orderId).order();
        OrderStatus status = order.fields().status();
        if (!status.holdsCar()) {
            throw new BadRequestException("Only an agreed order changes its car: a potential order chooses one of"
                    + " the offered cars");
        }
        Car newCar = findCar(q);
        if (order.carId() != null && newCar.id() == order.carId()) {
            throw new BadRequestException("This is already the order's car");
        }
        checkOneActive(newCar.id(), orderId, status);
        Car oldCar = order.carId() == null ? null : cars.findById(order.carId()).orElse(null);
        try {
            orders.update(orderId, newCar.id(), CarSnapshot.of(newCar), order.customer(), order.fields());
        } catch (DuplicateKeyException e) {
            throw activeOrderExists(newCar.id(), null);
        }
        boolean republished = false;
        if (oldCar != null) {
            addCandidate(orderId, oldCar, admin);
            if (republishOld && oldCar.status() == CarStatus.SOLD) {
                cars.updateStatus(oldCar.id(), CarStatus.PUBLISHED);
                republished = true;
            }
        }
        assigned(orderId, newCar, status);
        contacts.insert(orderId, ContactKind.NOTE, changeNote(order.car().title(), order.carId(),
                newCar.specs().title(), newCar.id(), republished), clock.instant(), admin);
        log.info("Order {}: car {} changed to {}{}", orderId, order.carId(), newCar.id(),
                republished ? " (the previous one back on the site)" : "");
        return get(orderId);
    }

    /** The "Комуникация" note of a changed car (the admins' language). */
    static String changeNote(String oldTitle, Long oldId, String newTitle, long newId, boolean republished) {
        String from = (oldTitle == null ? "кола" : oldTitle) + (oldId == null ? "" : " (Обява № " + oldId + ")");
        return "Колата е сменена: " + from + " → " + (newTitle == null ? "кола" : newTitle) + " (Обява № " + newId
                + ")" + (republished ? ". Предишната е върната в сайта." : ".");
    }

    // ---------------------------------------------------------------- contacts

    @Transactional
    public AdminOrder addContact(long orderId, ContactRequest r, String admin) {
        find(orderId);
        contacts.insert(orderId, r.kind(), r.body().strip(), r.happenedAt() == null ? clock.instant()
                : r.happenedAt(), admin);
        return get(orderId);
    }

    @Transactional
    public AdminOrder updateContact(long orderId, long contactId, ContactRequest r) {
        if (!contacts.update(orderId, contactId, r.kind(), r.body().strip(),
                r.happenedAt() == null ? clock.instant() : r.happenedAt())) {
            throw new NotFoundException("Contact entry not found: " + contactId);
        }
        return get(orderId);
    }

    @Transactional
    public AdminOrder removeContact(long orderId, long contactId) {
        if (!contacts.delete(orderId, contactId)) {
            throw new NotFoundException("Contact entry not found: " + contactId);
        }
        return get(orderId);
    }

    private void closeInquiry(Long inquiryId, OrderStatus status) {
        if (inquiryId != null && status.closesInquiry()) {
            inquiries.updateStatus(inquiryId, Inquiry.Status.CLOSED);
        }
    }

    /**
     * The order and its documents' rows; then the documents' stored files and, when the car is already gone, the
     * photo files the order kept (not those another order of that car keeps too, or a car still shows). The car
     * stays as it is. Not one transaction: the files are removed over the network after the rows are.
     */
    public void delete(long id) {
        CustomerOrder order = find(id).order();
        List<OrderDocument> docs = documents.findByOrder(id);
        orders.delete(id);
        if (order.carId() == null) {
            for (String path : order.carPhotos()) {
                if (orders.photoKeptByOtherOrder(path, id) || images.existsByStoragePath(path)) {
                    continue;
                }
                try {
                    photoStorage.delete(path);
                } catch (RuntimeException e) {
                    log.warn("Could not delete the kept photo {} of order {}: {}", path, id, e.toString());
                }
            }
        }
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
        Car car = findCar(q);
        List<CarImage> photos = images.findByCarId(car.id());
        return new CarLookup(car.id(), car.specs().title(), car.slug(), car.status(),
                photos.isEmpty() ? null : carMapper.url(photos.getFirst()), car.totalPriceEur(),
                orders.findActiveOrderOf(car.id(), null).orElse(null));
    }

    // ---------------------------------------------------------------- helpers

    /** The car by its number as the car page shows it ("Обява № 29538", "№ 29538", "29538") or a link to it. */
    private Car findCar(String q) {
        String text = q == null ? null : q.strip().replaceFirst("(?iu)^обява\\s*", "");
        return cars.findByNumberOrLink(text).orElseThrow(() -> new NotFoundException("No car for: " + q));
    }

    private void assigned(long orderId, Car car, OrderStatus status) {
        if (!status.holdsCar()) {
            return;
        }
        if (car.status() != CarStatus.SOLD) {
            cars.updateStatus(car.id(), CarStatus.SOLD);
        }
        events.publishEvent(new OrderCarAssigned(orderId, car.id()));
    }

    private void checkOneActive(long carId, Long orderId, OrderStatus status) {
        if (!status.holdsCar()) {
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
        OrderStatus status = r.status() == null ? OrderStatus.CONTRACT_SIGNED : r.status();
        return new OrderFields(vin == null ? null : vin.toUpperCase(java.util.Locale.ROOT), r.agreedPriceEur(),
                r.depositEur(), status, OrderText.clean(r.containerNumber()), r.contractDate(),
                OrderText.clean(r.notes()), r.nextContactAt(),
                status == OrderStatus.CANCELLED ? r.lostReason() : null);
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
                row.documentCount(), o.createdAt(), o.updatedAt(), f.nextContactAt(), f.lostReason(),
                row.candidateCount(), o.inquiryId());
    }

    private AdminOrder toAdmin(OrderRow row) {
        CustomerOrder o = row.order();
        OrderFields f = o.fields();
        return new AdminOrder(o.id(), o.carId(), o.car(), row.carStatus(), coverUrl(row), o.customer(), f.vin(),
                f.agreedPriceEur(), f.depositEur(), f.status(), f.containerNumber(), f.contractDate(), f.notes(),
                o.createdAt(), o.updatedAt(), o.createdBy(),
                documents.findByOrder(o.id()).stream().map(DocumentView::of).toList(), carPhotos(o),
                f.nextContactAt(), f.lostReason(), inquiryInfo(o.inquiryId()),
                candidates.findByOrder(o.id()).stream().map(CandidateView::of).toList(),
                contacts.findByOrder(o.id()).stream().map(ContactView::of).toList(),
                orders.findIdsByPhone(o.customer().phone(), o.id(), RELATED_LIMIT),
                inquiries.findIdsByPhoneKey(OrderRepository.phoneKey(o.customer().phone()),
                        o.inquiryId() == null ? -1L : o.inquiryId(), RELATED_LIMIT));
    }

    private InquiryInfo inquiryInfo(Long inquiryId) {
        if (inquiryId == null) {
            return null;
        }
        return inquiries.findById(inquiryId)
                .map(i -> new InquiryInfo(i.id(), i.createdAt(), i.message(), i.carId(), i.carTitle(), i.carSlug(),
                        i.status().name(), i.heardFrom(), i.attribution()))
                .orElse(null);
    }

    private List<String> carPhotos(CustomerOrder o) {
        if (o.carId() != null) {
            return images.findByCarId(o.carId()).stream().map(carMapper::url).toList();
        }
        return o.carPhotos().stream().map(carMapper::storedUrl).toList();
    }
}
