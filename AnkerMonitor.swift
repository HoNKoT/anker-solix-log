// Anker Monitor — メニューバーアプリ
//
// 構成は Gradle Queue Monitor と同じ:
//   Python のバックエンド (anker_monitor.py, port 19877) が値を配り、
//   このアプリが 2 秒おきに /status を読んでメニューバーに出す。
//   クリックでポップオーバー、メニューの「ウィンドウで開く」で独立ウィンドウ。
//   どちらも中身はバックエンドのダッシュボード (WKWebView)。
//
// ビルド: amon setup / amon deploy が
//   xcrun swiftc -O AnkerMonitor.swift -o ... -framework AppKit -framework WebKit
// を実行する。

import AppKit
import UserNotifications
import WebKit

// MARK: - 定数

let dataDir = FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent(".anker-monitor")
let launchConfigURL = dataDir.appendingPathComponent("launch.json")
let backendLogURL = dataDir.appendingPathComponent("backend.log")

/// ~/.anker-monitor/remote.json があれば「リモートモード」: 自分では Bluetooth もバックエンドも動かさず、
/// 別の Mac のバックエンド (amon lan on + amon token new) を見に行く。amon remote <URL> <トークン> が作る。
struct RemoteConfig: Decodable {
    let url: String
    let token: String?
}

let remote: RemoteConfig? = {
    guard let data = try? Data(contentsOf: dataDir.appendingPathComponent("remote.json")),
          let cfg = try? JSONDecoder().decode(RemoteConfig.self, from: data),
          URL(string: cfg.url)?.host != nil else { return nil }
    return cfg
}()
let isRemote = remote != nil
let baseURL = URL(string: remote?.url ?? "http://localhost:19877")!
let remoteToken = remote?.token.flatMap { $0.isEmpty ? nil : $0 }

/// ブラウザには WebView の Cookie が渡らないので、リモートのときは ?token= を付けて開く (バックエンドが Cookie に移して URL から消す)
func browserURL(_ url: URL) -> URL {
    guard let t = remoteToken, var c = URLComponents(url: url, resolvingAgainstBaseURL: true) else { return url }
    c.queryItems = (c.queryItems ?? []) + [URLQueryItem(name: "token", value: t)]
    return c.url ?? url
}

// MARK: - /status の形

struct PortInfo: Decodable {
    let key: String
    let dir: String
    let w: Int
}

struct Device: Decodable {
    let id: String
    let name: String?
    let state: String?
    let battery: Int?
    let power_in: Int?
    let power_out: Int?
    let charging: String?
    let detail: String?
    let icon: String?
    let model_label: String?
    let ports: [PortInfo]?
}

struct Status: Decodable {
    let devices: [Device]
}

struct LaunchConfig: Decodable {
    let python: String
    let script: String
}

// MARK: - ダッシュボード表示 (ポップオーバーとウィンドウで共用)

/// ページが読めるまでの下地 (WebView は読み込み前に黒く見えることがあるので、ウィンドウ色で塗っておく)
final class BackdropView: NSView {
    override func draw(_ dirtyRect: NSRect) {
        NSColor.windowBackgroundColor.setFill()
        dirtyRect.fill()
    }
}

final class DashboardViewController: NSViewController, WKNavigationDelegate {
    // ページの中から「ブラウザで開く」を出せるように、アプリの WebView だと分かる名前を付ける
    private let web: WKWebView = {
        let c = WKWebViewConfiguration()
        c.applicationNameForUserAgent = "AnkerMonitorApp"
        return WKWebView(frame: .zero, configuration: c)
    }()
    private var currentURL = baseURL

    /// ポップオーバーのときだけ「ウィンドウで切り離し」を出す (ウィンドウ自身には要らない)
    var showsDetachButton = false { didSet { detachButton?.isHidden = !showsDetachButton } }
    private var detachButton: NSButton?

    // 画面の下の操作バー (GQM と同じ: 左下に「ウィンドウで切り離し」、右下に「ブラウザで開く」)
    private func makeButton(_ title: String, symbol: String, action: Selector) -> NSButton {
        let b = NSButton(title: title, target: self, action: action)
        b.bezelStyle = .rounded
        b.controlSize = .small
        b.font = .systemFont(ofSize: NSFont.smallSystemFontSize)
        if let img = NSImage(systemSymbolName: symbol, accessibilityDescription: title) {
            b.image = img
            b.imagePosition = .imageLeading
        }
        return b
    }

