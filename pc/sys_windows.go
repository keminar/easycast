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

// pickFile shows the standard Windows "open file" dialog and returns the chosen path ("" if cancelled).
func pickFile() (string, error) {
	script := `
Add-Type -AssemblyName System.Windows.Forms
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
$owner = New-Object System.Windows.Forms.Form -Property @{ TopMost = $true; ShowInTaskbar = $false }
$f = New-Object System.Windows.Forms.OpenFileDialog
$f.Title = '选择要推送到电视的视频'
$f.Filter = '视频文件|*.mp4;*.mkv;*.avi;*.mov;*.flv;*.ts;*.wmv;*.webm;*.m4v|所有文件|*.*'
if ($f.ShowDialog($owner) -eq 'OK') { $f.FileName }
`
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
