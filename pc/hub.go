package main

import (
	"context"
	"net/http"
	"path"
	"strings"
	"sync"
	"time"
)

// Live relay: OBS publishes over RTMP, the TV pulls the same stream as HTTP-FLV.
//
// An RTMP audio/video message body is exactly an FLV tag body, so relaying is just re-wrapping:
// FLV header, then tag headers + the untouched bodies. Late joiners get the cached codec
// sequence headers first and start at the next keyframe.

const (
	tagAudio = 8
	tagVideo = 9

	subBuffer = 512 // packets a viewer may fall behind before it is dropped
)

type packet struct {
	kind byte
	ts   uint32
	data []byte
	key  bool // video keyframe
	seq  bool // codec sequence header (AVC/AAC config)
}

type subscriber struct {
	ch chan packet
}

type liveStream struct {
	name string

	mu         sync.Mutex
	publishing bool
	videoSeq   []byte
	audioSeq   []byte
	subs       map[*subscriber]struct{}
	changed    chan struct{} // closed and replaced whenever publishing starts or stops
}

type hub struct {
	mu      sync.Mutex
	streams map[string]*liveStream
}

func newHub() *hub { return &hub{streams: map[string]*liveStream{}} }

func (h *hub) get(name string) *liveStream {
	h.mu.Lock()
	defer h.mu.Unlock()
	s := h.streams[name]
	if s == nil {
		s = &liveStream{name: name, subs: map[*subscriber]struct{}{}, changed: make(chan struct{})}
		h.streams[name] = s
	}
	return s
}

// info returns, for the UI, which streams are being published and how many viewers they have.
func (h *hub) info() map[string]streamInfo {
	h.mu.Lock()
	defer h.mu.Unlock()
	out := map[string]streamInfo{}
	for name, s := range h.streams {
		s.mu.Lock()
		out[name] = streamInfo{Publishing: s.publishing, Viewers: len(s.subs)}
		s.mu.Unlock()
	}
	return out
}

type streamInfo struct {
	Publishing bool `json:"publishing"`
	Viewers    int  `json:"viewers"`
}

func (s *liveStream) signalLocked() {
	close(s.changed)
	s.changed = make(chan struct{})
}

// startPublish claims the stream; it fails if another publisher already owns it.
func (s *liveStream) startPublish() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.publishing {
		return false
	}
	s.publishing = true
	s.videoSeq, s.audioSeq = nil, nil
	s.signalLocked()
	return true
}

// stopPublish ends the stream and disconnects every viewer.
func (s *liveStream) stopPublish() {
	s.mu.Lock()
	defer s.mu.Unlock()
	if !s.publishing {
		return
	}
	s.publishing = false
	s.videoSeq, s.audioSeq = nil, nil
	for sub := range s.subs {
		close(sub.ch)
		delete(s.subs, sub)
	}
	s.signalLocked()
}

// push forwards one RTMP audio/video message body to every viewer.
func (s *liveStream) push(kind byte, ts uint32, data []byte) {
	if len(data) == 0 {
		return
	}
	p := packet{kind: kind, ts: ts, data: data}
	switch kind {
	case tagVideo:
		p.key = data[0]>>4 == 1
		// AVC (codec 7) with packet type 0 is the decoder configuration record.
		p.seq = data[0]&0x0F == 7 && len(data) > 1 && data[1] == 0
	case tagAudio:
		// AAC (format 10) with packet type 0 is the AudioSpecificConfig.
		p.seq = data[0]>>4 == 10 && len(data) > 1 && data[1] == 0
	}

	s.mu.Lock()
	defer s.mu.Unlock()
	if !s.publishing {
		return
	}
	if p.seq {
		if kind == tagVideo {
			s.videoSeq = data
		} else {
			s.audioSeq = data
		}
	}
	for sub := range s.subs {
		select {
		case sub.ch <- p:
		default: // viewer too slow: cut it loose rather than buffering without bound
			close(sub.ch)
			delete(s.subs, sub)
		}
	}
}

