# Privacy Policy

**Effective date:** August 16, 2026
**Last updated:** August 16, 2026

VERTEX TECH SOLUTIONS ("we," "us," "our") operates the Socks5 Relay – VPN & Proxy mobile application (the "App"). This Privacy Policy explains what information the App accesses, how it's used, and the choices you have. By using the App, you agree to the practices described here.

If you have questions this policy doesn't answer, contact us at techinfo.vertex@gmail.com.

## 1. Summary

- The App does not require you to create an account.
- Your proxy settings (host, port, username, password) and saved favorites are stored **only on your device** — we do not receive or store them on any server we operate.
- The App connects directly to certain third-party services (listed below) to fetch proxy lists and test connectivity. Those services receive limited technical data (like IP addresses) as a normal part of how internet connections work — not because we send them anything.
- We do not sell your personal information.

## 2. Information We Do NOT Collect

We do not operate a backend server for this App, and we do not collect, receive, or store:
- Your name, email address, or other account information (no account is required).
- Your proxy credentials (host, port, username, password).
- Your browsing history or the content of traffic you route through any proxy.
- Your saved favorite proxies.

The data listed above is stored locally on your device (using Android's standard app-private storage) and is never transmitted to us.

## 3. Information the App Accesses on Your Device

To function, the App requests the following Android permissions:

| Permission | Why it's needed |
|---|---|
| VPN Service (`BIND_VPN_SERVICE`) | To create a local VPN interface that routes traffic through the proxy you configure. This is required by Android for any app that relays network traffic this way. |
| Internet / Network State | To connect to proxies and check network connectivity. |
| Foreground Service / Notifications | Android requires an active VPN connection to show a persistent notification, so you always know when the tunnel is active. |

Granting the VPN permission means the App can see network traffic passing through the tunnel while it's active, in order to relay it to your configured proxy. **We do not log, inspect, store, or transmit the contents of that traffic to ourselves or any third party.** The App is not a "no-log" *proxy service* itself — it is a client that connects you to a proxy server you choose, and that server's own logging practices are outside our control (see Section 5).

## 4. Third-Party Services the App Connects To

The App connects directly from your device to the following external services. We do not act as an intermediary for these connections — your device talks to them directly, and their own privacy practices apply to that connection.

- **Proxy servers you configure or select** (including any pulled from the free proxy list feature) — these are operated by third parties we do not control. See Section 5.
- **Public proxy list data providers** — the App fetches free proxy list data from public, third-party data sources on your behalf. This is a read-only request; no personal data is sent beyond standard network request metadata (e.g., your IP address, inherent to any internet connection). We intentionally do not expose the specific source endpoints within the App or this policy, to reduce the risk of misuse (such as scraping or overloading those public sources).
- **A third-party IP geolocation service** — used only when you tap "Test proxy," to confirm the proxy's exit IP/location. This request is made *through the proxy being tested*, not directly from your device.
- **Advertising network (Google AdMob)** — the App displays ads through Google AdMob to remain free to use. AdMob may collect device identifiers and usage data for ad delivery, personalization, and measurement, subject to your consent choices (see the in-app consent prompt) and Google's own privacy policy.

## 5. Free Public Proxies — Important Notice

The App includes a feature that lists free, publicly available proxy servers sourced from public third-party data providers. **These proxy servers are operated by unknown third parties, not by us.** When you choose to connect through one:
- That proxy operator can potentially see and, in the case of unencrypted traffic, modify the data passing through it.
- We have no visibility into, and no control over, what any given proxy operator logs, stores, or does with traffic that passes through their server.
- We do not vet, endorse, or guarantee the safety, legality, or reliability of any listed proxy.

We recommend not using free public proxies for sensitive activity (banking, login credentials, private communications). This is disclosed in the App and here for transparency, not as a limitation of liability — see the Terms of Service for the full disclaimer.

## 6. Data Storage and Security

All settings, credentials, and favorites are stored using Android's standard app-private storage (SharedPreferences / local files), which is sandboxed by the operating system and inaccessible to other apps under normal circumstances. This data is not encrypted at rest by default. If your device is rooted or compromised, this data could potentially be accessed by other software with elevated permissions. Do not store credentials for proxies you cannot afford to have exposed.

Uninstalling the App removes all locally stored data.

## 7. Children's Privacy

The App is not directed at children under 13 (or the relevant minimum age in your jurisdiction), and we do not knowingly collect personal information from children. If you believe a child has provided us information, contact us at techinfo.vertex@gmail.com and we will address it.

## 8. Your Choices and Rights

Because we do not collect personal data on our own servers, most data-subject requests (access, deletion, correction) are satisfied by managing data directly on your device — e.g., clearing the App's data or uninstalling it. If applicable law (such as GDPR or CCPA) grants you additional rights regarding any data we do process (see Section 4, e.g., ad SDK data), contact us at techinfo.vertex@gmail.com and we will respond consistent with applicable law.

## 9. International Users

The App and the third-party services it connects to (including proxies and the services listed in Section 4) may process data in countries other than your own, which may have different data protection laws than where you are located. By using the App, you understand and accept that your information may be processed outside of your home country.

## 10. Changes to This Policy

We may update this Privacy Policy from time to time. Material changes will be reflected by updating the "Last updated" date above, and, where required by law, we will provide additional notice. Continued use of the App after changes take effect constitutes acceptance of the updated policy.

## 11. Contact Us

Questions or concerns about this Privacy Policy:

**VERTEX TECH SOLUTIONS**
Email: techinfo.vertex@gmail.com