    override func loadView() {
        web.navigationDelegate = self
        web.setValue(false, forKey: "drawsBackground")  // 読み込み前に黒くならないように

        // Auto Layout は使わず、位置を直接決める (制約から大きさが決まって、細長いウィンドウになるのを避ける)。
        // 座標は左下が原点。下から 38pt が操作バー、その上に 1pt の区切り線、残りが WebView。
        let size = NSSize(width: 380, height: 640)
        let barH: CGFloat = 38
        preferredContentSize = size
        let root = BackdropView(frame: NSRect(origin: .zero, size: size))
        root.autoresizesSubviews = true

        web.frame = NSRect(x: 0, y: barH + 1, width: size.width, height: size.height - barH - 1)
        web.autoresizingMask = [.width, .height]
        root.addSubview(web)

        let line = NSBox(frame: NSRect(x: 0, y: barH, width: size.width, height: 1))
        line.boxType = .separator
        line.autoresizingMask = [.width, .maxYMargin]
        root.addSubview(line)

        let bar = NSView(frame: NSRect(x: 0, y: 0, width: size.width, height: barH))
        bar.autoresizingMask = [.width, .maxYMargin]
        root.addSubview(bar)

        let detach = makeButton("ウィンドウで切り離し", symbol: "arrow.up.forward.app", action: #selector(detachTapped))
        detach.isHidden = !showsDetachButton
        detachButton = detach
        let browser = makeButton("ブラウザで開く", symbol: "safari", action: #selector(browserTapped))
        for b in [detach, browser] { b.sizeToFit() }
        detach.frame.origin = NSPoint(x: 10, y: (barH - detach.frame.height) / 2)
        detach.autoresizingMask = [.maxXMargin]  // 左に固定
        browser.frame.origin = NSPoint(x: size.width - 10 - browser.frame.width, y: (barH - browser.frame.height) / 2)
        browser.autoresizingMask = [.minXMargin]  // 右に固定
        bar.addSubview(detach)
        bar.addSubview(browser)

        view = root
    }

    @objc private func detachTapped() { onOpenWindow?(currentURL.path.isEmpty ? "/" : currentURL.path) }

    @objc private func browserTapped() { NSWorkspace.shared.open(browserURL(currentURL)) }

    private var cookieReady = remoteToken == nil

    func load() {
        web.navigationDelegate = self  // ビューを作る前に load されても失敗を拾えるように
        // ダッシュボードの fetch は相対パスでヘッダーを付けられないので、トークンは Cookie で渡す (読み込みより先に入れる)
        if !cookieReady, let t = remoteToken, let host = baseURL.host,
           let cookie = HTTPCookie(properties: [.name: "amon_token", .value: t, .domain: host, .path: "/",
                                                .expires: Date().addingTimeInterval(365 * 86400)]) {
            web.configuration.websiteDataStore.httpCookieStore.setCookie(cookie) { [weak self] in
                guard let self else { return }
                self.cookieReady = true
                self.web.load(URLRequest(url: self.currentURL))
            }
            return
        }
        web.load(URLRequest(url: currentURL))
    }

    func loadIfNeeded() {
        if web.url == nil { load() }
    }

    /// 「/」(いま) / 「/history」(履歴) / 「/devices」(機器) のページを開く
    func show(path: String) {
        currentURL = URL(string: path, relativeTo: baseURL)?.absoluteURL ?? baseURL
        load()
    }

    /// 「ウィンドウで開く」が押されたときに呼ばれる (引数はページのパス)
    var onOpenWindow: ((String) -> Void)?

    /// ポップオーバーを開くたびに、最初のページ (いま) に戻す
    func goHomeIfNeeded() {
        if currentURL.path != "/" { show(path: "/") }
    }

    func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction,
                 decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        if let url = navigationAction.request.url {
            // ページ内の「ブラウザで開く」: アプリ内では開かず、既定のブラウザで同じページを開く
            // ページ内の「ウィンドウで開く」: ポップオーバーから独立したウィンドウに出す (表示中のページのまま)
            if url.path == "/open-window" {
                let to = URLComponents(url: url, resolvingAgainstBaseURL: false)?
                    .queryItems?.first(where: { $0.name == "to" })?.value ?? "/"
                onOpenWindow?(to)
                decisionHandler(.cancel)
                return
            }
            if url.path == "/open-in-browser" {
                let to = URLComponents(url: url, resolvingAgainstBaseURL: false)?
                    .queryItems?.first(where: { $0.name == "to" })?.value ?? "/"
                NSWorkspace.shared.open(browserURL(URL(string: to, relativeTo: baseURL)?.absoluteURL ?? baseURL))
                decisionHandler(.cancel)
                return
            }
            if navigationAction.targetFrame?.isMainFrame ?? true, url.host == baseURL.host {
                currentURL = url  // やり直し (retry) と「開いているページ」の記憶用
            }
        }
        decisionHandler(.allow)
    }

    // バックエンドがまだ立っていない間は 2 秒おきにやり直す
    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        retry()
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        retry()
    }

    private func retry() {
        DispatchQueue.main.asyncAfter(deadline: .now() + 2) { [weak self] in
            self?.load()
        }
    }
}

// MARK: - メニューバーのアイコン
//
// 機種ごとの形のアイコンを並べる (メニューバーを圧迫しない)。
//   ・形は「全体のフォルム」と「液晶の位置」で見分ける (C300 DC は縦長で液晶が上寄り、C2000 Gen 2 は横長で液晶が広い)
//   ・残量は下から塗る (50% なら下半分が白、上半分がグレー)。白は明るい/暗いメニューバーで自動的に反転する
//   ・充電中は塗りが緑 / 20% 未満 (充電していないとき) は塗りと輪郭が赤 / 未接続・未取得は点線
//   ・入力があるとき (充電中) だけ、アイコンの横にソーラー/ACの記号と入力 W を出す。入力が無ければ何も足さない
// 形はダッシュボードのアイコン (dashboard.html の iconSVG) と同じ 24x24 の座標で描く。

struct InputSpec: Equatable {
    let kind: String  // solar / ac / other
    let watts: Int
}

