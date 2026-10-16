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

## 他の端末（別の PC / iPad）で見る（LAN 内公開）
同じ LAN にいる端末のブラウザで、サーバーを動かしている PC の URL を開くだけ（例: `http://192.168.11.22:5081/`）。
- URL はサーバー起動ログ（`Open from other devices on the LAN: ...`）と、画面の「別の PC / iPad で見る」に出る。QR も出るので、iPad ではそれを読めばよい。
- 起動は `apps/webrtc-cam/pc/start-server.ps1`（PC の電源が入っていて、サーバーが動いている間だけ見られる）。
- 視聴者は**複数台同時**に見られる（接続ごとに別々にエンコードして送るので、台数が増えるほどスマホの負荷と発熱が増える）。画質設定は全員に共通。
- 認証は付けていない（LAN 内の検証用）。視聴者側は誰でも見られ、画質も変えられる。Android（送信側）だけトークンが要る。
- 初回だけ、Windows ファイアウォールの許可が要る（下記）。許可範囲は同じサブネットだけで、インターネット側には開かない。
- 動作確認済み: Wi-Fi 接続のスマホから PC の LAN アドレスへ HTTP 接続でき、同時に 3 つのブラウザタブで受信できた。iPad（Safari）での表示は未確認。

## スマホの縦・横
- スマホを縦・横（逆向きも）にすると、PC のブラウザの映像も縦長・横長に切り替わる。WebRTC が映像に回転情報を付けて送り、ブラウザが自動で回転して表示する。
- アプリは `screenOrientation="fullSensor"`。端末の「自動回転」の設定が OFF でも、持ち替えればセンサーに従って回る。
- 向きが変わっても**再接続しない**: `configChanges`（orientation など）で画面を作り直さない。さらに、画面が作り直されたときは、起動時のインテント（デバッグ用の接続・切断の指示）を再処理しない（以前は再処理して、向きを変えるたびに接続が張り直され、「切断」のインテントが残っていると QR 読み取り画面に戻っていた）。
- 確認結果（Chrome、`user_rotation` で向きを変えて測定）: 縦 0° = 表示 720x1280、横 90° = 1280x720、縦 180° = 720x1280、横 270° = 1280x720。切り替え中も配信は止まらない。画面の「表示サイズ(向き)」で見られる。
- 上下逆（180°）で映像が正立しているかは目視で確認すること（表示サイズだけでは分からない）。Safari / iPad は未確認。
- 解像度の指定（例 720p）は向きに関係なく「短辺×長辺」として扱われ、縦のときは 720x1280 で出る。

## 画面の操作
- **全画面**: 画面上部の「全画面」ボタン、または映像のダブルクリックで、映像だけを全画面にする。全画面中は右上の「全画面を終了」、Esc、ダブルクリックで戻る。縦横比は保たれ、黒背景で表示する。
- iPhone の Safari は要素の全画面に対応しないため、映像そのものの全画面表示（OS 標準の画面）になる。iPad は未確認。
- 動作確認: Node.js の `puppeteer-core` で実際の Chrome を操作して、ページの読み込み（エラーなし）、全画面の開始と終了を確認した。

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
Invoke-RestMethod http://127.0.0.1:5081/api/viewer-stats   # ブラウザが計測した受信統計(stats = 直近、viewers = 視聴者(タブ)ごと)
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
- **Windows ファイアウォールが `WebRtcCam.Server.exe` をブロックする**: 初回起動時の確認画面で許可しないと、Public ネットワークでブロックのルールが作られ、スマホや他の端末から届かない。確認画面で許可し直すか、`apps/webrtc-cam/pc/allow-lan-firewall.ps1` を実行する（管理者権限の確認画面が出る。ブロックのルールを消し、TCP 5081 を同じサブネットだけに許可する）。実行ファイルの場所（`bin\Debug\...`）が変わると、実行ファイル単位のルールは効かなくなるので、ポート単位の許可のほうが安定する。
- **接続確認の方法**: スマホから PC への到達は、`adb shell "(printf 'GET /api/health HTTP/1.0\r\n\r\n'; sleep 2) | toybox nc -w 4 <PC の IP> 5081"` で確かめられる。
- ブラウザ用の JavaScript は、Blazor のフィンガープリント機能と干渉しないよう、サーバー側の `wwwroot/js/` に置いている。

## 今後の候補
- 60fps が出ない原因の調査（カメラのモード、`Camera2` の fps レンジ）。
- 画質を数値で比べる（PSNR / SSIM）仕組み、録画して見比べる機能。
- 音声の追加、前面カメラへの切り替え、複数のブラウザで同時視聴。
- 画面から取り出した 1 コマを AI に要約させる連携。