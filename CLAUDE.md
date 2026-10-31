# premium-cars-backend

REST API for the Premium Cars Import website (cars imported from South Korea and China, sold in Bulgaria).
Sibling repos: `premium-cars-frontend` (Next.js, calls this API) and `premium-cars-infra` (local PostgreSQL + Vault).

## Stack

Java 21, Spring Boot 3.5, Maven, PostgreSQL 17, Liquibase, Spring Security (session login + CSRF for
`/api/admin/**`), Spring Cloud Vault (secrets, AppRole), Jsoup (HTML import), springdoc (Swagger UI at `/swagger-ui.html`).

## Commands

```bash
mvn test                 # unit tests + DB tests (Testcontainers; skipped when Docker is not running)
mvn spring-boot:run      # needs PostgreSQL + Vault from premium-cars-infra (docker compose up -d there)
mvn -B verify            # what CI runs
```

Always run `mvn test` before finishing a change.

## Hard rules

- **No JPA / Hibernate / Spring Data repositories / Lombok.** Data access is plain SQL through Spring's
  `JdbcClient` with named parameters. All SQL for a table lives in its `*Repository` class.
- Never concatenate user input into SQL. Dynamic WHERE clauses are built from fixed fragments plus named
  parameters (see `CarSearchSql`); sort orders come from the `CarSort` enum.
- Domain types are Java records (`Car`, `CarSpecs`, `PricingInputs`, `CarImage`, `Inquiry`). Enums are stored as
  their `name()`. Build `CarSpecs` with `CarSpecs.builder()` / `toBuilder()`: `build()` trims, maps blank to null
  and truncates to the column sizes. EV fields (battery, range, motor) are nullable; combustion cars leave them null.
