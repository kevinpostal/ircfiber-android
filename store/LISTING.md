# Play Store listing — IRC Fiber

## App details
- **Play Console app ID:** `4972420423182553986` (developer account `6263843010714945824`, Broke Jokes)
- **Package name:** `com.kevinpostal.fiberirc`
- **App name:** IRC Fiber
- **Category:** Communication (saved in Play Console)
- **Store contact email:** `admin@ircfiber.com` (saved)
- **Website:** `https://ircfiber.com` (saved)
- **Content rating:** IARC questionnaire saved: ESRB Teen, ClassInd/USK/IARC Generic 12+, PEGI Parental guidance. The intended Play audience is **18 and over**, independently of the regional content ratings.

## Short description (≤80 chars) — saved in the default listing
```
IRC chat with a cloud bouncer, rich text, media, and synced message history.
```

## Full description (≤4000 chars)
Local native-signup copy below is not published; the saved Play listing may still describe website-only signup.
```
IRC Fiber is an Android IRC client for ircfiber.com. Sign in to your account and connect to your IRC networks, channels, and conversations. Your cloud bouncer stays joined while your phone is offline, keeping message history ready when you return.

FEATURES
• Multiple IRC networks, channels, and private conversations
• mIRC colors, ANSI art, and block-character rendering
• FIGlet and TheDraw color-font styling for messages
• Inline image, video, and paste previews
• File uploads and code snippets shared in chat
• Channel and nickname completion, emoji shortcodes, and reply threads
• Member lists, whois, and channel moderation tools
• Light and dark appearance options
• A foreground messaging service for background chat connections
• Sign in with an ircfiber.com account, Google, or GitHub

A free ircfiber.com account is required. Create one in the app with a username, email and password, confirm the emailed link on any device, then return to continue. Existing accounts can sign in as before.

The connection service uses an ongoing Android notification while you are signed in. Android power management and force-stop can still interrupt network access; IRC Fiber reconnects when access returns.
```

## Graphics — saved in the default listing
- App icon: `ic_launcher_play_store_512.png` — 512×512, 8-bit sRGB, no alpha
- Feature graphic: `feature_graphic_1024x500.png` — 1024×500, 8-bit sRGB, no alpha
- Phone screenshots: `screenshots/01_login.png`, `02_channel.png`, `03_chat_live.png` — 1080×2400; reviewed for store use. The chat screenshot contains a test account's “Hello” in `#ircfiber`.
- Do not upload `04_members.png` or `05_buffer_list.png` unchanged: they expose other members' nicknames and connection diagnostics.
- The default listing (name, short and full description, icon, feature graphic, three phone screenshots) is **In review** with the closed-testing submission. It is not a public production listing.

## Required URLs
- **Privacy policy:** `https://ircfiber.com/privacy` — Android-inclusive copy deployed with owner approval; covers install/device details, activity/security records, uploads and reports.
- **Terms of service:** `https://ircfiber.com/terms` — Android-inclusive copy live; owner confirmed public nudity and non-newsworthy graphic violence are prohibited, now explicit in the guidelines.
- **Support and deletion:** `https://ircfiber.com/support#delete-account` — Android, iPhone and website deletion steps, plus verified ownership requests through `admin@ircfiber.com`.

## Data safety form (Play Console → App content → Data safety)
The saved Data safety questionnaire describes the uploaded default release and the current ircfiber.com server. Revisit it whenever collection, SDK configuration or server behavior changes.

Release-configuration context:
- Firebase/FCM and Sentry are present in the code but inert in the default release build because their keys are blank (no `-P` overrides were used for the current bundle). The current build sends no FCM push token and no Sentry crash reports. Do not declare push-token or automatic crash-report collection for this build; if a later build supplies those keys, revisit the form.
- No analytics SDK and no ad SDK.

