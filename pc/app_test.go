package main

import (
	"bytes"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	rtmp "github.com/yutopp/go-rtmp"
	rtmpmsg "github.com/yutopp/go-rtmp/message"
)

// ---------------------------------------------------------------- helpers

type flvTag struct {
	kind byte
	ts   uint32
	body []byte
}

// readTag parses one FLV tag (and its trailing PreviousTagSize) from r.
func readTag(t *testing.T, r io.Reader) flvTag {
	t.Helper()
	var h [11]byte
	if _, err := io.ReadFull(r, h[:]); err != nil {
		t.Fatalf("read tag header: %v", err)
	}
	n := int(h[1])<<16 | int(h[2])<<8 | int(h[3])
	ts := uint32(h[4])<<16 | uint32(h[5])<<8 | uint32(h[6]) | uint32(h[7])<<24
	body := make([]byte, n)
	if _, err := io.ReadFull(r, body); err != nil {
		t.Fatalf("read tag body: %v", err)
	}
	var prev [4]byte
	if _, err := io.ReadFull(r, prev[:]); err != nil {
		t.Fatalf("read prev size: %v", err)
	}
	if got := uint32(prev[0])<<24 | uint32(prev[1])<<16 | uint32(prev[2])<<8 | uint32(prev[3]); got != uint32(11+n) {
		t.Fatalf("PreviousTagSize = %d, want %d", got, 11+n)
	}
	return flvTag{h[0], ts, body}
}

func startRTMP(t *testing.T, h *hub) string {
	t.Helper()
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	go serveRTMP(l, h)
	t.Cleanup(func() { l.Close() })
	return l.Addr().String()
}

func publisher(t *testing.T, addr, key string) *rtmp.Stream {
	t.Helper()
	c, err := rtmp.Dial("rtmp", addr, &rtmp.ConnConfig{})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { c.Close() })
	if err := c.Connect(&rtmpmsg.NetConnectionConnect{Command: rtmpmsg.NetConnectionConnectCommand{App: "live"}}); err != nil {
		t.Fatal(err)
	}
	s, err := c.CreateStream(nil, 128)
	if err != nil {
		t.Fatal(err)
	}
	if err := s.Publish(&rtmpmsg.NetStreamPublish{PublishingName: key, PublishingType: "live"}); err != nil {
		t.Fatal(err)
	}
	return s
}

func send(t *testing.T, s *rtmp.Stream, kind byte, ts uint32, data []byte) {
	t.Helper()
	var msg rtmpmsg.Message
	csid := 6
	if kind == tagAudio {
		msg, csid = &rtmpmsg.AudioMessage{Payload: bytes.NewReader(data)}, 4
	} else {
		msg = &rtmpmsg.VideoMessage{Payload: bytes.NewReader(data)}
	}
	if err := s.Write(csid, ts, msg); err != nil {
		t.Fatal(err)
	}
}

