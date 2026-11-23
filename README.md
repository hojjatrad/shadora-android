# SHADORA — Independent Multi-Protocol Android VPN Client

SHADORA is an independent, extensible Android VPN client built with Kotlin, Jetpack Compose (Material 3), Room, and Clean Architecture + MVVM. It has zero mandatory dependency on external third-party control panels and supports full **Persian (RTL)** and **English (LTR)** interfaces.

## Supported Android Versions & CPU ABIs

- **Minimum Android**: Android 7.0 (API 24)
- **Target / Compile SDK**: Android 16 (API 36)
- **CPU ABIs**: `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`

## Honest Engine & Protocol Capability Matrix

SHADORA never marks a protocol as operationally supported unless its native engine binary is bundled and verified:

| Protocol(s) | Engine Adapter | Current Build Status | Notes / Native Core Integration |
| :--- | :--- | :--- | :--- |
| **WireGuard** | `WireGuardEngineAdapter` | **Native Bundled (`libwg-go.so`)** | Backed by official `com.wireguard.android:tunnel:1.0.20230706` (`GoBackend`). Full encrypted WireGuard tunneling works out of the box. |
| **VLESS, VMess, Trojan, Shadowsocks** | `XrayEngineAdapter` | **Parser + Xray JSON Compiler + Android TUN Ready** | Complete URI/JSON parsing, Reality/TLS/WS/gRPC compilation via `XrayJsonCompiler`, Android `VpnService` TUN, anti-leak DNS, IPv6 guard, Split Tunneling, and protected TCP reachability verification. Drop `libv2ray.aar` into `app/libs/` to enable full native Xray packet forwarding. |
| **AmneziaWG** | `AmneziaWgEngineAdapter` | **Parser + Obfuscation Params + Android TUN Ready** | Full INI parser & validation for `Jc`, `Jmin`, `Jmax`, `S1`, `S2`, `H1..H4`. Requires bundled `amneziawg-android` (`libawg-go.so`) for obfuscated UDP packet forwarding. |
| **OpenVPN** | `OpenVpnEngineAdapter` | **Secure `.ovpn` Parser + Android TUN Ready** | Parses `.ovpn` profiles and blocks dangerous shell-execution directives (`script-security`, `up`, `down`, `plugin`). Requires bundled `ics-openvpn` (`libovpnexec.so`) for full OpenVPN data channel. |
| **TUIC, Mieru, Lightway, Cloak** | `UnsupportedProtocolAdapter` | **Unsupported (No Native Engine)** | Accurately detected on import so configs are never misclassified, and explicitly reported as unsupported until licensed native cores are bundled. |

## Architecture Modules (Package Boundaries)

- `core/` — Domain models, `ConnectionState`, `TrafficSnapshot`, bilingual RTL/LTR localization (`ShadoraStrings`), formatting utilities.
- `security/` — `KeystoreCryptoManager` (Android Keystore AES-256-GCM), `SafeLogger` (credential redaction), `NetworkSecurityGuard` (HTTPS-only, SSRF private/metadata IP block, max 3 redirects, 5 MB payload cap).
- `data/` — Room `ShadoraDatabase` with 6 tables (`configurations`, `subscriptions`, `servers`, `connection_history`, `app_settings`, `engine_settings`) and `ShadoraRepository`.
- `configmanager/` — `ConfigParser`, `CountryDetector`, `XrayJsonCompiler`, `QrCodeHelper` (ZXing live CameraX + Photo Picker + QR generator).
- `subscriptionmanager/` — `SubscriptionManager` with SSRF-protected OkHttp client, Base64 decoding, `subscription-userinfo` quota parsing, and manual-config preservation.
- `enginemanager/` — `VpnEngineAdapter`, `EngineAdapters`, `EngineManager` (mutex-guarded lifecycle preventing concurrent conflicting engines).
- `vpnservice/` — `ShadoraVpnService` (`VpnService` + TUN + DNS anti-leak + IPv6 routing + Split Tunneling + Kill Switch + `ConnectivityManager.NetworkCallback` + Android 14+ `specialUse` foreground notification) & `ServerLatencyTester`.
- `ui/` — Luxury Obsidian Black, Neon Crimson Red & Metallic Silver Material 3 Compose UI (`HomeScreen`, `ServersScreen`, `SubscriptionsScreen`, `AddConfigScreen`, `SettingsScreen`, `AboutScreen`).

## Open-Source Dependencies & Licenses

- `com.wireguard.android:tunnel:1.0.20230706` — Apache-2.0
- `com.google.zxing:core:3.5.3` — Apache-2.0
- `com.squareup.okhttp3:okhttp:4.10.0` — Apache-2.0
- `androidx.room:*`, `androidx.camera:*`, `androidx.compose.*` — Apache-2.0
- `Vazirmatn`, `Space Grotesk`, `JetBrains Mono` fonts — SIL OFL 1.1
