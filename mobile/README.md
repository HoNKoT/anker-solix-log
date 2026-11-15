# Anker Monitor (スマホアプリ)

記録している Mac のバックエンド (`anker_monitor.py`) の `GET /status` を読んで、
ポータブル電源の残量・入出力・充電状態をスマホで見るアプリ。Android と iOS (画面のみ。ウィジェットは Android だけ)。

- **画面**: 機器ごとの残量 %、入力 W / 出力 W、充電中かどうか、接続状態。表示中は 3 秒おきに読む。
- **ホーム画面ウィジェット**: 機器ごとの残量 % と充電 / 放電。WorkManager で 15 分おきに更新し、
  アプリを開いたときにもすぐ反映する。即時のプッシュ (FCM) は無い。
- 読み取りだけ。バックエンドの API (`amon lan on` で外に出る読み取り用のパス) を Bearer トークン付きで読む。

## 構成

```
mobile/
├── shared/       Kotlin Multiplatform。/status の型、Ktor の API、URL の補完、定期取得、エラーの言い分け
│   ├── commonMain    共通ロジック
│   ├── androidMain   OkHttp エンジン、DataStore への保存
│   └── iosMain       Darwin エンジン、NSUserDefaults への保存
├── sharedUi/     Compose Multiplatform の画面 (設定・メイン)、ViewModel、Preview
│   └── iosMain       MainViewController() (iOS アプリから呼ぶ入口)
├── androidApp/   Android の入れ物 (MainActivity)、Glance のウィジェット、WorkManager
├── iosApp/       iOS の入れ物 (SwiftUI + Xcode プロジェクト)。SharedUi framework の MainViewController() を表示する
└── screenshots/  Preview のスクリーンショット (android/ と ios/)、シミュレータで起動した画面 (ios-app/)
```

バージョンは `gradle/libs.versions.toml` にまとめてある (AGP 9.5.1 / Kotlin 2.4.21 / Gradle 9.9.1 / Ktor 3.6.1 /
Compose Multiplatform 1.12.2)。Material3 は Compose Multiplatform 1.12.2 のリリースノートが組み合わせとして指定している
`1.12.0-alpha03` (JetBrains 版の Material3 は alpha 番号で出ている)。
AGP 9 なので、shared は `com.android.kotlin.multiplatform.library` プラグインを使う。

## ビルド

Android Studio で `mobile/` を開くか、コマンドで:

```bash
cd mobile
./gradlew :androidApp:assembleDebug      # APK: androidApp/build/outputs/apk/debug/androidApp-debug.apk
./gradlew :shared:testAndroidHostTest    # shared のユニットテスト (/status のパースなど)
./gradlew :androidApp:installDebug       # つないだ端末に入れる
```

- JDK 17 以上が要る (Android Studio 同梱の JBR でよい)。
- `local.properties` の `sdk.dir` に Android SDK の場所を書く (Android Studio で開けば自動で作られる)。
- `./gradlew check` / `allTests` は iOS (Kotlin/Native) までビルドしようとするので、Android だけなら上のタスクを使う。

## Preview とスクリーンショット

画面の Preview は `sharedUi/src/commonMain/.../Previews.kt` にある (見本の値は `/status` の形に合わせた `PreviewSamples`)。
Android Studio の Preview でも見られるほか、同じ Preview を両 OS で描いて画像にできる:

```bash
./gradlew :androidApp:recordRoborazziDebug              # → screenshots/android/*.png (Robolectric)
./gradlew :sharedUi:recordRoborazziIosSimulatorArm64    # → screenshots/ios/*.png (iOS シミュレータ。Xcode が要る)
```

- 一覧は `screenshotPreviews` (Previews.kt)。Preview を足したらここにも足すと、両 OS の画像が増える。
- Preview ではダイナミックカラーを切っている (`AnkerTheme(dynamicColor = false)`) ので、両 OS で同じ配色になる。
- iOS は iPhone 15 相当 (393×852pt @3x) で描く。Android は 411×891dp @xxhdpi。
- iOS のテストが `dyld_sim mmap() of segment failed` で落ちたら、Xcode 更新直後の CoreSimulator の入れ替えが原因のことがある。もう一度流すと通った。
- ホーム画面ウィジェット (Glance) はこの仕組みの対象外。

## スマホで見るまでの手順

### 1. 記録している Mac で (1 回だけ)

```bash
amon lan on      # 外 (Tailscale / LAN) から読めるようにする。読み取りだけ許可される
amon token new   # トークンを作る (もうあれば `amon token` で表示)
```

`amon lan on` の出力に `外出先用 URL (Tailscale): http://100.x.y.z:19877` が出る。この IP とトークンを使う。

### 2. スマホに Tailscale を入れる

1. Google Play から **Tailscale** を入れ、Mac と**同じアカウント**でログインする。
2. Tailscale を「接続」にする。Mac 側も Tailscale がオンになっていること。
3. (任意) MagicDNS が有効なら、IP の代わりに Mac の名前 (例: `my-mac` や `my-mac.tailXXXX.ts.net`) も使える。