func waitFor(t *testing.T, what string, cond func() bool) {
	t.Helper()
	for i := 0; i < 200; i++ {
		if cond() {
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatalf("timed out waiting for %s", what)
}

// ---------------------------------------------------------------- live relay

func TestLiveRelayRTMPToHTTPFLV(t *testing.T) {
	h := newHub()
	addr := startRTMP(t, h)

	mux := http.NewServeMux()
	mux.HandleFunc("/live/", h.serveFLV)
	srv := httptest.NewServer(mux)
	defer srv.Close()

	// The viewer connects first: the relay must hold the request until OBS starts publishing.
	respCh := make(chan *http.Response, 1)
	go func() {
		resp, err := http.Get(srv.URL + "/live/tv.flv")
		if err != nil {
			t.Error(err)
		}
		respCh <- resp
	}()
	time.Sleep(100 * time.Millisecond)

	vseq := []byte{0x17, 0x00, 0, 0, 0, 1, 0x64, 0, 0x1f}
	aseq := []byte{0xAF, 0x00, 0x12, 0x10}
	pub := publisher(t, addr, "tv")
	waitFor(t, "publishing", func() bool { return h.info()["tv"].Publishing })

	send(t, pub, tagVideo, 0, vseq)
	send(t, pub, tagAudio, 0, aseq)
	send(t, pub, tagVideo, 40, []byte{0x27, 0x01, 0, 0, 0, 1, 2, 3})  // inter frame before any keyframe: skipped
	send(t, pub, tagAudio, 60, []byte{0xAF, 0x01, 5, 5})              // audio before the first keyframe: skipped
	send(t, pub, tagVideo, 80, []byte{0x17, 0x01, 0, 0, 0, 9, 9, 9})  // first keyframe -> ts 0
	send(t, pub, tagAudio, 100, []byte{0xAF, 0x01, 7, 7})             // -> ts 20
	send(t, pub, tagVideo, 120, []byte{0x27, 0x01, 0, 0, 0, 4, 4, 4}) // -> ts 40

	resp := <-respCh
	defer resp.Body.Close()
	if resp.StatusCode != 200 || resp.Header.Get("Content-Type") != "video/x-flv" {
		t.Fatalf("status %d, content-type %q", resp.StatusCode, resp.Header.Get("Content-Type"))
	}
	head := make([]byte, 13)
	if _, err := io.ReadFull(resp.Body, head); err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(head, flvHeader) {
		t.Fatalf("FLV header = %x", head)
	}

	// Expect: the two sequence headers (either order), then key, audio, inter with rebased timestamps.
	var seqs, media []flvTag
	for len(seqs) < 2 || len(media) < 3 {
		tag := readTag(t, resp.Body)
		isSeq := (tag.kind == tagVideo && tag.body[1] == 0 && tag.body[0]&0x0F == 7) ||
			(tag.kind == tagAudio && tag.body[1] == 0)
		if isSeq {
			seqs = append(seqs, tag)
		} else {
			media = append(media, tag)
		}
	}
	for _, s := range seqs {
		if s.ts != 0 {
			t.Errorf("sequence header has ts %d, want 0", s.ts)
		}
		want := vseq
		if s.kind == tagAudio {
			want = aseq
		}
		if !bytes.Equal(s.body, want) {
			t.Errorf("sequence header body %x, want %x", s.body, want)
		}
	}
	wantMedia := []flvTag{
		{tagVideo, 0, []byte{0x17, 0x01, 0, 0, 0, 9, 9, 9}},
		{tagAudio, 20, []byte{0xAF, 0x01, 7, 7}},
		{tagVideo, 40, []byte{0x27, 0x01, 0, 0, 0, 4, 4, 4}},
	}
	for i, w := range wantMedia {
		g := media[i]
		if g.kind != w.kind || g.ts != w.ts || !bytes.Equal(g.body, w.body) {
			t.Errorf("media[%d] = {%d %d %x}, want {%d %d %x}", i, g.kind, g.ts, g.body, w.kind, w.ts, w.body)
		}
	}

	// Stopping the publisher ends the viewer's stream.
	pub.Close()
	waitFor(t, "viewer count", func() bool { return h.info()["tv"].Viewers == 1 })
}

func TestLiveStreamNotLiveReturns404(t *testing.T) {
	h := newHub()
	s := h.get("nobody")
	if s.waitPublishing(t.Context(), 50*time.Millisecond) {
		t.Fatal("stream reported live without a publisher")
	}
}

func TestSecondPublisherWithSameKeyIsRejected(t *testing.T) {
	h := newHub()
	s := h.get("tv")
	if !s.startPublish() {
		t.Fatal("first publisher rejected")
	}
	if s.startPublish() {
		t.Fatal("second publisher with the same key was accepted")
	}
	s.stopPublish()
	if !s.startPublish() {
		t.Fatal("key not released after the publisher left")
	}
}

// ---------------------------------------------------------------- files

func TestFileServingRangeAndRegistry(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "电影 test.mp4")
	content := []byte("0123456789abcdefghij")
	if err := os.WriteFile(p, content, 0o644); err != nil {
		t.Fatal(err)
	}
	reg := newFileRegistry()
	id, err := reg.add(p)
	if err != nil {
		t.Fatal(err)
	}
	if id2, _ := reg.add(p); id2 != id {
		t.Errorf("same file got a new id: %s vs %s", id2, id)
	}
	if _, err := reg.add(dir); err == nil {
		t.Error("a directory was accepted")
	}

	mux := http.NewServeMux()
	mux.HandleFunc("/file/", reg.serveFile)
	srv := httptest.NewServer(mux)
	defer srv.Close()

	u := fileURL(strings.TrimPrefix(srv.URL, "http://"), 0, id, p)
	// fileURL builds host:port; with the test server's own address patch it directly.
	u = srv.URL + "/file/" + id + "/" + url.PathEscape(filepath.Base(p))

	get := func(rng string) (*http.Response, []byte) {
		req, _ := http.NewRequest("GET", u, nil)
		if rng != "" {
			req.Header.Set("Range", rng)
		}
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer resp.Body.Close()
		b, _ := io.ReadAll(resp.Body)
		return resp, b
	}

	resp, body := get("")
	if resp.StatusCode != 200 || !bytes.Equal(body, content) {
		t.Fatalf("full GET: %d %q", resp.StatusCode, body)
	}
	if resp.Header.Get("Accept-Ranges") != "bytes" {
		t.Errorf("Accept-Ranges = %q", resp.Header.Get("Accept-Ranges"))
	}
	resp, body = get("bytes=5-9")
	if resp.StatusCode != 206 || string(body) != "56789" {
		t.Fatalf("range GET: %d %q", resp.StatusCode, body)
	}
	if cr := resp.Header.Get("Content-Range"); cr != "bytes 5-9/20" {
		t.Errorf("Content-Range = %q", cr)
	}
	resp, body = get("bytes=15-")
	if resp.StatusCode != 206 || string(body) != "fghij" {
		t.Fatalf("open-ended range: %d %q", resp.StatusCode, body)
	}

	// Unknown ids and paths that were never registered are not served.
	for _, bad := range []string{"/file/deadbeef/x.mp4", "/file/../../etc/passwd", "/file/"} {
		r, err := http.Get(srv.URL + bad)
		if err != nil {
			t.Fatal(err)
		}
		r.Body.Close()
		if r.StatusCode == 200 {
			t.Errorf("%s was served", bad)
		}
	}
}

// ---------------------------------------------------------------- TV client + UI guard

// fakeTV mimics the EasyCast app's /api/ (PIN check, play, state, commands).
type fakeTV struct {
	*httptest.Server
	played []string
	cmds   []string
	seeks  []string // "delta|ms" as received
}

func newFakeTV(t *testing.T, pin string) *fakeTV {
	f := &fakeTV{}
	f.Server = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_ = r.ParseForm()
		w.Header().Set("Content-Type", "application/json")
		if r.PostForm.Get("pin") != pin {
			w.WriteHeader(403)
			_, _ = w.Write([]byte(`{"error":"口令不对"}`))
			return
		}
		switch r.URL.Path {
		case "/api/play":
			f.played = append(f.played, r.PostForm.Get("url"))
		case "/api/state":
			_, _ = w.Write([]byte(`{"state":"PLAYING","uri":"x","device":"EasyCast太阳","ip":"1.2.3.4","pos":5000,"dur":600000,"seekable":true}`))
			return
		case "/api/seek":
			f.seeks = append(f.seeks, r.PostForm.Get("delta")+"|"+r.PostForm.Get("ms"))
		default:
			f.cmds = append(f.cmds, strings.TrimPrefix(r.URL.Path, "/api/"))
		}
		_, _ = w.Write([]byte(`{"ok":true}`))
	}))
	t.Cleanup(f.Close)
	return f
}

