// EasyCast PC: pushes local files and OBS live streams to the EasyCast app on the TV.
//
//	easycast-pc.exe                 start, open the control page
//	easycast-pc.exe D:\movie.mp4    start and push that file right away
//
// One process does it all: a control page (127.0.0.1), an RTMP server for OBS, and an HTTP server
// the TV pulls from (files with Range support, and OBS streams as HTTP-FLV).
package main

import (
	"errors"
	"flag"
	"fmt"
	"log"
	"net"
	"net/http"
	"time"
)

func main() {
	noBrowser := flag.Bool("no-browser", false, "don't open the control page")
	flag.Parse()

	store := openConfig()
	a := &app{cfg: store, hub: newHub(), files: newFileRegistry()}
	cfg := store.get()

	// RTMP: OBS publishes here.
	rl, err := net.Listen("tcp", cfg.RTMPListen)
	if err != nil {
		log.Fatalf("RTMP 端口 %s 被占用或无法监听：%v\n（是不是已经开着另一个 easycast-pc 或 MediaMTX？）", cfg.RTMPListen, err)
	}
	go func() {
		if err := serveRTMP(rl, a.hub); err != nil {
			log.Printf("RTMP 服务退出：%v", err)
		}
	}()

	// Media: the TV pulls files and live streams from here, so it listens on every interface.
	mediaMux := http.NewServeMux()
	mediaMux.HandleFunc("/file/", a.files.serveFile)
	mediaMux.HandleFunc("/live/", a.hub.serveFLV)
	media := &http.Server{
		Addr:              fmt.Sprintf("0.0.0.0:%d", cfg.MediaPort),
		Handler:           mediaMux,
		ReadHeaderTimeout: 10 * time.Second,
	}
	ml, err := net.Listen("tcp", media.Addr)
	if err != nil {
		log.Fatalf("媒体端口 %d 被占用：%v", cfg.MediaPort, err)
	}
	go func() {
		if err := media.Serve(ml); err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Printf("媒体服务退出：%v", err)
		}
	}()

	// Control page: local only.
	uiAddr := fmt.Sprintf("127.0.0.1:%d", cfg.UIPort)
	ul, err := net.Listen("tcp", uiAddr)
	if err != nil {
		log.Fatalf("控制页端口 %s 被占用：%v", uiAddr, err)
	}
	ui := &http.Server{Handler: a.uiHandler(), ReadHeaderTimeout: 10 * time.Second}
	go func() { _ = ui.Serve(ul) }()

	pageURL := "http://" + uiAddr + "/"
	fmt.Println("EasyCast PC 已启动")
	fmt.Println("  控制页：  ", pageURL)
	fmt.Println("  OBS 推流：", "rtmp://"+cfg.RTMPListen+"/live   密钥 tv")
	fmt.Printf("  电视拉流：  http://%s:%d/  （需要放行 Windows 防火墙 TCP %d）\n", cfg.hostIP(), cfg.MediaPort, cfg.MediaPort)

	if !*noBrowser {
		openBrowser(pageURL)
	}

	// A file dragged onto the exe (or given on the command line) is pushed straight away.
	if p := flag.Arg(0); p != "" {
		go func() {
			time.Sleep(500 * time.Millisecond)
			if u, err := a.playPath(p); err != nil {
				log.Printf("推送 %s 失败：%v", p, err)
			} else {
				log.Printf("已推送到电视：%s", u)
			}
		}()
	}

	select {} // run until the window is closed
}

func init() { log.SetFlags(log.Ltime) }
