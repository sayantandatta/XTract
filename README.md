# XTract

An Android app (APK, for sideloading onto your own phone) that wraps X in a native shell so you can read a thread,
save it, print it, or hand it to Claude for a summary.

```
┌──────────────────────────────┐
│ ▣ XTract        caption  (◔) │   ← logo + title (tap for menu), status, progress dial
├──────────────────────────────┤
│                              │
│        x.com, full screen    │   ← no address bar, no browser controls
│                              │
├──────────────────────────────┤
│    PDF      Claude    Print  │   ← the three actions
└──────────────────────────────┘
```

## Using it

1. **Log in to X** once with your X username and password. The session is kept.
2. **Open a post.** Browse to it inside the app, use *Share → XTract* from the X app, or tap the XTract title and
   choose *Open a post link…* (it pre-fills a link from your clipboard). The three actions light up on post pages.
3. Tap an action. Each one first **collects the whole thread**: the app scrolls through every reply, opens the
   collapsed "Show more replies" sections and captures each post as it goes. The dial at the top shows progress;
   tap it to stop early and keep what has loaded. A collected thread is reused by the next action.
   - **PDF** saves the original post and every reply (author, handle, time, text, quoted posts, engagement stats,
     permalink) to `Downloads/XTract/<handle>-<id>-thread.pdf`, then offers to open or share it.
   - **Claude** sends the thread to the Claude Android app as a text file, together with a prompt that asks for
     the trending ideas, overall sentiment, dissent, open questions and key takeaways as a summary document. The
     summary appears in Claude. If Claude isn't installed, the app points you to its Google Play page.
   - **Print** sends the same PDF to Android's print dialog, which can use any printer or "Save as PDF".

The dial is idle while you browse and scroll by hand. It only moves while a thread is being collected, written or
prepared for printing.

## Look and feel

A single light theme in X's palette: white bars, hairline dividers, X blue for the brand and the dial, and three
rounded action tiles at the bottom. The tiles are greyed out until you open a post. A thin blue line under the top
bar shows while X is loading a page. The x.com page itself keeps whatever display setting your X account has.

## Install

Copy `dist/XTract.apk` to your phone and open it. Allow "Install unknown apps" for your file manager or browser
when Android asks. Requires Android 10 or newer. The Claude app (`com.anthropic.claude`) is needed for the
Claude action.

The APK is signed with the debug key in `keystore/` (password `android`). That key is fine for personal
sideloading, but don't use it to publish the app anywhere.

## Privacy

Everything stays on the phone except what you explicitly send: the thread text goes to the Claude app (and from
there to Anthropic) only when you tap Claude. The app has no analytics and no API keys. It needs only the
`INTERNET` permission; files are written through MediaStore, so no storage permission is requested.

## Build

There are two ways to build. Both produce the same app with the same signing key.

**Without the Android SDK** (this is how `dist/XTract.apk` was built). On Debian or Ubuntu with JDK 17+:

```sh
sudo apt-get install aapt dalvik-exchange zipalign apksigner
./scripts/build-apk.sh            # → dist/XTract.apk
```

The script downloads the API 34 `android.jar` once and checks its SHA-256. To use your own copy, set
`ANDROID_JAR=$ANDROID_HOME/platforms/android-34/android.jar`.

**With Android Studio / Gradle**: open the folder, or run `./gradlew assembleDebug`. Output:
`app/build/outputs/apk/debug/app-debug.apk`.

The code is plain Java with no libraries (no AndroidX), so it has to stay free of lambdas and other Java 8
language features that the `dx` dexer in the SDK-less build doesn't support.

## Test

`test/scraper.test.js` runs the thread collector against a mock X page in headless Chromium. The mock page
copies X's markup: it virtualises the list, lazy-loads posts, has collapsed replies, an ad, a quoted post, emoji
and a "Discover more" section.

```sh
npm i playwright && node test/scraper.test.js
```

## Notes and limits

- The collector depends on X's page markup (`article[data-testid="tweet"]`, `tweetText`, `User-Name`). If X
  changes its markup, update `app/src/main/assets/scraper.js`.
- "Sign in with Google" doesn't work inside embedded browsers. Use your X username and password.
- Very large threads take a while to collect, roughly one screen per second.
- The icons for PDF and Print are Material Design glyphs; the Claude button uses a generic starburst, not
  Anthropic's trademarked logo.

## Layout

```
app/src/main/
  AndroidManifest.xml
  assets/scraper.js            auto-scroll + collect script injected into x.com
  java/com/xtract/app/
    MainActivity.java          screen, actions, jobs (collect → PDF / Claude / print)
    ProgressDial.java          the ring in the top bar
    ThreadScraper.java         JS bridge for scraper.js
    ThreadData.java            collected posts + plain-text rendering
    PdfExporter.java           thread PDF (android.graphics.pdf)
    Downloads.java             saves files to Downloads/XTract via MediaStore
    ClaudeShare.java           share to the Claude app / Play Store link
    Settings.java              last page
  res/drawable/                vector icons
scripts/build-apk.sh           SDK-less build
dist/XTract.apk                prebuilt APK
```