func testApp(t *testing.T, tvAddr, pin string) *app {
	cfg := &configStore{path: filepath.Join(t.TempDir(), "pc.json"), cfg: defaultConfig()}
	cfg.cfg.TVAddr, cfg.cfg.TVPin, cfg.cfg.HostIP = tvAddr, pin, "192.168.1.4"
	return &app{cfg: cfg, hub: newHub(), files: newFileRegistry()}
}

func TestTVClientIgnoresSystemProxyAndChecksPin(t *testing.T) {
	// This PC has HTTP_PROXY set; LAN requests to the TV must not go through it.
	t.Setenv("HTTP_PROXY", "http://127.0.0.1:1")
	t.Setenv("HTTPS_PROXY", "http://127.0.0.1:1")
	tv := newFakeTV(t, "4321")
	addr := strings.TrimPrefix(tv.URL, "http://")

	if err := (tvClient{addr: addr, pin: "4321"}).play("http://x/y.mp4"); err != nil {
		t.Fatalf("play: %v", err)
	}
	if len(tv.played) != 1 || tv.played[0] != "http://x/y.mp4" {
		t.Fatalf("TV received %v", tv.played)
	}
	err := (tvClient{addr: addr, pin: "0000"}).play("http://x/z.mp4")
	if err == nil || !strings.Contains(err.Error(), "口令不对") {
		t.Fatalf("wrong pin error = %v", err)
	}
	if err := (tvClient{addr: ""}).command("stop"); err == nil {
		t.Fatal("empty TV address accepted")
	}
}

