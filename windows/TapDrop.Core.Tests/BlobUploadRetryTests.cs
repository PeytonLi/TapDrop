using System.Net;
using System.Net.Http;
using TapDrop.Core;
using Xunit;

namespace TapDrop.Core.Tests;

public sealed class BlobUploadRetryTests
{
    [Fact]
    public async Task RetriesTransientHttpStatusThenSucceeds()
    {
        var handler = new StatusSequenceHandler(HttpStatusCode.ServiceUnavailable, HttpStatusCode.Created);
        var client = new HttpClient(handler);
        await using var relay = new RelayClient(Pair(), client);

        var blob = await relay.UploadBlobAsync("payload"u8.ToArray(), "test.txt", "text/plain", CancellationToken.None);

        Assert.Equal(2, handler.RequestCount);
        Assert.Equal("PUT", handler.Methods[0]);
        Assert.Equal("PUT", handler.Methods[1]);
        Assert.Equal(7, blob.Size);
    }

    [Fact]
    public async Task DoesNotRetryPermanentClientError()
    {
        var handler = new StatusSequenceHandler(HttpStatusCode.BadRequest, HttpStatusCode.Created);
        var client = new HttpClient(handler);
        await using var relay = new RelayClient(Pair(), client);

        await Assert.ThrowsAsync<HttpRequestException>(() =>
            relay.UploadBlobAsync("payload"u8.ToArray(), "test.txt", "text/plain", CancellationToken.None));

        Assert.Equal(1, handler.RequestCount);
    }

    [Theory]
    [InlineData(HttpStatusCode.RequestTimeout, true)]
    [InlineData(HttpStatusCode.TooManyRequests, true)]
    [InlineData(HttpStatusCode.ServiceUnavailable, true)]
    [InlineData(HttpStatusCode.BadRequest, false)]
    [InlineData(HttpStatusCode.NotFound, false)]
    public void ClassifiesBlobUploadHttpFailures(HttpStatusCode status, bool expected)
    {
        Assert.Equal(expected, RelayClient.IsTransientBlobUploadFailure(
            new HttpRequestException("test", null, status)));
    }

    private static PairInfo Pair() => new(
        "https://relay.example",
        "abcdefghijklmnopqrstuv",
        TapDropCrypto.ToBase64Url(new byte[32]),
        "Test");

    private sealed class StatusSequenceHandler(params HttpStatusCode[] statuses) : HttpMessageHandler
    {
        private int _requestCount;
        public int RequestCount => _requestCount;
        public List<string> Methods { get; } = [];

        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            Methods.Add(request.Method.Method);
            var index = Interlocked.Increment(ref _requestCount) - 1;
            var status = statuses[Math.Min(index, statuses.Length - 1)];
            return Task.FromResult(new HttpResponseMessage(status));
        }
    }
}
