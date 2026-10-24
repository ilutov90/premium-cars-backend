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
  `euroStandard` (1-6). Importers fill what the source shows (`FeatureMapper`), the admins edit the rest.
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
- Secrets (DB password, autowini login, Meta token, Viber channel token, imgbb key, Resend key, first admin password) come only from Vault
  (`secret/premium-cars/backend`, keys = the `${...}` placeholder names in `application.yml`). No defaults for
  secrets in `application.yml`, `.env`, tests or git; tests use dummy values in `src/test/resources`. Tokens go in
  headers, never URLs. CI runs gitleaks.
- Admin auth: session cookie + CSRF (`auth` package). Never re-introduce HTTP Basic, passwords in the browser or
  CORS for the API; the browser reaches the API through the frontend (same origin).
- Code, comments, identifiers and docs (README, CLAUDE.md) in English; user-facing texts live in the frontend (Bulgarian).
  Exception: the inquiry e-mail to the business (`InquiryNotifier`) is Bulgarian.
- `Dockerfile` is the production image (CI pushes it to GHCR); local development never needs it.

## Layout (`com.premiumcars`)

| Package | What |
|---|---|
| `catalog` | cars and photos: domain records, `CarRepository`, `CarImageRepository`, `CarService`, public + admin controllers |
| `importing` | import by link: `ImportService`, one `SourceAdapter` per site, `PageFetcher` |
| `importing.extract` | `GenericPageExtractor` (JSON-LD, `__NEXT_DATA__`, Open Graph, text), `EvSpecsExtractor`, `TitleParser`, `LabelValues`, `FeatureMapper` (equipment text of both sites -> `CarFeature`, Euro class, steering side) |
| `importing.browser` | `PageRenderer` / `PlaywrightPageRenderer`: headless Chromium for JS-rendered pages (`app.importer.browser.enabled`) |
| `importing.source.autohome` | global.autohome.com.cn: `AutohomePageParser`, `AutohomeSpecSheet` (the "Specifications" table from the page's Next.js data: parameters + standard equipment, "○" options are not counted), `AutohomeSourceAdapter` (renders when photos/EV data are missing) |
| `importing.source.autowini` | autowini.com: `AutowiniClient` (Keycloak login, cookies, the item API for the price, autowini's own USD->EUR rate: euro prices are taken as autowini shows them), `AutowiniPageParser` (incl. the seller's "Options", desktop and mobile page), `AutowiniSourceAdapter` |
| `pricing` | `PriceCalculator` (final price = car + base costs + step costs + profit), `PricingProfile` + repository (one formula per site, in the DB, edited in the admin panel), `ExchangeRateProvider` (`EcbExchangeRateProvider`: daily ECB rates, configured ones as fallback). A car's price is calculated once and kept with its breakdown (`car.price_breakdown`): only its own price fields or an explicit recalculation change it, never rates, profiles or restarts |
| `media` | `ImageStorage` (local folder now, S3/R2 later), `ImageDownloader` |
| `social` | publishing to Facebook / Instagram / Viber: `MetaGraphClient` (Graph API), `ViberChannelClient` (Channels Post API) + `ViberWebhookController` (signed callback), `ImgbbUploader`, `SocialPublisher`, `social_post`, `post_template` (text templates filled in by the admin panel: one shared by Facebook and Instagram, stored as `FACEBOOK`, one for Viber), `post_setting` (phone, Viber link, office for the templates), `scheduled_post` + `ScheduledPostService` (one publication = one post per network, published at the same time in the background now or later; checked every 30 s, never retried) |
| `auth` | admin login: `AdminAuthService` (bcrypt, lock, admins managing admins), `AdminAuthController` (login/logout/me/password), `AdminUserController` (`/api/admin/users`, one-time passwords), `AdminSessionFilter` (ends sessions of deleted users / changed passwords), `RateLimitFilter`, `admin_user` |
| `inquiry` | customer inquiries; `InquiryNotifier` e-mails the admin through Resend after the commit (async) |
| `config`, `common` | security, CORS, properties, errors, paging |

Extension points (add a class, no other changes): new source site -> `SourceAdapter`; cloud photo storage ->
`ImageStorage`; another rate source -> `ExchangeRateProvider`; more inquiry notifications -> another `InquiryCreatedEvent` listener.

## API contract

DTOs are in `catalog/web/dto/CarDtos.java` and `inquiry/InquiryDtos.java`. The frontend mirrors them in
`src/lib/types.ts` — when a DTO changes, the frontend must change in the same feature.

## Tests

- `CarSearchSqlTest`, `PriceCalculatorTest`, `GenericPageExtractorTest`, `EvSpecsExtractorTest`, `FeatureMapperTest`,
  `AutohomePageParserTest`, `AutowiniPageParserTest`: fast, no DB, no network.
- `JdbcRepositoriesTest`, `AdminAuthTest`: full Spring context on PostgreSQL 17 in Docker (Testcontainers,
  `@ServiceConnection`); `VaultConfigTest`: real Vault in Docker. `src/test/resources/application.properties`
  turns Vault and rate limits off for the other tests.
- When an import misreads a real page: save it under `src/test/resources/pages/` and add a case to
  the matching parser test. Tests never log in or hit the real sites.
- `EvSpecsExtractor`, `TitleParser` and the frontend's `description.ts` are ports of `autowini_poster_parallel.py`;
  keep their output identical to the script.
