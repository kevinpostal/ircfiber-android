# Play Store listing — IRC Fiber

## App details
- **Package name:** `com.kevinpostal.fiberirc`
- **App name:** IRC Fiber
- **Category:** Communication
- **Content rating:** Everyone / Teen (user-generated chat text; answer the Play Console questionnaire — "User-generated content: yes, text chat")

## Short description (≤80 chars, currently 78)
```
Free cloud IRC bouncer & client — always-connected chat, anywhere you go.
```

## Full description (≤4000 chars)
```
IRC Fiber keeps you connected to IRC around the clock. Sign in once and your
bouncer stays joined to every channel — messages, mentions and history are
waiting for you the moment you open the app, on this phone or any other
device signed into the same account.

FEATURES
• Always-on bouncer — never miss a message, even when the app is closed
• Full mIRC color and ANSI art rendering, including block-art and sextant
  character support
• FIGlet and TheDraw colour-font text styling for the composer
• Inline image, video and pastebin previews
• File uploads and code snippets, shared right in the channel
• Channel and nick completion, emoji shortcodes, reply threads
• Member list with moderator ranks, kick/ban, whois
• Push notifications for mentions and direct messages
• Dark, high-contrast interface built for reading chat logs comfortably
• Sign in with your ircfiber.com account, GitHub, or Google

IRC Fiber is a free account at https://ircfiber.com. This app is the
Android client; sign in with an existing account or create one from the
app.
```

## Graphics
- `ic_launcher_play_store_512.png` — 512×512, 8-bit sRGB, no alpha ✓
- `feature_graphic_1024x500.png` — 1024×500, 8-bit sRGB, no alpha ✓
- `screenshots/01_login.png` … `05_buffer_list.png` — real device screenshots, 1080×2400 (exceeds Play's 320–3840px requirement), captured from a genuine signed-in session against production `ircfiber.com` during this session's verification pass. Google requires 2–8 phone screenshots; these 5 cover login, a live channel, a sent/echoed message, the real member list, and the buffer-list drawer.

## Required URLs
- **Privacy policy:** `https://ircfiber.com/privacy` (live, 200 OK, already serves the account's privacy policy — same one iOS and web already link to)
- **Terms of service** (optional field, recommended): `https://ircfiber.com/terms` (live, 200 OK)
- **Support contact:** use the existing ircfiber.com support channel/email

## Data safety form (Play Console → App content → Data safety)
Based on what the app actually collects/sends (from the implemented code, not guessed):
- **Collected:** account username/email (sign-in), chat messages (sent through the user's own IRC account, not collected by this app for any purpose beyond transport), device identifier (`X-IRCFiber-Device`, a locally-generated UUID in Android Block Store, used only to identify this install's bouncer session — not linked to advertising), push token (FCM, only if the user enables notifications and a Firebase project is configured), crash/diagnostic logs (opt-in export only, user-triggered, never logs message text/passwords per `AppLog`'s privacy rule).
- **Not collected:** no analytics SDK, no ad SDK, no location, no contacts, no device file access beyond user-picked uploads.
- **Data shared with third parties:** none beyond the user's own IRC network (ircfiber.com), which is the service the app is a client for.
- **Security:** all network traffic over HTTPS/TLS (release build has no cleartext exception — `usesCleartextTraffic="false"`, debug-only cleartext allowances are excluded from the release manifest).
- **Data deletion:** the app's Settings → Delete Account calls `DELETE /api/me`, which deletes the account and its data server-side.

## App signing
- Upload key: `~/.android/fiberirc-release.jks`, alias `fiberirc` (generated this session; **back this up** — losing it means you can't upload updates signed with the same key. Play App Signing will ask you to opt in on first upload, which lets Google manage the actual distribution key and recover from a lost upload key via their key-reset process).
- Release bundle: `app/build/outputs/bundle/release/app-release.aab` (signed, verified with `jarsigner -verify` → "jar verified").

## What's NOT done — requires the user's own Google account
Actually submitting to the Play Store needs a **Google Play Console developer account** (one-time $25 fee, tied to a real Google identity) and manual steps only the account owner can do:
1. Create/sign in to a Play Console account at https://play.google.com/console.
2. Create the app listing (package `com.kevinpostal.fiberirc`), paste in the description/screenshots/graphics from this folder.
3. Upload `app-release.aab` to a testing or production track.
4. Complete the Data safety form (content above), content rating questionnaire, and target audience declaration.
5. Opt into Play App Signing when prompted.
6. Submit for review.

No credentials for such an account exist in this environment, and creating one requires real payment/identity information this agent cannot provide. Everything else — the working app, the signed release bundle, the listing copy, the graphics, the privacy policy link, and the data-safety answers — is ready to paste in.
