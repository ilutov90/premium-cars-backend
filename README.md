# premium-cars-backend

REST API на сайта Premium Cars Import: каталог, импорт на коли по линк, крайна цена в България, запитвания.
Java 21 · Spring Boot 3.5 · чист SQL през `JdbcClient` (без JPA) · PostgreSQL · Liquibase.

Пълната инструкция за локален старт на трите части е в репото **premium-cars-infra** (`README.md`).

## Бърз старт

```bash
# 1. базата (от premium-cars-infra)
docker compose up -d

# 2. бекендът
mvn spring-boot:run
```

- http://localhost:8080/actuator/health → `{"status":"UP"}`
- http://localhost:8080/api/public/cars → демо колите
- http://localhost:8080/swagger-ui.html → всички API методи

Тестове: `mvn test`. `JdbcRepositoriesTest` вдига PostgreSQL в Docker; ако Docker не е пуснат, тестът се пропуска.

## Конфигурация

Всичко е в `src/main/resources/application.yml` и може да се смени с променлива на средата:

| Настройка | Променлива | По подразбиране |
|---|---|---|
| Парола на админа | `APP_ADMIN_PASSWORD` | `admin123`, **смени я** |
| Връзка с базата | `SPRING_DATASOURCE_URL`, `..._USERNAME`, `..._PASSWORD` | `localhost:5433/premiumcars` |
| Транспорт по подразбиране | `APP_PRICING_DEFAULT_SHIPPING_EUR` | 2500 |
| Марж по подразбиране | `APP_PRICING_DEFAULT_MARGIN_EUR` | 1500 |
| Фиксирани такси | `APP_PRICING_FIXED_FEES_EUR` | 600 |
| Мито / ДДС | `app.pricing.customs-duty-rate` / `vat-rate` | 0.10 / 0.20 |
| Валутни курсове | `app.pricing.exchange-rates` | ориентировъчни, **обнови ги** |
| Демо коли при празна база | `LIQUIBASE_CONTEXTS` | `demo` (`none` = без демо) |

След промяна на курсове или цени: `POST /api/admin/cars/recalculate-prices` (от Swagger).

Крайна цена: `(кола в € + транспорт) × (1 + мито) × (1 + ДДС) + такси + марж`. Ставките са конфигурация, не закон;
провери ги с митнически брокер. За електромобили от Китай въведи допълнителното мито на ниво кола.

## Достъп до базата

Без ORM. Всеки репозиторий съдържа SQL-а за своята таблица:

| Клас | Таблица | Бележка |
|---|---|---|
| `CarRepository` | `car` | търсене с динамичен WHERE (`CarSearchSql`), сортиране с `NULLS LAST` |
| `CarImageRepository` | `car_image` | позиция 0 = корица; позициите се пренареждат с един SQL |
| `InquiryRepository` | `inquiry` | `LEFT JOIN car` за заглавието на колата |

Промяна на схемата: нов файл `src/main/resources/db/changelog/changes/003-....sql` и ред в `db.changelog-master.yaml`.
Никога не редактирай вече изпълнен changeset.

## Когато импортът не разпознае поле

Парсерът опитва JSON-LD → `__NEXT_DATA__` → Open Graph → текст. Ако сайтът смени структурата си:

1. В браузъра: „Save page as… → HTML only“ за проблемната обява.
2. Сложи файла в `src/test/resources/pages/` и добави тест в `GenericPageExtractorTest`.
3. Добави ключа на липсващото поле в списъците в `GenericPageExtractor` (напр. `PRICE_KEYS`).

## Чести проблеми

| Симптом | Решение |
|---|---|
| `Connection refused` към 5433 | Docker Desktop пуснат ли е? `docker compose ps` в premium-cars-infra |
| Порт 8080 е зает | `SERVER_PORT=8081 mvn spring-boot:run` (и обнови `NEXT_PUBLIC_API_URL` във фронтенда) |
| CORS грешка в браузъра | Добави адреса на фронтенда в `app.cors.allowed-origins` |
| Liquibase checksum грешка | Някой changeset е редактиран след изпълнение; върни го и добави нов файл |
| `JdbcRepositoriesTest` е „skipped“ | Docker не е достъпен. Пусни Docker Desktop |
