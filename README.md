# Socks5 Relay – VPN & Proxy (scaffold)

A from-scratch Android project demonstrating the same architecture pattern
we discussed: a `VpnService` that captures device traffic and relays it
through a SOCKS5 proxy, with your own UI/branding/package name.

This is **original code**, not a repackage of any existing app — it's meant
as a learning scaffold you extend, not a finished production VPN client.

## Structure

```
app/src/main/java/com/socksrelay/vertex/
  MainActivity.kt          UI: host/port/auth fields, Test Proxy, Logs, connect
  LogsActivity.kt           In-app log viewer (no adb needed)
  SocksVpnService.kt        Owns the tun interface + foreground notification
  log/
    AppLog.kt                In-memory log buffer with plain-English hints
  net/
    IpV4Packet.kt            IPv4 header parse/build + checksum
    TcpPacket.kt              TCP segment parse/build + checksum
    TcpSession.kt             Per-flow state (seq/ack bookkeeping)
    Socks5Client.kt           RFC 1928/1929 SOCKS5 CONNECT + auth handshake
    ProxyTester.kt            Standalone end-to-end proxy connectivity test
    UdpDnsProxy.kt            DNS-over-UDP passthrough
    PacketRouter.kt           Main tun read loop / dispatch
```

## New: Logs screen

Every meaningful event — VPN interface setup, each SOCKS5 handshake step,
each flow opening/failing, DNS lookups — is logged through `AppLog`, which
keeps the last 1000 entries in memory and shows them on the **Logs** screen
(tap "View logs" from the main screen). No adb required. Failures include a
plain-English hint about what they usually mean, e.g.:

```
14:32:07.112 [ERROR] Socks5Client: Could not reach proxy 127.0.0.1:1080: Connection refused
         → Check that: (1) the host/port are correct, (2) the SOCKS5 server is
           actually running, (3) if testing on the same machine, '127.0.0.1'
           means the PHONE itself, not your computer — use your computer's LAN
           IP address instead, and (4) your phone and the proxy are on a
           network that can reach each other (same Wi-Fi, or a public IP/port).
```

"Copy all" puts the full log text on the clipboard so you can paste it
somewhere (a notes app, a chat, etc.) if you want help debugging it.

## New: Test proxy (real end-to-end test)

The "Test proxy" button (see `ProxyTester.kt`) does a *complete* real test,
independent of the VPN entirely:

1. Opens a plain TCP socket to your SOCKS5 host/port.
2. Does the full RFC 1928 handshake (+ RFC 1929 username/password auth if
   you filled those fields in).
3. Asks the proxy to `CONNECT` to `example.com:80`.
4. Sends a real `HEAD / HTTP/1.1` request through the resulting tunnel.
5. Confirms a real HTTP response comes back.

If this passes, your proxy is proven to work end-to-end — so if the VPN
still shows "no internet" afterwards, the bug is in the VPN's packet
routing (`PacketRouter`/`SocksVpnService`), not your proxy configuration.
That split is the fastest way to know which half of the system to debug.
This test runs as the app's own normal traffic — it does **not** go through
the app's own tunnel even while connected, since `SocksVpnService` excludes
the app's own package from the VPN.

## New: Username/password auth

`Socks5Client` now supports RFC 1929 username/password authentication.
Fill in the "Proxy authentication" fields in the app (leave blank for a
no-auth proxy) — they're used both by "Test proxy" and by the real VPN
connection.

## Fixed: `protect()` vs `addDisallowedApplication()` conflict

An earlier version of this scaffold called both `builder.addDisallowedApplication(packageName)`
*and* `protect()` on each relay socket. On real devices this combination
made `protect()` fail 100% of the time — the two mechanisms overlap, and a
socket belonging to an already app-excluded process has "nothing to
protect" as far as the tunnel is concerned. This scaffold now relies on
`protect()` alone (the standard approach used by `ToyVpn` and most real VPN
apps) and does **not** call `addDisallowedApplication` for its own package.

## Fixed: `protect()` still failing after the above (fd lifecycle + device lockdown)

