# Android アプリ開発

複数の Android アプリを 1 つの Gradle ビルド（マルチモジュール）で管理するリポジトリ。

## 構成
```
settings.gradle.kts        # include でアプリを登録
gradle/libs.versions.toml  # 依存・プラグインのバージョン一元管理
apps/<アプリ名>/           # 各アプリ（1 アプリ = 1 モジュール）
libs/                      # 複数アプリで共有するもの（必要になったら追加）
docs/                      # ドキュメント
```

## アプリ一覧
| アプリ | モジュール | 概要 | ドキュメント |
|---|---|---|---|
| QR Scanner | `:apps:qrscanner` | 複数QRを同時認識して枠表示・履歴一覧 | [qrscanner.md](qrscanner.md) |

## 新しいアプリを追加する手順
1. `apps/<名前>/` を作成（`build.gradle.kts`、`src/main/...`）
2. `settings.gradle.kts` に `include(":apps:<名前>")` を追加
3. 依存は `gradle/libs.versions.toml` に追加して `libs.xxx` で参照

## ビルド・実行
- `JAVA_HOME` は Android Studio 同梱 JBR を指定: `C:\Program Files\Android\Android Studio\jbr`
- ビルド: `gradlew :apps:qrscanner:assembleDebug`
- インストール: `adb install -r apps/qrscanner/build/outputs/apk/debug/qrscanner-debug.apk`
