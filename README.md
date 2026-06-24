# SpellscrollDev Gradle Plugin
![Gradle Plugin Portal Version](https://img.shields.io/gradle-plugin-portal/v/app.spellscroll.dev)


A Gradle plugin for developing Spellscroll plugins. It wires up the Spellscroll API dependency, generates a constants class, builds your optional UI module, downloads the Spellscroll dev runtime, and launches it with your plugin loaded for live development.

## Applying the Plugin

The plugin is published under the ID `app.spellscroll.dev`. Apply it alongside the `java-library` plugin:

```groovy
plugins {
    id 'java-library'
    id 'app.spellscroll.dev'
}
```

## Configuration

All configuration lives in a `spellscroll {}` block. Three properties are required:

```groovy
spellscroll {
    // REQUIRED
    pluginId   = 'com.example.my-plugin'   // reverse-domain plugin identifier
    pluginName = 'My Plugin'               // human-readable name
    apiVersion = '1.1.0'                   // spellscroll-api version to compile against (1.1.0 minimum)

    // Optional — defaults shown
    pluginVersion           = project.version                        // falls back to project.version if unset
    uiModuleDir             = "${rootProject.projectDir}/ui-module"
    ignoreDefaultPluginsDir = true
    buildTask               = 'build'                                // task spellscrollDev depends on before launching
}
```

### Properties

| Property | Required | Default | Description |
|---|---|---|---|
| `pluginId` | Yes | — | Reverse-domain plugin ID, e.g. `com.example.my-plugin` |
| `pluginName` | Yes | — | Human-readable name, e.g. `My Plugin` |
| `apiVersion` | Yes | — | Version of `spellscroll-api` to depend on (must be `1.1.0` or higher) |
| `pluginVersion` | No | `project.version` | Plugin version; must be set explicitly if `project.version` is unspecified |
| `uiModuleDir` | No | `<rootProjectDir>/ui-module` | Path to your UI module npm project |
| `ignoreDefaultPluginsDir` | No | `true` | Passes `--ignore-default-plugins-dir` to Spellscroll on launch |
| `buildTask` | No | `'build'` | Task that `spellscrollDev` depends on before launching (e.g. `'assemble'` to skip tests). Set to an empty string to disable. |

## Automatic Behaviors

When the `java` or `java-library` plugin is also applied, the SpellscrollDev plugin wires up several things automatically — no extra configuration needed.

**Spellscroll API dependency**

`spellscroll-api` is added as both `compileOnly` and `annotationProcessor` at the version specified by `apiVersion`:

```
app.spellscroll:spellscroll-api:<apiVersion>
```

This gives you access to the full plugin API at compile time without bundling it into your JAR.

**Build config source generation**

`generateSpellscrollBuildConfig` is automatically run before `compileJava`. Its output directory is added to the main Java source set, so the generated class is available to your code without any manual `srcDirs` configuration.

**UI bundle in processResources**

If you have a UI module, `spellscrollBuildUi` runs as part of `processResources`. Its output is copied into the JAR under `/ui`, making the built frontend accessible at runtime as a classpath resource.

**Build dependency for launch**

`spellscrollDev` automatically depends on the `build` task (configurable via `buildTask`), so your project is fully built before the app is launched. This includes `jar` as a transitive dependency. Set `buildTask = 'assemble'` to skip tests, or set it to an empty string to disable the dependency entirely.

## Tasks

### `generateSpellscrollBuildConfig`

Generates a Java constants class containing your plugin's ID, version, and name as `static final String` fields. Useful for referencing these values in your plugin code without hardcoding them.

**Generated class location:** `build/generated/sources/spellscroll/main/java`

The class name and package are derived from your configuration:
- **Package:** `project.group`, lowercased (e.g. `com.example`)
- **Class name:** `pluginName` in PascalCase + `Constants` (e.g. `MyPluginConstants`)

Example output for `group = 'com.example'`, `pluginName = 'My Plugin'`:

```java
package com.example;

public final class MyPluginConstants {

    public static final String PLUGIN_ID      = "com.example.my-plugin";
    public static final String PLUGIN_VERSION = "1.0.0";
    public static final String PLUGIN_NAME    = "My Plugin";

    private MyPluginConstants() {}
}
```

This task is cacheable — it only reruns when your `build.gradle` or the relevant config values change.

---

### `spellscrollDownloadAssets`

Downloads the Spellscroll dev runtime for the current OS and configured `apiVersion`, then extracts it to:

```
~/.spellscroll/dev-cache/<apiVersion>/<os>/
```

The first time this task runs it will open a browser window asking you to sign in with Google or Microsoft. After a successful sign-in the credentials are stored in the OS native credential store and subsequent runs are silent (unless the token expires). The download itself is skipped on subsequent runs if the assets for the requested version are already present on disk.

`spellscrollDev` depends on this task automatically, so you rarely need to invoke it directly.

---

### `spellscrollDev`

Runs the `build` task (or the task configured via `buildTask`) and launches Spellscroll with your plugin loaded in dev mode. The dev runtime is sourced from the local cache populated by `spellscrollDownloadAssets`. Spellscroll is launched with the following flags:

```
Spellscroll.exe
  --plugin-dir      <build/libs>
  --dev-plugin     <pluginId>
  [--ignore-default-plugins-dir]
```

Gradle blocks until the Spellscroll process exits. Stopping the Gradle task (e.g. Ctrl+C) force-kills the Spellscroll process.

To use a different build task (e.g. to skip tests):

```groovy
spellscroll {
    buildTask = 'assemble'
}
```

---

### `spellscrollBuildUi`

Runs `npm install` and `npm run build` inside `uiModuleDir` and places the output in `build/generated/frontend/`. This output is automatically bundled into your plugin JAR under `/ui` via `processResources`.

The task skips gracefully if:
- `uiModuleDir` does not exist
- No `package.json` is found in `uiModuleDir`
- `package.json` does not declare a dependency on `@spellscroll/ui-sdk`

You do not need to run this task manually — it runs as part of the normal build.

---

### `spellscrollInitUi`

A one-time scaffolding task that creates a ready-to-use UI module at `uiModuleDir`. It fetches the latest `@spellscroll/ui-sdk` version from the npm registry and generates:

| File | Description |
|---|---|
| `package.json` | npm project with `@spellscroll/ui-sdk` dependency and `build`/`serve` scripts |
| `spellscroll.config.js` | esbuild config: entry point, output paths, dev server port (3001) |
| `src/main.jsx` | Starter React component using `useSpellscrollMessaging()` |

This task is safe to re-run — it skips any file that already exists.

```
./gradlew spellscrollInitUi
```

## UI Module Development Workflow

1. **Scaffold** (once): `./gradlew spellscrollInitUi`
2. **Install dependencies**: `cd ui-module && npm install`
3. **Start the dev server** in a terminal: `npm run serve`  
   The dev server hot-reloads at `http://localhost:3001` — keep it running independently of Gradle.
4. **Launch the app**: `./gradlew spellscrollDev`  
   Gradle builds the plugin JAR and launches Spellscroll. The running dev server is picked up automatically.

For production builds, `npm run build` (via `spellscrollBuildUi`) bundles the UI into the JAR — no dev server needed.

## Full Example

```groovy
// build.gradle
plugins {
    id 'java-library'
    id 'app.spellscroll.dev'
}

group   = 'com.example'
version = '1.0.0'

repositories {
    mavenCentral()
}

spellscroll {
    pluginId   = 'com.example.my-plugin'
    pluginName = 'My Plugin'
    apiVersion = '1.1.0'
}
```

With this configuration:
- `spellscroll-api:1.1.0` is added as `compileOnly` + `annotationProcessor`
- `com.example.MyPluginConstants` is generated and available in your source
- `./gradlew spellscrollDev` builds the JAR and launches the app
- `./gradlew spellscrollInitUi` sets up a UI module at `ui-module/` if you want one
