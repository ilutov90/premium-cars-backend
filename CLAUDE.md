# premium-cars-backend

REST API for the Premium Cars Import website (cars imported from South Korea and China, sold in Bulgaria).
Sibling repos: `premium-cars-frontend` (Next.js, calls this API) and `premium-cars-infra` (local PostgreSQL).

## Stack

Java 21, Spring Boot 3.5, Maven, PostgreSQL 17, Liquibase, Spring Security (HTTP Basic for `/api/admin/**`),
Jsoup (HTML import), springdoc (Swagger UI at `/swagger-ui.html`).

## Commands

```bash
mvn test                 # unit tests + DB tests (Testcontainers; skipped when Docker is not running)
mvn spring-boot:run      # needs PostgreSQL on localhost:5433 (docker compose up -d in premium-cars-infra)
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
- The car API is nested: `{specs, pricing}` in requests and admin responses, `specs` in public responses.
- jsonb columns: write with `CAST(:param AS jsonb)`, read/write through `JsonColumns`.
- Schema changes: add a **new** Liquibase SQL file under `src/main/resources/db/changelog/changes/`
  (next free number, e.g. `004-...sql`) and include it in `db.changelog-master.yaml`. Never edit an applied changeset.
- Network calls (page/photo download) happen outside DB transactions (`ImportService` is not `@Transactional`).
- The source site URL, source id and dealer are admin-only; never return them from `/api/public/**`.
- Customers see only the final price (`priceEur` / `totalPriceEur`); purchase price, costs and profit are admin-only.
- Secrets (autowini login, Meta token, imgbb key) come only from environment variables or the git-ignored `.env`
  next to `pom.xml` (`AUTOWINI_USER`, `AUTOWINI_PASS`, `FB_ACCESS_TOKEN`, `IMGBB_API_KEY` …). Tokens go in headers, never URLs. Never store them in the database, `application.yml`, tests or git.
- Code, comments, identifiers and docs (README, CLAUDE.md) in English; user-facing texts live in the frontend (Bulgarian).

## Layout (`com.premiumcars`)

| Package | What |
|---|---|
| `catalog` | cars and photos: domain records, `CarRepository`, `CarImageRepository`, `CarService`, public + admin controllers |
| `importing` | import by link: `ImportService`, one `SourceAdapter` per site, `PageFetcher` |
| `importing.extract` | `GenericPageExtractor` (JSON-LD, `__NEXT_DATA__`, Open Graph, text), `EvSpecsExtractor`, `TitleParser`, `LabelValues` |
| `importing.browser` | `PageRenderer` / `PlaywrightPageRenderer`: headless Chromium for JS-rendered pages (`app.importer.browser.enabled`) |
| `importing.source.autohome` | global.autohome.com.cn: `AutohomePageParser`, `AutohomeSourceAdapter` (renders when photos/EV data are missing) |
| `importing.source.autowini` | autowini.com: `AutowiniClient` (Keycloak login, cookies), `AutowiniPageParser`, `AutowiniSourceAdapter` |
| `pricing` | `PriceCalculator` (final price = car + base costs + step costs + profit), `PricingProfile` + repository (one formula per site, in the DB, edited in the admin panel), `ExchangeRateProvider` |
| `media` | `ImageStorage` (local folder now, S3/R2 later), `ImageDownloader` |
| `social` | publishing to Facebook / Instagram: `MetaGraphClient` (Graph API), `ImgbbUploader`, `SocialPublisher`, `social_post` |
| `inquiry` | customer inquiries |
| `config`, `common` | security, CORS, properties, errors, paging |

Extension points (add a class, no other changes): new source site -> `SourceAdapter`; cloud photo storage ->
`ImageStorage`; daily ECB rates -> `ExchangeRateProvider`; inquiry notifications -> `InquiryService.create`.

## API contract

DTOs are in `catalog/web/dto/CarDtos.java` and `inquiry/InquiryDtos.java`. The frontend mirrors them in
`src/lib/types.ts` — when a DTO changes, the frontend must change in the same feature.

## Tests

- `CarSearchSqlTest`, `PriceCalculatorTest`, `GenericPageExtractorTest`, `EvSpecsExtractorTest`,
  `AutohomePageParserTest`, `AutowiniPageParserTest`: fast, no DB, no network.
- `JdbcRepositoriesTest`: full Spring context on PostgreSQL 17 in Docker (Testcontainers, `@ServiceConnection`).
- When an import misreads a real page: save it under `src/test/resources/pages/` and add a case to
  the matching parser test. Tests never log in or hit the real sites.
- `EvSpecsExtractor`, `TitleParser` and the frontend's `description.ts` are ports of `autowini_poster_parallel.py`;
  keep their output identical to the script.
