# XTract status

Paused on 2026-10-27. Everything except logging in to X inside the app is built and builds cleanly.

## Works (as far as it can be checked without a phone)
- Layout from the sketch: logo and title, status caption and progress dial on top, X in the middle, PDF / Claude / Print along the bottom.
- Thread collection: `app/src/main/assets/scraper.js` scrolls a post, opens collapsed replies and gathers every post. Tested in headless Chromium against a mock X page (`node test/scraper.test.js`, passes).
- PDF export to `Downloads/XTract`, Print through Android's print dialog, and sharing the thread to the Claude app (with a Google Play link if it isn't installed).
- Builds: `./scripts/build-apk.sh` (no Android SDK needed) and GitHub Actions (`.github/workflows/build-apk.yml`, passing). Prebuilt APK: `dist/XTract.apk`.

## Doesn't work
**Logging in to X inside XTract.** The login screen accepts the username, then X rejects the password step ("Sorry, you are not allowed to log in at this time"). The same account logs in fine in Chrome on the same phone, so the account and network are fine. X rejects sign-ins from apps that embed its website.

What was tried, in order, and the result:
1. Removing `; wv` from the user agent (the first version): rejected.
2. Full Chrome user agent, `window.chrome`, clean cookies: got to the password step, then rejected.
3. Trying to remove the `X-Requested-With` header and the "Android WebView" client-hint brand: this WebView (155) doesn't expose the methods, so that was not possible.
4. Back to the first version's browser setup: still rejected.

Further tweaks to the embedded browser are unlikely to help, and imitating Chrome to get past X's check isn't a path worth continuing.

## Options if this is picked up again
- **Read the official X app's screen** with an Android accessibility service. No login in XTract; the thread is read from the X app where the user is already signed in. Needs a one-time Accessibility setting, and the first build would need adjusting against real screenshots of the X app.
- **Official X API** (paid plan, about $200 a month; replies only from the last 7 days). The user would give XTract their API key.
- PDF, Print and Claude sharing can be reused unchanged with either option.
