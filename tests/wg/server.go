// Testserver voor de VPN van Rene's Tools: de officiële wireguard-go met een nep-tunnel die elk pakket
// terugstuurt (afzender en bestemming omgewisseld). Alleen voor tests/wg/run-interop.sh.
//
//	server <luisterpoort> <eigen-private-key-b64> <peer-public-key-b64> [initiate:<poort-van-de-peer>]
package main

import (
	"encoding/base64"
	"encoding/hex"
	"fmt"
	"os"
	"os/signal"
	"strings"
	"time"

	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun"
)

type echoTun struct {
	in     chan []byte
	events chan tun.Event
}

func (t *echoTun) File() *os.File           { return nil }
func (t *echoTun) MTU() (int, error)        { return 1420, nil }
func (t *echoTun) Name() (string, error)    { return "echo", nil }
func (t *echoTun) Events() <-chan tun.Event { return t.events }
func (t *echoTun) Close() error             { return nil }
func (t *echoTun) BatchSize() int           { return 1 }

func (t *echoTun) Read(bufs [][]byte, sizes []int, offset int) (int, error) {
	p := <-t.in
	sizes[0] = copy(bufs[0][offset:], p)
	return 1, nil
}

func (t *echoTun) Write(bufs [][]byte, offset int) (int, error) {
	for _, b := range bufs {
		p := append([]byte(nil), b[offset:]...)
		if len(p) >= 20 && p[0]>>4 == 4 {
			var a [4]byte
			copy(a[:], p[12:16])
			copy(p[12:16], p[16:20])
			copy(p[16:20], a[:])
			if p[9] == 17 && len(p) >= 28 { // UDP: poorten omwisselen
				p[20], p[21], p[22], p[23] = p[22], p[23], p[20], p[21]
			}
		}
		if len(p) >= 40 && p[0]>>4 == 6 {
			var a [16]byte
			copy(a[:], p[8:24])
			copy(p[8:24], p[24:40])
			copy(p[24:40], a[:])
			if p[6] == 17 && len(p) >= 48 {
				p[40], p[41], p[42], p[43] = p[42], p[43], p[40], p[41]
			}
		}
		fmt.Printf("got %d\n", len(b)-offset)
		t.in <- p
	}
	return len(bufs), nil
}

func hexKey(b64 string) string {
	k, err := base64.StdEncoding.DecodeString(b64)
	if err != nil || len(k) != 32 {
		panic("bad key")
	}
	return hex.EncodeToString(k)
}

func main() {
	port, priv, peer := os.Args[1], os.Args[2], os.Args[3]
	t := &echoTun{in: make(chan []byte, 1024), events: make(chan tun.Event, 1)}
	t.events <- tun.EventUp
	dev := device.NewDevice(t, conn.NewDefaultBind(), device.NewLogger(device.LogLevelError, "wg: "))
	cfg := fmt.Sprintf("private_key=%s\nlisten_port=%s\npublic_key=%s\nallowed_ip=10.0.0.2/32\nallowed_ip=fd00::2/128\n", hexKey(priv), port, hexKey(peer))
	initiate := len(os.Args) > 4 && strings.HasPrefix(os.Args[4], "initiate:")
	if initiate {
		cfg += "endpoint=127.0.0.1:" + strings.TrimPrefix(os.Args[4], "initiate:") + "\n"
	}
	if err := dev.IpcSet(cfg); err != nil {
		panic(err)
	}
	if err := dev.Up(); err != nil {
		panic(err)
	}
	fmt.Println("ready")
	if initiate {
		go func() {
			time.Sleep(500 * time.Millisecond)
			// UDP-pakket 10.0.0.1:7 → 10.0.0.2:9 met "hallo van de server"
			payload := []byte("hallo van de server")
			p := make([]byte, 28+len(payload))
			p[0] = 0x45
			total := len(p)
			p[2], p[3] = byte(total>>8), byte(total)
			p[8], p[9] = 64, 17
			copy(p[12:16], []byte{10, 0, 0, 1})
			copy(p[16:20], []byte{10, 0, 0, 2})
			p[21], p[23] = 7, 9
			ul := 8 + len(payload)
			p[24], p[25] = byte(ul>>8), byte(ul)
			copy(p[28:], payload)
			t.in <- p
		}()
	}
	c := make(chan os.Signal, 1)
	signal.Notify(c, os.Interrupt)
	go func() { time.Sleep(10 * time.Minute); c <- os.Interrupt }()
	<-c
	dev.Close()
}
