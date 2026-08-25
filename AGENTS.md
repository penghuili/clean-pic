# Clean Screenshots Agent Instructions

## Project

- This is an Android Jetpack Compose app.
- The repository branch is `main` and the GitHub remote is `origin`.
- Keep user-visible UI text in Chinese unless the task asks for another language.

## Java and build

Use the local JDK 17 for Gradle. The default `java` on this machine may point to Java 8, which is too old for the Android Gradle Plugin.

```powershell
$taskJavaHome = 'C:\aadata\dev-tools\jdk17'
$env:JAVA_HOME = $taskJavaHome
$env:Path = "$taskJavaHome\bin;$env:Path"
```

After code changes:

```powershell
git diff --check
.\gradlew.bat assembleRelease
.\gradlew.bat --stop
```

The signed release APK is generated at:

```text
app\build\outputs\apk\release\clean-pic-v{versionName}.apk
```

After each release build, stop the Gradle daemon with `.\gradlew.bat --stop` so
the idle OpenJDK process does not remain running. Run this after the build
finishes, including after a failed build when a daemon was started.

`keystore.properties` and keystore files are local-only signing material. Never commit them or expose their contents.

## Release versioning

Before every release, update the app version according to Semantic Versioning in `app/build.gradle.kts`:

- `versionName` must use `MAJOR.MINOR.PATCH` format.
- Increase `PATCH` for bug fixes and small UI or behavior corrections.
- Increase `MINOR` for backward-compatible features.
- Increase `MAJOR` for breaking changes or incompatible behavior changes.
- Increase `versionCode` monotonically for every published release.
- If a pre-release version is needed, use a SemVer suffix such as `-beta.1` in `versionName` and still increment `versionCode`.

Verify the final `versionName` and `versionCode` before building and report them with the release APK.

## Completion workflow

For a completed change:

1. Inspect the diff and run `git diff --check`.
2. Build `assembleRelease` with JDK 17 and report the APK path.
3. Stop the Gradle daemon with `.\gradlew.bat --stop`.
4. Stage only the intended source and documentation changes.
5. Commit with a concise message describing the change.
6. Push the commit to `origin main`.
7. Report the commit, push result, build result, and release APK path.

Do not commit generated APKs, `app/build`, Gradle caches, local IDE files, or signing credentials.
