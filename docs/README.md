# Android アプリ開発 検証リポジトリ

「いまの Android でどんなことができるか」を試すための検証用リポジトリ。
Android アプリと、連携する PC 側アプリ（.NET）を **1 アプリ = 1 フォルダ** にまとめて管理する。

## フォルダ構成
```
settings.gradle.kts         # Android アプリの登録（projectDir を apps/<名前>/android に向ける）
gradle/libs.versions.toml   # 依存・バージョンの一元管理
docs/                       # 全体共通のドキュメント（このファイル）
apps/
  <アプリ名>/
    android/                # Android アプリ（Gradle モジュール :<アプリ名>）
    pc/                     # PC 側（.NET: Server / Client / Contracts）。無いアプリもある
    tools/                  # 補助ツール（任意）
    docs/                   # このアプリ専用のドキュメント（README.md を必ず置く）
```

## アプリ一覧
| アプリ | 概要 | Android | PC | ドキュメント |
|---|---|---|---|---|
| qrscanner | 複数の QR / バーコードを同時認識して枠表示。PC へ送って表示 | `:qrscanner` | ScanMonitor | [apps/qrscanner/docs](../apps/qrscanner/docs/README.md) |
| camerastream | カメラ映像を Wi-Fi で PC へ送信（JPEG）。文字認識（OCR）の枠を重ねて表示 | `:camerastream` | CameraView | [apps/camerastream/docs](../apps/camerastream/docs/README.md) |
| webrtc-cam | カメラ映像を WebRTC でライブ配信し、画質を検証（計画中） | - | - | [apps/webrtc-cam/docs](../apps/webrtc-cam/docs/README.md) |

## 新しいアプリを追加する手順
1. `apps/<名前>/` を作り、`android/`（`build.gradle.kts`、`src/main/...`）、必要なら `pc/`、`docs/README.md` を置く。
2. `settings.gradle.kts` の `androidApps` に `<名前>` を追加する。
3. 依存は `gradle/libs.versions.toml` に追加し、`libs.xxx` で参照する。
4. 上の「アプリ一覧」に 1 行追加する。

## 開発環境（この PC）
- Android Studio（同梱 JBR を JDK として使用）、Android SDK は `%LOCALAPPDATA%\Android\Sdk`。
- 環境変数 `JAVA_HOME` = `C:\Program Files\Android\Android Studio\jbr`、`ANDROID_HOME` = SDK のパス。
- .NET SDK 8 / 9 / 10（検証アプリは net10.0）。
- 実機: Pixel 8a（Android 16 / API 36、arm64-v8a）。USB デバッグ有効。

## ビルド・インストール
```powershell
.\gradlew.bat :<アプリ名>:assembleDebug
adb install -r apps/<アプリ名>/android/build/outputs/apk/debug/<アプリ名>-debug.apk
```
PC 側（例）: `dotnet run --project apps/<アプリ名>/pc/src/<サーバー> --launch-profile http`

## 実機に入れるときの注意
- デバッグ署名は PC ごとに異なる。別の PC で入れたアプリは、署名が違うため上書きできない（`INSTALL_FAILED_UPDATE_INCOMPATIBLE`）。その場合はアンインストールするか、`debug.keystore` を揃える。