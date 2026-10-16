# ScanMonitor（検証用 .NET アプリ）

Android アプリが読み取ったバーコードの「画像 + データ」を受信し、ブラウザへリアルタイム表示する検証用システム。

## 構成
```
apps/qrscanner/pc/
├─ ScanMonitor.sln
└─ src/
    ├─ ScanMonitor.Server/     # ASP.NET Core: 受信API + SignalR + Blazor WASM のホスト + Serilog
    ├─ ScanMonitor.Client/     # Blazor WebAssembly（リアルタイム一覧画面）
    └─ ScanMonitor.Contracts/  # 共有 DTO / ルート定義
```

## 通信
```
Android --HTTP POST /api/scans (multipart: image + metadata JSON)--> Server
Server  --SignalR /hubs/scans  "ScanReceived" (メタ情報のみ)-------> Blazor WASM
Blazor  --GET /api/images/{id} (画像本体)---------------------------> Server
```
- PC と Android は HTTP（平文）で接続する。USB テザリング、または同じ Wi-Fi / LAN のどちらでも使える（動作確認済み）。
- Server は `http://0.0.0.0:5080` で待ち受け（`appsettings.json` の `Urls`）。

### metadata（JSON）
```json
{ "deviceId": "pixel-8a", "capturedAt": "2026-10-13T09:00:00+09:00",
  "imageWidth": 1280, "imageHeight": 720,
  "codes": [ { "value": "4901234567894", "format": "EAN_13",
               "corners": [ {"x":100,"y":100}, {"x":300,"y":100}, {"x":300,"y":200}, {"x":100,"y":200} ] } ] }
```
`corners` は画像（JPEG）のピクセル座標。

## 起動
```
cd apps/qrscanner/pc
dotnet run --project src/ScanMonitor.Server
```
- 画面: http://localhost:5080/
- ログ: コンソールに Serilog で出力（受信 / 拒否 / 配信 / 画面の接続・切断 / アクセスログ）。
  Development 環境では Debug レベル（各コードの内容も出力）。
- 画像は `src/ScanMonitor.Server/data/images/` に保存（git 管理外）。メタ情報は直近 500 件をメモリ保持。

## 疎通確認（curl）
```
curl.exe -F "metadata=<meta.json" -F "image=@t.jpg;type=image/jpeg" http://localhost:5080/api/scans
```

## 接続方法とネットワークの注意
- **USB テザリング**: スマホ側で USB テザリングを有効化すると、PC に新しい NIC が増える（`ipconfig` で確認）。
- **同じ Wi-Fi / LAN**: スマホと PC を同じネットワークに接続するだけでよい（USB ケーブル不要）。
  別の PC のブラウザから `http://<サーバーPCのIP>:5080/` を開いても、同じ受信結果をリアルタイムに見られる。
- どちらの場合も、接続用 QR に載る接続候補（PC の全 IPv4）からアプリが届くものを自動で選ぶ。
- サーバーは `0.0.0.0:5080` で待ち受ける。Windows ファイアウォールで TCP 5080 の受信を許可する（管理者 PowerShell）:
  `New-NetFirewallRule -DisplayName "ScanMonitor 5080" -Direction Inbound -Protocol TCP -LocalPort 5080 -Action Allow`
- **セキュリティ**: 送信（`POST /api/scans`）はトークン必須だが、閲覧側（受信一覧・画像・接続用 QR）は認証なし。
  同じネットワークの誰でも見られるため、検証用途以外では閲覧側の認証追加か待受アドレスの制限を行うこと。

## ペアリング（アドレス入力不要）
1. PC でブラウザから `http://localhost:5080/` を開くと、上部に **Android 接続用 QR** が表示される。
2. Android アプリのカメラでその QR を映す → 「接続先を設定しますか？」→ 接続。
3. アプリは QR 内の接続候補（この PC の全 IPv4）を並列に `GET /api/health` で疎通確認し、届いたものを使う。
4. 以降、履歴に新規追加されたバーコードが、フレーム画像付きで自動送信される。

- QR は `scanmonitor://pair?v=1&name=<PC名>&token=<トークン>&u=<URL>&u=<URL>...` の形式。
- トークンは `src/ScanMonitor.Server/data/api-token.txt` に保存（初回起動時に自動生成。再起動しても同じ）。
  送信 API は `X-Api-Key` ヘッダーが一致しないと 401。
- 接続候補は 5 秒ごとに画面側で更新される（USB テザリングの有効化/無効化に追従）。
- アプリ側は接続先を端末に保存し、送信失敗時は再解決・再送（最大 5 回、バックオフ）する。
  PC のトークンが変わった場合（`api-token.txt` を消した等）は、QR を読み直す。

## API
| メソッド | パス | 認証 | 概要 |
|---|---|---|---|
| GET | `/api/health` | なし | 疎通確認 |
| GET | `/api/pairing` | なし | 接続候補・トークン・QR ペイロード |
| GET | `/api/pairing/qr.svg` | なし | 接続用 QR（SVG） |
| POST | `/api/scans` | `X-Api-Key` | 画像 + metadata の受信 |
| GET | `/api/scans` | なし | 直近の受信一覧 |
| GET | `/api/images/{id}` | なし | 画像本体 |
| WS | `/hubs/scans` | なし | SignalR（`ScanReceived`） |
