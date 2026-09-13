# Performance checks

Run these on the representative tablet from the repository root:

```powershell
.\gradlew.bat :app:generateBaselineProfile
.\gradlew.bat :benchmark:connectedBenchmarkReleaseAndroidTest
```

The first command records the startup/list-navigation profile into the app.
The second records cold-start timing and note-list frame timing. Keep the tablet
unplugged from battery-saving modes and avoid interacting with it during a run.

For a device-free compile check:

```powershell
.\gradlew.bat :benchmark:assembleBenchmarkRelease
```