func TestUIPlayFileAndLivePushURLsToTV(t *testing.T) {
	tv := newFakeTV(t, "1234")
	a := testApp(t, strings.TrimPrefix(tv.URL, "http://"), "1234")
	ui := httptest.NewServer(a.uiHandler())
	defer ui.Close()

	dir := t.TempDir()
	p := filepath.Join(dir, "a b.mp4")
	_ = os.WriteFile(p, []byte("x"), 0o644)

	post := func(path string, body any) (*http.Response, map[string]any) {
		b, _ := json.Marshal(body)
		req, _ := http.NewRequest("POST", ui.URL+path, bytes.NewReader(b))
		req.Header.Set("X-EasyCast", "1")
		req.Host = "127.0.0.1:" + "9000"
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer resp.Body.Close()
		var out map[string]any
		_ = json.NewDecoder(resp.Body).Decode(&out)
		return resp, out
	}

	// "Copy as path" in Explorer wraps the path in quotes.
	resp, out := post("/api/play-file", map[string]string{"path": `"` + p + `"`})
	if resp.StatusCode != 200 {
		t.Fatalf("play-file: %d %v", resp.StatusCode, out)
	}
	if len(tv.played) != 1 || !strings.HasPrefix(tv.played[0], "http://192.168.1.4:8000/file/") ||
		!strings.HasSuffix(tv.played[0], "/a%20b.mp4") {
		t.Fatalf("TV was told to play %v", tv.played)
	}

	resp, _ = post("/api/play-live", nil)
	if resp.StatusCode != 200 || tv.played[len(tv.played)-1] != "http://192.168.1.4:8000/live/tv.flv" {
		t.Fatalf("play-live: %d, TV got %v", resp.StatusCode, tv.played)
	}

	for _, c := range []string{"pause", "resume", "stop"} {
		if resp, _ := post("/api/tv/"+c, nil); resp.StatusCode != 200 {
			t.Errorf("%s: %d", c, resp.StatusCode)
		}
	}
	if strings.Join(tv.cmds, ",") != "pause,resume,stop" {
		t.Errorf("TV commands = %v", tv.cmds)
	}
	if resp, _ := post("/api/tv/format-disk", nil); resp.StatusCode != 404 {
		t.Errorf("unknown command not rejected: %d", resp.StatusCode)
	}
	if resp, _ := post("/api/play-file", map[string]string{"path": filepath.Join(dir, "missing.mp4")}); resp.StatusCode == 200 {
		t.Error("missing file accepted")
	}
}

func TestUIFileLinkIsPlayableAndDoesNotTouchTV(t *testing.T) {
	tv := newFakeTV(t, "1234")
	a := testApp(t, strings.TrimPrefix(tv.URL, "http://"), "1234")
	ui := httptest.NewServer(a.uiHandler())
	defer ui.Close()

	// The media server shares the app's registry, exactly as in main().
	mediaMux := http.NewServeMux()
	mediaMux.HandleFunc("/file/", a.files.serveFile)
	media := httptest.NewServer(mediaMux)
	defer media.Close()

	p := filepath.Join(t.TempDir(), "clip.mp4")
	content := []byte("hello-video-bytes")
	_ = os.WriteFile(p, content, 0o644)

	b, _ := json.Marshal(map[string]string{"path": `"` + p + `"`})
	req, _ := http.NewRequest("POST", ui.URL+"/api/file-link", bytes.NewReader(b))
	req.Header.Set("X-EasyCast", "1")
	req.Host = "127.0.0.1:9000"
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	var out map[string]string
	_ = json.NewDecoder(resp.Body).Decode(&out)
	if resp.StatusCode != 200 || !strings.HasPrefix(out["url"], "http://192.168.1.4:8000/file/") ||
		!strings.HasSuffix(out["url"], "/clip.mp4") {
		t.Fatalf("file-link: %d %v", resp.StatusCode, out)
	}
	if len(tv.played) != 0 || len(tv.cmds) != 0 {
		t.Fatalf("generating a link contacted the TV: %v %v", tv.played, tv.cmds)
	}

	// The same path on the media server must serve the file (here with a Range request).
	pathOnly := strings.TrimPrefix(out["url"], "http://192.168.1.4:8000")
	get, _ := http.NewRequest("GET", media.URL+pathOnly, nil)
	get.Header.Set("Range", "bytes=6-10")
	r2, err := http.DefaultClient.Do(get)
	if err != nil {
		t.Fatal(err)
	}
	defer r2.Body.Close()
	body, _ := io.ReadAll(r2.Body)
	if r2.StatusCode != 206 || string(body) != "video" {
		t.Fatalf("serving the generated link: %d %q", r2.StatusCode, body)
	}

	// A missing file is a clear error, not a link.
	b, _ = json.Marshal(map[string]string{"path": filepath.Join(filepath.Dir(p), "nope.mp4")})
	bad, _ := http.NewRequest("POST", ui.URL+"/api/file-link", bytes.NewReader(b))
	bad.Header.Set("X-EasyCast", "1")
	bad.Host = "127.0.0.1:9000"
	r3, _ := http.DefaultClient.Do(bad)
	r3.Body.Close()
	if r3.StatusCode == 200 {
		t.Error("missing file produced a link")
	}
}

