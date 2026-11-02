//go:build windows

package main

import (
	"bytes"
	"fmt"
	"os/exec"
	"strings"
	"syscall"
)

// The default console code page is not UTF-8, which garbles the Chinese start-up messages.
func init() {
	kernel32 := syscall.NewLazyDLL("kernel32.dll")
	_, _, _ = kernel32.NewProc("SetConsoleOutputCP").Call(65001)
}

func hidden(cmd *exec.Cmd) *exec.Cmd {
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true}
	return cmd
}

// openBrowser opens url in the default browser.
func openBrowser(url string) {
	_ = hidden(exec.Command("rundll32", "url.dll,FileProtocolHandler", url)).Start()
}

// psTopmostType pulls in WinForms/Win32 so we can pin a picker dialog above the
// browser window that hosts the local control page.
const psTopmostType = `
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
Add-Type @"
using System;
using System.Runtime.InteropServices;
public class W32 {
  [DllImport("user32.dll")] public static extern IntPtr FindWindow(string cls, string title);
  [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr h, IntPtr after, int x, int y, int cx, int cy, uint f);
  public static readonly IntPtr TOPMOST = new IntPtr(-1);
  public const uint SWP_NOSIZE = 0x0001;
  public const uint SWP_NOMOVE = 0x0002;
}
"@
`

// psPinTopmost returns a snippet that keeps the window with the given title on
// top of every other window (the browser included) until it is closed. It must
// be appended before the dialog is shown; the timer runs inside the dialog's
// own message loop.
func psPinTopmost(title string) string {
	return fmt.Sprintf(`
$timer = New-Object System.Windows.Forms.Timer
$timer.Interval = 150
$timer.Add_Tick({
  $h = [W32]::FindWindow($null, '%s')
  if ($h -ne [IntPtr]::Zero) {
    [W32]::SetWindowPos($h, [W32]::TOPMOST, 0, 0, 0, 0, [W32]::SWP_NOSIZE -bor [W32]::SWP_NOMOVE) | Out-Null
    $timer.Stop()
  }
})
$timer.Start()
`, title)
}

// pickFile shows the standard Windows "open file" dialog and returns the chosen path ("" if cancelled).
// This is a WinForms OpenFileDialog, whose owner window can be pinned on top, so it never hides behind the browser.
func pickFile() (string, error) {
	title := "选择视频文件（选文件夹里的任意一个，会自动载入整文件夹）"
	script := psTopmostType + fmt.Sprintf(`
$owner = New-Object System.Windows.Forms.Form -Property @{ TopMost=$true; ShowInTaskbar=$false; Opacity=0; Size=New-Object System.Drawing.Size(0,0) }
$owner.Show()
%s
$f = New-Object System.Windows.Forms.OpenFileDialog
$f.Title = '%s'
$f.Filter = '视频文件|*.mp4;*.mkv;*.avi;*.mov;*.flv;*.ts;*.wmv;*.webm;*.m4v|所有文件|*.*'
$res = $f.ShowDialog($owner)
$owner.Close()
if ($res -eq 'OK') { $f.FileName }
`, psPinTopmost(title), title)
	out, err := hidden(exec.Command("powershell", "-NoProfile", "-STA", "-Command", script)).Output()
	if err != nil {
		return "", err
	}
	return strings.TrimSpace(string(bytes.TrimPrefix(out, []byte("\xef\xbb\xbf")))), nil
}

// allowFirewall opens the media port for private networks so the TV can reach this PC. Needs administrator rights.
func allowFirewall(port int) error {
	name := "EasyCast media"
	_ = hidden(exec.Command("netsh", "advfirewall", "firewall", "delete", "rule", "name="+name)).Run()
	err := hidden(exec.Command("netsh", "advfirewall", "firewall", "add", "rule",
		"name="+name, "dir=in", "action=allow", "protocol=TCP",
		fmt.Sprintf("localport=%d", port), "profile=private,domain")).Run()
	return err
}
