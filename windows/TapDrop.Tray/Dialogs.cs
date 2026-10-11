using QRCoder;
using TapDrop.Core;

namespace TapDrop.Tray;

internal sealed class SettingsDialog : Form
{
    private readonly TextBox _relay = new() { Width = 360 };
    private readonly TextBox _name = new() { Width = 360 };
    private readonly CheckBox _knockToSend = new() { Text = "Knock to send", AutoSize = true };
    private readonly TrackBar _sensitivity = new()
    {
        Minimum = 1,
        Maximum = 10,
        TickFrequency = 1,
        TickStyle = TickStyle.BottomRight,
        Width = 250,
    };
    private readonly Label _sensitivityLabel = new() { AutoSize = true };
    private readonly Button _testButton = new() { Text = "Start test", AutoSize = true };
    private readonly ProgressBar _levelMeter = new() { Minimum = 0, Maximum = 80, Dock = DockStyle.Fill };
    private readonly Label _levelLabel = new() { AutoSize = true, Text = "Peak: -100 dBFS · Noise floor: -80 dBFS" };
    private readonly ListBox _eventLog = new() { Dock = DockStyle.Fill };
    private KnockListener? _testListener;
    private long _lastMeterTick;

    public SettingsDialog(TrayConfig config)
    {
        Text = "TapDrop Settings";
        FormBorderStyle = FormBorderStyle.FixedDialog;
        StartPosition = FormStartPosition.CenterScreen;
        MaximizeBox = false;
        MinimizeBox = false;
        Width = 640;
        Height = 720;

        _relay.Text = string.IsNullOrWhiteSpace(config.RelayUrl) ? RelayDefaults.Url : config.RelayUrl;
        _name.Text = config.DeviceName;
        _knockToSend.Checked = config.KnockToSend;
        _sensitivity.Value = Math.Clamp(config.KnockSensitivity, 1, 10);
        UpdateSensitivityLabel();

        var layout = new TableLayoutPanel { Dock = DockStyle.Fill, Padding = new Padding(12), ColumnCount = 2, RowCount = 6 };
        layout.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 110));
        layout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 32));
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 32));
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 30));
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 68));
        layout.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 42));
        layout.Controls.Add(new Label { Text = "Relay URL", AutoSize = true, Anchor = AnchorStyles.Left }, 0, 0);
        layout.Controls.Add(_relay, 1, 0);
        layout.Controls.Add(new Label { Text = "Device name", AutoSize = true, Anchor = AnchorStyles.Left }, 0, 1);
        layout.Controls.Add(_name, 1, 1);
        layout.Controls.Add(_knockToSend, 0, 2);
        layout.SetColumnSpan(_knockToSend, 2);
        layout.Controls.Add(new Label
        {
            Text = "Double-knock the palm rest or lid to send your clipboard to your phone. Uses the microphone while enabled; audio is processed in memory only and is never stored or sent anywhere.",
            AutoSize = true,
            MaximumSize = new Size(580, 0),
        }, 0, 3);
        layout.SetColumnSpan(layout.GetControlFromPosition(0, 3)!, 2);

        var testGroup = new GroupBox { Text = "Test knock", Dock = DockStyle.Fill, Padding = new Padding(10) };
        var testLayout = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 1, RowCount = 4 };
        testLayout.RowStyles.Add(new RowStyle(SizeType.Absolute, 42));
        testLayout.RowStyles.Add(new RowStyle(SizeType.Absolute, 28));
        testLayout.RowStyles.Add(new RowStyle(SizeType.Absolute, 34));
        testLayout.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        var testControls = new FlowLayoutPanel { Dock = DockStyle.Fill, WrapContents = false };
        testControls.Controls.Add(_testButton);
        testControls.Controls.Add(_sensitivityLabel);
        testControls.Controls.Add(_sensitivity);
        testLayout.Controls.Add(testControls, 0, 0);
        testLayout.Controls.Add(_levelLabel, 0, 1);
        testLayout.Controls.Add(_levelMeter, 0, 2);
        testLayout.Controls.Add(_eventLog, 0, 3);
        testGroup.Controls.Add(testLayout);
        layout.Controls.Add(testGroup, 0, 4);
        layout.SetColumnSpan(testGroup, 2);

        var buttons = new FlowLayoutPanel { FlowDirection = FlowDirection.RightToLeft, Dock = DockStyle.Fill };
        var save = new Button { Text = "Save", DialogResult = DialogResult.OK, AutoSize = true };
        var cancel = new Button { Text = "Cancel", DialogResult = DialogResult.Cancel, AutoSize = true };
        buttons.Controls.Add(save);
        buttons.Controls.Add(cancel);
        layout.Controls.Add(buttons, 0, 5);
        layout.SetColumnSpan(buttons, 2);
        Controls.Add(layout);
        AcceptButton = save;
        CancelButton = cancel;
        _testButton.Click += (_, _) =>
        {
            if (_testListener is null) StartTest();
            else StopTest();
        };
        _sensitivity.ValueChanged += (_, _) =>
        {
            UpdateSensitivityLabel();
            if (_testListener is not null)
                _testListener.Config = KnockConfig.FromSensitivity(_sensitivity.Value);
        };
        FormClosing += (_, _) => StopTest();
        save.Click += (_, _) =>
        {
            if (!string.IsNullOrWhiteSpace(_relay.Text)
                && (!Uri.TryCreate(_relay.Text, UriKind.Absolute, out var uri) || uri.Scheme is not ("http" or "https")))
            {
                MessageBox.Show(this, "Enter a valid http(s) relay URL.", "TapDrop", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                DialogResult = DialogResult.None;
                return;
            }
            config.RelayUrl = _relay.Text.Trim().TrimEnd('/');
            config.DeviceName = string.IsNullOrWhiteSpace(_name.Text) ? Environment.MachineName : _name.Text.Trim();
            config.KnockToSend = _knockToSend.Checked;
            config.KnockSensitivity = _sensitivity.Value;
        };
    }

    private void StartTest()
    {
        var listener = new KnockListener(KnockConfig.FromSensitivity(_sensitivity.Value));
        listener.Transient += knockEvent => RunOnUi(() => AppendEvent(
            $"{knockEvent.TimeSeconds:0.00} s  transient  {knockEvent.RatioDb:+0;-0;0} dB  crest {knockEvent.CrestFactor:0.0}"));
        listener.Triggered += knockEvent => RunOnUi(() => AppendEvent("DOUBLE KNOCK — would send clipboard"));
        listener.Level += (level, floor) =>
        {
            var now = Environment.TickCount64;
            if (now - Interlocked.Read(ref _lastMeterTick) < 50) return;
            Interlocked.Exchange(ref _lastMeterTick, now);
            RunOnUi(() =>
            {
                _levelMeter.Value = Math.Clamp((int)Math.Round(level + 80), _levelMeter.Minimum, _levelMeter.Maximum);
                _levelLabel.Text = $"Peak: {level:0} dBFS · Noise floor: {floor:0} dBFS";
            });
        };
        if (!listener.Start(out var error))
        {
            listener.Dispose();
            MessageBox.Show(this, error, "TapDrop", MessageBoxButtons.OK, MessageBoxIcon.Warning);
            return;
        }
        _testListener = listener;
        _testButton.Text = "Stop test";
    }

    private void StopTest()
    {
        _testListener?.Dispose();
        _testListener = null;
        _testButton.Text = "Start test";
    }

    private void UpdateSensitivityLabel() => _sensitivityLabel.Text = $"Sensitivity: {_sensitivity.Value}";

    private void AppendEvent(string line)
    {
        if (_eventLog.Items.Count >= 200) _eventLog.Items.RemoveAt(0);
        _eventLog.Items.Add(line);
        _eventLog.TopIndex = _eventLog.Items.Count - 1;
    }

    private void RunOnUi(Action action)
    {
        if (IsDisposed || Disposing || !IsHandleCreated) return;
        try
        {
            BeginInvoke(new Action(() =>
            {
                if (!IsDisposed && !Disposing) action();
            }));
        }
        catch (InvalidOperationException) { }
    }
}

internal sealed class PairDialog : Form
{
    private readonly Label _status = new() { AutoSize = true, Text = "Waiting for the phone to scan this code…" };
    public PairDialog(PairInfo pair)
    {
        Text = "Pair phone";
        StartPosition = FormStartPosition.CenterParent;
        Width = 440;
        Height = 500;
        var imageBox = new PictureBox
        {
            Width = 360,
            Height = 360,
            SizeMode = PictureBoxSizeMode.Zoom,
            Left = 32,
            Top = 16,
        };
        using var generator = new QRCodeGenerator();
        using var code = generator.CreateQrCode(pair.ToQrPayload(), QRCodeGenerator.ECCLevel.Q);
        using var qr = new PngByteQRCode(code);
        using var data = new MemoryStream(qr.GetGraphic(8));
        using var image = Image.FromStream(data);
        imageBox.Image = new Bitmap(image);
        _status.Left = 32;
        _status.Top = 394;
        var cancel = new Button { Text = "Close", Left = 32, Top = 424, Width = 80, DialogResult = DialogResult.Cancel };
        Controls.Add(imageBox);
        Controls.Add(_status);
        Controls.Add(cancel);
        CancelButton = cancel;
    }

    public void SetStatus(string text) => _status.Text = text;
}