- Car data is limited to what mobile.bg's "Добави обява" form can take: extras are `CarFeature` (one constant per
  mobile.bg checkbox, `mobileBgLabel()` is the checkbox's exact value, stored by `name()` in `car.features`), plus
  `euroStandard` (1-6), and `batteryHealthPct` (battery SOH, guazi's "Battery Report"). Importers fill what the source shows (`FeatureMapper`), the admins edit the rest.
  `specs.inspection` (`Inspection`, `car.inspection`) is the source's inspection report as imported (guazi), read-only too.
  Besides, `specs.sheet` (`SpecGroup` list, `car.spec_sheet`) keeps the source's whole specification sheet as
  imported (autohome's "Specifications" table: parameters + standard equipment; autowini's "Options" by group),
  in the source's English, for the car page (the frontend translates it). It is read-only: saving a car keeps it.
- The car API is nested: `{specs, pricing}` in requests and admin responses, `specs` in public responses.
- jsonb columns: write with `CAST(:param AS jsonb)`, read/write through `JsonColumns`.
- Schema changes: add a **new** Liquibase SQL file under `src/main/resources/db/changelog/changes/`
  (next free number, e.g. `004-...sql`) and include it in `db.changelog-master.yaml`. Never edit an applied changeset.
- Network calls (page/photo download) happen outside DB transactions (`ImportService` is not `@Transactional`).
- The source site URL, source id and dealer are admin-only; never return them from `/api/public/**`.
- Customers see only the final price (`priceEur` / `totalPriceEur`); purchase price, costs and profit are admin-only.
- Secrets (DB password, autowini login, Meta token, Viber channel token, imgbb key, Resend key, R2 keys, first admin password) come only from Vault
  (`secret/premium-cars/backend`, keys = the `${...}` placeholder names in `application.yml`). No defaults for
  secrets in `application.yml`, `.env`, tests or git; tests use dummy values in `src/test/resources`. Tokens go in
  headers, never URLs. CI runs gitleaks.
- Admin auth: session cookie + CSRF (`auth` package). Never re-introduce HTTP Basic, passwords in the browser or
  CORS for the API; the browser reaches the API through the frontend (same origin).
- Code, comments, identifiers and docs (README, CLAUDE.md) in English; user-facing texts live in the frontend (Bulgarian).
  Exception: the e-mails to the business (`InquiryNotifier`, `AutoImportMailer`) are Bulgarian.
- `Dockerfile` is the production image (CI pushes it to GHCR); local development never needs it.

## Layout (`com.premiumcars`)

| Package | What |
|---|---|
| `catalog` | cars and photos: domain records (`MakeNames`: one name per make, applied when a car is imported: "Geely Auto" / "Geely Galaxy" -> Geely, the latter's models as "Galaxy …"), `CarRepository`, `CarImageRepository`, `CarService`, public + admin controllers. Public list rows bring the first 8 photos (`CarSummary.imageUrls`, cover first) for the cards. The public catalog filters by make and model (`FilterOptions.models`: make -> models of the public cars). The admin list (`GET /api/admin/cars`: status, text, source site, sort `updated`/`created`/`price_desc`/`price_asc`, pages of up to 48) and its counts per status (`GET /api/admin/cars/counts`) |
| `importing` | import by link: `ImportService`, one `SourceAdapter` per site, `PageFetcher` |
| `importing.extract` | `NextFlightData` (the `self.__next_f.push` data of Next.js App Router pages: autohome, guazi), `GenericPageExtractor` (JSON-LD, `__NEXT_DATA__`, Open Graph, text), `EvSpecsExtractor`, `TitleParser`, `LabelValues`, `FeatureMapper` (equipment text of both sites -> `CarFeature`, Euro class, steering side) |
| `importing.browser` | `PageRenderer` / `PlaywrightPageRenderer`: headless Chromium for JS-rendered pages (`app.importer.browser.enabled`) |
| `importing.source.autohome` | global.autohome.com.cn: `AutohomePageParser`, `AutohomeSpecSheet` (the "Specifications" table from the page's Next.js data: parameters + standard equipment, "○" options are not counted), `AutohomeSourceAdapter` (renders when photos/EV data are missing) |
| `importing.source.autowini` | autowini.com: `AutowiniClient` (Keycloak login, cookies, the item API for the price, autowini's own USD->EUR rate: euro prices are taken as autowini shows them), `AutowiniPageParser` (incl. the seller's "Options", desktop and mobile page), `AutowiniSourceAdapter` (a listing whose price cannot be read is not imported unless the admin enters it: `requiresPrice`) |
| `importing.source.guazi` | en.guazi.com (China, any language subdomain -> `en.`): `GuaziPageParser` (the whole listing is the `rawData` JSON in the page's Next.js data: Vehicle Details, all Specifications, Features, battery SOH, FOB prices in USD with the default port's current price used (auctions, `fixedPrice` 2, and guazi's stand-in "$9,999,999" mean no price: `GuaziPageParser.realPrice`; such a listing is not imported unless the admin enters the price, `SourceAdapter.requiresPrice`), all photos; no browser), `GuaziSourceAdapter`. Priced with the AUTOHOME formula; the inspection report (grade, accident/water/fire checks, areas, the defects found with the inspector's note and video) is kept as `specs.inspection` (`Inspection`, `car.inspection`, read-only like the sheet) and shown on the car page; a one-line summary also goes to the admin as an import warning. guazi answers plain requests from the server with a check page; with guazi's permission (Oct 2026) the page is then opened in the headless browser (`PageRenderer.renderWhen`, waits until the HTML has `rawData`), one at a time and at least 1 s apart. A page saved in the admin's browser can still be imported (`ImportRequest.html`, `SourceAdapter.importFromHtml`) |
| `pricing` | `PriceCalculator` (on the FOB price in EUR: final price = FOB + sea freight + land transport + commission + duty and VAT + profit, duty and VAT = a percent of FOB + sea freight, profit = a percent of FOB with a minimum; a car may have its own profit amount), `PricingProfile` + repository (one formula per site, in the DB, edited in the admin panel "Цени"; AUTOHOME also prices guazi; since 027 a fixed markup = markup low up to the threshold FOB, markup high above (1000 / 1500, 25 000; a car may have its own amount); since 030 a formula may have a duty and VAT table by FOB (`pricing_duty_bracket`, `DutyBracket`: the first row whose "FOB up to" is at least the FOB, the open row above all; autowini has one, then its percent is unused; 027's step for autowini is gone)). Breakdowns calculated before 020 keep the old parts (base costs, steps) until recalculated, `ExchangeRateProvider` (`EcbExchangeRateProvider`: daily ECB rates, configured ones as fallback). A car's price is calculated once and kept with its breakdown (`car.price_breakdown`): only its own price fields or an explicit recalculation change it, never rates, profiles or restarts. `catalog.service.PriceRecalcService` recalculates by hand (per site or all, `POST /api/admin/pricing-profiles/recalculations`): from the kept FOB (STORED) or reading each listing again (SOURCE, `ImportService.readListing`, 1 s apart); a hand-set final price is kept; the report (`price_recalc_run` / `_item`) lists the changed cars with their posts (`social.PostedChannels`: Facebook/Instagram/Viber posts and the mobile.bg / cars.bg listings made through the panel) |
| `media` | `ImageStorage`: `RoutingImageStorage` (primary) stores new photos in Cloudflare R2 when `app.media.r2` is configured (`R2ImageStorage`: S3 API with our own `SigV4` signer, no AWS SDK; storage paths "r2:<key>", shown from the bucket's custom domain), otherwise in the local folder (`LocalImageStorage`, paths without prefix); `R2PhotoMover` moves the local car photos to R2 after startup once R2 is on. `ImageDownloader`. Photos of sites whose CDN serves any browser (guazi, autohome: `SourceAdapter.linksPhotos()`) are not stored: `car_image.storage_path` is null and the site shows `source_url` (the variant that downloaded at import); autowini's CDN refuses other sites' visitors, so its photos are stored. `importing.CarPhotoFiles` gives any photo's bytes (linked ones downloaded at that moment, for publishing), `/media/photo/{imageId}` serves them from our origin (admin downloads, the mobile.bg extension), `StoredPhotoLinker` links the stored photos of linking sites once after startup |
| `social` | publishing to Facebook / Instagram / Viber: `MetaGraphClient` (Graph API), `ViberChannelClient` (Channels Post API) + `ViberWebhookController` (signed callback), `ImgbbUploader`, `SocialPublisher`, `social_post`, `post_template` (text templates filled in by the admin panel, `TemplateKey`: one shared by Facebook and Instagram, stored as `FACEBOOK`, one for Viber, one for mobile.bg's description `MOBILE_BG`), `post_setting` (phone, Viber link, office for the templates; the site's Meta Pixel id, public through `/api/public/contact`), `scheduled_post` + `ScheduledPostService` (one publication = one post per network, published at the same time in the background now or later; checked every 30 s, never retried) |
| `auth` | admin login: `AdminAuthService` (bcrypt, lock, admins managing admins), `AdminAuthController` (login/logout/me/password), `AdminUserController` (`/api/admin/users`, one-time passwords), `AdminSessionFilter` (ends sessions of deleted users / changed passwords), `RateLimitFilter`, `admin_user` |
| `mobilebg` | mobile.bg module of the admin panel: `MobileBgMapper` (a car -> mobile.bg's "Добави обява" values; brand/model from `marketplace.ModelMatcher` against mobile.bg's lists; problems as warning codes the frontend words), `MobileBgService` / `MobileBgController` (`GET /api/admin/cars/{id}/mobile-bg`, `GET/PUT /api/admin/mobile-bg/catalog`, `POST /api/admin/cars/{id}/mobile-bg/listing`), `mobile_bg_catalog` (mobile.bg's lists as the admins' browser extension last read them), `mobile_bg_listing` (where a car was posted). The server never calls mobile.bg: the extension (frontend repo, `extension/mobile-bg`) types the form in the admin's browser and the admin presses mobile.bg's buttons |
| `marketplace` | shared by the mobile.bg and cars.bg modules: `ModelNames` (keys without case/spaces/dashes, models without the sources' powertrain words, the sites' other spellings of makes, Chinese export names: "Yuan UP" -> "Atto 2"), `ModelMatcher` (an admin's choice, else the same name = AUTO, else export names / one name starting with the other = SUGGESTED, never taken alone; MODEL_MISSING, BRAND_MISSING: never another make; NO_CATALOG), `marketplace_model_map` (the admins' choices or "not on the site", keyed by our names' keys), `MarketplaceService` / `MarketplaceController` (`/api/admin/marketplace/models`: the page "Съпоставяне на модели", every make + model of our cars by number of cars with its match per site; PUT a choice, DELETE to reset). A site's lists read again drop the "not on the site" choices the site now has |
| `carsbg` | cars.bg module, like mobile.bg's: `CarsBgMapper` (a car -> the "Публикувай обява" form's ids: category, fuel, gear, doors, colour, `e[]` extras; the year capped at this year with a warning; our model's name in the 15-character `engine` field when cars.bg's differs), `CarsBgService` / `CarsBgController` (`GET /api/admin/cars/{id}/cars-bg`, `GET/PUT /api/admin/cars-bg/catalog`, `POST/DELETE /api/admin/cars/{id}/cars-bg/listing`), `cars_bg_catalog` (makes and models with ids as the extension read them), `cars_bg_listing` (one offer per car: the panel does not post a car twice). The server never calls cars.bg; the extension never presses its publish button |
| `inquiry` | customer inquiries with where they came from (`HeardFrom`: "Откъде научихте за нас?", `Attribution`: UTM parameters, landing page, referring site); `InquiryNotifier` e-mails the admin through Resend after the commit (async); the admin report (`GET /api/admin/reports/inquiries`: per channel, campaign, car and week, Sofia days) |
| `delivery` | "Доставени коли": handed-over cars with the customer's words and a photo (`delivered_car`, photo uploaded as the request body), shown on the site only when published, which needs the customer's consent |
| `autoimport` | automatic import (admin tab "Автоматичен импорт", `/api/admin/auto-import`): a main switch and one per source (`auto_import_state`, `auto_import_source` with the source's filters: autowini Inspected/Actual, guazi grades), the makes with year from and chosen models (`auto_import_brand`), the sources' own make/model lists (`auto_import_catalog`). `AutowiniSearch` (v2api.autowini.com `/items/cars`, no login) and `GuaziSearch` (`POST /os/facade/search/product/list`; only the first 500 results of a search are served, so big makes are read per model and per year) behind `SourceSearch`, paced by `SearchHttp`. `AutoImportRunner`: nightly at 03:00 Sofia (guazi first) or on demand, also as a dry run that changes nothing; queues new listings (`auto_import_listing`; a listing over the mileage limit `auto_import_state.max_mileage_km`, default 200 000, null = none, is kept as `SKIPPED` and queued again when the limit allows it: guazi shows the km in the listing address, otherwise `ImportService.importAutomatically` checks the page before saving anything), links cars already in the catalog (also imported by hand), hides as `REMOVED` (`car.removed_at`) the cars whose listing was missing in 2 runs in a row (makes read whole only), publishes them again when they return, also checks the source's other cars (added by hand or of makes not imported: their make searched without filters, `auto_import_manual_check`), notes every hidden car with its posts (`auto_import_removed`, `GET /runs/{id}/removed`), stops without removing when over 20 % would go (`AutoImportMailer` e-mails), deletes cars REMOVED for 30 days; `auto_import_run` is the report. `AutoImportWorker` imports the queue one by one (guazi first, 20 photos, `ImportService.importAutomatically`) and publishes; a failure is tried 3 times, then again by the next run while the source has it (the "Неуспешни" tab: `GET /failures` paged with the reasons grouped, `POST /failures/delete`); no photos -> left a draft; listings without a price are never queued (counted as read) and a car that comes without a FOB price is deleted again (not put on the site) |
| `config`, `common` | security, CORS, properties, errors, paging |

Extension points (add a class, no other changes): new source site -> `SourceAdapter`; cloud photo storage ->
`ImageStorage` (routed by `RoutingImageStorage`); another rate source -> `ExchangeRateProvider`; more inquiry notifications -> another `InquiryCreatedEvent` listener.

## API contract

DTOs are in `catalog/web/dto/CarDtos.java`, `inquiry/InquiryDtos.java` and `delivery/DeliveredCarDtos.java`. The frontend mirrors them in
`src/lib/types.ts` — when a DTO changes, the frontend must change in the same feature.

## Tests

- `CarSearchSqlTest`, `SearchParsingTest` (both sites' search API shapes), `PriceCalculatorTest`, `ModelMatcherTest`, `CarsBgMapperTest`, `MobileBgMapperTest`, `GenericPageExtractorTest`, `EvSpecsExtractorTest`, `FeatureMapperTest`,
  `AutohomePageParserTest`, `AutowiniPageParserTest`, `GuaziPageParserTest`: fast, no DB, no network
  (`guazi-bxrayk2hvc.html` is a real listing's `rawData` + JSON-LD in the page's structure, without the rest of the
  saved page, which carries the logged-in visitor's account; `guazi-68pfqu2abf.html` is a reduced copy).
- `JdbcRepositoriesTest`, `AdminAuthTest`, `AutoImportRunnerTest` (a run's bookkeeping with a made-up search): full Spring context on PostgreSQL 17 in Docker (Testcontainers,
  `@ServiceConnection`); `VaultConfigTest`: real Vault in Docker. `src/test/resources/application.properties`
  turns Vault and rate limits off for the other tests.
- When an import misreads a real page: save it under `src/test/resources/pages/` and add a case to
  the matching parser test. Tests never log in or hit the real sites.
- `EvSpecsExtractor`, `TitleParser` and the frontend's `description.ts` are ports of `autowini_poster_parallel.py`;
  keep their output identical to the script.