Removing `addDisallowedApplication` didn't fully fix it on real hardware —
logs showed `protect()` failing 100% of the time for TCP sockets while the
UDP/DNS socket's `protect()` succeeded. That split was the key clue: a
plain `java.net.Socket()` doesn't necessarily have a real underlying OS
file descriptor until it's bound or connected (created lazily on many
Android versions), while `DatagramSocket()`'s no-arg constructor binds
immediately — so `protect()` had nothing real to mark for TCP sockets.
Two changes together fix this:

1. **`Socks5Client`** now calls `socket.bind(InetSocketAddress(0))` before
   `protect()`, forcing the fd to exist.
2. **`net/VpnProtect.kt`** replaces the direct `protect(Socket)` /
   `protect(DatagramSocket)` calls with a more reliable path: duplicate the
   fd via the public `ParcelFileDescriptor.fromSocket()` /
   `fromDatagramSocket()` API, call the low-level `protect(int fd)`
   overload on the duplicate, then close the duplicate. This is the same
   workaround several real-world open-source Android VPN clients use for
   exactly this device-inconsistency.

**If `protect()` still fails after both of these**, the Logs screen will
now point at one more real possibility: Android's **"Block connections
without VPN"** setting (Settings → Network & internet → VPN → gear icon
next to any VPN app, including this one). When enabled for *any* VPN app
on the device, it forbids all traffic from bypassing a tunnel — which
breaks `protect()` by design, since its whole job is to let specific
sockets bypass the tunnel. Check that toggle is off for every VPN-capable
app listed, not just this one.

## New: paste a proxy string directly

The "Paste proxy" field at the top accepts either:
```
ip:port
ip:port@username:password
```
Tap "Fill fields from pasted text" and it parses that into the individual
host/port/username/password fields below (see `ProxyStringParser.kt`).

## New: settings persist across restarts

Host/port/username/password are saved to `SharedPreferences`
(`SettingsStore.kt`) whenever you tap Connect, Test proxy, or "Fill fields
from pasted text", and also on every `onPause()` as a backstop — so
reopening the app picks up right where you left off instead of showing the
placeholder defaults again. Note: this uses plain unencrypted
`SharedPreferences`, fine for a personal test proxy on your own device; see
the doc comment in `SettingsStore.kt` if you want it encrypted instead.

## Improved: proxy test now reports exit IP/ISP/location

"Test proxy" (`ProxyTester.kt`) now does two real checks through the
tunnel: an HTTP reachability check against `example.com`, then a lookup
against a public IP geolocation API (`ip-api.com`) *through the same
proxy*, reporting back the exit IP, city, region/state, zip, country,
timezone, and ISP — solid evidence of exactly where your traffic is
actually exiting from, not just "a TCP handshake succeeded somewhere."

## New: dark/light theme

Tap the sun/moon icon top-right on the main screen → System default / Light
/ Dark. Saved via `SettingsStore` and applied at process startup in
`SocksRelayApp` (an `Application` subclass) so there's no flash of the
wrong theme on launch. Works automatically because the app's base theme is
`Theme.MaterialComponents.DayNight` — background/surface/text colors adapt
on their own; only the brand accent color stays fixed across both modes.

## New: HTTP and SOCKS4 proxy support

The relay now speaks three protocols, not just SOCKS5 — pick one with the
radio buttons on the main screen:
- `net/Socks5Client.kt` — unchanged, RFC 1928/1929.
- `net/Socks4Client.kt` — SOCKS4/4a (CONNECT only; SOCKS4 has no real
  password auth, just an optional "user ID" string some proxies check).
- `net/HttpProxyClient.kt` — HTTP `CONNECT` tunneling (RFC 7231 §4.3.6),
  with `Proxy-Authorization: Basic` if you supply credentials.
- `net/ProxyClient.kt` — dispatches to whichever of the three based on the
  selected protocol; `PacketRouter`, `ProxyTester`, and the free-proxy
  "Test" button all go through this instead of any protocol-specific
  client directly.

## New: rebranded color palette + monogram icon