struct IconSpec: Equatable {
    let kind: String  // c300dc / c2000g2 / compact / box / large
    let pct: Int?
    let charging: Bool
    let connected: Bool
    var inputs: [InputSpec] = []
    var loading = false  // 接続中・探し中 (くるくる回るマークを出す)
    var discharging = false  // 出力 > 入力 (1W 以上): 塗りをオレンジにする
    var usbOut = false  // USB で出力中: USB マークを重ねる
}

enum StatusIcon {
    static let size: CGFloat = 18  // 1台ぶんのアイコンの幅と高さ
    static let blockGap: CGFloat = 7  // 機器と機器のあいだ
    static let inGap: CGFloat = 3  // アイコンと入力表示のあいだ
    static let glyph: CGFloat = 10  // ソーラー/AC の記号の大きさ
    static let textFont = NSFont.monospacedDigitSystemFont(ofSize: 11, weight: .medium)

    /// (x, y, 幅, 高さ, 角丸)。24x24 の座標。
    private static func body(_ kind: String) -> (CGFloat, CGFloat, CGFloat, CGFloat, CGFloat) {
        switch kind {
        case "c300dc": return (5.5, 3.2, 13, 18.3, 2.5)    // 縦長 (右側にストラップ)
        case "c2000g2": return (1.5, 6.5, 21, 14.5, 2.5)   // 横長
        case "compact": return (5, 9, 14, 12, 2.5)
        case "large": return (1.5, 8.5, 21, 12.5, 2.5)
        default: return (2.5, 9.5, 19, 11.5, 2.5)
        }
    }

    /// 塗りの色。ここを変えれば「充電中でも20%未満なら赤」などに変えられる。
    private static func fillColor(_ s: IconSpec) -> NSColor {
        if s.discharging { return .systemOrange }
        if s.charging { return .systemGreen }
        if let p = s.pct, p < 20 { return .systemRed }
        return .labelColor
    }

    private static func wattText(_ w: Int) -> String { "\(w)W" }

    private static func inputsWidth(_ s: IconSpec) -> CGFloat {
        if s.inputs.isEmpty { return 0 }
        var w: CGFloat = 0
        for (i, inp) in s.inputs.enumerated() {
            let tw = (wattText(inp.watts) as NSString).size(withAttributes: [.font: textFont]).width
            w += glyph + 1 + ceil(tw) + (i > 0 ? 4 : 0)
        }
        return w
    }

    private static func blockWidth(_ s: IconSpec) -> CGFloat {
        size + (s.inputs.isEmpty ? 0 : inGap + inputsWidth(s))
    }

    static func image(for specs: [IconSpec], phase: Int = 0) -> NSImage {
        let list = specs.isEmpty ? [IconSpec(kind: "box", pct: nil, charging: false, connected: false)] : specs
        let width = list.map(blockWidth).reduce(0, +) + blockGap * CGFloat(list.count - 1)
        let img = NSImage(size: NSSize(width: ceil(width), height: size), flipped: true) { _ in
            var x: CGFloat = 0
            for s in list {
                draw(s, originX: x, phase: phase)
                if !s.inputs.isEmpty { drawInputs(s, originX: x + size + inGap) }
                x += blockWidth(s) + blockGap
            }
            return true
        }
        img.isTemplate = false
        return img
    }

    // MARK: 入力 (ソーラー / AC) の記号と W 数

    private static func drawInputs(_ s: IconSpec, originX: CGFloat) {
        var x = originX
        let ink = NSColor.labelColor
        let top = (size - glyph) / 2
        for (i, inp) in s.inputs.enumerated() {
            if i > 0 { x += 4 }
            drawGlyph(inp.kind, x: x, y: top, ink: ink)
            x += glyph + 1
            let str = wattText(inp.watts) as NSString
            let attrs: [NSAttributedString.Key: Any] = [.font: textFont, .foregroundColor: ink]
            let sz = str.size(withAttributes: attrs)
            str.draw(at: NSPoint(x: x, y: (size - sz.height) / 2), withAttributes: attrs)
            x += ceil(sz.width)
        }
    }

    private static func drawGlyph(_ kind: String, x: CGFloat, y: CGFloat, ink: NSColor) {
        func p(_ a: CGFloat, _ b: CGFloat) -> NSPoint { NSPoint(x: x + a, y: y + b) }
        ink.setFill()
        ink.setStroke()
        switch kind {
        case "solar":  // 太陽
            NSBezierPath(ovalIn: NSRect(x: x + 2.9, y: y + 2.9, width: 4.2, height: 4.2)).fill()
            let rays = NSBezierPath()
            for k in 0..<8 {
                let a = CGFloat(k) * .pi / 4
                rays.move(to: p(5 + cos(a) * 3.4, 5 + sin(a) * 3.4))
                rays.line(to: p(5 + cos(a) * 4.9, 5 + sin(a) * 4.9))
            }
            rays.lineWidth = 1
            rays.lineCapStyle = .round
            rays.stroke()
        case "ac":  // コンセントのプラグ
            NSBezierPath(roundedRect: NSRect(x: x + 2.4, y: y + 4.0, width: 5.2, height: 3.6), xRadius: 1.2, yRadius: 1.2).fill()
            NSBezierPath(rect: NSRect(x: x + 3.3, y: y + 1.0, width: 1.0, height: 3.2)).fill()
            NSBezierPath(rect: NSRect(x: x + 5.7, y: y + 1.0, width: 1.0, height: 3.2)).fill()
            let cord = NSBezierPath()
            cord.move(to: p(5, 7.6))
            cord.line(to: p(5, 9.6))
            cord.lineWidth = 1
            cord.stroke()
        default:  // 稲妻 (入力元が分からないとき)
            let bolt = NSBezierPath()
            bolt.move(to: p(5.8, 0.6))
            bolt.line(to: p(2.4, 5.5))
            bolt.line(to: p(4.8, 5.5))
            bolt.line(to: p(4.2, 9.4))
            bolt.line(to: p(7.6, 4.2))
            bolt.line(to: p(5.2, 4.2))
            bolt.close()
            bolt.fill()
        }
    }

