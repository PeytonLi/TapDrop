using System.Collections.Concurrent;
using System.Net;
using System.Net.Http.Headers;
using System.Net.WebSockets;
using System.Diagnostics;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Threading.Channels;

namespace TapDrop.Core;

public sealed class RelayClient : IAsyncDisposable
{
    private const int MaxPlaintextBlobBytes = 95 * 1024 * 1024;
    private const int InlineTextBytes = 32 * 1024;
    private static readonly TimeSpan StandardRequestTimeout = TimeSpan.FromSeconds(30);
    private static readonly TimeSpan BlobRequestTimeout = TimeSpan.FromMinutes(10);
    private static readonly TimeSpan BlobUploadRetryWindow = TimeSpan.FromMinutes(15);
    private readonly PairInfo _pair;
    private readonly HttpClient _httpClient;
    private readonly Channel<PendingSend> _outgoing = Channel.CreateUnbounded<PendingSend>();
    private readonly ConcurrentDictionary<string, PendingSend> _pending = new();
    private readonly object _dedupeLock = new();
    private readonly Queue<string> _recentOrder = new();
    private readonly HashSet<string> _recentIds = [];
    private readonly Dictionary<string, (int Count, long Generation)> _handlerFailures = [];
    private readonly Queue<(string Id, long Generation)> _handlerFailureOrder = new();
    private long _handlerFailureGeneration;
    private readonly SemaphoreSlim _socketSendLock = new(1, 1);
    private readonly object _uploadRetryLock = new();
    private readonly Channel<byte> _reconnectSignals = Channel.CreateBounded<byte>(new BoundedChannelOptions(1)
    {
        FullMode = BoundedChannelFullMode.DropWrite,
        SingleReader = true,
        SingleWriter = false,
    });
    private TaskCompletionSource<long> _uploadRetrySignal = NewUploadRetrySignal();
    private long _uploadRetryVersion;
    private ClientWebSocket? _activeSocket;
    private CancellationTokenSource? _lifetime;
    private Task? _runTask;
    private Func<ReceivedItem, Task>? _handler;

    public RelayClient(PairInfo pair, HttpClient? httpClient = null)
    {
        _pair = pair;
        _httpClient = httpClient ?? new HttpClient { Timeout = Timeout.InfiniteTimeSpan };
    }

    public Action<string>? Log { get; set; }
    public event Action<bool, bool>? ConnectionChanged;
    public event Action<string>? Delivered;
    public Task Completion => _runTask ?? Task.CompletedTask;

    public void ReconnectNow()
    {
        _reconnectSignals.Writer.TryWrite(0);
        SignalUploadRetries();
        Volatile.Read(ref _activeSocket)?.Abort();
    }

    public async Task RegisterAsync(CancellationToken cancellationToken = default)
    {
        using var request = new HttpRequestMessage(HttpMethod.Post, PairUri("/register"));
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", _pair.AuthToken);
        using var timeout = CreateRequestTimeout(cancellationToken, StandardRequestTimeout);
        using var response = await _httpClient.SendAsync(request, timeout.Token);
        if (!response.IsSuccessStatusCode)
        {
            throw new HttpRequestException(
                $"Relay registration failed with {(int)response.StatusCode} {response.ReasonPhrase}.",
                null,
                response.StatusCode);
        }
    }

    public Task StartAsync(string role, Func<ReceivedItem, Task> handler, CancellationToken cancellationToken = default)
    {
        if (_runTask is not null) throw new InvalidOperationException("Relay client has already been started.");
        if (role is not ("laptop" or "phone")) throw new ArgumentException("Role must be laptop or phone.", nameof(role));
        _handler = handler;
        _lifetime = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        _runTask = RunAsync(role, _lifetime.Token);
        return Task.CompletedTask;
    }

    public async Task<string> SendTextAsync(string text, string role, string source = "cli", CancellationToken cancellationToken = default)
    {
        EnsureStarted();
        var bytes = Encoding.UTF8.GetBytes(text);
        var item = new Item { Kind = "text", Source = source, CreatedAt = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(), Sender = _pair.DeviceName };
        if (bytes.Length <= InlineTextBytes)
        {
            item.Text = text;
        }
        else
        {
            item.Blob = await UploadBlobAsync(bytes, "text.txt", "text/plain", cancellationToken);
        }
        return await SendItemAsync(item, role, cancellationToken);
    }

