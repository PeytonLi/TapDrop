using System.Security.Cryptography;
using System.Text.Json;
using TapDrop.Core;
using Xunit;

namespace TapDrop.Core.Tests;

public sealed class ProtocolTests
{
    private readonly JsonDocument _vectors = JsonDocument.Parse(File.ReadAllText(
        Path.Combine(AppContext.BaseDirectory, "test-vectors.json")));

    [Fact]
    public void ReproducesKeyAndTokenVectors()
    {
        var root = _vectors.RootElement;
        var masterKey = TapDropCrypto.FromBase64Url(root.GetProperty("masterKey_b64u").GetString()!);
        Assert.Equal(root.GetProperty("encKey_hex").GetString(), Hex(TapDropCrypto.DeriveEncryptionKey(masterKey)));
        Assert.Equal(root.GetProperty("authKey_hex").GetString(), Hex(TapDropCrypto.DeriveAuthenticationKey(masterKey)));
        Assert.Equal(root.GetProperty("authToken").GetString(), TapDropCrypto.GetAuthToken(masterKey));
        Assert.Equal(root.GetProperty("tokenHash_hex").GetString(), TapDropCrypto.GetTokenHash(TapDropCrypto.GetAuthToken(masterKey)));
    }

    [Fact]
    public void BuildsAndParsesQrVectorExactly()
    {
        var expected = _vectors.RootElement.GetProperty("qrParsed");
        var pair = new PairInfo(
            expected.GetProperty("relayUrl").GetString()!,
            expected.GetProperty("pairId").GetString()!,
            _vectors.RootElement.GetProperty("masterKey_b64u").GetString()!,
            expected.GetProperty("deviceName").GetString()!);
        var payload = _vectors.RootElement.GetProperty("qrPayload").GetString()!;
        Assert.Equal(payload, pair.ToQrPayload());

        var parsed = PairInfo.ParseQrPayload(payload);
        Assert.Equal(expected.GetProperty("relayUrl").GetString(), parsed.RelayUrl);
        Assert.Equal(expected.GetProperty("pairId").GetString(), parsed.PairId);
        Assert.Equal(_vectors.RootElement.GetProperty("masterKey_b64u").GetString(), parsed.MasterKey);
        Assert.Equal(expected.GetProperty("deviceName").GetString(), parsed.DeviceName);
        Assert.Throws<FormatException>(() => PairInfo.ParseQrPayload("https://example.com"));
    }

    [Fact]
    public void DecryptsAndReproducesEnvelopeVector()
    {
        var root = _vectors.RootElement;
        var message = root.GetProperty("message");
        var pair = PairFromVector();
        var id = message.GetProperty("id").GetString()!;
        var item = TapDropCrypto.DecryptItem(pair, message.GetProperty("from").GetString()!, id,
            JsonSerializer.Deserialize<Envelope>(message.GetProperty("envelope").GetRawText(), ProtocolJson.Options)!);
        Assert.Equal("url", item.Kind);
        Assert.Equal("https://example.com/a?b=c", item.Url);
        Assert.Equal("Galaxy S24", item.Sender);

        var plaintext = JsonSerializer.Deserialize<Item>(message.GetProperty("plaintext").GetString()!, ProtocolJson.Options)!;
        var encrypted = TapDropCrypto.EncryptItem(
            pair,
            message.GetProperty("from").GetString()!,
            id,
            plaintext,
            TapDropCrypto.FromBase64Url(message.GetProperty("nonce_b64u").GetString()!));
        Assert.Equal(message.GetProperty("envelope").GetProperty("c").GetString(), encrypted.C);
        Assert.ThrowsAny<CryptographicException>(() => TapDropCrypto.DecryptItem(pair, "laptop", id, encrypted));
    }

    [Fact]
    public void DecryptsAndReproducesBlobVectorAndRejectsWrongAad()
    {
        var root = _vectors.RootElement.GetProperty("blob");
        var pair = PairFromVector();
        var blobId = root.GetProperty("id").GetString()!;
        var body = Convert.FromHexString(root.GetProperty("body_hex").GetString()!);
        var plaintext = TapDropCrypto.DecryptBlob(pair, blobId, body);
        Assert.Equal(root.GetProperty("plaintext_hex").GetString(), Hex(plaintext));
        Assert.Equal(root.GetProperty("plaintext_sha256_hex").GetString(), Hex(SHA256.HashData(plaintext)));
        Assert.Equal(body, TapDropCrypto.EncryptBlob(pair, blobId, plaintext, body.AsSpan(0, 12)));
        Assert.ThrowsAny<CryptographicException>(() => TapDropCrypto.DecryptBlob(pair, blobId + "x", body));
    }

    [Fact]
    public void RoundTripsUnknownFieldsAndRejectsTamperedData()
    {
        var item = new Item { Kind = "text", Text = "hello", Source = "cli", CreatedAt = 1760000000000, Sender = "Laptop" };
        var pair = PairFromVector();
        var id = Guid.NewGuid().ToString();
        var envelope = TapDropCrypto.EncryptItem(pair, "laptop", id, item);
        Assert.Equal("hello", TapDropCrypto.DecryptItem(pair, "laptop", id, envelope).Text);
        Assert.ThrowsAny<CryptographicException>(() => TapDropCrypto.DecryptItem(
            new PairInfo(pair.RelayUrl, pair.PairId, TapDropCrypto.ToBase64Url(RandomNumberGenerator.GetBytes(32)), pair.DeviceName),
            "laptop",
            id,
            envelope));

        var withUnknown = JsonSerializer.Deserialize<Item>(
            """{"kind":"text","text":"ok","futureField":true}""",
            ProtocolJson.Options);
        Assert.Equal("ok", withUnknown!.Text);
    }

    private PairInfo PairFromVector()
    {
        var root = _vectors.RootElement;
        return new PairInfo(
            root.GetProperty("qrParsed").GetProperty("relayUrl").GetString()!,
            root.GetProperty("pairId").GetString()!,
            root.GetProperty("masterKey_b64u").GetString()!,
            "Test");
    }

    private static string Hex(byte[] value) => Convert.ToHexString(value).ToLowerInvariant();
}
