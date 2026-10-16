# webrtc-cam

Android のカメラ映像を **WebRTC** で PC のブラウザへライブ配信し、**どれくらいきれいに表示できるか**を検証するアプリ。
解像度・fps・ビットレート・コーデックを PC の画面から切り替えて、画質・遅延・端末の負荷を比べられる。

## 場所
| 場所 | 内容 |
|---|---|
| `apps/webrtc-cam/android` | Android アプリ（`:webrtc-cam`、パッケージ `com.knomu1.webrtccam`） |
| `apps/webrtc-cam/pc` | WebRtcCam（ASP.NET Core + Blazor WASM。シグナリング用 WebSocket と画面） |
| `apps/webrtc-cam/docs` | このドキュメント |

## ドキュメント
- [webrtc-overview.md](webrtc-overview.md) — WebRTC の仕組みの解説

## 仕組み
```
Android (libwebrtc + カメラ)  ──WebSocket(シグナリング: SDP/ICE)──►  WebRtcCam.Server  ──►  ブラウザ (RTCPeerConnection)
        └────────────────── 映像 (H.264 等 / SRTP / UDP、サーバーを通らない) ──────────────────►  <video>
```
- サーバーは SDP と ICE を中継するだけ（`/ws`）。映像は Android からブラウザへ直接流れる。
- 接続は PC 画面の QR（`webrtccam://pair?...`）を Android で読む。接続先は保存され、次回から自動接続。
- ブラウザの画面で、解像度・fps・ビットレート・コーデックを選んで「適用」すると、Android に反映される。
- 画面に受信側（ブラウザ）と送信側（Android）の実測値（解像度・fps・ビットレート・エンコーダ・遅延・端末温度）を表示する。
- LAN 内の検証用。STUN / TURN は使わない。ブラウザ（視聴者）側は認証なし、Android（送信側）はトークン必須。

## 起動
```powershell
# PC 側（0.0.0.0:5081 で待ち受け）
dotnet run --project apps/webrtc-cam/pc/src/WebRtcCam.Server --launch-profile http
# Android 側
.\gradlew.bat :webrtc-cam:assembleDebug
adb install -r apps/webrtc-cam/android/build/outputs/apk/debug/webrtc-cam-debug.apk
```
ブラウザで `http://127.0.0.1:5081/` を開き、Android アプリで画面の QR を読む。

## QR を省略して接続する（デバッグビルドのみ、AI / adb からの操作用）
```powershell
adb shell am start -n com.knomu1.webrtccam/.MainActivity --es server_url http://<PC の IP>:5081 --es token <トークン>
adb shell am start -n com.knomu1.webrtccam/.MainActivity --ez disconnect true   # 切断
```
- トークンは `pc/src/WebRtcCam.Server/data/api-token.txt`（初回起動時に自動生成）。
- デバッグビルドは、画面ロック中でもアプリを前面に出す（ロック中は Android がカメラ利用を止めるため）。
- ファイアウォールで PC に届かないときは、USB 経由で通せる: `adb reverse tcp:5081 tcp:5081` のあと `--es server_url http://127.0.0.1:5081`。

## 画質を API から変える（AI / スクリプト用）
```powershell
Invoke-RestMethod -Method Post -ContentType application/json -Uri http://127.0.0.1:5081/api/config `
  -Body '{"width":1920,"height":1080,"fps":30,"bitrateKbps":10000,"codec":"H264"}'
Invoke-RestMethod http://127.0.0.1:5081/api/viewer-stats   # ブラウザが計測した直近の受信統計
```
`codec` は `H264` / `VP8` / `VP9` / `AV1`。コーデックを変えると、接続を張り直す（数秒）。

## 検証結果（2026-10-17、Pixel 8a → PC のブラウザ、同一 Wi-Fi、画面ロック解除状態）
| 設定 | 受信側の実測 | Android のエンコーダ | 備考 |
|---|---|---|---|
| 720p / 30fps / 5 Mbps / H.264 | 1280x720 29fps 5.0 Mbps | `c2.exynos.h264.encoder`（HW） | ジッタバッファ 約 11ms、RTT 約 6ms |
| 1080p / 30fps / 10 Mbps / H.264 | 1920x1080 29fps 9.6 Mbps | 同上 | 破棄フレーム 0 |
| 1080p / 60fps / 15 Mbps / H.264 | 1920x1080 **30fps** 12.9 Mbps | 同上 | 60fps を指定しても 30fps（カメラ側の制約とみられる。未調査） |
| 4K / 30fps / 20 Mbps / H.264 | 3840x2160 29fps 19.2 Mbps | 同上 | ハードウェアで 4K が可能 |
| 4K / 30fps / 20 Mbps / AV1 | 3840x2160 **20fps** 10.5 Mbps | `c2.google.av1.encoder`（ソフト） | 30fps に届かない |
| 1080p / 30fps / 10 Mbps / VP8・VP9 | 30fps 8〜9 Mbps | `c2.exynos.vp8/vp9.encoder` | 動作する |

- 端末の電池温度は 36〜37℃ 台（camerastream の OCR 併用時は約 40℃）。ハードウェアエンコードは負荷が小さい。
- 受信遅延の目安: ジッタバッファ 5〜15ms + 往復 5〜6ms（映像の取り込み・エンコード・表示の遅延は別）。
- 画質の主観評価（きれいさ）は、画面で目視して確認する。

## ハマりどころ（原因と対処）
- **画面ロック中は映像が約 2 秒で止まる**: Android がロック中アプリのカメラ利用を制限する。端末のロックを解除しておく（デバッグビルドは前面表示の設定で回避）。
- **PC のポート 5080 は別アプリが使うことがある**: このアプリは 5081 を使う（camerastream は 5080）。
- **Windows ファイアウォールが `WebRtcCam.Server.exe` をブロックする**: 初回起動時の確認画面で許可しないと、Public ネットワークでブロックのルールが作られる。許可するには、管理者権限の PowerShell で次を実行する。
  ```powershell
  Remove-NetFirewallRule -DisplayName 'WebRtcCam.Server'
  New-NetFirewallRule -DisplayName 'WebRtcCam.Server' -Direction Inbound -Action Allow -Profile Any `
    -Program (Resolve-Path apps\webrtc-cam\pc\src\WebRtcCam.Server\bin\Debug\net10.0\WebRtcCam.Server.exe)
  ```
- ブラウザ用の JavaScript は、Blazor のフィンガープリント機能と干渉しないよう、サーバー側の `wwwroot/js/` に置いている。

## 今後の候補
- 60fps が出ない原因の調査（カメラのモード、`Camera2` の fps レンジ）。
- 画質を数値で比べる（PSNR / SSIM）仕組み、録画して見比べる機能。
- 音声の追加、前面カメラへの切り替え、複数のブラウザで同時視聴。
- 画面から取り出した 1 コマを AI に要約させる連携。