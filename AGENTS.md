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
```

The signed release APK is generated at:

```text
app\build\outputs\apk\release\app-release.apk
```

`keystore.properties` and keystore files are local-only signing material. Never commit them or expose their contents.

## Completion workflow

For a completed change:

1. Inspect the diff and run `git diff --check`.
2. Build `assembleRelease` with JDK 17 and report the APK path.
3. Stage only the intended source and documentation changes.
4. Commit with a concise message describing the change.
5. Push the commit to `origin main`.
6. Report the commit, push result, build result, and release APK path.

Do not commit generated APKs, `app/build`, Gradle caches, local IDE files, or signing credentials.
