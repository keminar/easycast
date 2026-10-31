package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"
)

// This PC may have a system proxy (HTTP_PROXY) that cannot reach the LAN; the TV is always direct.
var directClient = &http.Client{
	Timeout:   8 * time.Second,
	Transport: &http.Transport{Proxy: nil},
}

// tvClient talks to the web API of the EasyCast app on the TV (port 8200 by default).
type tvClient struct {
	addr  string // host:port, with or without http://
	pin   string
	quick bool // short timeout, for status polling an offline TV
}

var quickClient = &http.Client{
	Timeout:   2500 * time.Millisecond,
	Transport: &http.Transport{Proxy: nil},
}

func (c tvClient) base() string {
	a := strings.TrimSpace(c.addr)
	a = strings.TrimPrefix(a, "http://")
	a = strings.TrimSuffix(a, "/")
	if a != "" && !strings.Contains(a, ":") {
		a += ":8200"
	}
	return "http://" + a
}

// call POSTs to the TV and decodes its JSON reply. A non-2xx reply becomes an error carrying the TV's message.
func (c tvClient) call(path string, form url.Values) (map[string]any, error) {
	if strings.TrimSpace(c.addr) == "" {
		return nil, errors.New("还没有连接电视：请填好电视地址和口令，点「保存并连接」")
	}
	if form == nil {
		form = url.Values{}
	}
	form.Set("pin", c.pin)
	client := directClient
	if c.quick {
		client = quickClient
	}
	resp, err := client.PostForm(c.base()+path, form)
	if err != nil {
		return nil, fmt.Errorf("连不上电视 %s：%v", c.base(), err)
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))

	var out map[string]any
	_ = json.Unmarshal(body, &out)
	if resp.StatusCode/100 != 2 {
		msg, _ := out["error"].(string)
		if msg == "" {
			msg = resp.Status
		}
		return nil, errors.New(msg)
	}
	return out, nil
}

func (c tvClient) play(mediaURL string) error {
	_, err := c.call("/api/play", url.Values{"url": {mediaURL}})
	return err
}

// seek jumps by deltaMs (negative = back) or, when absolute is true, to deltaMs from the start.
func (c tvClient) seek(ms int64, absolute bool) error {
	key := "delta"
	if absolute {
		key = "ms"
	}
	_, err := c.call("/api/seek", url.Values{key: {strconv.FormatInt(ms, 10)}})
	return err
}

func (c tvClient) command(name string) error {
	_, err := c.call("/api/"+name, nil)
	return err
}

// rename changes the name the TV advertises, which is what phone cast lists show.
func (c tvClient) rename(name string) error {
	_, err := c.call("/api/name", url.Values{"name": {name}})
	return err
}

func (c tvClient) state() (map[string]any, error) {
	c.quick = true
	return c.call("/api/state", nil)
}
