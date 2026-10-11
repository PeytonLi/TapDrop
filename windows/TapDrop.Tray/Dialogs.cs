using QRCoder;
using TapDrop.Core;

namespace TapDrop.Tray;

internal sealed class SettingsDialog : Form
{
    private readonly TextBox _relay = new() { Width = 360 };
    private readonly TextBox _name = new() { Width = 360 };

    public SettingsDialog(TrayConfig config)
    {
        Text = "TapDrop Settings";
        FormBorderStyle = FormBorderStyle.FixedDialog;
        StartPosition = FormStartPosition.CenterScreen;
        MaximizeBox = false;
        MinimizeBox = false;
        Width = 480;
        Height = 190;

        _relay.Text = string.IsNullOrWhiteSpace(config.RelayUrl) ? RelayDefaults.Url : config.RelayUrl;
        _name.Text = config.DeviceName;
        var layout = new TableLayoutPanel { Dock = DockStyle.Fill, Padding = new Padding(12), ColumnCount = 2, RowCount = 3 };
        layout.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 110));
        layout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        layout.Controls.Add(new Label { Text = "Relay URL", AutoSize = true, Anchor = AnchorStyles.Left }, 0, 0);
        layout.Controls.Add(_relay, 1, 0);
        layout.Controls.Add(new Label { Text = "Device name", AutoSize = true, Anchor = AnchorStyles.Left }, 0, 1);
        layout.Controls.Add(_name, 1, 1);
        var buttons = new FlowLayoutPanel { FlowDirection = FlowDirection.RightToLeft, Dock = DockStyle.Fill };
        var save = new Button { Text = "Save", DialogResult = DialogResult.OK, AutoSize = true };
        var cancel = new Button { Text = "Cancel", DialogResult = DialogResult.Cancel, AutoSize = true };
        buttons.Controls.Add(save);
        buttons.Controls.Add(cancel);
        layout.Controls.Add(buttons, 1, 2);
        Controls.Add(layout);
        AcceptButton = save;
        CancelButton = cancel;
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
        };
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
