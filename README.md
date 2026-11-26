# Gemini Enhanced for Android

Gemini Enhanced is an Android app for Google Gemini. It shows the Gemini web app in a WebView and adds what the web app lacks on a phone: a native chat sidebar, replies that keep going after you leave the app, and a strict allow list that blocks every request Gemini does not need.

Unofficial app, not affiliated with Google.

## Features

- Native chat sidebar. Swipe right from anywhere or tap the menu button to open it. It follows your finger, shows pinned chats and the open chat, and appears at once on start from a cache on the device. Back closes it.
- Background replies. If you switch apps, turn the screen off or swipe the app away while Gemini is writing, the reply still finishes. A notification shows while Gemini is replying and another one when the reply is ready.
- Opens the chat you had open last.
- No blank or black screen when you return to the app. If the page crashes, the app rebuilds it with your chat.
- Near zero CPU use while idle. Gemini's hidden loading animation is switched off and the app runs no timers while nothing is happening.
- Code and HTML previews work, including the chart and script libraries they load.
- Generated images can be saved to Downloads.
- Phones get Gemini's mobile layout, tablets the desktop layout.

## Install

- GitHub: download the APK from [Releases](https://github.com/tikkamasalla/gemini-enhanced-android/releases).
- F-Droid: coming once the app is listed.

Android 5.0 or newer. You need a Google account to use Gemini.

## Build from source

You need JDK 17 and the Android SDK (platform 35).

```
git clone https://github.com/tikkamasalla/gemini-enhanced-android.git
cd gemini-enhanced-android
./gradlew assembleRelease
```

The unsigned APK is in `app/build/outputs/apk/release/`. Sign it with your own key using `apksigner`. A debug build (`./gradlew assembleDebug`) installs next to the release build as a separate app.

## Privacy

The app has no analytics, no ads and no trackers. It talks only to Google, and only to the hosts Gemini needs.

Every request the page makes is checked against an allow list. A host passes if it is one of these or a subdomain of one:

| Host | Why |
| --- | --- |
| gemini.google.com | the Gemini web app |
| accounts.google.com, apis.google.com, play.google.com | sign in and account switching |
| www.google.com | reCAPTCHA during sign in |
| gstatic.com | scripts, styles and fonts |
| googleusercontent.com | avatars, uploaded and generated images |
| googleapis.com | Gemini's backend APIs |
| push.clients6.google.com | file and image uploads |
| geminiweb-pa.clients6.google.com | Gemini's web API, used while uploading |
| waa-pa.clients6.google.com | page attestation; without it replies stall |
| signaler-pa.clients6.google.com | the channel that streams replies |
| scf.usercontent.goog | sandboxed frames for code and HTML previews |

Two public CDNs, cdn.jsdelivr.net and cdnjs.cloudflare.com, are allowed only for requests that come from a preview frame, because previews load their libraries from there. The same CDNs stay blocked for everything else.

Other rules:

- Plain HTTP is never loaded, and third party cookies are off.
- Links to other sites open in your browser instead of inside the app.
- WebView usage reporting and Android app backup are off.
- The chat list for the sidebar is cached in the app's private storage on your device. Nothing leaves the device except the requests Gemini itself makes.

Permissions:

- Internet: to load Gemini.
- Notifications, foreground service and wake lock: to keep a reply streaming while the app is in the background and to tell you when it is done.
- Microphone: only passed to the page when Gemini asks for it.
- Storage, on Android 9 and older only: to save images to Downloads.

## Credits

Based on geminiAssist by AcideFluorhydrique (GPL-3.0), which builds on gptAssist by woheller69 and GMaps WV by Divested Computing Group.

## License

GPL-3.0. See [LICENSE](LICENSE).
