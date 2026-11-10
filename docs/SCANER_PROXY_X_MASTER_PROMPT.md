# Scaner Proxy X — Product & Engineering Master Prompt

## Mission
Develop Scaner Proxy X into a reliable Android proxy discovery, inventory, and diagnostics tool. Optimize for truthful results, fast search, clear controls, resilient storage, and professional UX. Never fabricate endpoints, latency, geolocation, protocol support, or throughput.

## Non-negotiable constraints
- Work only on `main`; never create branches.
- Preserve `applicationId=com.happ.proxyfinder`, stable signing, SQLite, SharedPreferences, existing imports/exports and HAPP compatibility.
- No sing-box/core integration, schema redesign, credential guessing, authentication bypass, or removal of existing functionality without explicit approval.
- No indiscriminate internet-wide active scanning. Internet-wide discovery means querying public feeds, indexed datasets, and authorized discovery APIs. Active TCP probing is restricted to explicitly supplied, authorized ranges, bounded by strict address/port/concurrency/time budgets.
- An open TCP port is only a candidate. A proxy is “working” only after a protocol-aware handshake and a successful end-to-end request for protocols actually supported by this app.
- Preserve last-known-good data on transient failures. Every result records source, timestamp, method, and confidence.

## Product architecture
1. Discovery: public config feeds, user-provided URLs/subscriptions, clipboard/file/HAPP import, deduplication, incremental ingestion, source health and source-level error reporting.
2. IP/port range scanner: multiple CIDR/range inputs, strict IPv4 validation, bounded aggregate host and port counts, cancellation, progress, configurable timeouts/concurrency within safe hard limits, and explicit authorization notice. Do not scan arbitrary global address space.
3. Candidate verification: DNS → TCP → TLS (where applicable) → protocol handshake → proxy tunnel → HTTP response. Each stage has its own result and latency; unsupported protocols remain “not fully verified”.
4. GeoIP: distinguish endpoint IP from proxy exit IP; show country/region/city/ASN/provider only when sourced; cache with timestamp and provider; handle disagreement and private/reserved addresses.
5. Search: search IP, IPv6, hostname, port, protocol, country, city, ASN, source, and status; debounced input; normalized IP/host matching; no blocking main thread.
6. Filters: protocol multi-select, country, status (working/tcp-only/untested/failed/stale), latency bands, favorites, source, IPv4/IPv6, recently checked; clear-all and active-filter count.
7. Sorting: verified quality, latency, country, last checked, discovery time; unknown values must sort last.
8. Settings: scan limits, timeout, concurrency within hard caps, port presets/custom ports, auto-check interval, data usage, GeoIP providers/cache, appearance, export behavior, privacy and permissions. Persist settings compatibly in SharedPreferences.
9. Results: show evidence and timestamps. Separate TCP reachable, protocol handshake passed, tunnel verified, and stale results.
10. Storage: retain SQLite/SharedPreferences; incremental writes; deduplicate by canonical endpoint/config; retain history and last-known-good state; no destructive migrations.
11. UX: dark premium network-intelligence design, accessible contrast, concise Russian labels, consistent iconography, responsive cards, clear empty/loading/error/cancelled states.
12. Performance: bounded coroutines, structured cancellation, no unbounded queues, pagination/lazy lists, cache, backoff, network-change handling, memory and response-size limits.

## Search and range semantics
- “Search all internet” is implemented through public data sources and authorized APIs, not by probing every IPv4/IPv6 address.
- Active range scans accept multiple CIDRs/ranges and port lists, deduplicate overlaps, calculate total work before start, enforce hard aggregate caps, and show exact scope.
- Reject malformed, excessive, multicast, unspecified, loopback, and other non-routable targets where inappropriate. Private network scans remain available for LAN diagnostics.
- Never attempt password guessing. Credentials may only be used when explicitly supplied by the user for systems they administer.

## Quality gates
- Write tests before changing behavior.
- Test CIDR boundaries, overlapping ranges, malformed input, cap enforcement, cancellation, deduplication, filter composition, sort stability, and preservation of last-known-good records.
- Run focused tests, full `./gradlew test`, and `:app:assembleDebug` through CI.
- Inspect CI logs and artifacts. Do not call an APK functional merely because CI passed; distinguish compile/test success from real-device and live-network validation.
- Bump versionCode/versionName for distributable changes; keep signing stable for in-place upgrades.
- Commit small, coherent increments to main and report exact commit, CI run, test result, APK artifact, and known limitations.

## Execution order
A. Audit current implementation and document contracts.
B. Improve range input and safe multi-CIDR scheduling.
C. Improve search indexing and filtering/sorting.
D. Consolidate settings and persist scan preferences.
E. Improve discovery source reliability and source health.
F. Improve staged diagnostics and GeoIP evidence.
G. Polish screen hierarchy, accessibility, and launcher identity.
H. Full regression audit, CI build, artifact verification, upgrade compatibility review.

## Definition of done
A feature is done only when its behavior is implemented, covered by tests, integrated into the existing UI, persists correctly where required, reports honest status, and passes CI. If a capability requires a protocol engine or external API that is not present, show it as unsupported/configuration-needed rather than simulating success.
