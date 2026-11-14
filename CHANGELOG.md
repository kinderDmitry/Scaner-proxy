## [1.11.0] - 2026-11-14

### Added
- Separate HAPP subscription for uploaded JSON and public-feed scanner results.
- Global scanner page with search by country, city, endpoint, protocol and source.
- Public SOCKS5 feed sources from ProxyScrape and Thordata, alongside the existing VLESS feeds.
- Independent fastest-node Auto Pick and fixed-size subscription variants for both catalogs.

### Changed
- Catalog builder now fetches and verifies public feeds as a separate source family; uploaded JSON is no longer mixed into scanner results.
- Both catalogs retain their last successful publication if a source returns no candidates or no candidate passes verification.
- Every subscription entry displays the measured fastest node first, then geo-labelled nodes.

### Safety
- Public discovery consumes published feed URLs only; it does not sweep arbitrary third-party address ranges.
- Private, loopback, link-local, multicast and non-global endpoints remain excluded.

## [1.10.0] - 2026-11-13

### Added
- Filter-aware result pipeline: protocol, country, availability and favorites filters are applied before every bulk copy/share/JSON export.
- Direct result-count selector: 25, 50, 100, 200, 500, 1,000 or all stored results (up to 5,000 loaded).
- Persistent WorkManager fallback for background monitoring every 15 minutes when Android permits network work.
- Hourly refresh of published public VLESS/SOCKS5 feeds during background monitoring.
- Parallel GeoIP lookup through ipwho.is, ipapi.co and ipinfo.io.

### Changed
- Result filtering and sorting run on Dispatchers.Default with a short input debounce.
- GeoIP enrichment is moved off the critical tunnel-check worker queue and processed separately in batches.
- Best verified and lowest-latency candidates remain prioritized for repeated checks.

### Notes
- Android controls exact background execution timing; WorkManager is not a real-time guarantee.
- Active IP/port checks remain limited to authorized ranges; public discovery uses published configuration feeds only.
- HAPP/Hiddify exports include only verified, supported configurations matching the active result filters.

## 1.9.3
- Сканирование нескольких IPv4/CIDR диапазонов за один запуск.
- До 4 диапазонов, максимум /24 на диапазон и 1024 уникальных адреса суммарно.
- Пересекающиеся диапазоны объединяются без повторных проверок.
- Обновлены подсказки и поле ввода диапазонов; ограничения и предупреждение об авторизованном сканировании видны в интерфейсе.

## 1.9.2
- Обновлена адаптивная иконка приложения: щит, глобус и сетевой радар.
- Обновлены фирменные цвета, статусные акценты и брендинг Scaner Proxy X.
- Сохранены applicationId, SQLite и SharedPreferences для установки поверх предыдущей версии при совпадении подписи.

# Changelog

## 1.9.1 (versionCode 12)

- First versioned update after 1.9.0.
- Android package identity remains `com.happ.proxyfinder` so this build can update an existing installation.
- The CI debug signing key remains stable between builds to preserve update compatibility.
- Existing SQLite and SharedPreferences data are retained; no storage migration is introduced in this release.

## 1.9.0 (versionCode 11)

- Previous baseline release.
