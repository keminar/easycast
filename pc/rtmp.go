package main

import (
	"errors"
	"io"
	"log"
	"net"
	"strings"

	rtmp "github.com/yutopp/go-rtmp"
	rtmpmsg "github.com/yutopp/go-rtmp/message"
)

// rtmpHandler receives what OBS publishes and hands it to the live hub.
type rtmpHandler struct {
	rtmp.DefaultHandler
	hub    *hub
	stream *liveStream
}

func (h *rtmpHandler) OnPublish(_ *rtmp.StreamContext, _ uint32, cmd *rtmpmsg.NetStreamPublish) error {
	// OBS "stream key" arrives as the publishing name, possibly with ?query parameters.
	name := strings.SplitN(cmd.PublishingName, "?", 2)[0]
	if name == "" {
		return errors.New("empty stream key")
	}
	s := h.hub.get(name)
	if !s.startPublish() {
		return errors.New("stream key already in use")
	}
	h.stream = s
	log.Printf("OBS 开始推流：%s", name)
	return nil
}

func (h *rtmpHandler) OnAudio(timestamp uint32, payload io.Reader) error {
	return h.forward(tagAudio, timestamp, payload)
}

func (h *rtmpHandler) OnVideo(timestamp uint32, payload io.Reader) error {
	return h.forward(tagVideo, timestamp, payload)
}

func (h *rtmpHandler) forward(kind byte, ts uint32, payload io.Reader) error {
	data, err := io.ReadAll(payload)
	if err != nil {
		return err
	}
	if h.stream != nil {
		h.stream.push(kind, ts, data)
	}
	return nil
}

func (h *rtmpHandler) OnClose() {
	if h.stream != nil {
		log.Printf("OBS 停止推流：%s", h.stream.name)
		h.stream.stopPublish()
		h.stream = nil
	}
}

// serveRTMP accepts OBS connections on addr until the listener is closed.
func serveRTMP(l net.Listener, h *hub) error {
	srv := rtmp.NewServer(&rtmp.ServerConfig{
		OnConnect: func(conn net.Conn) (io.ReadWriteCloser, *rtmp.ConnConfig) {
			return conn, &rtmp.ConnConfig{
				Handler: &rtmpHandler{hub: h},
				ControlState: rtmp.StreamControlStateConfig{
					DefaultBandwidthWindowSize: 6 * 1024 * 1024 / 8,
				},
			}
		},
	})
	return srv.Serve(l)
}
