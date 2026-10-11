using System.Diagnostics;
using System.IO.Pipes;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
using System.Windows.Forms;
using Microsoft.Toolkit.Uwp.Notifications;
using Microsoft.Win32;
using TapDrop.Core;

namespace TapDrop.Tray;

internal sealed class TrayApplicationContext : ApplicationContext
{
    public const string PipeName = "TapDrop.Tray.Instance";
    private const int HotkeyId = 0x5444;
    private const uint ModAlt = 0x0001;
    private const uint ModControl = 0x0002;
    private const int VkP = 0x50;
    private readonly HotkeyForm _host;
    private readonly NotifyIcon _icon;
    private readonly TrayConfig _config;
    private readonly ToolStripMenuItem _recentMenu;
    private readonly ToolStripMenuItem _autoOpenMenu;
    private readonly ToolStripMenuItem _startupMenu;
    private readonly List<RecentEntry> _recent = [];
    private readonly List<string> _pendingSendPaths = [];
    private readonly CancellationTokenSource _pipeCancellation = new();
    private RelayClient? _client;
    private PairDialog? _pairDialog;
    private bool _connected;
    private bool _peerOnline;

    public TrayApplicationContext(string[] args)
    {
        _config = ConfigStore.Load();
        _host = new HotkeyForm();
        _host.HotkeyPressed += (_, _) => _ = SendClipboardAsync();
        _ = _host.Handle;
        RegisterHotKey(_host.Handle, HotkeyId, ModControl | ModAlt, VkP);

        var menu = new ContextMenuStrip();
        menu.Items.Add("Send clipboard\tCtrl+Alt+P", null, async (_, _) => await SendClipboardAsync());
        menu.Items.Add("Send files…", null, async (_, _) => await PickAndSendFilesAsync());
        _recentMenu = new ToolStripMenuItem("Recent");
        menu.Items.Add(_recentMenu);
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("Pair phone…", null, async (_, _) => await PairPhoneAsync());
        menu.Items.Add("Settings…", null, (_, _) => ShowSettings());
        _autoOpenMenu = new ToolStripMenuItem("Auto-open links") { CheckOnClick = true, Checked = _config.AutoOpenLinks };
        _autoOpenMenu.CheckedChanged += (_, _) =>
        {
            _config.AutoOpenLinks = _autoOpenMenu.Checked;
            ConfigStore.Save(_config);
        };
        menu.Items.Add(_autoOpenMenu);
        _startupMenu = new ToolStripMenuItem("Start with Windows") { CheckOnClick = true, Checked = _config.StartWithWindows };
        _startupMenu.CheckedChanged += (_, _) =>
        {
            _config.StartWithWindows = _startupMenu.Checked;
            SetStartWithWindows(_config.StartWithWindows);
            ConfigStore.Save(_config);
        };
        menu.Items.Add(_startupMenu);
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("Open TapDrop folder", null, (_, _) => OpenPath(ConfigStore.DirectoryPath));
        menu.Items.Add("Unpair", null, async (_, _) => await UnpairAsync());
        menu.Items.Add("Quit", null, (_, _) => ExitThread());

        _icon = new NotifyIcon
        {
            Icon = SystemIcons.Application,
            Text = "TapDrop — unpaired",
            ContextMenuStrip = menu,
            Visible = true,
        };
        _icon.DoubleClick += (_, _) => ShowSettings();
        ToastNotificationManagerCompat.OnActivated += ToastActivated;
        SetStartWithWindows(_config.StartWithWindows);
        CreateSendToShortcut();
        _ = StartPipeServerAsync(_pipeCancellation.Token);

        if (args.Length > 0) HandleForwardedArguments(args);
        Application.Idle += InitializeOnIdle;
    }

    private async void InitializeOnIdle(object? sender, EventArgs eventArgs)
    {
        Application.Idle -= InitializeOnIdle;
        await InitializeAsync();
    }

    protected override void ExitThreadCore()
    {
        _pipeCancellation.Cancel();
        _icon.Visible = false;
        _icon.Dispose();
        UnregisterHotKey(_host.Handle, HotkeyId);
        _host.Close();
        _host.Dispose();
        _client?.DisposeAsync().AsTask().GetAwaiter().GetResult();
        _pipeCancellation.Dispose();
        base.ExitThreadCore();
    }

