# camerastream

Android のカメラ映像を Wi-Fi で PC へ送り、ブラウザ（Blazor WASM）に表示するアプリ。画面上の文字を認識（OCR）し、検出枠と文字列も PC 画面に重ねて表示する。

| 場所 | 内容 |
|---|---|
| `apps/camerastream/android` | Android アプリ（`:camerastream`、パッケージ `com.knomu1.camerastream`） |
| `apps/camerastream/pc` | CameraView（ASP.NET Core + Blazor WASM。`src/CameraView.Server` / `.Client` / `.Contracts`） |

## 仕組み
```
Android ──(1) 映像: POST /api/frames (JPEG, 最大 約10fps)──────► CameraView.Server ──► MJPEG /api/stream ──► Blazor 画面 (<img>)
        ──(2) 文字認識: POST /api/ocr (JSON: 文字 + 4 隅の座標, 約3回/秒) ──►  /api/ocr ──► 枠と文字を映像に重ねる
```
- 認証: ヘッダー `X-Api-Key`（トークンは `pc/src/CameraView.Server/data/api-token.txt`。初回起動時に自動生成）。
- ペアリング: PC の画面に出る QR（`camerastream://pair?...`）を Android で読む。接続先は保存され、次回から自動接続。
- 文字認識: ML Kit（日本語対応）。端末内で動く。
- 映像は JPEG を 1 枚ずつ送る方式。滑らかな動画は [webrtc-cam](../../webrtc-cam/docs/README.md) で検証する予定。

## 起動
```powershell
# PC 側（0.0.0.0:5080 で待ち受け）
dotnet run --project apps/camerastream/pc/src/CameraView.Server --launch-profile http
# Android 側のビルドとインストール
.\gradlew.bat :camerastream:assembleDebug
adb install -r apps/camerastream/android/build/outputs/apk/debug/camerastream-debug.apk
```
ブラウザで `http://<PC の IP>:5080/` を開き、Android アプリで画面の QR を読む。

## QR を省略して接続する（デバッグビルドのみ、AI / adb からの操作用）
```powershell
adb shell am start -n com.knomu1.camerastream/.MainActivity --es server_url http://<PC の IP>:5080 --es token <トークン>
adb shell am start -n com.knomu1.camerastream/.MainActivity --ez disconnect true   # 切断
```

## 検証でわかったこと
- 同じ LAN の Wi-Fi で接続でき、約 8〜9fps（768x1024）で受信できた。USB テザリングは不要だった。
- 実機（Pixel 8a）が**かなり熱くなる**。原因は、毎コマの Bitmap 変換 + JPEG 圧縮（CPU）、OCR（約3回/秒）、画面常時点灯、USB 充電中の発熱。
- 軽くする案: OCR の ON/OFF と頻度の調整、フレームレートを下げる、YUV から直接 JPEG にする、送信中は画面を暗くする。

## 既知の課題
- 認識枠は映像より最大 0.3 秒ほど遅れる（映像と OCR を別々に送っているため）。
- `Microsoft.AspNetCore.Components.WebAssembly.Server` 10.0.12 に高重大度の既知の脆弱性警告が出る（更新版の確認が必要）。