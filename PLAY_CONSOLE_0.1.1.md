# Publish Android 0.1.1 to the closed test

The upload artifact is:

`android/app/build/outputs/bundle/release/app-release.aab`

It contains package `com.kernferm.flippermessenger`, version name `0.1.1`, and version code `46`. The Flipper application remains `0.0.10`.

1. Open Google Play Console and select **Flipper Messenger**.
2. Open **Test and release → Testing → Closed testing**.
3. Open the existing **Alpha** track and select **Create new release**.
4. Upload `android/app/build/outputs/bundle/release/app-release.aab`.
5. Use release name **Flipper Messenger 0.1.1 (46)**.
6. Paste the Android 0.1.1 items from `RELEASE_NOTES_0.1.1.md` into the English release-notes field.
7. Select **Next**, resolve any Play Console errors, then **Save and publish** or **Start rollout to closed testing**.
8. Complete **Publishing overview → Send changes for review** if managed publishing or review is required.

Keep the existing Google Group `testers-community@googlegroups.com` assigned on the track's **Testers** tab. The existing opt-in URL remains:

`https://play.google.com/apps/testing/com.kernferm.flippermessenger`

## Tester Community app

The app already exists in the Tester Community app library. Do not create a duplicate listing. Keep:

- App name: **Flipper Messenger**
- Developer: **BubblesTheDev** (or the exact existing profile display name)
- Package: **com.kernferm.flippermessenger**
- Opt-in link: `https://play.google.com/apps/testing/com.kernferm.flippermessenger`
- Google Group required before opt-in: `testers-community@googlegroups.com`

After Google approves the closed-test update, testers who already opted in receive Android 0.1.1 through Google Play. They do not need a new opt-in link.
