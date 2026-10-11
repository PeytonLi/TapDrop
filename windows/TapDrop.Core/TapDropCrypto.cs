using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace TapDrop.Core;

public static class TapDropCrypto
{
    private static readonly byte[] Salt = Encoding.ASCII.GetBytes("tapdrop-v1");

    public static byte[] DeriveEncryptionKey(ReadOnlySpan<byte> masterKey)
    {
        var output = new byte[32];
        HKDF.DeriveKey(HashAlgorithmName.SHA256, masterKey, output, Salt, "enc"u8);
        return output;
    }

    public static byte[] DeriveAuthenticationKey(ReadOnlySpan<byte> masterKey)
    {
        var output = new byte[32];
        HKDF.DeriveKey(HashAlgorithmName.SHA256, masterKey, output, Salt, "auth"u8);
        return output;
    }

    public static string GetAuthToken(ReadOnlySpan<byte> masterKey) =>
        ToBase64Url(DeriveAuthenticationKey(masterKey));

    public static string GetTokenHash(string authToken) =>
        Convert.ToHexString(SHA256.HashData(Encoding.ASCII.GetBytes(authToken))).ToLowerInvariant();

    public static Envelope EncryptItem(PairInfo pair, string fromRole, string messageId, Item item) =>
        EncryptItem(pair, fromRole, messageId, item, RandomNumberGenerator.GetBytes(12));

    public static Envelope EncryptItem(
        PairInfo pair,
        string fromRole,
        string messageId,
        Item item,
        ReadOnlySpan<byte> nonce)
    {
        var plaintext = JsonSerializer.SerializeToUtf8Bytes(item, ProtocolJson.Options);
        var cipher = Encrypt(
            DeriveEncryptionKey(pair.MasterKeyBytes),
            nonce,
            plaintext,
            Encoding.ASCII.GetBytes($"tapdrop/v1/msg/{pair.PairId}/{fromRole}/{messageId}"));
        return new Envelope { V = 1, N = ToBase64Url(nonce), C = ToBase64Url(cipher) };
    }

    public static Item DecryptItem(PairInfo pair, string fromRole, string messageId, Envelope envelope)
    {
        if (envelope.V != 1) throw new CryptographicException("Unsupported envelope version.");
        var plaintext = Decrypt(
            DeriveEncryptionKey(pair.MasterKeyBytes),
            FromBase64Url(envelope.N),
            FromBase64Url(envelope.C),
            Encoding.ASCII.GetBytes($"tapdrop/v1/msg/{pair.PairId}/{fromRole}/{messageId}"));
        return JsonSerializer.Deserialize<Item>(plaintext, ProtocolJson.Options)
            ?? throw new JsonException("Message item was empty.");
    }

    public static byte[] EncryptBlob(PairInfo pair, string blobId, ReadOnlySpan<byte> plaintext) =>
        EncryptBlob(pair, blobId, plaintext, RandomNumberGenerator.GetBytes(12));

    public static byte[] EncryptBlob(
        PairInfo pair,
        string blobId,
        ReadOnlySpan<byte> plaintext,
        ReadOnlySpan<byte> nonce)
    {
        var cipher = Encrypt(
            DeriveEncryptionKey(pair.MasterKeyBytes),
            nonce,
            plaintext,
            Encoding.ASCII.GetBytes($"tapdrop/v1/blob/{pair.PairId}/{blobId}"));
        var body = new byte[nonce.Length + cipher.Length];
        nonce.CopyTo(body);
        cipher.CopyTo(body.AsSpan(nonce.Length));
        return body;
    }

    public static byte[] DecryptBlob(PairInfo pair, string blobId, ReadOnlySpan<byte> body)
    {
        if (body.Length < 12 + 16) throw new CryptographicException("Encrypted blob is truncated.");
        return Decrypt(
            DeriveEncryptionKey(pair.MasterKeyBytes),
            body[..12],
            body[12..],
            Encoding.ASCII.GetBytes($"tapdrop/v1/blob/{pair.PairId}/{blobId}"));
    }

    public static string ToBase64Url(ReadOnlySpan<byte> bytes) =>
        Convert.ToBase64String(bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_');

    public static byte[] FromBase64Url(string value)
    {
        var normalized = value.Replace('-', '+').Replace('_', '/');
        normalized += new string('=', (4 - normalized.Length % 4) % 4);
        return Convert.FromBase64String(normalized);
    }

    private static byte[] Encrypt(byte[] key, ReadOnlySpan<byte> nonce, ReadOnlySpan<byte> plaintext, ReadOnlySpan<byte> aad)
    {
        var ciphertext = new byte[plaintext.Length];
        var tag = new byte[16];
        using var aes = new AesGcm(key, tag.Length);
        aes.Encrypt(nonce, plaintext, ciphertext, tag, aad);
        return [.. ciphertext, .. tag];
    }

    private static byte[] Decrypt(byte[] key, ReadOnlySpan<byte> nonce, ReadOnlySpan<byte> cipherAndTag, ReadOnlySpan<byte> aad)
    {
        if (cipherAndTag.Length < 16) throw new CryptographicException("Encrypted value is truncated.");
        var plaintext = new byte[cipherAndTag.Length - 16];
        using var aes = new AesGcm(key, 16);
        aes.Decrypt(nonce, cipherAndTag[..^16], cipherAndTag[^16..], plaintext, aad);
        return plaintext;
    }
}