    private async Task InitializeAsync()
    {
        if (string.IsNullOrWhiteSpace(_config.RelayUrl))
        {
            ShowSettings();
            if (string.IsNullOrWhiteSpace(_config.RelayUrl)) return;
        }
        if (_config.IsPaired) await RestartClientAsync();
        else UpdateIcon();
    }

    private void ShowSettings()
    {
        var oldRelay = _config.RelayUrl;
        var oldName = _config.DeviceName;
        using var dialog = new SettingsDialog(_config);
        if (dialog.ShowDialog(_host) != DialogResult.OK) return;
        ConfigStore.Save(_config);
        if (_config.IsPaired && (oldRelay != _config.RelayUrl || oldName != _config.DeviceName)) _ = RestartClientAsync();
        UpdateIcon();
    }

    private async Task PairPhoneAsync()
    {
        if (string.IsNullOrWhiteSpace(_config.RelayUrl))
        {
            ShowSettings();
            if (string.IsNullOrWhiteSpace(_config.RelayUrl)) return;
        }

        var pair = PairInfo.Generate(_config.RelayUrl, _config.DeviceName);
        await using var registering = new RelayClient(pair);
        await registering.RegisterAsync();
        _config.SetPair(pair);
        ConfigStore.Save(_config);
        await RestartClientAsync(pair);

        _pairDialog?.Close();
        _pairDialog?.Dispose();
        _pairDialog = new PairDialog(pair);
        _pairDialog.FormClosed += (_, _) => _pairDialog = null;
        _pairDialog.Show(_host);
    }

    private async Task RestartClientAsync(PairInfo? pair = null)
    {
        if (_client is not null)
        {
            await _client.DisposeAsync();
            _client = null;
        }
        if (!_config.IsPaired && pair is null)
        {
            UpdateIcon();
            return;
        }

        var info = pair ?? _config.ToPairInfo();
        _client = new RelayClient(info);
        _client.ConnectionChanged += (connected, peerOnline) => RunOnUi(() =>
        {
            _connected = connected;
            _peerOnline = peerOnline;
            UpdateIcon();
            if (peerOnline && _pairDialog is not null && !_pairDialog.IsDisposed)
            {
                _pairDialog.BeginInvoke(new Action(() => _pairDialog.Close()));
            }
        });
        _client.Delivered += id => RunOnUi(() => _icon.Text = ClipTooltip($"TapDrop — delivered {id[..Math.Min(8, id.Length)]}"));
        await _client.RegisterAsync();
        await _client.StartAsync("laptop", HandleReceivedAsync);
        UpdateIcon();
        if (_pendingSendPaths.Count > 0)
        {
            var paths = _pendingSendPaths.ToArray();
            _pendingSendPaths.Clear();
            HandleForwardedArguments(paths);
        }
    }