// subscribe registers a viewer and returns the cached sequence headers to send first.
func (s *liveStream) subscribe() (sub *subscriber, videoSeq, audioSeq []byte, ok bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if !s.publishing {
		return nil, nil, nil, false
	}
	sub = &subscriber{ch: make(chan packet, subBuffer)}
	s.subs[sub] = struct{}{}
	return sub, s.videoSeq, s.audioSeq, true
}

func (s *liveStream) unsubscribe(sub *subscriber) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, ok := s.subs[sub]; ok {
		close(sub.ch)
		delete(s.subs, sub)
	}
}

// waitPublishing blocks until the stream is live, so the TV can be told to play before OBS starts.
func (s *liveStream) waitPublishing(ctx context.Context, max time.Duration) bool {
	deadline := time.NewTimer(max)
	defer deadline.Stop()
	for {
		s.mu.Lock()
		live, changed := s.publishing, s.changed
		s.mu.Unlock()
		if live {
			return true
		}
		select {
		case <-changed:
		case <-deadline.C:
			return false
		case <-ctx.Done():
			return false
		}
	}
}

// ---------------------------------------------------------------- FLV output

var flvHeader = []byte{'F', 'L', 'V', 1, 0x05, 0, 0, 0, 9, 0, 0, 0, 0}

func appendTag(dst []byte, kind byte, ts uint32, body []byte) []byte {
	n := len(body)
	dst = append(dst,
		kind,
		byte(n>>16), byte(n>>8), byte(n),
		byte(ts>>16), byte(ts>>8), byte(ts), byte(ts>>24),
		0, 0, 0,
	)
	dst = append(dst, body...)
	total := uint32(11 + n)
	return append(dst, byte(total>>24), byte(total>>16), byte(total>>8), byte(total))
}

// serveFLV streams /live/<name>.flv to one viewer until it leaves or the publisher stops.
func (h *hub) serveFLV(w http.ResponseWriter, r *http.Request) {
	name := strings.TrimSuffix(path.Base(r.URL.Path), ".flv")
	s := h.get(name)

	// OBS may not be streaming yet when the TV is told to play: give it some time.
	if !s.waitPublishing(r.Context(), 20*time.Second) {
		http.Error(w, "stream not live", http.StatusNotFound)
		return
	}
	sub, vseq, aseq, ok := s.subscribe()
	if !ok {
		http.Error(w, "stream not live", http.StatusNotFound)
		return
	}
	defer s.unsubscribe(sub)

	w.Header().Set("Content-Type", "video/x-flv")
	w.Header().Set("Cache-Control", "no-cache")
	rc := http.NewResponseController(w)
	write := func(b []byte) error {
		_ = rc.SetWriteDeadline(time.Now().Add(10 * time.Second))
		if _, err := w.Write(b); err != nil {
			return err
		}
		return rc.Flush()
	}

	head := append([]byte{}, flvHeader...)
	if vseq != nil {
		head = appendTag(head, tagVideo, 0, vseq)
	}
	if aseq != nil {
		head = appendTag(head, tagAudio, 0, aseq)
	}
	if write(head) != nil {
		return
	}

	// Video starts at the first keyframe, and timestamps are rebased so the viewer's clock starts near 0.
	// (We cannot tell "no video config yet" from "audio-only", so audio-only streams are not supported.)
	started := false
	var base uint32
	var buf []byte
	for {
		select {
		case <-r.Context().Done():
			return
		case p, ok := <-sub.ch:
			if !ok {
				return
			}
			ts := uint32(0)
			if p.seq {
				// A config that arrives after we subscribed (or changes mid-stream) is not in the
				// header we sent, so pass it on. It always carries timestamp 0 until we have started.
				if started && p.ts > base {
					ts = p.ts - base
				}
			} else {
				if !started {
					if p.kind != tagVideo || !p.key {
						continue
					}
					started, base = true, p.ts
				}
				if p.ts > base {
					ts = p.ts - base
				}
			}
			buf = appendTag(buf[:0], p.kind, ts, p.data)
			if write(buf) != nil {
				return
			}
		}
	}
}
