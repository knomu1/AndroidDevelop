using System.Diagnostics;
using System.Net;
using System.Text;
using ZXing;
using ZXing.Common;

// 読み取りテスト用のサンプル QR / バーコードを 1 枚の A4 PDF に出力する。
// 使い方: dotnet run --project dotnet/SampleCodes [出力先PDF]
// PDF 化には Google Chrome（ヘッドレス）を使う。

var samples = new Sample[]
{
    new("QR コード（URL）", BarcodeFormat.QR_CODE, "https://example.com/scanmonitor/sample-1"),
    new("QR コード（英数字）", BarcodeFormat.QR_CODE, "SCANMONITOR-QR-0002"),
    new("QR コード（日本語）", BarcodeFormat.QR_CODE, "テストQR こんにちは 2026"),
    new("QR コード（数字・長め）", BarcodeFormat.QR_CODE, "01234567890123456789012345678901234567890123456789"),
    new("DataMatrix", BarcodeFormat.DATA_MATRIX, "DM-SAMPLE-0006"),
    new("JAN-13 (EAN-13)", BarcodeFormat.EAN_13, "4901234567894"),
    new("JAN-8 (EAN-8)", BarcodeFormat.EAN_8, "12345670"),
    new("UPC-A", BarcodeFormat.UPC_A, "012345678905"),
    new("CODE128（英数字）", BarcodeFormat.CODE_128, "ABC-12345"),
    new("CODE128（12桁・伝票風）", BarcodeFormat.CODE_128, "123456789012"),
    new("CODE39", BarcodeFormat.CODE_39, "CODE39-TEST"),
    new("ITF（Interleaved 2 of 5）", BarcodeFormat.ITF, "1234567890"),
    new("CODABAR (NW-7)", BarcodeFormat.CODABAR, "A123456B"),
};

var outputPdf = Path.GetFullPath(args.Length > 0 ? args[0] : Path.Combine("output", "sample-codes.pdf"));
Directory.CreateDirectory(Path.GetDirectoryName(outputPdf)!);
var htmlPath = Path.ChangeExtension(outputPdf, ".html");

var html = new StringBuilder();
html.Append("""
<!doctype html>
<html lang="ja"><head><meta charset="utf-8"><title>サンプル QR / バーコード</title>
<style>
@page { size: A4; margin: 10mm; }
body { font-family: "Yu Gothic", "Meiryo", sans-serif; margin: 0; }
h1 { font-size: 13pt; margin: 0 0 3mm; }
.grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 3mm; }
.cell { border: 0.3mm solid #bbb; padding: 2mm; break-inside: avoid; text-align: center; }
.code { height: 26mm; display: flex; align-items: center; justify-content: center; background: #fff; }
.label { font-size: 8.5pt; font-weight: bold; margin-top: 2mm; }
.value { font-size: 7.5pt; color: #333; word-break: break-all; }
</style></head><body>
<h1>読み取りテスト用 サンプル QR / バーコード</h1>
<div class="grid">
""");

foreach (var s in samples)
{
    var svg = RenderSvg(s);
    html.Append($"<div class=\"cell\"><div class=\"code\">{svg}</div>")
        .Append($"<div class=\"label\">{WebUtility.HtmlEncode(s.Label)}</div>")
        .Append($"<div class=\"value\">{WebUtility.HtmlEncode(s.Value)}</div></div>\n");
}
html.Append("</div></body></html>");
File.WriteAllText(htmlPath, html.ToString(), new UTF8Encoding(false));

var chrome = new[]
{
    @"C:\Program Files\Google\Chrome\Application\chrome.exe",
    @"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe",
    Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), @"Google\Chrome\Application\chrome.exe"),
}.FirstOrDefault(File.Exists);

if (chrome is null)
{
    Console.Error.WriteLine($"Chrome が見つかりません。HTML のみ出力しました: {htmlPath}");
    return 1;
}

var profile = Path.Combine(Path.GetTempPath(), "samplecodes-chrome");
var psi = new ProcessStartInfo(chrome)
{
    ArgumentList =
    {
        "--headless=new", "--disable-gpu", $"--user-data-dir={profile}",
        "--no-pdf-header-footer", $"--print-to-pdf={outputPdf}",
        new Uri(htmlPath).AbsoluteUri,
    },
    UseShellExecute = false,
};
using (var proc = Process.Start(psi)!)
{
    proc.WaitForExit(60_000);
}

if (!File.Exists(outputPdf))
{
    Console.Error.WriteLine("PDF の生成に失敗しました。");
    return 1;
}

Console.WriteLine($"{samples.Length} 個のコードを出力しました: {outputPdf}");
return 0;

static string RenderSvg(Sample s)
{
    var is2D = s.Format is BarcodeFormat.QR_CODE or BarcodeFormat.DATA_MATRIX;
    var hints = new Dictionary<EncodeHintType, object>
    {
        [EncodeHintType.CHARACTER_SET] = "UTF-8",
        [EncodeHintType.MARGIN] = is2D ? 2 : 10, // 余白（モジュール単位）。バーコードはクワイエットゾーンを確保
        [EncodeHintType.DATA_MATRIX_SHAPE] = ZXing.Datamatrix.Encoder.SymbolShapeHint.FORCE_SQUARE,
    };
    // 幅・高さを 0 にすると最小サイズ（1 モジュール = 1 単位）で得られる。1D は高さを持たせる。
    var matrix = new MultiFormatWriter().encode(s.Value, s.Format, 0, is2D ? 0 : 40, hints);

    // 縦横比を保ったまま、表示枠（mm）に収まる大きさを求める
    var (boxW, boxH) = is2D ? (24.0, 24.0) : (54.0, 24.0);
    var scale = Math.Min(boxW / matrix.Width, boxH / matrix.Height);
    var w = (matrix.Width * scale).ToString("0.##", System.Globalization.CultureInfo.InvariantCulture);
    var h = (matrix.Height * scale).ToString("0.##", System.Globalization.CultureInfo.InvariantCulture);

    var sb = new StringBuilder();
    sb.Append($"<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"{w}mm\" height=\"{h}mm\" viewBox=\"0 0 {matrix.Width} {matrix.Height}\" shape-rendering=\"crispEdges\">");
    sb.Append($"<rect width=\"{matrix.Width}\" height=\"{matrix.Height}\" fill=\"#fff\"/><path fill=\"#000\" d=\"");
    for (var y = 0; y < matrix.Height; y++)
    {
        for (var x = 0; x < matrix.Width;)
        {
            if (!matrix[x, y]) { x++; continue; }
            var start = x;
            while (x < matrix.Width && matrix[x, y]) x++;
            sb.Append($"M{start} {y}h{x - start}v1h-{x - start}z");
        }
    }
    sb.Append("\"/></svg>");
    return sb.ToString();
}

internal sealed record Sample(string Label, BarcodeFormat Format, string Value);