Collected by the app's own server (ircfiber.com), automatically or through user actions:
- Account info: username, email, user ID.
- Messages: chat messages and message history (stored by the bouncer to sync history).
- User-picked content: photos, videos, other audio files, files and pastes the user chooses to upload.
- Install identifier: a locally generated install UUID (`X-IRCFiber-Device`, stored in Android Block Store), sent with device model, OS version, app version and time zone headers.
- App settings stored for the account.
- Server-side: sign-in IP address, coarse location derived from IP, security logs and message counts.

Optional / user-initiated:
- Reports the user chooses to submit can include message plaintext.

Other notes for the form:
- **Security:** release network traffic uses HTTPS/TLS (`usesCleartextTraffic="false"`; debug-only cleartext allowances are excluded from the release manifest).
- **Account deletion:** Settings → Delete Account calls `DELETE /api/me`. The verified server purge includes account, networks, buffers/history, preferences, sessions, uploads and NickServ registration. The deletion URL is saved in Play Console.
- **Sharing:** the saved form declares collected data, not third-party sharing, applying Google's service-provider and user-initiated-transfer exemptions. Ordinary user-selected IRC recipients are not a claim of end-to-end encryption.
- **Saved categories:** personal info (name, email, user IDs), approximate location, other in-app messages, photos/videos, other audio files, files/docs, app activity (interactions, other UGC, other actions), and device/other IDs. User-picked uploads and reports are optional; account/messaging/security collection is required. None is declared ephemeral.

## Android background connections
- The signed-in session is application-owned, not activity-owned. Reopening the app reuses its existing connections.
- A non-exported `remoteMessaging` foreground service keeps the session out of the cached-app freezer. It starts at visible sign-in and stops when the session ends; it does not sign in from a boot receiver or restart itself after force-stop.
- The low-importance **IRC connections** notification shows the connected-network count without message text. On Android 13+, notification permission controls its visibility, not whether the foreground service can run; when denied, Android still lists the service in its active-apps controls.
- Android deep Doze can still restrict network access. Existing liveness probes and reconnect handling recover when access returns; this is not a battery-optimization exemption or a guarantee against force-stop.
- TCP connection and TLS handshake attempts time out after 15 seconds each. TLS shutdown runs off the UI thread. After ten failed automatic reconnects, retries pause until the app returns to the foreground, a network path returns, or the user taps the disconnected server title; an explicit user disconnect remains stopped.

## Native Audio source (not a released or device-qualified claim)
- Audio uses authenticated website account IDs, independently of IRC networks. Previewing a room does not join it. Explicit Join starts a separate media-playback foreground service and connects muted without creating a microphone.
- Only explicit Unmute requests microphone permission and promotes microphone foreground ownership before server-authorized publication. Mute, deafen, interruption, leave and account loss stop/unpublish/dispose owned tracks; undeafen/reconnect do not unmute. SDK microphone prewarming and silent communication-mode playback are disabled.
- `data/voice/VoiceRoomOwner.kt` owns media across activity recreation and text/settings/viewer navigation. `VoiceConnectionService.kt` supplies generation-bound notification and real MediaSession controls. Android may hide lock-screen controls when notification permissions/privacy settings disallow them; first unqualified background unmute opens Audio instead of starting hidden capture.
- Audio is transport-encrypted through a trusted SFU, not MLS end-to-end encryption. No recording, camera, screen or data-chat grants are used. Connected Bluetooth selection needs Bluetooth Connect on Android 12+, never scan/location permission.
- Verified source checks: debug APK assembly, 23 voice behavioral unit checks and Android-test Kotlin compilation passed. `make uitest-only ONLY='Voice'` passed three tests on isolated managed API-35 emulators. The merged manifest contains the separate non-exported `microphone|mediaPlayback` service and no unused projection service. All packaged arm64-v8a/x86_64 native libraries have at least 16-KB ELF load alignment; `zipalign -c -P 16 4` passed for the debug APK.
- Signed 1.1.0/code 3 APK and AAB build successfully with the established release key. The APK package/code and generated update manifest agree; its certificate matches published 1.0.0, all 14 arm64-v8a/x86_64 libraries meet 16-KB ELF alignment, and `zipalign -c -P 16 4` passes. Legacy 32-bit libraries are not 16-KB-capable ABIs.
- An isolated API-35 emulator accepted the signed upgrade from published 1.0.0 to 1.1.0/code 3 without uninstalling: package UID and the actual persisted device-identity preferences were byte-identical, and the upgraded app cold-launched successfully. No user installation was touched.
- Release remains gated on real source-matched SFU audio and consented physical Android background/lock-screen/route/interruption/foreground-service testing with separate-network peers. A physical Android/tester is unavailable. Emulator upgrade proof does not qualify physical media; no upload or production activation is implied.