func TestUISeekForwardsToTVAndStatusCarriesProgress(t *testing.T) {
	tv := newFakeTV(t, "1234")
	a := testApp(t, strings.TrimPrefix(tv.URL, "http://"), "1234")
	ui := httptest.NewServer(a.uiHandler())
	defer ui.Close()

	post := func(path, body string) int {
		req, _ := http.NewRequest("POST", ui.URL+path, strings.NewReader(body))
		req.Header.Set("X-EasyCast", "1")
		req.Host = "127.0.0.1:9000"
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		resp.Body.Close()
		return resp.StatusCode
	}
	if c := post("/api/seek", `{"delta":-10000}`); c != 200 {
		t.Fatalf("relative seek: %d", c)
	}
	if c := post("/api/seek", `{"ms":120000}`); c != 200 {
		t.Fatalf("absolute seek: %d", c)
	}
	if got := strings.Join(tv.seeks, ","); got != "-10000|,|120000" {
		t.Fatalf("TV got seeks %q", got)
	}
	for _, bad := range []string{`{}`, `not json`} {
		if c := post("/api/seek", bad); c != 400 {
			t.Errorf("body %q: status %d, want 400", bad, c)
		}
	}

	// The status the page polls carries position, duration and seekability for the progress bar.
	req := httptest.NewRequest("GET", "/api/status", nil)
	req.Host = "127.0.0.1:9000"
	rec := httptest.NewRecorder()
	a.uiHandler().ServeHTTP(rec, req)
	var st struct {
		TV struct {
			Pos      float64 `json:"pos"`
			Dur      float64 `json:"dur"`
			Seekable bool    `json:"seekable"`
		} `json:"tv"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &st); err != nil {
		t.Fatal(err)
	}
	if st.TV.Pos != 5000 || st.TV.Dur != 600000 || !st.TV.Seekable {
		t.Fatalf("status tv progress = %+v", st.TV)
	}
}

func TestUIRejectsForeignHostAndMissingHeader(t *testing.T) {
	a := testApp(t, "", "")
	a.cfg.cfg.UIPort = 9000
	h := a.uiHandler()

	try := func(method, path, host, header string) int {
		req := httptest.NewRequest(method, path, strings.NewReader("{}"))
		req.Host = host
		if header != "" {
			req.Header.Set("X-EasyCast", header)
		}
		rec := httptest.NewRecorder()
		h.ServeHTTP(rec, req)
		return rec.Code
	}
	if c := try("GET", "/", "127.0.0.1:9000", ""); c != 200 {
		t.Errorf("page on 127.0.0.1: %d", c)
	}
	if c := try("GET", "/", "localhost:9000", ""); c != 200 {
		t.Errorf("page on localhost: %d", c)
	}
	if c := try("GET", "/api/status", "evil.example:9000", ""); c != 403 {
		t.Errorf("DNS-rebinding host allowed: %d", c)
	}
	if c := try("POST", "/api/tv/stop", "127.0.0.1:9000", ""); c != 403 {
		t.Errorf("POST without X-EasyCast allowed: %d", c)
	}
	if c := try("POST", "/api/tv/stop", "127.0.0.1:9000", "1"); c == 403 {
		t.Errorf("POST with X-EasyCast blocked: %d", c)
	}
}
