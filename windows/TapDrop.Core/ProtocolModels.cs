using System.Text.Json;
using System.Text.Json.Serialization;
using System.Security.Cryptography;
using System.Text.RegularExpressions;

namespace TapDrop.Core;

public static class RelayDefaults
{
    public const string Url = "https://tapdrop-relay.lipeyton2006.workers.dev";
}

public sealed class PairInfo(string relayUrl, string pairId, string masterKey, string deviceName)
{
    public string RelayUrl { get; set; } = relayUrl.TrimEnd('/');
    public string PairId { get; set; } = pairId;
    public string MasterKey { get; set; } = masterKey;
    public string DeviceName { get; set; } = deviceName;

    [JsonIgnore]
    public byte[] MasterKeyBytes => TapDropCrypto.FromBase64Url(MasterKey);

    public static PairInfo Generate(string relayUrl, string deviceName)
    {
        var key = RandomNumberGenerator.GetBytes(32);
        var id = RandomNumberGenerator.GetBytes(16);
        return new PairInfo(relayUrl, TapDropCrypto.ToBase64Url(id), TapDropCrypto.ToBase64Url(key), deviceName);
    }

    public static PairInfo FromQrPayload(string payload, string deviceName)
    {
        var parsed = ParseQrPayload(payload);
        return new PairInfo(parsed.RelayUrl, parsed.PairId, parsed.MasterKey, deviceName);
    }

    public string ToQrPayload()
    {
        var values = new[]
        {
            $"v=1",
            $"r={Uri.EscapeDataString(RelayUrl)}",
            $"p={Uri.EscapeDataString(PairId)}",
            $"k={Uri.EscapeDataString(MasterKey)}",
            $"n={Uri.EscapeDataString(DeviceName)}",
        };
        return $"tapdrop://pair?{string.Join("&", values)}";
    }

    public static PairQrPayload ParseQrPayload(string payload)
    {
        if (!Uri.TryCreate(payload, UriKind.Absolute, out var uri)
            || uri.Scheme != "tapdrop"
            || uri.Host != "pair")
        {
            throw new FormatException("Invalid TapDrop pairing QR payload.");
        }

        var query = uri.Query.TrimStart('?')
            .Split('&', StringSplitOptions.RemoveEmptyEntries)
            .Select(part => part.Split('=', 2))
            .ToDictionary(
                parts => Uri.UnescapeDataString(parts[0]),
                parts => parts.Length > 1 ? Uri.UnescapeDataString(parts[1].Replace("+", "%20")) : "",
                StringComparer.Ordinal);

        if (!query.TryGetValue("v", out var version) || version != "1"
            || !query.TryGetValue("r", out var relayUrl)
            || !Uri.TryCreate(relayUrl, UriKind.Absolute, out _)
            || !query.TryGetValue("p", out var pairId)
            || !Regex.IsMatch(pairId, "^[A-Za-z0-9_-]{22}$")
            || !query.TryGetValue("k", out var masterKey)
            || TapDropCrypto.FromBase64Url(masterKey).Length != 32
            || !query.TryGetValue("n", out var deviceName))
        {
            throw new FormatException("Invalid TapDrop pairing QR payload.");
        }

        return new PairQrPayload(relayUrl, pairId, masterKey, deviceName);
    }

    public string AuthToken => TapDropCrypto.GetAuthToken(MasterKeyBytes);
}

public sealed record PairQrPayload(string RelayUrl, string PairId, string MasterKey, string DeviceName);

public sealed class BlobRef
{
    public string Id { get; set; } = "";
    public string Name { get; set; } = "";
    public string Mime { get; set; } = "application/octet-stream";
    public long Size { get; set; }
    public string Sha256 { get; set; } = "";
}

public sealed class Item
{
    [JsonPropertyOrder(0)]
    public string Kind { get; set; } = "text";

    [JsonPropertyOrder(1)]
    public string? Text { get; set; }

    [JsonPropertyOrder(2)]
    public string? Url { get; set; }

    [JsonPropertyOrder(3)]
    public BlobRef? Blob { get; set; }

    [JsonPropertyOrder(4)]
    public string? Source { get; set; }

    [JsonPropertyOrder(5)]
    public long? CreatedAt { get; set; }

    [JsonPropertyOrder(6)]
    public string? Sender { get; set; }
}

public sealed class Envelope
{
    public int V { get; set; } = 1;
    public string N { get; set; } = "";
    public string C { get; set; } = "";
}

public sealed record ReceivedItem(string MessageId, string From, Item Item, string? BlobPath);

public static class ProtocolJson
{
    public static readonly JsonSerializerOptions Options = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
        UnmappedMemberHandling = JsonUnmappedMemberHandling.Skip,
        PropertyNameCaseInsensitive = true,
    };

    public static string Serialize<T>(T value) => JsonSerializer.Serialize(value, Options);
    public static T? Deserialize<T>(string value) => JsonSerializer.Deserialize<T>(value, Options);
}
