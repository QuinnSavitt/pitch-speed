# Pitch Speed

An Android app that turns your phone's camera into a baseball pitch speed gun.

Prop the phone up sideways off to the side of the pitch, tell it how far away
the release point is, and start throwing — every pitch that crosses the frame
is detected and timed automatically, no buttons to press mid-throw.

## How it works

The camera analyzer downsamples each frame to a coarse luma grid and looks for
a bright object that moved significantly since the previous frame. The
diff-weighted centroid of that motion is the ball's estimated position for
that frame, expressed as a fraction of the frame width.

That fraction is turned into a real angle using the phone's actual horizontal
field of view (read from the camera hardware characteristics), and the angle
becomes a real lateral position using the distance you entered:

```
realX = distance * tan(angle)
```

Speed is the change in `realX` over the change in time between the first and
last sample of a detected sweep, averaged the way a Doppler radar's reported
speed is effectively averaged over its detection window.

This is a fun approximation, not a certified radar gun. Accuracy depends on
lighting, camera angle, and an accurate distance entry.

## Features

- Auto-detecting capture — no button-mashing, just point the camera and pitch
- Onboarding walkthrough for camera placement and calibration
- Distance presets (Little League, middle school, high school/college/MLB) or
  custom distance entry
- mph / km/h toggle and adjustable detection sensitivity
- Session history with fastest/average speed per session, stored entirely
  on-device
- Shareable result card image for texting/sharing a session's results
- No accounts, no network access, no data ever leaves the phone

## Building

Requires JDK 17, the Android SDK (platform 34, build-tools 34.0.0), and
Gradle 8.9+.

```
./gradlew assembleDebug
```

The resulting APK is at `app/build/outputs/apk/debug/app-debug.apk`.

## License

MIT
