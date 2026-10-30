package main

import (
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"mime"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"sync"
)

// Local files are only served after they were registered here, under an unguessable id,
// so the media port never exposes the rest of the disk.
type fileRegistry struct {
	mu     sync.Mutex
	byID   map[string]string
	byPath map[string]string
}

func newFileRegistry() *fileRegistry {
	return &fileRegistry{byID: map[string]string{}, byPath: map[string]string{}}
}

// add registers a file and returns its id; the same file keeps the same id.
func (r *fileRegistry) add(p string) (string, error) {
	abs, err := filepath.Abs(p)
	if err != nil {
		return "", err
	}
	st, err := os.Stat(abs)
	if err != nil {
		return "", err
	}
	if st.IsDir() {
		return "", errors.New("这是一个文件夹，请选择视频文件")
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	if id, ok := r.byPath[abs]; ok {
		return id, nil
	}
	b := make([]byte, 8)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	id := hex.EncodeToString(b)
	r.byID[id] = abs
	r.byPath[abs] = id
	return id, nil
}

func (r *fileRegistry) lookup(id string) (string, bool) {
	r.mu.Lock()
	defer r.mu.Unlock()
	p, ok := r.byID[id]
	return p, ok
}

// fileURL is the address the TV uses to fetch a registered file.
func fileURL(host string, port int, id, filePath string) string {
	return fmt.Sprintf("http://%s:%d/file/%s/%s", host, port, id, url.PathEscape(filepath.Base(filePath)))
}

// serveFile handles /file/<id>/<name>. http.ServeContent gives Range support, so the TV can seek,
// pause for a long time and start playing before the whole file is read.
func (r *fileRegistry) serveFile(w http.ResponseWriter, req *http.Request) {
	parts := strings.SplitN(strings.TrimPrefix(req.URL.Path, "/file/"), "/", 2)
	p, ok := r.lookup(parts[0])
	if !ok {
		http.NotFound(w, req)
		return
	}
	f, err := os.Open(p)
	if err != nil {
		http.NotFound(w, req)
		return
	}
	defer f.Close()
	st, err := f.Stat()
	if err != nil {
		http.NotFound(w, req)
		return
	}
	if ct := mime.TypeByExtension(strings.ToLower(filepath.Ext(p))); ct != "" {
		w.Header().Set("Content-Type", ct)
	} else {
		w.Header().Set("Content-Type", videoType(p))
	}
	http.ServeContent(w, req, filepath.Base(p), st.ModTime(), f)
}

// videoType covers extensions Windows has no registered MIME type for.
func videoType(p string) string {
	switch strings.ToLower(filepath.Ext(p)) {
	case ".mkv":
		return "video/x-matroska"
	case ".ts":
		return "video/mp2t"
	case ".flv":
		return "video/x-flv"
	default:
		return "application/octet-stream"
	}
}
