# webrtc-cam

Android のカメラ映像を **WebRTC** で PC のブラウザへライブ配信し、**どれくらいきれいに表示できるか**を検証するアプリ。

## 目的
- WebRTC によるライブ配信の画質・遅延・端末の発熱を確かめる。
- 解像度・fps・ビットレート・コーデックを切り替えて比較する。

## 状態
- 計画中（実装前）。

## ドキュメント
| ファイル | 内容 |
|---|---|
| [webrtc-overview.md](webrtc-overview.md) | WebRTC の仕組みの解説 |

## 関連
- 画像を 1 枚ずつ送る方式の検証は `camerastream`（`apps/camerastream` / `dotnet/CameraView`）。
