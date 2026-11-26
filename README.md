# Tap to Share

[日本語](#日本語) | [English](#english)

---

## 日本語

**Tap to Share** は、NFC で端末をかざすだけでリンク・テキスト・写真・動画・ファイル・フォルダを端末間で直接共有できる Android アプリです。

### 主な特徴

- **NFC のみで即座にテキスト・URL を共有**
  短文テキストや URL は、NFC（HCE + NDEF）だけを使って瞬時に相手端末へ共有できます。
- **ファイル・フォルダの高速ダイレクト転送**
  写真・動画・各種ファイル・フォルダは、同一 Wi-Fi（LAN）または Wi-Fi Direct（ルーター・インターネット不要）を利用して高速に直接転送します。
- **暗号化・認証付きのプライベート転送**
  AES-GCM によるチャンク単位の暗号化および認証付き転送に対応しています。外部サーバーは一切使用せず、端末間のみで完結します。
- **快適な使い勝手とカスタマイズ**
  - 初回起動時のオンボーディング（4ステップ）
  - 外観モード切り替え（システム設定に従う / ライト / ダーク）
  - ダイナミックカラー（Material You）対応
  - 送受信履歴の管理
  - 受信時の自動アクション設定（URL を自動で開く・クリップボードへコピーなど）

### 動作要件（Requirements）

- **OS**: Android 10（API 29）以上
- **ハードウェア**: NFC および NFC HCE（Host-based Card Emulation）対応端末
- **権限・OS バージョン別の補足**:
  - **Android 13 以降**: 位置情報権限は不要です（`NEARBY_WIFI_DEVICES` 権限のみで動作します）。
  - **Android 10〜12**: OS の仕様上、Wi-Fi Direct の検出に「位置情報」権限が必要です。
  - **Android 15 以降**: 高度な NFC 干渉抑制に対応しています。

### ビルド手順（Build）

Android SDK（`ANDROID_HOME` または `local.properties`）が設定された環境で、プロジェクトルートから以下を実行します。

```powershell
# Debug APK のビルド
.\gradlew.bat assembleDebug

# ユニットテストの実行
.\gradlew.bat testDebugUnitTest
```

macOS / Linux の場合は `.\gradlew.bat` の代わりに `./gradlew` を使用してください。

### ライセンス

このプロジェクトは [MIT License](LICENSE) の下で公開されています。

---

## English

**Tap to Share** is an Android app that lets you directly share links, text, photos, videos, files, and folders between devices simply by tapping them together via NFC.

### Features

- **Instant Text & URL Sharing over NFC**
  Short text and URLs are shared immediately using NFC (HCE + NDEF) alone—no Wi-Fi setup required.
- **High-Speed Direct File & Folder Transfer**
  Photos, videos, files, and folders are transferred directly at high speed over the same Wi-Fi network (LAN) or via Wi-Fi Direct (no router or internet connection required).
- **Encrypted & Authenticated Peer-to-Peer Transfer**
  Transfers are protected with chunk-level AES-GCM authenticated encryption. No external servers are used; all data moves directly between devices.
- **Thoughtful UX & Customization**
  - 4-step first-launch onboarding
  - Theme mode selection (System / Light / Dark)
  - Dynamic Color (Material You) support
  - Transfer history
  - Configurable automatic actions on receive

### Requirements

- **OS**: Android 10 (API 29) or higher
- **Hardware**: Device with NFC and NFC HCE (Host-based Card Emulation) support
- **Permissions & OS Notes**:
  - **Android 13+**: Location permission is not required (uses `NEARBY_WIFI_DEVICES` only).
  - **Android 10–12**: Location permission is required by Android OS specifications for Wi-Fi Direct discovery.
  - **Android 15+**: Supports enhanced NFC interference mitigation.

### Build

With the Android SDK configured (via `ANDROID_HOME` or `local.properties`), run the following commands from the project root:

```powershell
# Build Debug APK
.\gradlew.bat assembleDebug

# Run unit tests
.\gradlew.bat testDebugUnitTest
```

On macOS / Linux, use `./gradlew` instead of `.\gradlew.bat`.

### License

This project is licensed under the [MIT License](LICENSE).
