package main

import (
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

// How often the playlist watcher polls the TV to learn when the current file ended.
// Overridden to a small value in tests so sequential playback can be exercised quickly.
var playlistPoll = 2 * time.Second

// videoExt are the file extensions treated as playable video when listing a directory.
var videoExt = map[string]bool{
	".mp4": true, ".mkv": true, ".avi": true, ".mov": true, ".flv": true,
	".ts": true, ".wmv": true, ".webm": true, ".m4v": true, ".m2ts": true,
	".mpg": true, ".mpeg": true, ".3gp": true, ".vob": true, ".ogv": true, ".mts": true,
}

// playItem is one entry in a sequential-playback queue.
type playItem struct {
	Name string `json:"name"`
	URL  string `json:"url"` // address the TV fetches from this PC
}

// playlist drives sequential playback: it plays one item, then watches the TV and
// advances to the next once the current file ends on its own (or stops if the user does).
type playlist struct {
	mu      sync.Mutex
	active  bool
	items   []playItem
	idx     int
	done    chan struct{} // closed to stop the watcher goroutine
	playing bool          // the watcher has confirmed the current item is actually playing
	stuck   int           // consecutive polls that saw STOPPED without a decisive action
}

func newPlaylist() *playlist { return &playlist{} }

// isLinked reports whether we have a saved, connected TV to drive.
func (a *app) isLinked() bool {
	c := a.cfg.get()
	return strings.TrimSpace(c.TVAddr) != "" && !c.Disconnected
}

// listDir reads a directory on this PC, registers every video in the file registry, and
// returns them in natural-sorted order with the URL the TV will fetch. Sub-directories are skipped.
func (a *app) listDir(dir string) ([]playItem, error) {
	dir = strings.Trim(strings.TrimSpace(dir), `"`)
	info, err := os.Stat(dir)
	if err != nil {
		return nil, err
	}
	if !info.IsDir() {
		return nil, fmt.Errorf("这不是一个文件夹：%s", dir)
	}
	entries, err := os.ReadDir(dir)
	if err != nil {
		return nil, err
	}
	cfg := a.cfg.get()
	host := cfg.hostIP()
	var names []string
	for _, e := range entries {
		if e.IsDir() {
			continue
		}
		if videoExt[strings.ToLower(filepath.Ext(e.Name()))] {
			names = append(names, e.Name())
		}
	}
	sortNatural(names)
	var items []playItem
	for _, name := range names {
		p := filepath.Join(dir, name)
		id, err := a.files.add(p)
		if err != nil {
			continue
		}
		items = append(items, playItem{Name: name, URL: fileURL(host, cfg.MediaPort, id, p)})
	}
	if len(items) == 0 {
		return nil, fmt.Errorf("这个文件夹里没有找到视频文件（支持 %s 等）", strings.Join(videoExtList(), "、"))
	}
	return items, nil
}

// startPlaylist plays items in order and watches the TV to advance automatically.
func (a *app) startPlaylist(items []playItem) error {
	if !a.isLinked() {
		return fmt.Errorf("还没有连接电视：请填好电视地址和口令，点「保存并连接」")
	}
	if len(items) == 0 {
		return fmt.Errorf("没有可播放的视频")
	}
	a.pl.mu.Lock()
	a.pl.resetLocked()
	a.pl.items = items
	a.pl.idx = 0
	a.pl.active = true
	done := make(chan struct{})
	a.pl.done = done
	a.pl.mu.Unlock()

	if err := a.tv().play(items[0].URL); err != nil {
		a.pl.mu.Lock()
		a.pl.active = false
		a.pl.mu.Unlock()
		return err
	}
	go a.watchPlaylist(done)
	return nil
}

// stopPlaylist ends the watcher and tells the TV to stop the current video.
func (a *app) stopPlaylist() {
	a.pl.mu.Lock()
	a.pl.resetLocked()
	a.pl.mu.Unlock()
	_ = a.tv().command("stop")
}

// resetLocked tears down any running watcher; caller must hold pl.mu.
func (p *playlist) resetLocked() {
	if p.done != nil {
		close(p.done)
		p.done = nil
	}
	p.items = nil
	p.idx = 0
	p.active = false
	p.playing = false
	p.stuck = 0
}

// watchPlaylist polls the TV and advances through the queue. It ends when the whole
// queue is done, the user stops playback, playback fails to start, or done is closed.
//
// A natural end is detected as state STOPPED with the position still at the end (pos > 0);
// a manual stop resets the position to 0. We only advance after we have confirmed the
// current item is actually playing (playing == true), so a short pre-play window where the
// TV still reports STOPPED is not mistaken for an end, and advancing to the next item does
// not double-advance before the TV reports the new item playing.
func (a *app) watchPlaylist(done chan struct{}) {
	advance := func(next int, items []playItem) {
		a.pl.mu.Lock()
		a.pl.playing = false
		a.pl.mu.Unlock()
		if err := a.tv().play(items[next].URL); err != nil {
			a.pl.mu.Lock()
			a.pl.active = false
			a.pl.mu.Unlock()
			return
		}
		a.pl.mu.Lock()
		a.pl.idx = next
		a.pl.mu.Unlock()
	}
	for {
		select {
		case <-done:
			return
		case <-time.After(playlistPoll):
		}

		a.pl.mu.Lock()
		if !a.pl.active {
			a.pl.mu.Unlock()
			return
		}
		idx := a.pl.idx
		items := a.pl.items
		playing := a.pl.playing
		a.pl.mu.Unlock()

		if idx >= len(items) {
			return
		}
		st, err := a.tv().state()
		if err != nil {
			// TV unreachable for now; keep waiting. If the user stops the playlist, done ends us.
			continue
		}
		state, _ := st["state"].(string)
		pos := toInt64(st["pos"])

		switch state {
		case "PLAYING", "TRANSITIONING":
			a.pl.mu.Lock()
			a.pl.playing = true
			a.pl.stuck = 0
			a.pl.mu.Unlock()
			continue
		case "STOPPED":
			a.pl.mu.Lock()
			if pos <= 0 {
				// Position reset: a genuine user stop ends the queue. If we never confirmed a play,
				// it is a slow start or a play that never began, so keep waiting for a while.
				if playing {
					a.pl.active = false
					a.pl.mu.Unlock()
					return
				}
			} else {
				// Natural end (position left at the end of the file). Only advance once we have
				// actually seen this item play, so a pre-play blip is not mistaken for an end.
				if !playing {
					if a.pl.stuck++; a.pl.stuck > 40 {
						a.pl.active = false
						a.pl.mu.Unlock()
						return
					}
					a.pl.mu.Unlock()
					continue
				}
				next := idx + 1
				if next >= len(items) {
					a.pl.active = false
					a.pl.mu.Unlock()
					return
				}
				a.pl.mu.Unlock()
				advance(next, items)
				continue
			}
			// STOPPED with pos 0 and not yet playing: wait longer before giving up.
			if a.pl.stuck++; a.pl.stuck > 40 {
				a.pl.active = false
				a.pl.mu.Unlock()
				return
			}
			a.pl.mu.Unlock()
		default:
			continue
		}
	}
}

// playlistInfo returns what the control page needs to show queue progress.
func (a *app) playlistInfo() map[string]any {
	a.pl.mu.Lock()
	defer a.pl.mu.Unlock()
	if !a.pl.active {
		return map[string]any{"active": false}
	}
	var cur string
	if a.pl.idx >= 0 && a.pl.idx < len(a.pl.items) {
		cur = a.pl.items[a.pl.idx].Name
	}
	items := make([]playItem, 0, len(a.pl.items))
	for _, it := range a.pl.items {
		items = append(items, it)
	}
	return map[string]any{
		"active":  true,
		"index":   a.pl.idx,
		"count":   len(a.pl.items),
		"current": cur,
		"items":   items,
	}
}

// ---------------------------------------------------------------- helpers

// browseEntry is one folder or video shown by the in-page file browser.
type browseEntry struct {
	Name string `json:"name"`
	Path string `json:"path"`
}

// listRoots lists the drive letters (Windows) or "/" (other) shown at the top of the browser.
func listRoots() []browseEntry {
	out := []browseEntry{}
	if runtime.GOOS == "windows" {
		for c := 'A'; c <= 'Z'; c++ {
			p := string(c) + ":\\"
			if _, err := os.Stat(p); err == nil {
				out = append(out, browseEntry{Name: p, Path: p})
			}
		}
		return out
	}
	out = append(out, browseEntry{Name: "/", Path: "/"})
	if home, err := os.UserHomeDir(); err == nil && home != "/" {
		out = append(out, browseEntry{Name: home, Path: home})
	}
	return out
}

// parentDir returns the parent folder, or "" at a drive root (so the UI can fall back to the drive list).
func parentDir(p string) string {
	parent := filepath.Dir(p)
	if parent == p {
		return ""
	}
	return parent
}

func toInt64(v any) int64 {
	switch n := v.(type) {
	case float64:
		return int64(n)
	case int64:
		return n
	case int:
		return int64(n)
	}
	return 0
}

func videoExtList() []string {
	out := make([]string, 0, len(videoExt))
	for k := range videoExt {
		out = append(out, k)
	}
	sort.Strings(out)
	return out
}

// sortNatural orders names so that "2" comes before "10" (episode ordering).
func sortNatural(names []string) {
	sort.Slice(names, func(i, j int) bool { return naturalLess(names[i], names[j]) })
}

func isDigit(b byte) bool { return b >= '0' && b <= '9' }

func naturalLess(a, b string) bool {
	ai, bi := 0, 0
	for ai < len(a) && bi < len(b) {
		if isDigit(a[ai]) && isDigit(b[bi]) {
			as, ae := ai, ai
			for ae < len(a) && isDigit(a[ae]) {
				ae++
			}
			bs, be := bi, bi
			for be < len(b) && isDigit(b[be]) {
				be++
			}
			an, _ := strconv.Atoi(a[as:ae])
			bn, _ := strconv.Atoi(b[bs:be])
			if an != bn {
				return an < bn
			}
			ai, bi = ae, be
		} else {
			if a[ai] != b[bi] {
				return a[ai] < b[bi]
			}
			ai++
			bi++
		}
	}
	return len(a) < len(b)
}

// ---------------------------------------------------------------- HTTP handlers

// browse powers the in-page folder picker. It returns the sub-folders and videos of the given
// directory (or the drive list when path is empty). Rendering it inside the page means it can
// never hide behind the browser window, unlike a native PowerShell dialog.
func (a *app) browse(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Path string `json:"path"`
	}
	_ = json.NewDecoder(r.Body).Decode(&in)
	p := strings.Trim(strings.TrimSpace(in.Path), `"`)
	if p == "" {
		writeJSON(w, 200, map[string]any{
			"path": "", "parent": "", "dirs": listRoots(), "videos": []browseEntry{},
		})
		return
	}
	info, err := os.Stat(p)
	if err != nil {
		fail(w, 400, fmt.Errorf("无法打开：%s", p))
		return
	}
	if !info.IsDir() {
		fail(w, 400, fmt.Errorf("这不是文件夹：%s", p))
		return
	}
	entries, err := os.ReadDir(p)
	if err != nil {
		fail(w, 400, err)
		return
	}
	dirs := []browseEntry{}
	videos := []browseEntry{}
	for _, e := range entries {
		name := e.Name()
		full := filepath.Join(p, name)
		if e.IsDir() {
			dirs = append(dirs, browseEntry{Name: name, Path: full})
		} else if videoExt[strings.ToLower(filepath.Ext(name))] {
			videos = append(videos, browseEntry{Name: name, Path: full})
		}
	}
	sort.Slice(dirs, func(i, j int) bool { return strings.ToLower(dirs[i].Name) < strings.ToLower(dirs[j].Name) })
	sort.Slice(videos, func(i, j int) bool { return naturalLess(videos[i].Name, videos[j].Name) })
	writeJSON(w, 200, map[string]any{
		"path": p, "parent": parentDir(p), "dirs": dirs, "videos": videos,
	})
}

func (a *app) listDirHandler(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Path string `json:"path"`
	}
	if err := json.NewDecoder(r.Body).Decode(&in); err != nil || strings.TrimSpace(in.Path) == "" {
		fail(w, 400, fmt.Errorf("请先选择视频文件夹"))
		return
	}
	items, err := a.listDir(in.Path)
	if err != nil {
		fail(w, 400, err)
		return
	}
	writeJSON(w, 200, map[string]any{"items": items})
}

func (a *app) playlistStart(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Items []playItem `json:"items"`
	}
	if err := json.NewDecoder(r.Body).Decode(&in); err != nil || len(in.Items) == 0 {
		fail(w, 400, fmt.Errorf("没有要播放的视频（先列出文件夹）"))
		return
	}
	if err := a.startPlaylist(in.Items); err != nil {
		fail(w, 502, err)
		return
	}
	writeJSON(w, 200, map[string]bool{"ok": true})
}

func (a *app) playlistStop(w http.ResponseWriter, r *http.Request) {
	a.stopPlaylist()
	writeJSON(w, 200, map[string]bool{"ok": true})
}
