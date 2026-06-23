package app.spellscroll;

import org.gradle.api.provider.Property;

public abstract class SpellscrollGradleExtension {

    /** Override to the Spellscroll installation directory. */
    public abstract Property<String> getSpellscrollInstallDir();

    /** Override to the UI module NPM project directory. */
    public abstract Property<String> getUiModuleDir();

    /** When true, passes --ignore-default-plugins-dir to Spellscroll. */
    public abstract Property<Boolean> getIgnoreDefaultPluginsDir();

    /** REQUIRED: The plugin ID (e.g. "com.example.myplugin"). */
    public abstract Property<String> getPluginId();

    /** REQUIRED: Human-readable plugin name (e.g. "My Plugin"). */
    public abstract Property<String> getPluginName();

    /** Plugin version. Falls back to the Gradle project's version if not set. */
    public abstract Property<String> getPluginVersion();

    /** REQUIRED: Spellscroll API version to depend on (e.g. "1.2.3"). */
    public abstract Property<String> getApiVersion();
}
