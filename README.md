# premium-cars-backend

REST API of the Premium Cars Import website: catalog, import of cars by link, landed price in Bulgaria, inquiries.
Java 21 · Spring Boot 3.5 · plain SQL through `JdbcClient` (no JPA) · PostgreSQL · Liquibase.

The full guide for running all three parts locally is in the **premium-cars-infra** repo (`README.md`).

## Quick start

```bash
# 1. the database (from premium-cars-infra)
docker compose up -d

# 2. secrets (once): copy the example and fill in the autowini login
cp .env.example .env          # PowerShell: Copy-Item .env.example .env

# 3. the backend
mvn spring-boot:run
```

- http://localhost:8080/actuator/health → `{"status":"UP"}`
- http://localhost:8080/api/public/cars → the demo cars
- http://localhost:8080/swagger-ui.html → all API endpoints

Tests: `mvn test`. `JdbcRepositoriesTest` starts PostgreSQL in Docker; without Docker it is skipped
(CI fails if it is skipped).

## Importing cars

In the admin panel paste a listing URL; the backend downloads the page and photos and creates a draft.
Optionally enter the price and currency yourself (useful when the site hides the price).

| Site | How |
|---|---|
| global.autohome.com.cn (China) | page HTML first; if photos or EV data (battery, range, motor) are missing, the page is rendered in headless Chromium and the "More", "Electric Motor" and "Battery && Charging" tabs are opened |
| autowini.com (South Korea) | logs in with your autowini account (Keycloak), then reads the listing; specs and photos also work without login, the price only with it |
| anything else | generic extractor: JSON-LD → `__NEXT_DATA__` → Open Graph → page text |

### Autowini credentials

Credentials are read from environment variables, never from the database or git:

| Variable | What |
|---|---|
| `AUTOWINI_USER` | autowini login (e-mail) |
| `AUTOWINI_PASS` | autowini password |

Locally put them in `.env` next to `pom.xml` (git-ignored, loaded through `spring.config.import`).
The names match the `.env` of the posting script, so that file can be copied here as it is.
On a server, set them as real environment variables or secrets.

### Headless browser (Playwright)

The first import that needs rendering downloads Chromium (~150 MB) into the Playwright cache; later imports
reuse it. To turn rendering off (pages are then read from HTML only): `APP_IMPORTER_BROWSER_ENABLED=false`.

## Facebook and Instagram

