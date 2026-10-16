# 同じ LAN 内の端末からこのサーバー(TCP 5081)へ接続できるように、Windows ファイアウォールを許可する。
# 管理者権限が必要。管理者でなければ、自分で昇格し直す(確認画面が出る)。
# 公開範囲は「同じサブネット(LocalSubnet)」だけ。インターネット側には開かない。
if (-not ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole('Administrator')) {
    Start-Process powershell -Verb RunAs -ArgumentList '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "`"$PSCommandPath`""
    exit
}

# 初回起動時に自動で作られた「ブロック」ルール(実行ファイル単位)があると、許可より優先されてしまうので消す
Get-NetFirewallRule -DisplayName 'WebRtcCam.Server' -ErrorAction SilentlyContinue | Remove-NetFirewallRule
Get-NetFirewallRule -DisplayName 'WebRtcCam LAN 5081' -ErrorAction SilentlyContinue | Remove-NetFirewallRule

New-NetFirewallRule -DisplayName 'WebRtcCam LAN 5081' -Direction Inbound -Protocol TCP -LocalPort 5081 `
    -Action Allow -Profile Any -RemoteAddress LocalSubnet | Out-Null

Write-Host 'OK: TCP 5081 を同じサブネットから許可しました。'
Get-NetFirewallRule -DisplayName 'WebRtcCam LAN 5081' | Format-Table DisplayName, Enabled, Action, Direction, Profile
Read-Host 'Enter キーで閉じます'