    // MARK: 本体のアイコン

    private static func draw(_ s: IconSpec, originX: CGFloat, phase: Int) {
        let k = size / 24
        let (bx, by, bw, bh, r) = body(s.kind)
        func rect(_ x: CGFloat, _ y: CGFloat, _ w: CGFloat, _ h: CGFloat) -> NSRect {
            NSRect(x: originX + x * k, y: y * k, width: w * k, height: h * k)
        }
        func pt(_ x: CGFloat, _ y: CGFloat) -> NSPoint { NSPoint(x: originX + x * k, y: y * k) }
        let bodyRect = rect(bx, by, bw, bh)
        let path = NSBezierPath(roundedRect: bodyRect, xRadius: r * k, yRadius: r * k)
        let ink = NSColor.labelColor

        if s.connected {
            // 空の部分はグレー
            ink.withAlphaComponent(0.28).setFill()
            path.fill()
            // 残量を下から塗る (flipped なので y は下向きに増える)
            if let pct = s.pct {
                let level = CGFloat(max(0, min(100, pct))) / 100
                let top = bodyRect.minY + bodyRect.height * (1 - level)
                NSGraphicsContext.saveGraphicsState()
                path.addClip()
                fillColor(s).setFill()
                NSRect(x: bodyRect.minX, y: top, width: bodyRect.width, height: bodyRect.maxY - top).fill()
                NSGraphicsContext.restoreGraphicsState()
            }
        }

        // 輪郭 (未接続は点線で薄く。20%未満で充電していないときは輪郭も赤にして、塗りが細くても目立たせる)
        let low = s.connected && !s.charging && (s.pct ?? 100) < 20
        let line = low ? NSColor.systemRed : ink
        let lc = line.withAlphaComponent(s.connected ? 0.9 : 0.5)
        lc.setStroke()
        path.lineWidth = 1.1
        if !s.connected {
            var dash: [CGFloat] = [2, 1.5]
            path.setLineDash(&dash, count: 2, phase: 0)
        }
        path.stroke()

        // 機種ごとの特徴: 液晶の位置と大きさ (+ C300 DC はストラップ)
        func lcd(_ x: CGFloat, _ y: CGFloat, _ w: CGFloat, _ h: CGFloat, _ rad: CGFloat) {
            let lp = NSBezierPath(roundedRect: rect(x, y, w, h), xRadius: rad * k, yRadius: rad * k)
            lp.lineWidth = 1.0
            lc.setStroke()
            lp.stroke()
        }
        switch s.kind {
        case "c300dc":
            lcd(7.6, 6.4, 8.8, 4.0, 1.0)
            let strap = NSBezierPath()
            strap.move(to: pt(18.5, 9.0))
            strap.curve(to: pt(18.5, 16.5), controlPoint1: pt(21.6, 9.0), controlPoint2: pt(21.6, 16.5))
            lc.setStroke()
            strap.lineWidth = 1.1
            strap.stroke()
        case "c2000g2":
            lcd(4.5, 9.2, 15, 4.8, 1.3)
        default:
            break
        }

        // 本体の中心 (稲妻・くるくるの位置)。C300 DC は液晶の下、C2000 Gen 2 は液晶の中
        let cx = bx + bw / 2
        var cy = by + bh / 2
        if s.kind == "c300dc" { cy = by + bh * 0.68 }
        if s.kind == "c2000g2" { cy = 11.6 }

        // 充電中は稲妻マーク (白 + 薄い縁取り: 明るい/暗いメニューバーのどちらでも見える)
        if s.connected && s.charging {
            let h: CGFloat = s.kind == "c2000g2" ? 7.5 : 9
            let w = h * 0.62
            func b(_ u: CGFloat, _ v: CGFloat) -> NSPoint { pt(cx - w / 2 + u * w, cy - h / 2 + v * h) }
            let bolt = NSBezierPath()
            bolt.move(to: b(0.68, 0.0))
            bolt.line(to: b(0.0, 0.58))
            bolt.line(to: b(0.46, 0.58))
            bolt.line(to: b(0.30, 1.0))
            bolt.line(to: b(1.0, 0.38))
            bolt.line(to: b(0.54, 0.38))
            bolt.close()
            NSColor.black.withAlphaComponent(0.55).setStroke()
            bolt.lineWidth = 0.8
            bolt.lineJoinStyle = .round
            bolt.stroke()
            NSColor.white.setFill()
            bolt.fill()
        }

        // USB で出力中は、右下に USB マーク (白 + 黒の縁取り)
        if s.connected && s.usbOut {
            let x0 = bx + bw - 9.5, y0 = by + bh - 3.4
            func usbPath() -> NSBezierPath {
                let u = NSBezierPath()
                u.move(to: pt(x0, y0)); u.line(to: pt(x0 + 7, y0))
                u.move(to: pt(x0 + 2, y0)); u.line(to: pt(x0 + 3.6, y0 - 2))
                u.line(to: pt(x0 + 5.6, y0 - 2))
                u.move(to: pt(x0 + 3.4, y0)); u.line(to: pt(x0 + 4.6, y0 + 1.8))
                return u
            }
            for (col, wdt) in [(NSColor.black.withAlphaComponent(0.6), CGFloat(2.4)), (NSColor.white, CGFloat(1.1))] {
                let u = usbPath()
                col.setStroke()
                u.lineWidth = wdt
                u.lineCapStyle = .round
                u.lineJoinStyle = .round
                u.stroke()
            }
            NSColor.white.setFill()
            NSBezierPath(ovalIn: NSRect(x: originX + (x0 - 1.3) * k, y: (y0 - 1.3) * k, width: 2.6 * k, height: 2.6 * k)).fill()
        }

        // 接続中・探し中は、中心でくるくる回る (8本の線の濃さが順に回る)
        if !s.connected && s.loading {
            let c = pt(cx, cy)
            let n = 8
            for i in 0..<n {
                let a = CGFloat(i) * 2 * .pi / CGFloat(n) - .pi / 2
                let age = (phase - i + n) % n  // 0 = いちばん濃い (先頭)
                let alpha = max(0.12, 1.0 - CGFloat(age) * 0.13)
                let r0 = 2.2 * k * 1.0, r1 = 4.4 * k * 1.0
                let tick = NSBezierPath()
                tick.move(to: NSPoint(x: c.x + cos(a) * r0, y: c.y + sin(a) * r0))
                tick.line(to: NSPoint(x: c.x + cos(a) * r1, y: c.y + sin(a) * r1))
                tick.lineWidth = 1.2
                tick.lineCapStyle = .round
                ink.withAlphaComponent(alpha).setStroke()
                tick.stroke()
            }
        }
    }
}

