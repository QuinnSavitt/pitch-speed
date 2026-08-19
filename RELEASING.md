# Shipping an update

The app is distributed as a signed APK through GitHub Releases. The
permanent download link (always the newest version) is:

    https://github.com/QuinnSavitt/pitch-speed/releases/latest/download/PitchSpeed.apk

## Flow

1. Bump `versionCode` (+1) and `versionName` in `app/build.gradle.kts`
2. `./gradlew testReleaseUnitTest assembleRelease` — signing uses the
   local `keystore.properties` + `pitchspeed-release.jks` (never committed;
   back them up — losing them means friends must uninstall to update)
3. Commit, push, then:
   `cp app/build/outputs/apk/release/app-release.apk PitchSpeed.apk`
   `gh release create vX.Y PitchSpeed.apk --title "Pitch Speed vX.Y" --notes "..."`
   The asset must be named exactly `PitchSpeed.apk` so the /latest/ link stays stable.
4. Email everyone in `release/recipients.txt` (gitignored, local only) the
   release notes + the permanent link.

Or just tell Claude Code: "ship vX.Y: <what changed>" — it runs the whole flow.
Recipients can also self-serve: Watch -> Custom -> Releases on the GitHub repo.