## App signing
- App signing and upload key: `~/.android/fiberirc-release.jks`, alias `fiberirc`. Back up the keystore securely; Google can reset an upload key, but losing the local signing key prevents independently signing compatible sideload updates.
- With explicit owner approval, the existing key was encrypted using Google's PEPK tool and imported into Play App Signing.
- **Verified matching SHA-256:** `C4:67:18:70:CE:AD:06:41:CA:61:78:FB:6D:BD:03:83:EC:00:32:84:3B:6B:2E:35:39:A0:80:7F:8F:3E:2A:FE` in Play's persisted App signing page and the current release APK. Play updates can therefore preserve the sideload installation; do not uninstall an encrypted installation unnecessarily.
- Release bundle: `app/build/outputs/bundle/release/app-release.aab`, version 1.0.0/code 1, target SDK 36, minimum API 26. Google Play accepted and processed it. No deobfuscation or native debug-symbol file is available in this build; Play reports two non-blocking diagnostics warnings.
- Local next-release configuration: **1.1.0/code 3**, with the same package, minimum SDK and signing identity. Published `android-v1.0.0` has code 1; the previous local candidate used code 2. No upload or publication is implied. Release variants fail without the existing key; they never use a debug-key fallback.

## APK update release artifacts
- Run `./gradlew :app:writeUpdateManifest :app:bundleRelease` with the existing release signing access. The manifest at `app/build/outputs/updates/android-update.json` derives package, name, code and minimum SDK from the release configuration and depends on the signed release APK.
- Increment both version name and code for each published APK release. Stage the APK as `ircfiber-android.apk` alongside `android-update.json` for a new `android-v<versionName>` tag in `kevinpostal/ircfiber-pages`. Never replace an old release asset or infer version codes from release prose.
- Uploading requires separate owner authorization. When authorized, upload both assets to a draft, verify the complete pair and signing certificate, then publish. The legacy `android-v1.0.0` has no manifest; before the first complete checker release is published, APK checks can report unavailable.
- Existing binaries need one ordinary bootstrap upgrade before they can show native update notices. Play/TestFlight/App Store distribution and actual store-upgrade testing remain separate release gates.
- Locally staged 1.0.1/code 2: `app/build/outputs/updates/ircfiber-android.apk`, `ircfiber-android.aab` and `android-update.json`. Both binary signers match the certificate above. On the isolated `ircfiber-updates-smoke-20261106` emulator, installing the APK over signed 1.0.0 with `adb install -r` retained the package UID and byte-identical sample display preferences; the updated release launched successfully. This is APK upgrade proof, not Play installation proof.