Replaced the single sharp blue (#2962FF) with a softer two-tone palette:
`brand_primary` (#4E91FD) as the main surface color, `brand_primary_dark`
(#2C2CFF) as accent/status-bar. Both live in `colors.xml` and are pulled
into `themes.xml` and the launcher icon — one place to change if the
palette needs adjusting again.

The launcher icon now has "SR" inside the shield, built as a simple
blocky/pixel-style monogram (`ic_launcher_foreground.xml`) rather than
smooth font curves — deliberately, since thin curved letterforms often
blur into illegibility at real launcher-icon sizes, while a blocky grid
stays crisp.

## New: Settings screen (theme moved here, + language, split tunneling, about)

Tapping the gear icon (top-right on the main screen, replacing the old
standalone theme icon) opens a proper Settings screen:

- **Theme** — same picker as before, just relocated.
- **Language** — a real switcher (`LanguageManager.kt`, wrapping AndroidX's
  per-app language API — it handles persistence and Activity recreation
  itself). Ships with English and Spanish (`res/values-es/strings.xml`,
  full key parity with the default). Adding another language is: create
  `res/values-<code>/strings.xml` with the same keys translated, add a
  `<locale>` line to `res/xml/locales_config.xml`, and add it to
  `LanguageManager.SUPPORTED_LANGUAGES`. Worth a professional/native
  review pass on the Spanish (and any future) translations before wide
  release — these were done directly, not by a native speaker.
- **Split tunneling** — a real, functioning implementation, not just a
  toggle: when on, pick specific apps (`AppListActivity.kt`, using a
  `<queries>` manifest block to see launchable apps without the more
  sensitive `QUERY_ALL_PACKAGES` permission) and only those apps' traffic
  uses the tunnel — everything else bypasses it. **Important correctness
  detail**: this uses `addAllowedApplication()`, the same *category* of
  app-level tunnel-scoping mechanism as `addDisallowedApplication()` —
  which we'd already learned breaks `protect()` when it excludes this
  app's own traffic (see the "protect() vs addDisallowedApplication()"
  section above). Same risk applies here in reverse, so
  `SocksVpnService.applySplitTunneling()` always adds this app's own
  package to the allow-list alongside whatever the user picks, keeping
  its own relay sockets "inside the tunnel's jurisdiction" so `protect()`
  keeps working. This app itself is hidden from the picker UI since it's
  always automatically included, not a real choice.
- **About** — app version (from `BuildConfig.VERSION_NAME`), Contact Us,
  Privacy Policy, Terms of Service, About Us. These render the markdown
  documents drafted earlier, **bundled as assets** (`app/src/main/assets/`)
  and rendered by a minimal from-scratch markdown-to-styled-text renderer
  (`MarkdownLite.kt` — headers and bold only, no external library, since
  that's all these four documents actually use). Bundled rather than
  fetched from a URL because none of them are hosted anywhere yet; swap
  to a WebView + real URL once they are, if you'd prefer that.

## New: real ads require a signed release build

Seeing test ads after installing is expected, not a bug — `BuildConfig.DEBUG`
controls which ad IDs get used (see `ads/AdIds.kt`), and Android Studio's Run
button / `./gradlew installDebug` both produce **debug** builds every time.
Real ads only ever appear in a **release** build.

Release builds also need to be signed to install on a device at all (unlike
debug builds, which get an automatic throwaway signature) — `build.gradle.kts`
now loads a signing config from `keystore.properties`, gitignored, never
committed. Steps:

1. **Generate your own keystore** (do this once — keep the resulting `.jks`
   file and passwords somewhere safe and private; losing it later means you
   can't publish updates to the same Play Store listing anymore):
   ```
   keytool -genkeypair -v -keystore release-key.jks -keyalg RSA -keysize 2048 -validity 10000 -alias socksrelay
   ```
   Run this from the project root — it'll prompt for passwords and some
   identity fields (name/org/etc., can be anything for now).

2. **Copy the template and fill it in:**
   ```
   cp keystore.properties.example keystore.properties
   ```
   Edit `keystore.properties` with the real `storePassword`/`keyPassword` you
   just set, and confirm `storeFile` points at the `.jks` file from step 1.

3. **Build and install the release APK:**
   ```
   ./gradlew assembleRelease
   adb install app/build/outputs/apk/release/app-release.apk
   ```

4. **Check the in-app Logs screen after opening the app** — `AdManager`
   logs every ad load success/failure (tag `AdManager`), so you can confirm
   real ad unit IDs are actually being requested and whether they're
   filling, rather than just guessing from what you see on screen.

**Two things that can still limit what you see even on a correctly signed
release build, and aren't code issues:** brand-new AdMob accounts/ad units
sometimes have lower fill rates for a while as Google's system builds up
demand data for them, and Google occasionally serves "house ads" (blank or
generic filler) instead of a paid ad when no real bid wins the auction.
Neither means anything's broken — give it a few days of real usage before
concluding a placement isn't working.

## New: AdMob monetization (real ad unit IDs wired in)

Your real App ID and four ad unit IDs are live in the code, gated behind
`BuildConfig.DEBUG` — **debug builds always use Google's official sample
test IDs, release builds use your real ones, automatically.** Never build
a debug APK expecting real ads, and never tap through real ad units from
your own dev device repeatedly — that's exactly the "invalid traffic"
pattern that gets AdMob accounts suspended. See `ads/AdIds.kt`.

**Where each placement fires:**

| Placement | Trigger | File |
|---|---|---|
| `Rewarded_ProxyUnlock` | Tapping "Watch ad to unlock" on a country's proxy list | `CountryProxiesActivity.kt` |
| `Interstitial_CountryView` | Tapping into a country from the country list | `CountryListActivity.kt` |
| `Interstitial_PreConnect` | Tapping Connect, **before** `VpnService.prepare()`/starting the tunnel | `MainActivity.kt` |
| `Interstitial_Engagement` | MainActivity returning to the foreground, cooldown-gated | `MainActivity.kt` |

**The VPN-routing conflict we discussed is handled structurally, not just
by convention:** the pre-connect interstitial fires *before* the tunnel
exists, so that ad request always goes over the real network — never
through whatever proxy the user's about to connect to. The engagement and
country-view interstitials are preloaded well in advance (at SDK init and
on entering the country list, respectively), so their network-heavy part
(the ad creative download) has almost always already happened before any
connection exists, even though *showing* a preloaded ad doesn't need a
fresh network request either way.

**Freemium unlock**: only the first 3 proxies per country show by default;
watching the rewarded ad unlocks the rest for that country, persisted
locally (`freeproxy/UnlockStore.kt`) — once unlocked, stays unlocked,
rather than re-nagging every visit. Change that in one place if you'd
rather it expire and re-lock periodically for more ad revenue.

**Frequency capping** (`ads/AdPlacements.kt`, backed by
`ads/AdFrequencyGuard.kt`, persisted across restarts): country-view interstitial
max once per 3 minutes, pre-connect max once per 10 minutes, engagement max
once per 5 minutes. These are starting points, not tuned against real
data — adjust once you can see actual session lengths and retention impact
in AdMob's console.

**Consent (GDPR/UK)**: `ads/ConsentManager.kt` wraps Google's User
Messaging Platform SDK and runs before `AdManager` initializes anything.
Required for users in those regions; a no-op elsewhere. This needs to be
reflected in the Privacy Policy draft from earlier — the ad-network
placeholder section there should get filled in with AdMob specifics now
that it's actually integrated.

**Not yet done, worth knowing:** the `AndroidManifest.xml`/build changes
here haven't been compiled in this environment (same limitation as
everything else in this scaffold — no network access to resolve Gradle
dependencies). Also worth adding before shipping: a `proguard-rules.pro`
entry if you ever flip `isMinifyEnabled = true` (AdMob ships its own
consumer ProGuard rules so this is usually automatic, but verify after
enabling minification), and a review of Play Console's Data Safety form
once ads are live, since AdMob collects device/advertising identifiers
that need disclosing there.

## New: US/UK pinned to top, dead-proxy pruning, per-country refresh + protocol filter

- **Default countries** — United States and United Kingdom always appear
  first in the country list, followed by everyone else alphabetically
  (`FreeProxyRepository.groupedByCountry()`, `PINNED_COUNTRY_CODES`).
- **Dead proxies auto-removed** — every refresh (manual or the 15-minute
  auto-refresh, both go through the same `refresh()` function) now runs
  (historical — see "Proxy sources & verification (current)" at the bottom) a fast, parallel raw TCP-connect
  check against every fetched proxy, dropping anything that doesn't even
  accept a connection. This is deliberately *not* a full protocol
  handshake (that's what the Test button is for, and doing that
  automatically for hundreds of proxies on every refresh would be far too
  slow) — passing means "something's listening," not "the proxy protocol
  itself works." One caveat: this check is skipped while the VPN is
  actively connected, since these are plain sockets that would otherwise
  get captured by your own tunnel — testing candidate proxy B's
  reachability *through* proxy A gives meaningless results. Pruning
  resumes automatically once you disconnect.
- **Refresh button per country** — the country proxies screen has its own
  Refresh button now, which triggers the same global refresh and
  re-displays this country's slice once it completes (live via a
  `FreeProxyRepository` listener, same pattern as the country list screen).
- **Protocol filter** — three multi-select chips (SOCKS5 / SOCKS4 / HTTP)
  on the country proxies screen; any combination, including all three or
  none. `FavoritesActivity` got split off into its own simpler layout
  (`activity_favorites.xml`) in the process, since a "refresh the live
  upstream list" button never made sense on a user-curated favorites
  screen.

## Diagnosed: iplocate still returning 0 even on "successful" fetches

After the 503-throttle fix, logs showed something more specific: some
countries failed loudly (503, timeout) but *most* fetched successfully
with no error logged — and still contributed zero proxies. That means the
failure wasn't fetching, it was the **cross-referencing step**: joining
`protocols/*.txt` against `countries/*/proxies.txt` by exact `ip:port`
string match, which apparently wasn't matching for any entry even when
both files fetched fine. Rather than keep debugging a fragile join between
two independently-maintained files, this was the trigger to add sources
that don't have that problem at all (see below) — iplocate is kept as a
supplementary source, now isolated so its issues can't zero out everything
else.

## New: three-source merge with cross-source dedup

Added the two sources you asked for, evaluated by data format first:

- **`vakhov/fresh-proxy-list`** (`VakhovSource.kt`) — a single
  semicolon-delimited CSV, hosted on GitHub *Pages*
  (`vakhov.github.io`, a different host than `raw.githubusercontent.com`
  or `api.github.com`, so no shared rate-limit exposure). Every row already
  has protocol flags *and* country together — no cross-referencing needed.
- **`proxifly/free-proxy-list`** (`ProxiflySource.kt`) — a single JSON file
  served via jsDelivr's CDN (`cdn.jsdelivr.net`), also self-contained
  (protocol + country per record). **Caveat**: the exact file path/field
  names are inferred from the repo's documented URL pattern, not
  byte-for-byte confirmed — if the Logs screen shows this source
  contributing 0 after a real refresh, that's the first place to check
  against the actual repo.
- **`iplocate/free-proxy-list`** (`IplocateSource.kt`) — kept, unchanged
  logic, now just one of three independent contributors instead of the
  only source.
- **Not added**: `proxifly.dev/tools/proxy-list` — that's a webpage/
  interactive tool, not a data file suitable for scheduled background
  fetching (likely a rate-limited or session-based API meant for manual
  browser use). The GitHub repo above is the right way to get the same
  data programmatically, and that's what's wired up instead.

All three run in parallel (`FreeProxyFetcher.kt`), and one source failing
entirely no longer zeroes out the others — a real improvement over the
single-source design, where any one upstream hiccup took down the whole
feature.

**Duplicates**: `FreeProxyFetcher` merges all three sources' results into
one list and deduplicates by `(protocol, host, port)` across all of them
combined, not per-source — so the same proxy appearing in two source lists
(common, since these lists overlap) only shows once.

## Fixed: every country fetch returning HTTP 503

The previous "make it fast" pass raised concurrency to 32 simultaneous
requests. Turns out `raw.githubusercontent.com` treats a sudden burst like
that as abuse and throttles it — returning `503` for *every* request fired
at once, not just some. Logs showed exactly that: protocol map fetch fine
(only 4 files), country discovery via the GitHub API fine (1 request), but
all 31 attempted country files failing with 503 simultaneously.

Fixed by dialing concurrency back down to a modest, sustainable level
(`PARALLELISM = 6`) and adding short exponential-backoff retries
specifically for `503`/`429`/5xx responses (`fetchText(..., retryOnThrottle
= true)`) — fast when the CDN's not under pressure, and self-corrects with
a brief pause + retry when it briefly pushes back, instead of just failing
outright.

## Fixed: proxy count stuck at zero, "no proxies available yet" every time

Even after the "Unknown" fix above, country discovery still went through a
single call to GitHub's Contents API (`api.github.com/.../contents/countries`)
to list which country folders exist. That API allows only **60
unauthenticated requests per hour, per IP address** — a budget that gets
burned through fast between manual refreshes, the 15-minute auto-refresh,
and repeated app restarts while testing. Once exhausted, every subsequent
refresh silently discovered zero countries and returned nothing, with no
obvious error — exactly the symptom in the screenshots.

Fixed by removing that single point of failure: `FreeProxyFetcher` now
tries the GitHub API first (fast when it works — one request), but falls
back to directly probing the full list of ISO 3166-1 country codes against
`raw.githubusercontent.com` (a CDN, not subject to that quota) if the API
call fails for any reason. Countries the repo doesn't have just 404 quickly
and are skipped — not logged as errors.

**Also made it fast, as requested**: every country/protocol file is now
fetched in parallel (a 32-thread pool) instead of one at a time with an
artificial delay between each. Combined with the fallback above no longer
having a dead end, a full refresh should now take a few seconds rather than
never completing.

**On browsing without connecting**: this already worked by design — the
proxy list loads via `FreeProxyRepository` independent of VPN state, and
Copy/Test in the country/favorites screens use plain sockets that don't
require (or go through) the VPN tunnel. It looked broken only because the
fetch itself was silently failing; with that fixed, browsing, copying, and
testing free proxies without ever connecting now works as intended.

## Fixed: all proxies showing as "Unknown", country page opening blank

Two real bugs from the previous round, both now fixed:

1. **Every proxy landed in "Unknown"** — the old country-detection step
   made a direct `HttpURLConnection` request to `http://ip-api.com/batch`
   (plain HTTP, no TLS). Android blocks cleartext HTTP traffic by default
   for apps targeting API 28+ when it goes through `HttpURLConnection`/
   `HttpsURLConnection` — so every geo-IP lookup silently failed, and every
   proxy ended up with no country. (Raw `Socket` traffic — the actual
   SOCKS/HTTP-CONNECT relay code and `ProxyTester`'s in-tunnel checks — is
   *not* subject to this restriction, only recognized HTTP-stack calls are,
   which is why that part kept working fine.)
2. **Tapping a country opened a blank white screen** — a knock-on bug:
   proxies with no country ended up in a synthetic "Unknown" group using
   the sentinel code `"??"`, but the actual `FreeProxy` objects still had
   `countryCode = null` — filtering the next screen by `"??"` matched
   nothing.

Both are moot now because the proxy source changed (see below) to one that
ships accurate, pre-sorted per-country files directly — no geo-IP lookup
step exists anymore, so there's no cleartext call to fail and no "Unknown"
bucket to mismatch.

## New: dark/light theme

Tap the sun/moon icon top-right on the main screen → System default / Light
/ Dark. Saved via `SettingsStore` and applied at process startup in
`SocksRelayApp` (an `Application` subclass) so there's no flash of the
wrong theme on launch.

## New: footer on every screen

"Powered by VERTEX TECH SOLUTIONS" now appears pinned at the bottom of
every screen (`res/layout/footer.xml`, included via `<include>`).

## New: favorite proxies

Every proxy row (in the country browser or favorites screen) has a star
next to its `ip:port`. Tapping it saves/removes that proxy via
`freeproxy/FavoritesStore.kt` (a small JSON file in app-private storage,
independent of the refreshable proxy-list cache, so favorites survive even
if a proxy later drops off the upstream list). The "Favorites" button on
the main screen opens a dedicated screen listing everything starred, with
the same Copy/Test actions.

## Changed: sorting

- Countries are sorted alphabetically by name (was: by proxy count).
- Within a country, proxies are sorted by protocol, then host, then port —
  so SOCKS5/SOCKS4/HTTP entries are grouped together, not interleaved.

## Removed: the in-app risk warning banner

Per your request, the "Browse free proxies" screen no longer shows the
warning banner — just flags, country names, and counts, straight to the
list. The underlying explanation is kept in this README instead of the UI.

## Free proxy list

Tap "Browse free proxies" on the main screen: a list of countries (flag +
name + count), sorted alphabetically. Tap a country to see its proxies,
sorted by protocol then host, each with:
- **Copy** — puts `ip:port` or `ip:port@user:pass` on the clipboard, ready
  to paste into the main screen's paste field.
- **Test** — runs the same real `ProxyTester` check used elsewhere.
- **☆ / ★** — save/remove as a favorite (see below).

Sources: see "Proxy sources & verification (current)" at the bottom — the
list now comes from three public GitHub-hosted lists of free, unauthenticated
proxies, and only proxies that pass a live check are shown.

**A note on privacy, since I removed the in-app warning banner per your
request:** these are proxies run by people you don't know. Some are fine
for casual testing; some log everything that passes through, and
unencrypted traffic through any of them can be read or modified in
transit. Worth knowing before routing anything sensitive through one.

`freeproxy/` package:
- `FreeProxyFetcher.kt` — fetches + cross-references the protocol/country
  files (see above), skips anything that doesn't parse into a valid IPv4
  host + 1–65535 port, dedupes by `(protocol, host, port)`.
- `FreeProxyRepository.kt` — the single source of truth: fetch → on-disk
  JSON cache (`filesDir/free_proxy_cache.json`) → in-memory list, with
  listeners so all screens update live. Started from
  `SocksRelayApp.onCreate()`, refreshes immediately, then every 15 minutes
  via a self-rescheduling `Handler`, for as long as the app process stays
  alive. This is **not** a `WorkManager` job — if Android kills the
  process, refreshing pauses until you reopen the app, at which point the
  cache loads instantly while a fresh fetch runs in the background. Swap
  in `WorkManager` if you need refreshing to survive process death too.
- `CountryFlags.kt` — converts an ISO country code to its flag emoji using
  the standard Unicode regional-indicator-symbol trick — no image assets.
- `FavoritesStore.kt` — persists starred proxies to their own small JSON
  file, independent of the refreshable cache above (so favorites survive
  even if a proxy later drops off the upstream list).
- No live connectivity check runs on every fetched proxy automatically
  (hundreds of proxies × multi-second timeouts would make every refresh
  take minutes) — "filter invalid" here means format/structural validity.
  Use the per-proxy **Test** button to check if a specific one actually
  works right now.

## How it works

1. `MainActivity` calls `VpnService.prepare()` to get the user's one-time
   consent, then starts `SocksVpnService`.
2. `SocksVpnService` builds a tun interface with `Builder().addRoute("0.0.0.0", 0)…establish()`
   — this is what makes it "whole device" (change to
   `addAllowedApplication()` calls instead if you want per-app scoping).
3. `PacketRouter` reads raw IP packets off the tun fd:
   - New TCP `SYN` → opens a real socket to the SOCKS5 proxy
     (`Socks5Client`), does the RFC 1928 handshake (+ auth), and asks it to
     `CONNECT` to the flow's actual destination. `VpnService.protect()`
     is called on that socket so its own traffic doesn't loop back into
     the tun it just created.
   - Subsequent packets on that flow → payload bytes get written straight
     to the SOCKS5 socket; bytes coming back from the socket get wrapped
     into spoofed TCP segments and written back into the tun.
   - UDP port 53 → resolved via a plain protected UDP socket to a public
     resolver (not proxied — see limitations below).

## Known limitations (this is a scaffold, not production code)

- **TCP handling is simplified**: no retransmission, no reordering, no
  window scaling, one segment in flight at a time. It works for typical
  request/response traffic but will misbehave under packet loss or with
  finicky servers. For production quality, use a real user-space network
  stack (e.g. gVisor's `netstack`, or wrap an existing native `tun2socks`)
  instead of hand-rolling more of this.
- **Only DNS is handled over UDP**; other UDP (QUIC/HTTP3, WebRTC, games)
  is currently dropped. Add a SOCKS5 `UDP ASSOCIATE` relay if you need it.
- **DNS bypasses the proxy** — it goes straight to `1.1.1.1`. Route it
  through the SOCKS5 proxy's UDP ASSOCIATE too if you want DNS queries to
  also go via the proxy.
- **No IPv6** — IPv6 packets are silently skipped by `PacketRouter`. If
  your test network prefers IPv6 (many mobile carriers do), that alone can
  look exactly like "connects but no internet" even with a working proxy.
  Worth checking against a Wi-Fi network first while you're debugging.
- Never actually built/run (no network access in the environment this was
  written in) — expect to fix small compile issues on first build.

## Debugging checklist (no adb needed)

1. Tap **Test proxy** first, before ever connecting the VPN. If it fails,
   fix that before touching the VPN at all — check the Logs screen for the
   hint under the error.
2. If Test proxy **passes** but the VPN still shows no internet after
   connecting and browsing for a bit, open **View logs** and look for lines
   from `PacketRouter` — specifically "New connection: ..." and "Failed to
   open flow..." lines, which show exactly which destinations are being
   attempted and why they fail.
3. If you see **no `PacketRouter` activity at all** while browsing, the
   problem is upstream of the proxy logic entirely — either the tun
   interface isn't really capturing traffic, or (see IPv6 note above) the
   device is only trying IPv6 destinations.

## Building

Needs Android Studio (or the command-line SDK) with network access to
resolve Gradle/Maven dependencies — this sandbox has neither, so the
project hasn't been compiled or tested. Standard flow:

```
./gradlew assembleDebug
```

Then install on a device/emulator and grant the VPN permission prompt when
you tap Connect.


## Bottom banner ad & ad logging (added)

- Every screen includes `layout/footer.xml`, which now contains the banner slot
  (`bannerAdContainer`). `SocksRelayApp` registers lifecycle callbacks that call
  `AdManager.attachBanner()` for each screen, so no per-screen code is needed.
- The banner ad unit ID is set in
  `REAL_BANNER` in `ads/AdIds.kt` (release builds; debug builds use
  Google's test banner).
- Like every other placement, the banner is never requested while the VPN is
  connected; it is removed on connect and reloaded after disconnect.
- Ad code logs only generic text ("Ad failed to load", "Ads paused while VPN is
  connected"). `AppLog` additionally redacts anything that looks like an ad unit
  ID before it reaches the Logs screen or Logcat.


## Proxy sources & verification (current)

The older sections above describe earlier iterations (Proxifly, Hproxy and
iplocate sources and a TCP-connect-only filter). The current design:

- **Sources:** `VakhovSource` ("Source 1", includes country), `MonosansSource`
  ("Source 2") and `TheSpeedXSource` ("Source 3") — the last two are plain
  `ip:port` lists (`ProxyListParser`), so their country is looked up only for
  proxies that pass verification (`GeoResolver`, HTTPS lookups with fallbacks).
- **Only working proxies are shown.** `ProxyVerifier` does TCP connect ->
  SOCKS5/SOCKS4/HTTP-CONNECT handshake -> a real `GET /generate_204`
  through the tunnel, which must return exactly `204`. `VerificationSession`
  runs 300 of these in parallel, checking the on-screen list first, then
  Source 2, Source 1, Source 3 (shuffled), with a 90 s budget.
- **Dead proxies are pruned continuously:** on every refresh (15 min), a quick
  re-check of the on-screen list every 3 min, a per-country re-check whenever
  a country screen opens, and immediately when a Test fails.
- Verification is paused while the VPN is connected (it would test through the
  user's own tunnel). Lists are sorted fastest-first by measured latency.