    public Task<string> SendUrlAsync(string url, string role, string source = "cli", CancellationToken cancellationToken = default)
    {
        EnsureStarted();
        var item = new Item
        {
            Kind = "url",
            Url = url,
            Source = source,
            CreatedAt = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
            Sender = _pair.DeviceName,
        };
        return SendItemAsync(item, role, cancellationToken);
    }

    public async Task<string> SendFileAsync(
        string path,
        string role,
        string? mime = null,
        string kind = "file",
        string source = "cli",
        CancellationToken cancellationToken = default)
    {
        EnsureStarted();
        var info = new FileInfo(path);
        if (!info.Exists) throw new FileNotFoundException("File to send was not found.", path);
        if (info.Length > MaxPlaintextBlobBytes)
        {
            throw new InvalidOperationException("TapDrop files are limited to 95 MiB.");
        }
        var content = await File.ReadAllBytesAsync(path, cancellationToken);
        var contentType = mime ?? GuessMimeType(info.Extension);
        var item = new Item
        {
            Kind = contentType.StartsWith("image/", StringComparison.OrdinalIgnoreCase) ? "image" : kind,
            Source = source,
            CreatedAt = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
            Sender = _pair.DeviceName,
            Blob = await UploadBlobAsync(content, info.Name, contentType, cancellationToken),
        };
        return await SendItemAsync(item, role, cancellationToken);
    }

    public async Task StopAsync()
    {
        if (_lifetime is null) return;
        await _lifetime.CancelAsync();
        if (_runTask is not null)
        {
            try { await _runTask; } catch (OperationCanceledException) { }
        }
        _lifetime.Dispose();
        _lifetime = null;
    }

    public async ValueTask DisposeAsync()
    {
        await StopAsync();
        _socketSendLock.Dispose();
        _httpClient.Dispose();
    }

    private async Task<string> SendItemAsync(Item item, string role, CancellationToken cancellationToken)
    {
        if (role is not ("laptop" or "phone")) throw new ArgumentException("Role must be laptop or phone.", nameof(role));
        var id = Guid.NewGuid().ToString();
        var envelope = TapDropCrypto.EncryptItem(_pair, role, id, item);
        var pending = new PendingSend(id, ProtocolJson.Serialize(new SendFrame("send", id, envelope)));
        if (!_pending.TryAdd(id, pending)) throw new InvalidOperationException("Message id collision.");
        await _outgoing.Writer.WriteAsync(pending, cancellationToken);
        using var registration = cancellationToken.Register(() => pending.Stored.TrySetCanceled(cancellationToken));
        try
        {
            return await pending.Stored.Task;
        }
        finally
        {
            _pending.TryRemove(id, out _);
        }
    }

    private void EnsureStarted()
    {
        if (_runTask is null) throw new InvalidOperationException("StartAsync must be called before sending.");
    }

    internal async Task<BlobRef> UploadBlobAsync(byte[] plaintext, string name, string mime, CancellationToken cancellationToken)
    {
        if (plaintext.Length > MaxPlaintextBlobBytes) throw new InvalidOperationException("TapDrop blobs are limited to 95 MiB.");
        var stopwatch = Stopwatch.StartNew();
        var backoffSeconds = 1.0;
        Exception? lastError = null;
        while (true)
        {
            var signal = GetUploadRetrySignal();
            var remaining = BlobUploadRetryWindow - stopwatch.Elapsed;
            if (remaining <= TimeSpan.Zero) throw lastError ?? new TimeoutException("Blob upload retry window expired.");
            try
            {
                return await UploadBlobOnceAsync(plaintext, name, mime, Min(BlobRequestTimeout, remaining), cancellationToken);
            }
            catch (Exception error) when (IsTransientBlobUploadFailure(error) && !cancellationToken.IsCancellationRequested)
            {
                lastError = error;
                remaining = BlobUploadRetryWindow - stopwatch.Elapsed;
                if (remaining <= TimeSpan.Zero) throw;
                if (GetUploadRetrySignal().Version != signal.Version)
                {
                    backoffSeconds = 1;
                    continue;
                }

                var jitter = 0.75 + Random.Shared.NextDouble() * 0.5;
                var delay = TimeSpan.FromSeconds(backoffSeconds * jitter);
                var signalled = await WaitForUploadRetryAsync(signal.Task, Min(delay, remaining), cancellationToken);
                backoffSeconds = signalled ? 1 : Math.Min(30, backoffSeconds * 2);
            }
        }
    }