### 3. アプリに入れる

初回起動の「接続先の設定」で:

| 項目 | 入れるもの |
|---|---|
| 接続先 | `100.x.y.z` (手順 1 の Tailscale IP) か MagicDNS 名。`http://` と `:19877` は省略できる (`amon remote` と同じ補完) |
| トークン | Mac で `amon token` を実行して表示された値。Mac 側でトークンを作っていなければ空欄 |

「接続を確認して保存」を押すと、その場で `/status` を読んで確かめる。

- **401 (トークンが違う)** / **403 (断られた)** のときは保存しない。
- つながらないときは、`amon remote` と同じく設定だけ保存して、後で読み直す。

### 4. ウィジェットを置く

ホーム画面を長押し → ウィジェット → **Anker Monitor / Anker 残量**。
15 分おき (WorkManager の最短間隔。端末の省電力で遅れることがある) と、アプリを開いたときに更新される。
更新できなかったときは、最後に読めた値に「(更新できません)」と付けて表示する。

## 表示されないとき

アプリのエラー表示は、メニューバーアプリ (`AnkerMonitor.swift` の `fetch()`) と同じ分け方になっている。

| 表示 | 確認すること |
|---|---|
| トークンが違います | Mac で `amon token` を実行し、表示された値を入れ直す |
| 〜に断られました (403) | Mac で `amon lan on` になっているか |
| 記録している Mac に接続できません | スマホと Mac の Tailscale がオンか、Mac がスリープしていないか |

- 平文 HTTP は `androidApp/src/main/res/xml/network_security_config.xml` の `base-config` で許可している。
  Android 9 以降は cleartext が既定で禁止なので、これが無いと Tailscale の `http://100.x...` が読めない
  (macOS 版でも ATS が同じ理由で拒否して何も表示されない不具合があった)。
- トークンは端末内の DataStore に平文で保存する (読み取り専用のトークンなので、暗号化はしていない)。

## iOS のビルド

`iosApp/iosApp.xcodeproj` を Xcode で開き、スキーム `iosApp` を選んで iPhone シミュレータ (または実機) で Run する。
コマンドでシミュレータ向けにビルドするなら:

```bash
cd mobile/iosApp
xcodebuild -project iosApp.xcodeproj -scheme iosApp -configuration Debug \
  -destination 'generic/platform=iOS Simulator' -derivedDataPath build/DerivedData build
open -a Simulator   # シミュレータを起動しておく (Xcode の Window > Devices and Simulators でも可)
xcrun simctl install booted build/DerivedData/Build/Products/Debug-iphonesimulator/AnkerMonitor.app
xcrun simctl launch booted app.ankermonitor.ios
```

- Kotlin 側は Xcode のビルドフェーズ「Compile Kotlin Framework」が `./gradlew :sharedUi:embedAndSignAppleFrameworkForXcode` を呼んで作る
  (KMP 標準の組み込み方)。出力は `sharedUi/build/xcode-frameworks/<構成>/<SDK>/SharedUi.framework` で、
  `FRAMEWORK_SEARCH_PATHS` と `-framework SharedUi` でリンクする。static framework なので Embed は不要
  (Gradle のログで `embedAndSignAppleFrameworkForXcode SKIPPED` と出るのは正常)。
- Run Script はログインシェルの設定を読まない。`JAVA_HOME` が無ければ Android Studio 同梱の JBR
  (`/Applications/Android Studio.app/Contents/jbr/Contents/Home`) を使う。ほかの JDK を使うなら Xcode を起動する環境で `JAVA_HOME` を設定する。
- `local.properties` (Android SDK の場所) も要る。Gradle が Android のプラグインも読み込むため。
- `ENABLE_USER_SCRIPT_SANDBOXING = NO`。Xcode のスクリプト用サンドボックスが有効だと Gradle がプロジェクト外に書けず失敗する。
- シミュレータは Apple Silicon (arm64) だけ。`sharedUi` が `iosArm64` / `iosSimulatorArm64` しか出さないので、x86_64 は除外している。
- 最低 iOS は 18.5。Compose Multiplatform 同梱の ICU が iOS 18.5 向けにビルドされていて、それより下にするとリンカが警告を出す。
- 実機で動かすときは Xcode の Signing & Capabilities で自分の Team を選ぶ (プロジェクトには Team を入れていない)。
- iOS も ATS が平文 http を拒否するので、`iosApp/iosApp/Info.plist` に `NSAppTransportSecurity` / `NSAllowsArbitraryLoads` を入れている
  (Tailscale の `http://100.x...` を読むため)。
- スマホ側の Tailscale は App Store の **Tailscale** を入れて、Mac と同じアカウントでログインする (手順 2 と同じ)。
- ウィジェット (WidgetKit) はまだ無い。Glance は Android 専用なので、iOS では別に作る必要がある。
