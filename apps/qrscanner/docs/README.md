# qrscanner

複数の QR / バーコードを同時に認識して枠表示し、履歴を一覧する Android アプリ。読み取った画像とデータは PC（ScanMonitor）へ送って表示できる。

| 場所 | 内容 |
|---|---|
| `apps/qrscanner/android` | Android アプリ（`:qrscanner`、パッケージ `com.knomu1.qrscanner`） |
| `apps/qrscanner/pc` | ScanMonitor（ASP.NET Core + SignalR + Blazor WASM） |
| `apps/qrscanner/tools/SampleCodes` | テスト用のサンプル QR / バーコード PDF を作るツール |

## ドキュメント
- [qrscanner.md](qrscanner.md) — アプリの開発計画
- [scanmonitor.md](scanmonitor.md) — PC 側（ScanMonitor）の構成と使い方