// MARK: - アプリ本体

final class AppDelegate: NSObject, NSApplicationDelegate, NSWindowDelegate, UNUserNotificationCenterDelegate {
    private var statusItem: NSStatusItem!
    private let popover = NSPopover()
    private let popoverVC = DashboardViewController()
    private var window: NSWindow?
    private var windowVC: DashboardViewController?

    private var backend: Process?
    private var lastBackendStart = Date.distantPast
    private var configProblem: String?
    private var timer: Timer?
    private var lowNotified = Set<String>()  // 残量低下を通知済みの機器

    // MARK: 起動

    func applicationDidFinishLaunching(_ notification: Notification) {
        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        if let btn = statusItem.button {
            btn.imagePosition = .imageLeft
            btn.target = self
            btn.action = #selector(statusClicked)
            btn.sendAction(on: [.leftMouseUp, .rightMouseUp])
        }
        setIcons([IconSpec(kind: "compact", pct: nil, charging: false, connected: false, loading: true)], tooltip: "接続を確認しています…")

        popover.behavior = .transient
        popover.contentSize = NSSize(width: 380, height: 640)
        popover.contentViewController = popoverVC

        installMainMenu()
        setupNotifications()
        popoverVC.showsDetachButton = true
        _ = popoverVC.view          // 先に作って読み込みを始める (起動直後にクリックしても黒い画面にならないように)
        popoverVC.loadIfNeeded()
        popoverVC.onOpenWindow = { [weak self] path in self?.detachToWindow(path) }

        fetch()
        timer = Timer.scheduledTimer(withTimeInterval: 2, repeats: true) { [weak self] _ in
            self?.fetch()
        }
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { false }

    func applicationWillTerminate(_ notification: Notification) {
        stopBackend()
    }

    // MARK: バックエンドの起動・停止
    //
    // すでに誰かが 19877 で動かしていれば (ターミナルで起動した場合など) 何もしない。
    // 応答がなく、自分が起動した子プロセスも無いときだけ起動する。

    private func startBackendIfNeeded() {
        if backend?.isRunning == true { return }
        if Date().timeIntervalSince(lastBackendStart) < 10 { return }  // 落ち続けても連打しない
        lastBackendStart = Date()

        guard let data = try? Data(contentsOf: launchConfigURL),
              let cfg = try? JSONDecoder().decode(LaunchConfig.self, from: data) else {
            configProblem = "起動設定がありません。ターミナルで amon setup を実行してください"
            return
        }
        guard FileManager.default.isExecutableFile(atPath: cfg.python),
              FileManager.default.fileExists(atPath: cfg.script) else {
            configProblem = "Python またはスクリプトが見つかりません。amon setup をやり直してください"
            return
        }
        configProblem = nil

        try? FileManager.default.createDirectory(at: dataDir, withIntermediateDirectories: true)
        if !FileManager.default.fileExists(atPath: backendLogURL.path) {
            FileManager.default.createFile(atPath: backendLogURL.path, contents: nil)
        }

        let p = Process()
        p.executableURL = URL(fileURLWithPath: cfg.python)
        p.arguments = [cfg.script]
        var env = ProcessInfo.processInfo.environment
        env["PYTHONUNBUFFERED"] = "1"
        p.environment = env
        if let fh = try? FileHandle(forWritingTo: backendLogURL) {
            fh.seekToEndOfFile()
            p.standardOutput = fh
            p.standardError = fh
        }
        do {
            try p.run()
            backend = p
        } catch {
            configProblem = "バックエンドを起動できません: \(error.localizedDescription)"
        }
    }

    private func stopBackend() {
        guard let p = backend, p.isRunning else { return }
        p.terminate()  // SIGTERM。Python 側でBluetoothを切ってから終わる
        let deadline = Date().addingTimeInterval(3)
        while p.isRunning && Date() < deadline {
            usleep(100_000)
        }
    }

    // MARK: 状態の取得

    private var inFlight = false
    private var remoteFailures = 0

    private func fetch() {
        // VPN 越しは 2 秒を超えることがあるので、前の応答を待っている間は次を投げない (重ねると詰まる)
        if inFlight { return }
        inFlight = true
        var req = URLRequest(url: baseURL.appendingPathComponent("status"))
        req.timeoutInterval = isRemote ? 5 : 1.5
        if let t = remoteToken { req.setValue("Bearer \(t)", forHTTPHeaderField: "Authorization") }
        URLSession.shared.dataTask(with: req) { [weak self] data, resp, _ in
            let code = (resp as? HTTPURLResponse)?.statusCode
            let status = code == 200 ? data.flatMap { try? JSONDecoder().decode(Status.self, from: $0) } : nil
            DispatchQueue.main.async {
                guard let self = self else { return }
                self.inFlight = false
                if let status = status {
                    self.configProblem = nil
                    self.remoteFailures = 0
                    self.apply(status)
                } else if isRemote {
                    // 自分のバックエンドは起動しない (起動すると、この Mac で Bluetooth を読み始めてしまう)。
                    // Wi-Fi の切り替えなどで 1〜2 回取りこぼすのはよくあるので、3 回続いてから未接続の表示にする
                    self.remoteFailures += 1
                    if code == 401 {
                        self.configProblem = "トークンが違います。記録している Mac の amon token の値で amon remote をやり直してください"
                    } else if code == 403 {
                        self.configProblem = "\(baseURL.host ?? "") に断られました (403)。記録している Mac で amon lan on になっているか確認してください"
                    } else {
                        self.configProblem = nil
                    }
                    if self.remoteFailures >= 3 || self.configProblem != nil { self.applyBackendDown() }
                } else {
                    self.applyBackendDown()
                    self.startBackendIfNeeded()
                }
            }
        }.resume()
    }

    // MARK: メニューバーの表示

    private var lastSpecs: [IconSpec] = []

    private func apply(_ status: Status) {
        guard !status.devices.isEmpty else {
            setIcons([IconSpec(kind: "compact", pct: nil, charging: false, connected: false)],
                     tooltip: "機器が登録されていません。クリックして追加できます")
            return
        }
        let specs = status.devices.map { d -> IconSpec in
            let ok = d.state == "connected" && d.battery != nil
            let busy = d.state == "connecting" || d.state == "searching"  // 値が取れていない「接続済み」では回さない
            return IconSpec(kind: d.icon ?? "box", pct: ok ? d.battery : nil,
                            charging: ok && (d.power_in ?? 0) >= 1 && (d.power_in ?? 0) > (d.power_out ?? 0), connected: ok,
                            inputs: ok ? inputSpecs(d) : [], loading: busy,
                            discharging: ok && (d.power_out ?? 0) >= 1 && (d.power_out ?? 0) > (d.power_in ?? 0),
                            usbOut: ok && (d.ports ?? []).contains { $0.dir == "out" && $0.w > 0 && $0.key.hasPrefix("usb") })
        }
        checkLowBattery(status.devices)
        let tooltip = status.devices.map { d -> String in
            let name = d.name ?? d.id
            switch d.state ?? "" {
            case "connected":
                let b = d.battery.map { "\($0)%" } ?? "--%"
                let chg = d.charging == "CHARGING" ? " 充電中" : ""
                return "\(name): \(b)  ↓\(d.power_in ?? 0)W ↑\(d.power_out ?? 0)W\(chg)"
            case "connecting": return "\(name): 接続中…"
            case "searching": return "\(name): 探しています…"
            default: return "\(name): オフライン"
            }
        }.joined(separator: "\n")
        setIcons(specs, tooltip: tooltip)
    }

    /// 入力があるときだけ、入力元ごとの W 数 (ソーラー → AC → その他の順)。合計 0W なら空
    private func inputSpecs(_ d: Device) -> [InputSpec] {
        var solar = 0, ac = 0, other = 0
        for p in d.ports ?? [] where p.dir == "in" && p.w > 0 {
            switch p.key {
            case "solar": solar += p.w
            case "ac_in": ac += p.w
            default: other += p.w
            }
        }
        if solar + ac + other == 0, let pin = d.power_in, pin > 0 { other = pin }  // 内訳が無くても合計があれば出す
        var out: [InputSpec] = []
        if solar > 0 { out.append(InputSpec(kind: "solar", watts: solar)) }
        if ac + other > 0 { out.append(InputSpec(kind: "other", watts: ac + other)) }  // AC / USB 給電は、稲妻 (電気) マーク
        return out
    }

    // MARK: 残量低下の通知 (10% 未満、充電していないとき。1回だけ。15% 以上に戻るか充電が始まれば、また通知できる)

    private static let lowThreshold = 10
    private static let lowRearm = 15

    private func checkLowBattery(_ devices: [Device]) {
        for d in devices {
            guard d.state == "connected", let b = d.battery else { continue }
            let charging = d.charging == "CHARGING"
            if b < Self.lowThreshold && !charging {
                if !lowNotified.contains(d.id) {
                    lowNotified.insert(d.id)
                    notify(title: "\(d.name ?? d.id) の残量が \(b)% です",
                           body: "充電していません。出力 \(d.power_out ?? 0)W。充電してください。")
                }
            } else if b >= Self.lowRearm || charging {
                lowNotified.remove(d.id)
            }
        }
    }

    private var canUseNotificationCenter: Bool {
        Bundle.main.bundleIdentifier != nil && Bundle.main.bundlePath.hasSuffix(".app")
    }

    private func setupNotifications() {
        guard canUseNotificationCenter else { return }
        let c = UNUserNotificationCenter.current()
        c.delegate = self
        c.requestAuthorization(options: [.alert, .sound]) { _, _ in }
    }

    // アプリが前面にいても通知を出す (メニューバーアプリは常に前面扱いになることがある)
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .sound])
    }

    /// macOS の通知を出す。通知センターが使えない / 許可されていないときは osascript で代用する
    private func notify(title: String, body: String) {
        guard canUseNotificationCenter else { return osascriptNotify(title: title, body: body) }
        let c = UNUserNotificationCenter.current()
        c.getNotificationSettings { [weak self] st in
            guard st.authorizationStatus == .authorized || st.authorizationStatus == .provisional else {
                DispatchQueue.main.async { self?.osascriptNotify(title: title, body: body) }
                return
            }
            let content = UNMutableNotificationContent()
            content.title = title
            content.body = body
            content.sound = .default
            c.add(UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil))
        }
    }

    private func osascriptNotify(title: String, body: String) {
        func esc(_ s: String) -> String { s.replacingOccurrences(of: "\\", with: "\\\\").replacingOccurrences(of: "\"", with: "\\\"") }
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/usr/bin/osascript")
        p.arguments = ["-e", "display notification \"\(esc(body))\" with title \"\(esc(title))\" sound name \"Funk\""]
        try? p.run()
    }

    @objc private func testNotification() {
        notify(title: "Anker Monitor の通知テスト", body: "残量が 10% 未満になると、こうして通知します。")
    }

    private func applyBackendDown() {
        let waiting = isRemote ? "\(baseURL.host ?? "") に接続できません (Tailscale と、記録している Mac が起きているか確認してください)"
                               : "バックエンドを起動しています…"
        setIcons([IconSpec(kind: "compact", pct: nil, charging: false, connected: false, loading: configProblem == nil)],
                 tooltip: configProblem ?? waiting)
    }

    private var spinTimer: Timer?
    private var spinPhase = 0

    private func setIcons(_ specs: [IconSpec], tooltip: String) {
        guard let btn = statusItem.button else { return }
        btn.toolTip = tooltip
        if specs == lastSpecs && btn.image != nil { return }  // 変わっていなければ描き直さない
        lastSpecs = specs
        btn.title = ""
        btn.imagePosition = .imageOnly
        btn.image = StatusIcon.image(for: specs, phase: spinPhase)
        updateSpinner()
    }

    /// 接続中の機器があるあいだだけ、アイコンのくるくるを回す (それ以外はタイマーなし)
    private func updateSpinner() {
        let needs = lastSpecs.contains { $0.loading && !$0.connected }
        if needs && spinTimer == nil {
            spinTimer = Timer.scheduledTimer(withTimeInterval: 0.12, repeats: true) { [weak self] _ in
                guard let self, let btn = self.statusItem.button else { return }
                self.spinPhase = (self.spinPhase + 1) % 8
                btn.image = StatusIcon.image(for: self.lastSpecs, phase: self.spinPhase)
            }
            RunLoop.main.add(spinTimer!, forMode: .common)
        } else if !needs, let t = spinTimer {
            t.invalidate()
            spinTimer = nil
        }
    }

    // MARK: クリック

    @objc private func statusClicked() {
        let ev = NSApp.currentEvent
        let isRight = ev?.type == .rightMouseUp || (ev?.modifierFlags.contains(.control) ?? false)
        if isRight {
            statusItem.menu = makeMenu()
            statusItem.button?.performClick(nil)
            statusItem.menu = nil
        } else {
            togglePopover()
        }
    }

    private func togglePopover() {
        if popover.isShown {
            popover.performClose(nil)
        } else if let btn = statusItem.button {
            popoverVC.loadIfNeeded()
            popoverVC.goHomeIfNeeded()  // いつも「いま」から始める (履歴・機器は別ページ)
            popover.show(relativeTo: btn.bounds, of: btn, preferredEdge: .minY)
            NSApp.activate(ignoringOtherApps: true)
        }
    }

    private func makeMenu() -> NSMenu {
        let m = NSMenu()
        func add(_ title: String, _ sel: Selector, _ key: String = "") {
            let it = NSMenuItem(title: title, action: sel, keyEquivalent: key)
            it.target = self
            m.addItem(it)
        }
        add("ウィンドウで開く", #selector(openWindow), "0")
        let fl = NSMenuItem(title: "ウィンドウを常に手前に表示", action: #selector(toggleFloating(_:)), keyEquivalent: "")
        fl.target = self
        fl.state = UserDefaults.standard.bool(forKey: "windowFloating") ? .on : .off
        m.addItem(fl)
        add("履歴を開く", #selector(openHistory))
        if !isRemote { add("機器を追加…", #selector(openDevices)) }  // リモートからは機器の操作はできない (403)
        add("ブラウザで開く", #selector(openBrowser))
        m.addItem(.separator())
        add("通知をテスト", #selector(testNotification))
        if isRemote {
            let info = NSMenuItem(title: "リモート表示: \(baseURL.host ?? "")", action: nil, keyEquivalent: "")
            info.isEnabled = false
            m.addItem(info)
        } else {
            add("バックエンドのログを表示", #selector(revealLog))
            add("バックエンドを再起動", #selector(restartBackend))
        }
        m.addItem(.separator())
        add("終了", #selector(quit), "q")
        return m
    }

    // MARK: ウィンドウ

    /// ポップオーバーのページを、そのまま独立ウィンドウに出す
    private func detachToWindow(_ path: String) {
        openWindow()
        windowVC?.show(path: path)
    }

    @objc func openWindow() {
        popover.performClose(nil)
        if window == nil {
            let vc = DashboardViewController()
            vc.onOpenWindow = { [weak self] _ in self?.window?.makeKeyAndOrderFront(nil) }  // すでにウィンドウ
            // contentViewController 経由だと大きさが固定されることがあるので、ウィンドウを自分で作って、
            // 中身のビューだけ載せる (ドラッグで自由に拡大縮小できる)
            let w = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 420, height: 720),
                             styleMask: [.titled, .closable, .miniaturizable, .resizable],
                             backing: .buffered, defer: false)
            w.contentView = vc.view
            w.title = "Anker Monitor"
            w.contentMinSize = NSSize(width: 320, height: 400)
            w.contentMaxSize = NSSize(width: CGFloat.greatestFiniteMagnitude, height: CGFloat.greatestFiniteMagnitude)
            w.isMovableByWindowBackground = false
            w.isReleasedWhenClosed = false
            w.delegate = self
            w.setFrameAutosaveName("AnkerMonitorWindow2")  // 縦に伸びた古い保存サイズを引き継がない
            w.level = UserDefaults.standard.bool(forKey: "windowFloating") ? .floating : .normal
            window = w
            windowVC = vc
        }
        windowVC?.loadIfNeeded()
        // ウィンドウを出している間だけ Dock に出して、Cmd+Tab で戻れるようにする
        NSApp.setActivationPolicy(.regular)
        window?.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
    }

    @objc private func toggleFloating(_ item: NSMenuItem) {
        let on = !UserDefaults.standard.bool(forKey: "windowFloating")
        UserDefaults.standard.set(on, forKey: "windowFloating")
        item.state = on ? .on : .off
        window?.level = on ? .floating : .normal
    }

    @objc private func openDevices() {
        openWindow()
        windowVC?.show(path: "/devices")
    }

    @objc private func openHistory() {
        openWindow()
        windowVC?.show(path: "/history")
    }

    func windowWillClose(_ notification: Notification) {
        NSApp.setActivationPolicy(.accessory)
    }

    private func installMainMenu() {
        let main = NSMenu()
        let appItem = NSMenuItem()
        main.addItem(appItem)
        let appMenu = NSMenu()
        appMenu.addItem(NSMenuItem(title: "Anker Monitor を終了",
                                   action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q"))
        appItem.submenu = appMenu

        let winItem = NSMenuItem()
        main.addItem(winItem)
        let winMenu = NSMenu(title: "ウィンドウ")
        winMenu.addItem(NSMenuItem(title: "閉じる", action: #selector(NSWindow.performClose(_:)), keyEquivalent: "w"))
        winMenu.addItem(NSMenuItem(title: "しまう", action: #selector(NSWindow.performMiniaturize(_:)), keyEquivalent: "m"))
        winItem.submenu = winMenu
        NSApp.mainMenu = main
    }

    // MARK: メニュー項目

    @objc private func openBrowser() {
        NSWorkspace.shared.open(browserURL(baseURL))
    }

    @objc private func revealLog() {
        if FileManager.default.fileExists(atPath: backendLogURL.path) {
            NSWorkspace.shared.activateFileViewerSelecting([backendLogURL])
        } else {
            NSWorkspace.shared.open(dataDir)
        }
    }

    @objc private func restartBackend() {
        stopBackend()
        backend = nil
        lastBackendStart = .distantPast
        startBackendIfNeeded()
    }

    @objc private func quit() {
        NSApp.terminate(nil)
    }
}

// MARK: - エントリポイント

let app = NSApplication.shared
app.setActivationPolicy(.accessory)  // メニューバーだけのアプリ (ウィンドウ表示中は一時的に .regular)
let delegate = AppDelegate()
app.delegate = delegate
app.run()
