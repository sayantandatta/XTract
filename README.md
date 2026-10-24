# XTract

An Android app (APK, for sideloading onto your own phone) for reading and exporting X threads:

1. **Browse X** in a built-in browser. Log in once and the session is kept.
2. **Open a thread**: navigate to a post, paste a link in the address bar, or use *Share → XTract* from the X app.
3. **Load all** scrolls the whole thread for you. It opens the collapsed "Show more replies" / "Show probable spam"
   sections and collects every reply as it goes. X removes posts from the page as you scroll past them, so this
   step is what lets the app capture long threads. Tap **Stop** at any time to keep what has been collected so far.
4. **PDF** exports the original post and all replies (author, handle, time, text, quoted posts, engagement stats,
   permalink) to `Downloads/XTract/<handle>-<id>-thread.pdf`.
5. **Analyze** finds the trending ideas in the replies and writes a summary document with these sections: overview,
   ranked trending ideas with quotes, sentiment, dissent, open questions and key takeaways. You can read it in the
   app, copy it, or export it to `Downloads/XTract/<handle>-<id>-summary.pdf`.

If you tap PDF or Analyze before Load all, the app collects the thread first.

## Install

Copy `dist/XTract.apk` to your phone and open it. Allow "Install unknown apps" for your file manager or browser
when Android asks. Requires Android 10 or newer.

The APK is signed with the debug key in `keystore/` (password `android`). That key is fine for personal
sideloading, but don't use it to publish the app anywhere.

## How "Analyze" uses Claude

Claude models don't run on the phone itself. Both options send the thread text to Anthropic's cloud.

- **Analyze with Claude (API)**: the app calls the Claude API directly with your own API key. Get a key at
  console.anthropic.com and enter it under ⚙ Settings. The key is encrypted with the Android Keystore and is only
  ever sent to `api.anthropic.com`. You can pick the model in Settings: `claude-opus-5-5` (the default),
  `claude-sonnet-5-5` or `claude-haiku-5-5`. API usage is billed to your Anthropic account. Threads longer than
  about 1M characters are cut off at the end, and the app tells you when that happens.
- **Send to Claude app**: no key needed. The app saves the thread as a text file and shares it, together with the
  analysis prompt, with the Claude Android app (or any app you choose). You then read the summary in that app.

Opus 5.5 and Sonnet 5.5 requests turn on server-side fallback. If a safety check declines a thread, Anthropic
reruns it on another model instead of failing.

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
- Logging in to X with "Sign in with Google" doesn't work inside embedded browsers. Use your X username and
  password.
- Very large threads take a while to scroll, roughly one screen per second.
- Everything stays on the phone, except the thread text that you choose to send to Claude.

## Layout

```
app/src/main/
  AndroidManifest.xml
  assets/scraper.js            auto-scroll + collect script injected into x.com
  java/com/xtract/app/
    MainActivity.java          browser UI, buttons, dialogs
    ThreadScraper.java         JS bridge for scraper.js
    ThreadData.java            collected posts + plain-text rendering
    PdfExporter.java           thread / summary PDFs (android.graphics.pdf)
    Downloads.java             saves files to Downloads/XTract via MediaStore
    ClaudeClient.java          Claude Messages API (streaming)
    Settings.java              preferences + Keystore-encrypted API key
scripts/build-apk.sh           SDK-less build
dist/XTract.apk                prebuilt APK
```
