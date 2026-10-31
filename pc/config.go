package main

import (
	"encoding/json"
	"net"
	"os"
	"path/filepath"
	"strings"
	"sync"
)

type config struct {
	TVAddr     string `json:"tv_addr"`     // e.g. 192.168.1.3:8200 (a router forward) or 192.168.5.57:8200
	TVPin      string `json:"tv_pin"`      // code shown on the TV's main screen
	HostIP     string `json:"host_ip"`     // address the TV uses to reach this PC; empty = auto
	MediaPort  int    `json:"media_port"`  // HTTP port the TV pulls files and live streams from
	RTMPListen string `json:"rtmp_listen"` // where OBS publishes to
	UIPort     int    `json:"ui_port"`
	// Disconnected parks the saved address and PIN without contacting the TV (the "断开连接" button).
	// Zero value = connected, so pc.json files written before this field existed keep working.
	Disconnected bool `json:"disconnected"`
}

func defaultConfig() config {
	return config{TVAddr: "", MediaPort: 8000, RTMPListen: "127.0.0.1:1935", UIPort: 9000}
}

type configStore struct {
	mu   sync.Mutex
	path string
	cfg  config
}

func openConfig() *configStore {
	dir, err := os.UserConfigDir()
	if err != nil {
		dir = "."
	}
	s := &configStore{path: filepath.Join(dir, "EasyCast", "pc.json"), cfg: defaultConfig()}
	if b, err := os.ReadFile(s.path); err == nil {
		_ = json.Unmarshal(b, &s.cfg)
	}
	if s.cfg.MediaPort == 0 {
		s.cfg.MediaPort = 8000
	}
	if s.cfg.UIPort == 0 {
		s.cfg.UIPort = 9000
	}
	if s.cfg.RTMPListen == "" {
		s.cfg.RTMPListen = "127.0.0.1:1935"
	}
	return s
}

func (s *configStore) get() config {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.cfg
}

func (s *configStore) update(f func(*config)) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	f(&s.cfg)
	if err := os.MkdirAll(filepath.Dir(s.path), 0o755); err != nil {
		return err
	}
	b, _ := json.MarshalIndent(s.cfg, "", "  ")
	return os.WriteFile(s.path, b, 0o600) // holds the TV's PIN
}

// lanIPs lists this PC's private IPv4 addresses, skipping virtual adapters where it can tell.
func lanIPs() []string {
	var out []string
	ifs, _ := net.Interfaces()
	for _, ifc := range ifs {
		if ifc.Flags&net.FlagUp == 0 || ifc.Flags&net.FlagLoopback != 0 {
			continue
		}
		n := strings.ToLower(ifc.Name)
		if strings.Contains(n, "vmware") || strings.Contains(n, "virtualbox") ||
			strings.Contains(n, "vethernet") || strings.Contains(n, "docker") || strings.Contains(n, "wsl") {
			continue
		}
		addrs, _ := ifc.Addrs()
		for _, a := range addrs {
			if ipn, ok := a.(*net.IPNet); ok {
				if ip4 := ipn.IP.To4(); ip4 != nil && ip4.IsPrivate() {
					out = append(out, ip4.String())
				}
			}
		}
	}
	return out
}

// hostIP is the address put into the URLs the TV fetches.
func (c config) hostIP() string {
	if c.HostIP != "" {
		return c.HostIP
	}
	if ips := lanIPs(); len(ips) > 0 {
		return ips[0]
	}
	return "127.0.0.1"
}