The car editor in the admin panel shows the post text (the posting script's format, editable) with
**Publish to Facebook** / **Publish to Instagram** buttons, the list of earlier posts, and "share with photos"
(the phone's or Windows share sheet, e.g. to the Instagram app) / "download photos".

Publishing uses the Meta Graph API like the posting script: Facebook gets an album post with up to 10 photos;
Instagram gets a carousel (photos are uploaded to imgbb first, because Instagram needs public photo URLs).
Settings, in `.env` like the autowini login (the same names as the script's `.env`):

| Variable | What | Where to get it |
|---|---|---|
| `FB_PAGE_ID` | the Facebook page id | Page → About / Meta Business Suite → Settings |
| `FB_ACCESS_TOKEN` | a long-lived **Page** access token with `pages_manage_posts`, `pages_read_engagement`, `instagram_basic`, `instagram_content_publish` | developers.facebook.com → your app → Graph API Explorer → select the page → generate token; exchange it for a long-lived one (Access Token Debugger → "Extend") and take the page token from `/me/accounts` |
| `IG_USER_ID` | the Instagram **business/creator** account linked to the page | Graph API Explorer: `/{FB_PAGE_ID}?fields=instagram_business_account` |
| `IMGBB_API_KEY` | imgbb API key | api.imgbb.com |

A network whose settings are missing has its button disabled. Restart the backend after changing `.env`.
Posts are recorded in `social_post` (link, date, number of photos); the editor asks before posting a car twice.

## Configuration

Everything is in `src/main/resources/application.yml` and can be overridden with an environment variable:

| Setting | Variable | Default |
|---|---|---|
| Admin password | `APP_ADMIN_PASSWORD` | `admin123`, **change it** |
| Database connection | `SPRING_DATASOURCE_URL`, `..._USERNAME`, `..._PASSWORD` | `localhost:5433/premiumcars` |
| Exchange rates | `app.pricing.exchange-rates` | approximate, **update them** |
| Max photos per import | `app.importer.max-images` | 30 |
| Headless browser | `APP_IMPORTER_BROWSER_ENABLED` | `true` |
| Facebook / Instagram | `FB_PAGE_ID`, `FB_ACCESS_TOKEN`, `IG_USER_ID`, `IMGBB_API_KEY` | empty (off) |
| Demo cars on an empty DB | `LIQUIBASE_CONTEXTS` | `demo` (`none` = no demo data) |

Prices are recalculated on every start; after changing exchange rates without a restart:
`POST /api/admin/cars/recalculate-prices` (from Swagger).

## Pricing

Customers see one final price that includes everything; the same number goes into the Facebook/Instagram post.
Each source site has its own formula in the `pricing_profile` table, edited in the admin panel ("Цени"):

```
final price = car price in € + base costs + step cost × steps above the threshold + profit
```

| | autohome (China) | autowini (South Korea) |
|---|---|---|
| base costs (all import costs up to the threshold) | 5860 € | 5000 € |
| step cost, for every 10 000 € above 10 000 € | 2880 € | 600 € |
| profit | 2000 € | 2000 € |

Above the threshold the step costs are proportional: autohome, car for 25 000 € = 5860 + 2880 (up to 20 000)
+ 1440 (the 5000 above 20 000) + 2000. The result is rounded to whole euros. Saving a profile reprices every car. Imported cars use their site's profile;
cars from other sites get one chosen in the editor.

## Data model

A car is `specs` (title, make/model/trim, year, mileage, fuel, transmission, drivetrain, engine, power,
battery kWh, electric range + cycle, motor kW, seats, doors, weight, dimensions, colour, notes, extra specs)
plus `pricing` (source price, currency, price profile). The public API returns only the final `priceEur`. The API uses the same nesting.

## Database access

No ORM. Each repository holds the SQL for its table:

| Class | Table | Note |
|---|---|---|
| `CarRepository` | `car` | search with a dynamic WHERE (`CarSearchSql`), sorting with `NULLS LAST` |
| `CarImageRepository` | `car_image` | position 0 = cover; positions are reordered with one SQL statement |
| `PricingProfileRepository` | `pricing_profile` | one row per source site |
| `SocialPostRepository` | `social_post` | posts published to Facebook / Instagram |
| `InquiryRepository` | `inquiry` | `LEFT JOIN car` for the car title |

Schema change: a new file `src/main/resources/db/changelog/changes/00N-....sql` and a line in `db.changelog-master.yaml`.
Never edit a changeset that has already run.

## When an import misreads a field

1. In the browser: "Save page as… → HTML only" for the listing.
2. Put the file in `src/test/resources/pages/` and add a test (`GenericPageExtractorTest`, `AutohomePageParserTest`
   or `AutowiniPageParserTest`).
3. Fix the parser (`importing/extract/`, `importing/source/autohome/`, `importing/source/autowini/`).

## Troubleshooting

| Symptom | Fix |
|---|---|
| `Connection refused` to 5433 | Is Docker Desktop running? `docker compose ps` in premium-cars-infra |
| Port 8080 is taken | `SERVER_PORT=8081 mvn spring-boot:run` (and update `NEXT_PUBLIC_API_URL` in the frontend) |
| CORS error in the browser | Add the frontend URL to `app.cors.allowed-origins` |
| Liquibase checksum error | A changeset was edited after it ran; revert it and add a new file |
| `JdbcRepositoriesTest` is skipped | Docker is not reachable. Start Docker Desktop |
| autowini import: "login failed" | Check `AUTOWINI_USER` / `AUTOWINI_PASS` in `.env`, restart the backend |
| autohome import lacks photos/EV data | Rendering is off or Chromium could not start; see the warnings on the import result |