## Optional native updates
- Release builds check on the first foreground and at most once per 24 hours thereafter. The attempt is recorded before networking; there is no background job or retry loop. Play download status is restored on every foreground independently of that interval. DEBUG automatic checks are disabled; tests inject their channel and clock explicitly.
- **Settings → Check for updates**, immediately after Version, checks now and bypasses both the interval and a previous Later. Only this action is disabled while checking. Empty/malformed metadata, offline stores and legacy releases without manifests are unavailable, not “up to date”; a newer APK needing a newer OS is reported separately.
- Notices never lock out chat. An automatic notice waits for a foreground with window focus and a hidden keyboard; another modal or active typing defers it. Later and cancellation retain the known update and suppress that channel/release's automatic notice for 24 hours.
- Play-installed copies use Play exclusively, including account/track/staged-rollout eligibility: errors never offer an APK fallback. Flexible updates download while chat remains usable; only **Restart to update** calls Play's completion API. Updates without flexible eligibility open the fixed Play listing, with an HTTPS fallback.
- Sideloaded copies select the highest numeric published stable Android tag carrying both release assets. The manifest must match the package, tag version, positive code/minimum SDK and exact versioned GitHub asset URLs. **Update** opens that APK externally: download it, then open it while keeping the current app installed. The browser and Android installer handle permissions and signature validation; there is no custom installer or install-packages permission.
- Metadata has a 15-second check deadline and uses a separate unauthenticated client. IRC cookies, device headers and authorization are never sent to GitHub. Fixtures exercise real `FakeAppUpdateManager` transitions and native UI, but Google's fake neither opens system consent nor installs a version; actual eligible Play upgrades still require separately authorized testing uploads.

```sh
./gradlew --no-daemon :app:assembleDebug :app:testDebugUnitTest
make uitest-only ONLY='SettingsE2eTest|AppUpdatesE2eTest'
```

## Play Console setup status — 2026-11-05
- Account: existing **Broke Jokes** personal developer account (`6263843010714945824`); the user confirmed it should own IRC Fiber.
- App created: **IRC Fiber**, app ID `4972420423182553986`, `com.kevinpostal.fiberirc`, English (US), App, Free.
- Declarations: the owner confirmed the Developer Program Policies and US export compliance (including encryption) declarations when asked, and the app was then created.
- Automatic installer protection is **off** by the user's choice, keeping direct APK installation (sideloading) supported alongside Google Play.
- Default store listing submitted and **In review** with the closed release. Category, contact email and website included.
- App content: the app has no ads, and is not a government, financial or health app.
- Reviewer access: dedicated non-admin `playreview` account created and normal production sign-in verified. Credentials and English instructions are saved in Play's restricted Sign in details; no password is stored in this repo.
- Owner approved IARC terms, existing trader status, 18+ audience, public Android policy updates, internal rollout and closed-test review submission. Ads, Advertising ID, government, financial, health and foreground-service declarations are complete; Data safety and content ratings are submitted. App content reports **You're all caught up** with no outstanding declarations.
- **Internal testing:** version 1.0.0/code 1 is active and **Available to internal testers**. The only authorized internal tester is `kevindpostal@gmail.com`. Opt-in link: `https://play.google.com/apps/internaltest/4700991566058658379`.
- **Closed testing – Alpha:** track `4700093639283585121`; release 1.0.0/code 1 is **In review**. All 178 supported country/region entries selected, separate closed tester list initially containing only the owner, feedback `admin@ircfiber.com`. Publishing overview confirmed **16 changes sent for review**, covering the release, listing, regions, testers and declarations.
- Google displays the temporary internal app name `com.kevinpostal.fiberirc (unreviewed)` until its review is complete. The production listing is not public.
- **Foreground-service evidence:** `https://ircfiber.com/uploads/8ca24b8674e54eb886e9e379c98bc0c3.mp4` is saved in the remote-messaging declaration. The sanitized 1:43 side-by-side recording shows a user-launched Android session, Android Active apps foreground-service status, a self-message sent from the web client while Android is backgrounded, and the same message in Android afterward. Only the non-admin review account was used; the owner's Samsung installation was untouched.
- Final dashboard: **Update status — In review**, production access disabled, **0 closed testers currently opted in**. Google review is not approval, and the 14-day requirement has not begun.

## Google production access requirement
- This personal developer account must run a **closed test with at least 12 testers opted in for at least 14 consecutive days**, then apply for production access and receive Google's approval, before the app can be published to production. This cannot be bypassed.
- The initial owner-only list does **not** meet that requirement. Testers must genuinely join and test; do not fabricate opt-ins or treat the internal test as the required closed test.
- The owner authorized this internal rollout and this closed-test submission, not a public production rollout. Production-access approval and any later public release remain separate steps.
