using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Security.Cryptography;
using QRCoder;
using ScanMonitor.Contracts;

namespace ScanMonitor.Server;

/// <summary>
/// Android とのペアリング情報（接続候補 URL・トークン）を作る。
/// トークンは data/api-token.txt に保存し、サーバー再起動後も同じ値を使う（再ペアリング不要にするため）。
/// </summary>
public sealed class PairingService
{
    private static readonly string[] VirtualMarkers =
        ["Default Switch", "WSL", "VirtualBox", "VMware", "Docker", "Loopback", "Bluetooth"];

    private readonly ILogger<PairingService> _logger;
    private string _lastCandidates = "";

    public PairingService(IConfiguration config, IHostEnvironment env, ILogger<PairingService> logger)
    {
        _logger = logger;
        Token = LoadOrCreateToken(env);
    }

    public string Token { get; }

    public string ServerName => Environment.MachineName;

    public bool IsValidToken(string? candidate) =>
        candidate is not null &&
        CryptographicOperations.FixedTimeEquals(
            System.Text.Encoding.UTF8.GetBytes(candidate),
            System.Text.Encoding.UTF8.GetBytes(Token));

    /// <summary>この PC の IPv4 アドレスから接続候補 URL を列挙する。USB テザリング(RNDIS/NCM)らしい NIC を先頭にする。</summary>
    public IReadOnlyList<string> GetCandidateUrls(int port)
    {
        var found = new List<(int Priority, string Url, string Nic)>();

        foreach (var nic in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (nic.OperationalStatus != OperationalStatus.Up) continue;
            if (nic.NetworkInterfaceType is NetworkInterfaceType.Loopback or NetworkInterfaceType.Tunnel) continue;
            var label = $"{nic.Name} {nic.Description}";
            if (VirtualMarkers.Any(m => label.Contains(m, StringComparison.OrdinalIgnoreCase))) continue;

            foreach (var ua in nic.GetIPProperties().UnicastAddresses)
            {
                if (ua.Address.AddressFamily != AddressFamily.InterNetwork) continue;
                var ip = ua.Address.ToString();
                if (ip.StartsWith("169.254.")) continue;

                var tethering = label.Contains("NDIS", StringComparison.OrdinalIgnoreCase)
                                || label.Contains("Android", StringComparison.OrdinalIgnoreCase)
                                || ip.StartsWith("192.168.42.");
                found.Add((tethering ? 0 : 1, $"http://{ip}:{port}", nic.Name));
            }
        }

        var ordered = found.OrderBy(f => f.Priority).ToList();
        return ordered.Select(f => f.Url).Distinct().ToList();
    }

    public PairingInfo Build(int port)
    {
        var urls = GetCandidateUrls(port);
        var key = string.Join(", ", urls);
        if (key != _lastCandidates)
        {
            _lastCandidates = key;
            _logger.LogInformation("Pairing candidates changed ({Count}): {Urls}", urls.Count, key);
        }

        var query = string.Join('&',
            new[] { "v=1", $"name={Uri.EscapeDataString(ServerName)}", $"token={Uri.EscapeDataString(Token)}" }
                .Concat(urls.Select(u => $"u={Uri.EscapeDataString(u)}")));
        return new PairingInfo(ServerName, Token, urls, $"scanmonitor://pair?{query}");
    }

    public string RenderQrSvg(string payload)
    {
        using var generator = new QRCodeGenerator();
        using var data = generator.CreateQrCode(payload, QRCodeGenerator.ECCLevel.M);
        return new SvgQRCode(data).GetGraphic(6);
    }

    private string LoadOrCreateToken(IHostEnvironment env)
    {
        var dir = Path.Combine(env.ContentRootPath, "data");
        Directory.CreateDirectory(dir);
        var path = Path.Combine(dir, "api-token.txt");

        if (File.Exists(path))
        {
            var existing = File.ReadAllText(path).Trim();
            if (existing.Length >= 16)
            {
                _logger.LogInformation("API token loaded from {Path}", path);
                return existing;
            }
        }

        var token = Convert.ToHexString(RandomNumberGenerator.GetBytes(16)).ToLowerInvariant();
        File.WriteAllText(path, token);
        _logger.LogInformation("API token generated and saved to {Path}", path);
        return token;
    }
}