    private async Task HandleReceivedAsync(ReceivedItem received)
    {
        await InvokeAsync(() =>
        {
            switch (received.Item.Kind)
            {
                case "text":
                {
                    var text = received.Item.Text ?? "";
                    Clipboard.SetText(text);
                    AddRecent(new RecentEntry("text", text, null, received.Item.Sender ?? "Phone"));
                    ShowToast("TapDrop", "Text copied to clipboard");
                    break;
                }
                case "url":
                {
                    var url = received.Item.Url ?? "";
                    AddRecent(new RecentEntry("url", url, null, received.Item.Sender ?? "Phone"));
                    ShowUrlToast(url);
                    if (_config.AutoOpenLinks) OpenPath(url);
                    break;
                }
                case "image":
                {
                    var path = SaveReceivedBlob(received, Environment.GetFolderPath(Environment.SpecialFolder.MyPictures), "TapDrop");
                    using var image = Image.FromFile(path);
                    Clipboard.SetImage(new Bitmap(image));
                    AddRecent(new RecentEntry("image", path, path, received.Item.Sender ?? "Phone"));
                    ShowImageToast(path);
                    break;
                }
                case "file":
                {
                    var path = SaveReceivedBlob(received, Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), Path.Combine("Downloads", "TapDrop"));
                    AddRecent(new RecentEntry("file", path, path, received.Item.Sender ?? "Phone"));
                    ShowFileToast(path);
                    break;
                }
            }
        });
    }

    private async Task SendClipboardAsync()
    {
        if (_client is null) return;
        try
        {
            if (Clipboard.ContainsFileDropList())
            {
                foreach (var path in Clipboard.GetFileDropList())
                    if (!string.IsNullOrWhiteSpace(path)) await _client.SendFileAsync(path, "laptop", source: "hotkey");
            }
            else if (Clipboard.ContainsImage())
            {
                var image = Clipboard.GetImage()!;
                var path = Path.Combine(Path.GetTempPath(), $"tapdrop-clipboard-{Guid.NewGuid():N}.png");
                try
                {
                    image.Save(path, System.Drawing.Imaging.ImageFormat.Png);
                    await _client.SendFileAsync(path, "laptop", "image/png", "image", "hotkey");
                }
                finally
                {
                    image.Dispose();
                    if (File.Exists(path)) File.Delete(path);
                }
            }
            else if (Clipboard.ContainsText())
            {
                var text = Clipboard.GetText();
                if (Regex.IsMatch(text, @"^https?://\S+$", RegexOptions.IgnoreCase))
                    await _client.SendUrlAsync(text, "laptop", "hotkey");
                else
                    await _client.SendTextAsync(text, "laptop", "hotkey");
            }
        }
        catch (Exception error)
        {
            ShowToast("TapDrop", error.Message);
        }
    }

    private async Task PickAndSendFilesAsync()
    {
        if (_client is null) return;
        using var dialog = new OpenFileDialog { Multiselect = true, Title = "Send files with TapDrop" };
        if (dialog.ShowDialog(_host) != DialogResult.OK) return;
        foreach (var path in dialog.FileNames)
        {
            try { await _client.SendFileAsync(path, "laptop", source: "tray"); }
            catch (Exception error) { ShowToast("TapDrop", error.Message); }
        }
    }

    private async Task UnpairAsync()
    {
        if (!_config.IsPaired) return;
        if (MessageBox.Show(_host, "Unpair this device and delete the relay queue?", "TapDrop", MessageBoxButtons.YesNo, MessageBoxIcon.Warning) != DialogResult.Yes)
            return;
        var pair = _config.ToPairInfo();
        using var http = new HttpClient();
        using var request = new HttpRequestMessage(HttpMethod.Delete, $"{pair.RelayUrl}/v1/pairs/{pair.PairId}");
        request.Headers.Authorization = new System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", pair.AuthToken);
        using var response = await http.SendAsync(request);
        response.EnsureSuccessStatusCode();
        await _client!.DisposeAsync();
        _client = null;
        _config.ClearPair();
        ConfigStore.Save(_config);
        _connected = false;
        _peerOnline = false;
        UpdateIcon();
    }

    private void AddRecent(RecentEntry entry)
    {
        _recent.Insert(0, entry);
        if (_recent.Count > 20) _recent.RemoveAt(20);
        _recentMenu.DropDownItems.Clear();
        if (_recent.Count == 0)
        {
            _recentMenu.DropDownItems.Add(new ToolStripMenuItem("(none)") { Enabled = false });
            return;
        }
        foreach (var recent in _recent)
        {
            var title = recent.Kind switch
            {
                "text" => recent.Content,
                "url" => recent.Content,
                _ => Path.GetFileName(recent.Content),
            };
            var item = new ToolStripMenuItem($"{recent.Sender}: {ClipTooltip(title)}");
            item.Click += (_, _) => ActivateRecent(recent);
            _recentMenu.DropDownItems.Add(item);
        }
    }

    private void ActivateRecent(RecentEntry entry)
    {
        if (entry.Kind is "text" or "url") Clipboard.SetText(entry.Content);
        else if (entry.Path is not null) OpenPath(entry.Path);
    }

    private void ShowUrlToast(string url)
    {
        try
        {
            new ToastContentBuilder()
                .AddText("TapDrop")
                .AddText(url)
                .AddButton(new ToastButton().SetContent("Open").AddArgument("action", "open").AddArgument("target", url))
                .AddButton(new ToastButton().SetContent("Copy").AddArgument("action", "copy").AddArgument("target", url))
                .Show();
        }
        catch { _icon.ShowBalloonTip(5000, "TapDrop", url, ToolTipIcon.Info); }
    }

    private void ShowImageToast(string path)
    {
        try
        {
            new ToastContentBuilder()
                .AddText("TapDrop")
                .AddText("Image received")
                .AddHeroImage(new Uri(path))
                .AddButton(new ToastButton().SetContent("Open").AddArgument("action", "open").AddArgument("target", path))
                .Show();
        }
        catch { _icon.ShowBalloonTip(5000, "TapDrop", "Image received", ToolTipIcon.Info); }
    }

    private void ShowFileToast(string path)
    {
        try
        {
            new ToastContentBuilder()
                .AddText("TapDrop")
                .AddText(Path.GetFileName(path))
                .AddButton(new ToastButton().SetContent("Open").AddArgument("action", "open").AddArgument("target", path))
                .AddButton(new ToastButton().SetContent("Show in folder").AddArgument("action", "folder").AddArgument("target", path))
                .Show();
        }
        catch { _icon.ShowBalloonTip(5000, "TapDrop", Path.GetFileName(path), ToolTipIcon.Info); }
    }

    private void ShowToast(string title, string message)
    {
        try { new ToastContentBuilder().AddText(title).AddText(message).Show(); }
        catch { _icon.ShowBalloonTip(4000, title, message, ToolTipIcon.Info); }
    }

    private void ToastActivated(ToastNotificationActivatedEventArgsCompat args)
    {
        try
        {
            var values = ToastArguments.Parse(args.Argument);
            if (!values.TryGetValue("action", out var action) || !values.TryGetValue("target", out var target)) return;
            RunOnUi(() =>
            {
                if (action == "copy") Clipboard.SetText(target);
                else if (action == "folder") OpenPath(Path.GetDirectoryName(target)!);
                else if (action == "open") OpenPath(target);
            });
        }
        catch (Exception error) { Console.Error.WriteLine(error); }
    }

    private string SaveReceivedBlob(ReceivedItem item, string root, string subdirectory)
    {
        if (item.BlobPath is null) throw new InvalidDataException("Received blob has no temporary file.");
        var directory = Path.Combine(root, subdirectory);
        Directory.CreateDirectory(directory);
        var filename = Path.GetFileName(item.Item.Blob?.Name ?? item.MessageId);
        if (string.IsNullOrWhiteSpace(filename)) filename = item.MessageId;
        var destination = Path.Combine(directory, filename);
        var stem = Path.GetFileNameWithoutExtension(destination);
        var extension = Path.GetExtension(destination);
        for (var index = 1; File.Exists(destination); index++)
            destination = Path.Combine(directory, $"{stem} ({index}){extension}");
        File.Copy(item.BlobPath, destination);
        return destination;
    }

    private void UpdateIcon()
    {
        if (_config.IsPaired)
        {
            if (_peerOnline)
            {
                _icon.Icon = SystemIcons.Information;
                _icon.Text = "TapDrop — peer online";
            }
            else if (_connected)
            {
                _icon.Icon = SystemIcons.Application;
                _icon.Text = "TapDrop — connected";
            }
            else
            {
                _icon.Icon = SystemIcons.Warning;
                _icon.Text = "TapDrop — connecting";
            }
        }
        else
        {
            _icon.Icon = SystemIcons.Error;
            _icon.Text = "TapDrop — unpaired";
        }
    }

    private void SetStartWithWindows(bool enabled)
    {
        using var key = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Run", writable: true)
            ?? Registry.CurrentUser.CreateSubKey(@"Software\Microsoft\Windows\CurrentVersion\Run", writable: true);
        if (enabled)
        {
            var exe = Environment.ProcessPath;
            if (!string.IsNullOrWhiteSpace(exe)) key.SetValue("TapDrop", $"\"{exe}\"");
        }
        else key.DeleteValue("TapDrop", throwOnMissingValue: false);
    }

    private void CreateSendToShortcut()
    {
        try
        {
            var sendTo = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "Microsoft", "Windows", "SendTo");
            Directory.CreateDirectory(sendTo);
            var shortcutPath = Path.Combine(sendTo, "TapDrop (phone).lnk");
            if (File.Exists(shortcutPath)) return;
            var shellType = Type.GetTypeFromProgID("WScript.Shell");
            if (shellType is null) return;
            dynamic shell = Activator.CreateInstance(shellType)!;
            dynamic shortcut = shell.CreateShortcut(shortcutPath);
            shortcut.TargetPath = Environment.ProcessPath;
            shortcut.Arguments = "--send";
            shortcut.Description = "Send to phone with TapDrop";
            shortcut.Save();
            Marshal.FinalReleaseComObject(shortcut);
            Marshal.FinalReleaseComObject(shell);
        }
        catch (Exception error) { Console.Error.WriteLine(error); }
    }

    private async Task StartPipeServerAsync(CancellationToken cancellationToken)
    {
        while (!cancellationToken.IsCancellationRequested)
        {
            try
            {
                using var server = new NamedPipeServerStream(PipeName, PipeDirection.In, 1, PipeTransmissionMode.Byte, PipeOptions.Asynchronous);
                await server.WaitForConnectionAsync(cancellationToken);
                using var reader = new StreamReader(server, Encoding.UTF8);
                var line = await reader.ReadLineAsync(cancellationToken);
                if (line is null) continue;
                var args = JsonSerializer.Deserialize<string[]>(line) ?? [];
                RunOnUi(() => HandleForwardedArguments(args));
            }
            catch (OperationCanceledException) { break; }
            catch (Exception error) { Console.Error.WriteLine(error); }
        }
    }

    private void HandleForwardedArguments(string[] args)
    {
        var paths = args.Where(argument => argument != "--send" && File.Exists(argument)).ToArray();
        if (paths.Length == 0 && args.Length > 1 && args[0] == "--send")
            paths = args.Skip(1).Where(File.Exists).ToArray();
        if (paths.Length == 0) return;
        if (_client is null)
        {
            _pendingSendPaths.AddRange(paths);
            return;
        }
        _ = Task.Run(async () =>
        {
            if (_client is null) return;
            foreach (var path in paths)
            {
                try { await _client.SendFileAsync(path, "laptop", source: "sendto"); }
                catch (Exception error) { RunOnUi(() => ShowToast("TapDrop", error.Message)); }
            }
        });
    }

    private Task InvokeAsync(Action action)
    {
        if (_host.IsDisposed) return Task.CompletedTask;
        if (!_host.InvokeRequired)
        {
            action();
            return Task.CompletedTask;
        }
        var completion = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        _host.BeginInvoke(new Action(() =>
        {
            try { action(); completion.SetResult(); }
            catch (Exception error) { completion.SetException(error); }
        }));
        return completion.Task;
    }

    private void RunOnUi(Action action)
    {
        if (_host.IsDisposed) return;
        if (_host.InvokeRequired) _host.BeginInvoke(action);
        else action();
    }

    private static void OpenPath(string path)
    {
        if (string.IsNullOrWhiteSpace(path)) return;
        Process.Start(new ProcessStartInfo(path) { UseShellExecute = true });
    }

    private static string ClipTooltip(string value) => value.Length <= 55 ? value : value[..52] + "…";

    private sealed record RecentEntry(string Kind, string Content, string? Path, string Sender);

    private sealed class HotkeyForm : Form
    {
        public event EventHandler? HotkeyPressed;

        protected override void WndProc(ref Message message)
        {
            if (message.Msg == 0x0312 && message.WParam.ToInt32() == HotkeyId) HotkeyPressed?.Invoke(this, EventArgs.Empty);
            base.WndProc(ref message);
        }
    }

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool RegisterHotKey(IntPtr hWnd, int id, uint modifiers, int key);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool UnregisterHotKey(IntPtr hWnd, int id);
}
