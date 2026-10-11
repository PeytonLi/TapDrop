using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using TapDrop.Core;

namespace TapDrop.Tray;

internal sealed class TrayConfig
{
    public string RelayUrl { get; set; } = "";
    public string PairId { get; set; } = "";
    public string ProtectedMasterKey { get; set; } = "";
    public string DeviceName { get; set; } = Environment.MachineName;
    public bool AutoOpenLinks { get; set; }
    public bool StartWithWindows { get; set; } = true;
    public bool KnockToSend { get; set; }
    public int KnockSensitivity { get; set; } = 5;
    public string PhoneName { get; set; } = "";

    public bool IsPaired => !string.IsNullOrWhiteSpace(PairId) && !string.IsNullOrWhiteSpace(ProtectedMasterKey);

    public PairInfo ToPairInfo()
    {
        var key = ProtectedData.Unprotect(Convert.FromBase64String(ProtectedMasterKey), null, DataProtectionScope.CurrentUser);
        return new PairInfo(RelayUrl, PairId, TapDropCrypto.ToBase64Url(key), DeviceName);
    }

    public void SetPair(PairInfo pair)
    {
        RelayUrl = pair.RelayUrl;
        PairId = pair.PairId;
        DeviceName = pair.DeviceName;
        ProtectedMasterKey = Convert.ToBase64String(
            ProtectedData.Protect(pair.MasterKeyBytes, null, DataProtectionScope.CurrentUser));
    }

    public void ClearPair()
    {
        PairId = "";
        ProtectedMasterKey = "";
    }
}

internal static class ConfigStore
{
    public static string DirectoryPath => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "TapDrop");

    public static string ConfigPath => Path.Combine(DirectoryPath, "config.json");

    public static TrayConfig Load()
    {
        if (!File.Exists(ConfigPath)) return new TrayConfig();
        return JsonSerializer.Deserialize<TrayConfig>(File.ReadAllText(ConfigPath), ProtocolJson.Options)
            ?? new TrayConfig();
    }

    public static void Save(TrayConfig config)
    {
        Directory.CreateDirectory(DirectoryPath);
        var json = JsonSerializer.Serialize(config, ProtocolJson.Options);
        File.WriteAllText(ConfigPath, json, Encoding.UTF8);
    }
}

internal static class TrayLog
{
    private static readonly object Sync = new();

    public static void Write(string message)
    {
        try
        {
            Directory.CreateDirectory(ConfigStore.DirectoryPath);
            var line = $"{DateTimeOffset.Now:O} {message}{Environment.NewLine}";
            lock (Sync) File.AppendAllText(Path.Combine(ConfigStore.DirectoryPath, "tray.log"), line, Encoding.UTF8);
        }
        catch { }
    }
}
