# QRスキャナーアプリ 開発計画（`:apps:qrscanner`）

## 目的
Android スマホ用アプリを開発する。アプリを起動するとカメラが開き、映った **大量のQRコードを同時に認識** し、
認識したQRの枠をカメラ映像上に囲って表示しつつ、読み取った内容を **履歴としてどんどん一覧表示** していく。

## 要件
- 起動と同時にカメラプレビューを表示する
- 画面内に複数あるQRコードを同時に検出する
- 検出したバーコード（QR / JAN）の周囲に枠（外形）をオーバーレイ描画する
- 読み取った内容を履歴リストに追加していく（同一内容の重複は排除、または回数表示）
- 履歴は新しいものが分かりやすいように表示する
- JAN コード（EAN-13 / EAN-8）も読み取れる
- 読み取りテスト用のサンプル JAN バーコードを画面から確認できる

## 技術方針（案）
| 項目 | 採用 |
|---|---|
| 言語 | Kotlin |
| UI | Jetpack Compose |
| カメラ | CameraX（Preview + ImageAnalysis） |
| QR検出 | ML Kit Barcode Scanning（複数検出・座標取得が可能） |
| 最小SDK | 26 以上を想定（プロジェクト作成時に確定） |

### 画面構成イメージ
- 上部: カメラプレビュー + 検出QRの枠オーバーレイ（Canvas で boundingBox / cornerPoints を座標変換して描画）
- 下部: 読み取り履歴の LazyColumn

### 処理の流れ
1. CameraX の ImageAnalysis でフレームを取得
2. ML Kit `BarcodeScanner`（全フォーマットを対象。宅急便の伝票バーコードなど CODE128 系も読む）で検出
3. 各バーコードの `cornerPoints` をプレビュー座標へ変換して枠を描画
4. `rawValue` を履歴に追加（重複判定あり）

## 開発環境
- 開発PC: Windows 11
- IDE: Android Studio（winget の `Google.AndroidStudio` でインストール）
- 実機: Pixel 8a（USBデバッグ有効化済み想定）

## 進捗
- [x] Android Studio インストール（winget）
- [x] Android Studio 初回セットアップ（Standard、SDK / Platform-Tools のダウンロード）
- [x] スマホで USB デバッグ有効化・PC を許可
- [x] プロジェクト作成（Kotlin + Compose）
- [x] CameraX プレビュー表示
- [x] ML Kit によるQR検出と枠描画
- [x] 履歴リスト表示（`assembleDebug` ビルド成功まで確認済み）
- [x] 実機（Pixel 8a）で動作確認
- [ ] JAN コード読み取り（EAN-13 / EAN-8）とサンプルバーコード表示（ビルド確認後、実機確認待ち）

## 別PCで続きを行う場合の手順
1. リポジトリを clone する: `git clone https://github.com/knomu1/AndroidDevelop.git`
2. Android Studio をインストール（`winget install -e --id Google.AndroidStudio`）し、初回セットアップで SDK を導入
3. スマホの「開発者向けオプション → USB デバッグ」をオンにし、PC接続時に許可
4. `adb devices` で端末が `device` と表示されることを確認
5. 上記「進捗」の未完了項目から再開する
