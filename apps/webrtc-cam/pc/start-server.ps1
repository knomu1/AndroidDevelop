# WebRtcCam サーバーを起動する。起動ログに、LAN 内の他の端末(別 PC / iPad)から開く URL が出る。
# 初めて LAN から見るときは、先に allow-lan-firewall.ps1 を 1 回実行する(Windows ファイアウォールの許可)。
Set-Location $PSScriptRoot
dotnet run --project src\WebRtcCam.Server --launch-profile http