    internal static bool IsTransientBlobUploadFailure(Exception error)
    {
        if (error is HttpRequestException { StatusCode: { } status })
        {
            var code = (int)status;
            return code is 408 or 429 || code >= 500;
        }
        return error is HttpRequestException or IOException or TimeoutException or OperationCanceledException;
    }

    private async Task<BlobRef> UploadBlobOnceAsync(
        byte[] plaintext,
        string name,
        string mime,
        TimeSpan requestTimeout,
        CancellationToken cancellationToken)
    {
        var blobId = TapDropCrypto.ToBase64Url(RandomNumberGenerator.GetBytes(16));
        var body = TapDropCrypto.EncryptBlob(_pair, blobId, plaintext);
        using var request = new HttpRequestMessage(HttpMethod.Put, PairUri($"/blobs/{blobId}"))
        {
            Content = new ByteArrayContent(body),
        };
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", _pair.AuthToken);
        request.Content.Headers.ContentType = new MediaTypeHeaderValue("application/octet-stream");
        using var timeout = CreateRequestTimeout(cancellationToken, requestTimeout);
        using var response = await _httpClient.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, timeout.Token);
        if (response.StatusCode != HttpStatusCode.Created)
        {
            throw new HttpRequestException(
                $"Blob upload failed with {(int)response.StatusCode} {response.ReasonPhrase}.",
                null,
                response.StatusCode);
        }
        return new BlobRef
        {
            Id = blobId,
            Name = name,
            Mime = mime,
            Size = plaintext.Length,
            Sha256 = Convert.ToHexString(SHA256.HashData(plaintext)).ToLowerInvariant(),
        };
    }

    private async Task RunAsync(string role, CancellationToken cancellationToken)
    {
        var backoff = TimeSpan.FromSeconds(1);
        while (!cancellationToken.IsCancellationRequested)
        {
            using var socket = new ClientWebSocket();
            Volatile.Write(ref _activeSocket, socket);
            var connectedAt = DateTime.MinValue;
            socket.Options.SetRequestHeader("Authorization", $"Bearer {_pair.AuthToken}");
            try
            {
                await socket.ConnectAsync(WebSocketUri(role), cancellationToken);
                connectedAt = DateTime.UtcNow;
                SetConnection(true, false);
                using var connection = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
                var incoming = Channel.CreateUnbounded<JsonElement>(new UnboundedChannelOptions
                {
                    SingleReader = true,
                    SingleWriter = true,
                });
                var receive = ReceiveLoopAsync(socket, incoming.Writer, connection.Token);
                var worker = ProcessMessagesAsync(socket, incoming.Reader, connection.Token);
                var send = SendLoopAsync(socket, connection.Token);
                var ping = PingLoopAsync(socket, connection.Token);
                await Task.WhenAny(receive, send, ping, worker);
                await connection.CancelAsync();
                incoming.Writer.TryComplete();
                socket.Abort();
                await IgnoreCancellation(receive);
                await IgnoreCancellation(send);
                await IgnoreCancellation(ping);
                await IgnoreCancellation(worker);
            }
            catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
            {
                break;
            }
            catch (Exception error)
            {
                WriteLog($"TapDrop relay connection: {error.Message}");
            }
            finally
            {
                Interlocked.CompareExchange(ref _activeSocket, null, socket);
                SetConnection(false, false);
            }

            if (cancellationToken.IsCancellationRequested) break;
            backoff = connectedAt != DateTime.MinValue && DateTime.UtcNow - connectedAt >= TimeSpan.FromSeconds(30)
                ? TimeSpan.FromSeconds(1)
                : TimeSpan.FromSeconds(Math.Min(30, backoff.TotalSeconds * 2));
            var jitter = 0.75 + Random.Shared.NextDouble() * 0.5;
            try
            {
                await WaitForReconnectAsync(TimeSpan.FromMilliseconds(backoff.TotalMilliseconds * jitter), cancellationToken);
            }
            catch (OperationCanceledException) { break; }
        }
    }

    private async Task ReceiveLoopAsync(
        ClientWebSocket socket,
        ChannelWriter<JsonElement> incoming,
        CancellationToken cancellationToken)
    {
        var buffer = new byte[8192];
        while (socket.State == WebSocketState.Open && !cancellationToken.IsCancellationRequested)
        {
            using var message = new MemoryStream();
            WebSocketReceiveResult result;
            do
            {
                result = await socket.ReceiveAsync(buffer, cancellationToken);
                if (result.MessageType == WebSocketMessageType.Close) throw new WebSocketException("Relay closed the connection.");
                message.Write(buffer, 0, result.Count);
                if (message.Length > 1024 * 1024) throw new InvalidDataException("Relay frame exceeded 1 MiB.");
            } while (!result.EndOfMessage);
            using var frame = JsonDocument.Parse(message.ToArray());
            if (GetString(frame.RootElement, "t") == "msg")
                await incoming.WriteAsync(frame.RootElement.Clone(), cancellationToken);
            else
                HandleControlFrame(frame.RootElement);
        }
    }

    private void HandleControlFrame(JsonElement frame)
    {
        var type = GetString(frame, "t");
        switch (type)
        {
            case "hello":
            case "presence":
                SetConnection(true, frame.TryGetProperty("peerOnline", out var online) && online.ValueKind == JsonValueKind.True);
                break;
            case "pong":
                Volatile.Write(ref _lastPongTicks, DateTime.UtcNow.Ticks);
                break;
            case "stored":
            {
                var id = GetString(frame, "id");
                if (id is not null && _pending.TryGetValue(id, out var pending)) pending.Stored.TrySetResult(id);
                break;
            }
            case "delivered":
            {
                var id = GetString(frame, "id");
                if (id is not null) Delivered?.Invoke(id);
                break;
            }
            case "error":
            {
                var id = GetString(frame, "id");
                var code = GetString(frame, "code") ?? "relay_error";
                if (id is not null && _pending.TryGetValue(id, out var pending))
                {
                    if (code == "duplicate_id") pending.Stored.TrySetResult(id);
                    else pending.Stored.TrySetException(new InvalidOperationException($"Relay rejected message: {code}."));
                }
                break;
            }
        }
    }

    private async Task ProcessMessagesAsync(
        ClientWebSocket socket,
        ChannelReader<JsonElement> incoming,
        CancellationToken cancellationToken)
    {
        await foreach (var frame in incoming.ReadAllAsync(cancellationToken))
            await ProcessMessageAsync(socket, frame, cancellationToken);
    }

    private async Task ProcessMessageAsync(ClientWebSocket socket, JsonElement frame, CancellationToken cancellationToken)
    {
        var id = GetString(frame, "id");
        var from = GetString(frame, "from");
        if (id is null || from is not ("laptop" or "phone")) return;
        lock (_dedupeLock)
        {
            if (_recentIds.Contains(id))
            {
                _handlerFailures.Remove(id);
                _ = SendAckAsync(socket, id, cancellationToken);
                return;
            }
            RememberId(id);
        }

        string? blobPath = null;
        string? blobIdToDelete = null;
        try
        {
            if (!frame.TryGetProperty("env", out var envelopeElement)) throw new JsonException("Message has no envelope.");
            var envelope = envelopeElement.Deserialize<Envelope>(ProtocolJson.Options)
                ?? throw new JsonException("Message envelope is invalid.");
            var item = TapDropCrypto.DecryptItem(_pair, from, id, envelope);
            if (item.Kind is not ("text" or "url" or "image" or "file"))
                throw new InvalidDataException($"Unknown item kind: {item.Kind}.");
            if (IsHandlerPoisoned(id))
            {
                blobIdToDelete = item.Blob?.Id;
                await SendAckAsync(socket, id, cancellationToken);
                ClearHandlerFailures(id);
                WriteLog($"Dropped TapDrop message {id} after 3 handler failures.");
                if (blobIdToDelete is not null)
                {
                    try { await DeleteBlobAsync(blobIdToDelete, cancellationToken); }
                    catch (Exception cleanupError) { WriteLog($"TapDrop blob cleanup failed: {cleanupError.Message}"); }
                }
                return;
            }
            if (item.Blob is not null)
            {
                blobIdToDelete = item.Blob.Id;
                using var request = new HttpRequestMessage(HttpMethod.Get, PairUri($"/blobs/{item.Blob.Id}"));
                request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", _pair.AuthToken);
                using var timeout = CreateRequestTimeout(cancellationToken, BlobRequestTimeout);
                using var response = await _httpClient.SendAsync(
                    request,
                    HttpCompletionOption.ResponseHeadersRead,
                    timeout.Token);
                if (response.StatusCode == HttpStatusCode.NotFound)
                    throw new PermanentMessageException($"Referenced blob {item.Blob.Id} was not found.");
                response.EnsureSuccessStatusCode();
                var encrypted = await response.Content.ReadAsByteArrayAsync(timeout.Token);
                var plaintext = TapDropCrypto.DecryptBlob(_pair, item.Blob.Id, encrypted);
                var digest = Convert.ToHexString(SHA256.HashData(plaintext)).ToLowerInvariant();
                if (plaintext.LongLength != item.Blob.Size
                    || !CryptographicOperations.FixedTimeEquals(
                        Encoding.ASCII.GetBytes(digest),
                        Encoding.ASCII.GetBytes(item.Blob.Sha256.ToLowerInvariant())))
                {
                    throw new InvalidDataException("Received blob size or SHA-256 does not match.");
                }
                blobPath = Path.Combine(Path.GetTempPath(), $"tapdrop-{Guid.NewGuid():N}");
                await File.WriteAllBytesAsync(blobPath, plaintext, cancellationToken);
                if (item.Kind == "text") item.Text = Encoding.UTF8.GetString(plaintext);
            }

            try
            {
                await (_handler?.Invoke(new ReceivedItem(id, from, item, blobPath)) ?? Task.CompletedTask);
                ClearHandlerFailures(id);
            }
            catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
            {
                throw;
            }
            catch (Exception handlerError)
            {
                if (RecordHandlerFailure(id) >= 3)
                {
                    await SendAckAsync(socket, id, cancellationToken);
                    ClearHandlerFailures(id);
                    WriteLog($"Dropped TapDrop message {id} after 3 handler failures: {handlerError.Message}");
                    if (blobIdToDelete is not null)
                    {
                        try { await DeleteBlobAsync(blobIdToDelete, cancellationToken); }
                        catch (Exception cleanupError) { WriteLog($"TapDrop blob cleanup failed: {cleanupError.Message}"); }
                    }
                    if (blobPath is not null && File.Exists(blobPath)) File.Delete(blobPath);
                    return;
                }
                throw;
            }
            await SendAckAsync(socket, id, cancellationToken);
            if (blobIdToDelete is not null) await DeleteBlobAsync(blobIdToDelete, cancellationToken);
            if (blobPath is not null && File.Exists(blobPath)) File.Delete(blobPath);
        }
        catch (Exception error) when (
            error is CryptographicException or JsonException or InvalidDataException or PermanentMessageException)
        {
            WriteLog($"Dropped TapDrop message {id}: {error.Message}");
            await SendAckAsync(socket, id, cancellationToken);
            if (blobIdToDelete is not null)
            {
                try { await DeleteBlobAsync(blobIdToDelete, cancellationToken); }
                catch (Exception cleanupError) { WriteLog($"TapDrop blob cleanup failed: {cleanupError.Message}"); }
            }
            if (blobPath is not null && File.Exists(blobPath)) File.Delete(blobPath);
        }
        catch
        {
            lock (_dedupeLock) ForgetId(id);
            if (blobPath is not null && File.Exists(blobPath)) File.Delete(blobPath);
            throw;
        }
    }

    private async Task SendLoopAsync(ClientWebSocket socket, CancellationToken cancellationToken)
    {
        while (await _outgoing.Reader.WaitToReadAsync(cancellationToken))
        {
            while (_outgoing.Reader.TryRead(out var pending))
            {
                if (pending.Stored.Task.IsCompleted) continue;
                try
                {
                    await SendFrameAsync(socket, pending.Frame, cancellationToken);
                    var completed = await Task.WhenAny(pending.Stored.Task, Task.Delay(TimeSpan.FromSeconds(15), cancellationToken));
                    if (completed != pending.Stored.Task)
                    {
                        if (!pending.Stored.Task.IsCompleted)
                        {
                            if (cancellationToken.IsCancellationRequested) _outgoing.Writer.TryWrite(pending);
                            else pending.Stored.TrySetException(new TimeoutException("Relay did not acknowledge the stored message within 15 seconds."));
                        }
                    }
                    if (cancellationToken.IsCancellationRequested) return;
                }
                catch
                {
                    if (!pending.Stored.Task.IsCompleted) _outgoing.Writer.TryWrite(pending);
                    throw;
                }
            }
        }
    }

    private async Task PingLoopAsync(ClientWebSocket socket, CancellationToken cancellationToken)
    {
        Volatile.Write(ref _lastPongTicks, DateTime.UtcNow.Ticks);
        var pingInterval = ReadPingSetting("TAPDROP_PING_INTERVAL_SECONDS", 25);
        var pongTimeout = ReadPingSetting("TAPDROP_PONG_TIMEOUT_SECONDS", 10);
        while (!cancellationToken.IsCancellationRequested)
        {
            await Task.Delay(pingInterval, cancellationToken);
            await SendFrameAsync(socket, "{\"t\":\"ping\"}", cancellationToken);
            var pingTicks = DateTime.UtcNow.Ticks;
            await Task.Delay(pongTimeout, cancellationToken);
            if (Volatile.Read(ref _lastPongTicks) < pingTicks) throw new TimeoutException("Relay pong timed out.");
        }
    }

    private async Task SendAckAsync(ClientWebSocket socket, string id, CancellationToken cancellationToken) =>
        await SendFrameAsync(socket, ProtocolJson.Serialize(new AckFrame("ack", id)), cancellationToken);

    private async Task SendFrameAsync(ClientWebSocket socket, string frame, CancellationToken cancellationToken)
    {
        var bytes = Encoding.UTF8.GetBytes(frame);
        await _socketSendLock.WaitAsync(cancellationToken);
        try
        {
            await socket.SendAsync(bytes, WebSocketMessageType.Text, true, cancellationToken);
        }
        finally
        {
            _socketSendLock.Release();
        }
    }

    private async Task DeleteBlobAsync(string blobId, CancellationToken cancellationToken)
    {
        using var request = new HttpRequestMessage(HttpMethod.Delete, PairUri($"/blobs/{blobId}"));
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", _pair.AuthToken);
        using var timeout = CreateRequestTimeout(cancellationToken, StandardRequestTimeout);
        using var response = await _httpClient.SendAsync(request, timeout.Token);
        response.EnsureSuccessStatusCode();
    }

    private Uri PairUri(string suffix) =>
        new($"{_pair.RelayUrl.TrimEnd('/')}/v1/pairs/{_pair.PairId}{suffix}", UriKind.Absolute);

    private Uri WebSocketUri(string role)
    {
        var uri = new Uri(PairUri($"/ws?role={role}").ToString());
        var builder = new UriBuilder(uri)
        {
            Scheme = uri.Scheme switch { "http" => "ws", "https" => "wss", _ => uri.Scheme },
            Port = uri.IsDefaultPort ? -1 : uri.Port,
        };
        return builder.Uri;
    }

    private void SetConnection(bool connected, bool peerOnline)
    {
        if (connected) SignalUploadRetries();
        try { ConnectionChanged?.Invoke(connected, peerOnline); } catch (Exception error) { WriteLog(error.ToString()); }
    }

    private (long Version, Task<long> Task) GetUploadRetrySignal()
    {
        lock (_uploadRetryLock) return (_uploadRetryVersion, _uploadRetrySignal.Task);
    }

    private void SignalUploadRetries()
    {
        TaskCompletionSource<long> signal;
        long version;
        lock (_uploadRetryLock)
        {
            version = ++_uploadRetryVersion;
            signal = _uploadRetrySignal;
            _uploadRetrySignal = NewUploadRetrySignal();
        }
        signal.TrySetResult(version);
    }

    private static TaskCompletionSource<long> NewUploadRetrySignal() =>
        new(TaskCreationOptions.RunContinuationsAsynchronously);

    private async Task<bool> WaitForUploadRetryAsync(Task<long> signal, TimeSpan delay, CancellationToken cancellationToken)
    {
        using var waiting = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        var delayTask = Task.Delay(delay, waiting.Token);
        var signalTask = signal.WaitAsync(waiting.Token);
        var completed = await Task.WhenAny(delayTask, signalTask);
        await completed;
        waiting.Cancel();
        return completed == signalTask;
    }

    private async Task WaitForReconnectAsync(TimeSpan delay, CancellationToken cancellationToken)
    {
        using var waiting = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        var delayTask = Task.Delay(delay, waiting.Token);
        var signalTask = _reconnectSignals.Reader.ReadAsync(waiting.Token).AsTask();
        var completed = await Task.WhenAny(delayTask, signalTask);
        await completed;
        waiting.Cancel();
    }

    private static TimeSpan Min(TimeSpan first, TimeSpan second) => first <= second ? first : second;

    private void WriteLog(string message)
    {
        try
        {
            if (Log is { } log) log(message);
            else Console.Error.WriteLine(message);
        }
        catch { }
    }

    private static CancellationTokenSource CreateRequestTimeout(CancellationToken cancellationToken, TimeSpan timeout)
    {
        var source = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        source.CancelAfter(timeout);
        return source;
    }

    private static TimeSpan ReadPingSetting(string name, int defaultSeconds) =>
        int.TryParse(Environment.GetEnvironmentVariable(name), out var seconds) && seconds > 0
            ? TimeSpan.FromSeconds(seconds)
            : TimeSpan.FromSeconds(defaultSeconds);

    private void RememberId(string id)
    {
        _recentIds.Add(id);
        _recentOrder.Enqueue(id);
        while (_recentOrder.Count > 1000)
        {
            var expired = _recentOrder.Dequeue();
            _recentIds.Remove(expired);
            _handlerFailures.Remove(expired);
        }
    }

    private void ForgetId(string id)
    {
        if (!_recentIds.Remove(id)) return;
        var retained = _recentOrder.Where(value => value != id).ToArray();
        _recentOrder.Clear();
        foreach (var value in retained) _recentOrder.Enqueue(value);
    }

    private int RecordHandlerFailure(string id)
    {
        lock (_dedupeLock)
        {
            int count;
            long generation;
            if (_handlerFailures.TryGetValue(id, out var failure))
            {
                count = failure.Count + 1;
                generation = failure.Generation;
            }
            else
            {
                count = 1;
                generation = ++_handlerFailureGeneration;
                _handlerFailureOrder.Enqueue((id, generation));
            }
            _handlerFailures[id] = (count, generation);
            while (_handlerFailureOrder.Count > 1000)
            {
                var oldest = _handlerFailureOrder.Dequeue();
                if (_handlerFailures.TryGetValue(oldest.Id, out var current)
                    && current.Generation == oldest.Generation)
                    _handlerFailures.Remove(oldest.Id);
            }
            return count;
        }
    }

    private bool IsHandlerPoisoned(string id)
    {
        lock (_dedupeLock) return _handlerFailures.TryGetValue(id, out var failure) && failure.Count >= 3;
    }

    private void ClearHandlerFailures(string id)
    {
        lock (_dedupeLock) _handlerFailures.Remove(id);
    }

    private static string? GetString(JsonElement frame, string name) =>
        frame.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.String ? value.GetString() : null;

    private static string GuessMimeType(string extension) => extension.ToLowerInvariant() switch
    {
        ".png" => "image/png",
        ".jpg" or ".jpeg" => "image/jpeg",
        ".gif" => "image/gif",
        ".webp" => "image/webp",
        ".txt" => "text/plain",
        ".pdf" => "application/pdf",
        ".zip" => "application/zip",
        _ => "application/octet-stream",
    };

    private static async Task IgnoreCancellation(Task task)
    {
        try { await task; } catch (OperationCanceledException) { } catch (WebSocketException) { }
    }

    private sealed class PermanentMessageException(string message) : Exception(message) { }
    private long _lastPongTicks;
    private sealed record SendFrame(string T, string Id, Envelope Env);
    private sealed record AckFrame(string T, string Id);
    private sealed class PendingSend(string id, string frame)
    {
        public string Id { get; } = id;
        public string Frame { get; } = frame;
        public TaskCompletionSource<string> Stored { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);
    }
}
