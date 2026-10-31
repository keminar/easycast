package main

import (
	_ "embed"
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
)

//go:embed web/index.html
var indexHTML []byte

// app is the control side: a small web UI on localhost that drives the TV.
type app struct {
	cfg   *configStore
	hub   *hub
	files *fileRegistry
}

// tv returns the client for the saved TV. After "断开连接" the address is empty on purpose, so every
// call fails with a clear "还没有连接电视" message instead of silently talking to the TV.
func (a *app) tv() tvClient {
	c := a.cfg.get()
	if c.Disconnected {
		return tvClient{}
	}
	return tvClient{addr: c.TVAddr, pin: c.TVPin}
}

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(v)
}

func fail(w http.ResponseWriter, code int, err error) {
	writeJSON(w, code, map[string]string{"error": err.Error()})
}

// uiHandler serves the page and the JSON API. It is bound to 127.0.0.1 only, and the checks below
// keep other web pages in the user's browser from driving it (CSRF / DNS rebinding).
func (a *app) uiHandler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/" {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_, _ = w.Write(indexHTML)
	})
	mux.HandleFunc("/api/status", a.status)
	mux.HandleFunc("/api/config", a.setConfig)
	mux.HandleFunc("/api/disconnect", a.disconnect)
	mux.HandleFunc("/api/pick", a.pick)
	mux.HandleFunc("/api/file-link", a.fileLink)
	mux.HandleFunc("/api/play-file", a.playFile)
	mux.HandleFunc("/api/play-live", a.playLive)
	mux.HandleFunc("/api/rename", a.rename)
	mux.HandleFunc("/api/seek", a.tvSeek)
	mux.HandleFunc("/api/tv/", a.tvCommand)
	mux.HandleFunc("/api/firewall", a.firewall)

	port := a.cfg.get().UIPort
	allowed := map[string]bool{
		fmt.Sprintf("127.0.0.1:%d", port): true,
		fmt.Sprintf("localhost:%d", port): true,
	}
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !allowed[r.Host] {
			http.Error(w, "forbidden host", http.StatusForbidden)
			return
		}
		if strings.HasPrefix(r.URL.Path, "/api/") && r.Method == http.MethodPost && r.Header.Get("X-EasyCast") != "1" {
			http.Error(w, "forbidden", http.StatusForbidden)
			return
		}
		mux.ServeHTTP(w, r)
	})
}

func (a *app) status(w http.ResponseWriter, r *http.Request) {
	cfg := a.cfg.get()
	configured := strings.TrimSpace(cfg.TVAddr) != ""
	linked := configured && !cfg.Disconnected
	tv := map[string]any{"configured": configured, "linked": linked, "ok": false}
	if linked {
		if st, err := a.tv().state(); err != nil {
			tv["ok"], tv["error"] = false, err.Error()
		} else {
			tv["ok"], tv["device"], tv["state"], tv["uri"] = true, st["device"], st["state"], st["uri"]
			tv["pos"], tv["dur"], tv["seekable"] = st["pos"], st["dur"], st["seekable"]
		}
	}
	rtmpAddr := cfg.RTMPListen
	if strings.HasPrefix(rtmpAddr, "0.0.0.0") {
		rtmpAddr = cfg.hostIP() + strings.TrimPrefix(rtmpAddr, "0.0.0.0")
	}
	writeJSON(w, 200, map[string]any{
		"config":     cfg,
		"pc_ip":      cfg.hostIP(),
		"pc_ips":     lanIPs(),
		"media_port": cfg.MediaPort,
		"rtmp_url":   "rtmp://" + rtmpAddr + "/live",
		"obs_key":    "tv",
		"live":       a.hub.info(),
		"tv":         tv,
	})
}

func (a *app) setConfig(w http.ResponseWriter, r *http.Request) {
	var in struct {
		TVAddr string `json:"tv_addr"`
		TVPin  string `json:"tv_pin"`
		HostIP string `json:"host_ip"`
		Link   bool   `json:"link"` // true = "保存并连接"; absent/false only stores the values
	}
	if err := json.NewDecoder(r.Body).Decode(&in); err != nil {
		fail(w, 400, err)
		return
	}
	err := a.cfg.update(func(c *config) {
		c.TVAddr = strings.TrimSpace(in.TVAddr)
		c.TVPin = strings.TrimSpace(in.TVPin)
		c.HostIP = strings.TrimSpace(in.HostIP)
		if in.Link {
			c.Disconnected = false
		}
	})
	if err != nil {
		fail(w, 500, err)
		return
	}
	writeJSON(w, 200, map[string]bool{"ok": true})
}

// disconnect stops talking to the TV but keeps the address and PIN on disk, so reconnecting later
// needs no re-typing. It does not stop what the TV is already playing (use "停止" for that).
func (a *app) disconnect(w http.ResponseWriter, r *http.Request) {
	err := a.cfg.update(func(c *config) {
		if strings.TrimSpace(c.TVAddr) != "" {
			c.Disconnected = true
		}
	})
	if err != nil {
		fail(w, 500, err)
		return
	}
	writeJSON(w, 200, map[string]bool{"ok": true})
}

func (a *app) pick(w http.ResponseWriter, r *http.Request) {
	p, err := pickFile()
	if err != nil {
		fail(w, 500, err)
		return
	}
	writeJSON(w, 200, map[string]string{"path": p})
}

// registerPath makes a local file available over HTTP and returns the address anything on the
// network (the TV, VLC, a browser, a phone) can open, without pushing it anywhere.
func (a *app) registerPath(p string) (string, error) {
	p = strings.Trim(strings.TrimSpace(p), `"`) // Explorer's "copy as path" adds quotes
	id, err := a.files.add(p)
	if err != nil {
		return "", err
	}
	cfg := a.cfg.get()
	return fileURL(cfg.hostIP(), cfg.MediaPort, id, p), nil
}

// playPath registers a local file and tells the TV to fetch it from this PC.
func (a *app) playPath(p string) (string, error) {
	u, err := a.registerPath(p)
	if err != nil {
		return "", err
	}
	return u, a.tv().play(u)
}

// fileLink returns the playable address of a local file and does not touch the TV.
func (a *app) fileLink(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Path string `json:"path"`
	}
	if err := json.NewDecoder(r.Body).Decode(&in); err != nil || strings.TrimSpace(in.Path) == "" {
		fail(w, 400, fmt.Errorf("请先选择或填写视频文件路径"))
		return
	}
	u, err := a.registerPath(in.Path)
	if err != nil {
		fail(w, 400, err)
		return
	}
	writeJSON(w, 200, map[string]string{"url": u})
}

func (a *app) playFile(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Path string `json:"path"`
	}
	if err := json.NewDecoder(r.Body).Decode(&in); err != nil || strings.TrimSpace(in.Path) == "" {
		fail(w, 400, fmt.Errorf("请先选择或填写视频文件路径"))
		return
	}
	u, err := a.playPath(in.Path)
	if err != nil {
		fail(w, 502, err)
		return
	}
	writeJSON(w, 200, map[string]string{"url": u})
}

func (a *app) playLive(w http.ResponseWriter, r *http.Request) {
	cfg := a.cfg.get()
	u := fmt.Sprintf("http://%s:%d/live/tv.flv", cfg.hostIP(), cfg.MediaPort)
	if err := a.tv().play(u); err != nil {
		fail(w, 502, err)
		return
	}
	writeJSON(w, 200, map[string]string{"url": u})
}

// rename changes the TV's advertised name. Phones see it in their cast list after the TV re-announces.
func (a *app) rename(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Name string `json:"name"`
	}
	if err := json.NewDecoder(r.Body).Decode(&in); err != nil {
		fail(w, 400, err)
		return
	}
	name := strings.TrimSpace(in.Name)
	if name == "" {
		fail(w, 400, fmt.Errorf("名字不能为空"))
		return
	}
	if err := a.tv().rename(name); err != nil {
		fail(w, 502, err)
		return
	}
	writeJSON(w, 200, map[string]any{"ok": true, "device": name})
}

func (a *app) tvCommand(w http.ResponseWriter, r *http.Request) {
	cmd := strings.TrimPrefix(r.URL.Path, "/api/tv/")
	switch cmd {
	case "pause", "resume", "stop":
	default:
		http.NotFound(w, r)
		return
	}
	if err := a.tv().command(cmd); err != nil {
		fail(w, 502, err)
		return
	}
	writeJSON(w, 200, map[string]bool{"ok": true})
}

// tvSeek forwards a seek to the TV: {"delta": -10000} jumps relative, {"ms": 120000} jumps to a position.
func (a *app) tvSeek(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Delta *int64 `json:"delta"`
		MS    *int64 `json:"ms"`
	}
	if err := json.NewDecoder(r.Body).Decode(&in); err != nil || (in.Delta == nil && in.MS == nil) {
		fail(w, 400, fmt.Errorf("需要 delta 或 ms"))
		return
	}
	var err error
	if in.MS != nil {
		err = a.tv().seek(*in.MS, true)
	} else {
		err = a.tv().seek(*in.Delta, false)
	}
	if err != nil {
		fail(w, 502, err)
		return
	}
	writeJSON(w, 200, map[string]bool{"ok": true})
}

func (a *app) firewall(w http.ResponseWriter, r *http.Request) {
	port := a.cfg.get().MediaPort
	if err := allowFirewall(port); err != nil {
		fail(w, 500, fmt.Errorf("放行失败（需要以管理员身份运行本程序），也可以手动放行 TCP %d：%v", port, err))
		return
	}
	writeJSON(w, 200, map[string]bool{"ok": true})
}
