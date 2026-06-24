package app.spellscroll;

import org.gradle.api.provider.Property;

/**
 * DSL extension that exposes the {@code spellscroll {}} configuration block.
 *
 * <p>Three properties are required — {@link #getPluginId()}, {@link #getPluginName()},
 * and {@link #getApiVersion()} — and must be set in the consumer's build script.
 * All other properties have sensible defaults configured by {@link SpellscrollGradlePlugin}.
 *
 * <p>Example usage in a consumer project:
 * <pre>{@code
 * spellscroll {
 *     pluginId   = 'com.example.my-plugin'
 *     pluginName = 'My Plugin'
 *     apiVersion = '1.1.0'
 * }
 * }</pre>
 */
public abstract class SpellscrollGradleExtension {

    /**
     * Path to the UI module npm project directory.
     *
     * <p>Defaults to {@code <projectDir>/ui-module}. The directory does not need to exist;
     * {@code spellscrollBuildUi} and {@code spellscrollInitUi} will create or skip it as needed.
     *
     * @return the UI module directory property
     */
    public abstract Property<String> getUiModuleDir();

    /**
     * When {@code true}, passes {@code --ignore-default-plugins-dir} to the Spellscroll process
     * on launch, preventing Spellscroll from loading plugins from its default directory.
     *
     * <p>Defaults to {@code true}.
     *
     * @return the ignore-default-plugins-dir flag property
     */
    public abstract Property<Boolean> getIgnoreDefaultPluginsDir();

    /**
     * <b>Required.</b> The plugin identifier (registered at <a href="https://spellscroll.app">Spellscroll.app</a> under "Developer Tools")
     * This identifier is always an all-lowercase alphanumeric only string.
     *
     * <p>Passed to Spellscroll via {@code --dev-plugin} and embedded in the generated constants class.
     *
     * @return the plugin ID property
     */
    public abstract Property<String> getPluginId();

    /**
     * <b>Required.</b> Human-readable plugin name (e.g. {@code My Plugin}).
     *
     * <p>Used to derive the generated constants class name (e.g. {@code MyPluginConstants})
     * and as the tab label in the scaffolded UI component.
     *
     * @return the plugin name property
     */
    public abstract Property<String> getPluginName();

    /**
     * Plugin version string embedded in the generated constants class as {@code PLUGIN_VERSION}.
     *
     * <p>Falls back to the Gradle {@code project.version} if not set explicitly.
     * Must be set (either here or via {@code project.version}) before the build runs.
     *
     * @return the plugin version property
     */
    public abstract Property<String> getPluginVersion();

    /**
     * Name of the build task that {@code spellscrollDev} depends on before launching.
     *
     * <p>Defaults to {@code "build"}. Override this when you want {@code spellscrollDev} to run a
     * different lifecycle task instead — for example, set it to {@code "assemble"} to skip tests,
     * or to any other task registered in the consumer project.
     *
     * <p>Only takes effect when the {@code java} plugin is also applied. Set to an empty string to
     * disable the build task dependency entirely (not recommended).
     *
     * @return the build task name property
     */
    public abstract Property<String> getBuildTask();

    /**
     * <b>Required.</b> Version of {@code spellscroll-api} to compile against (e.g. {@code 1.2.3}).
     * Must be {@code 1.1.0} or higher.
     *
     * <p>The plugin automatically adds {@code app.spellscroll:spellscroll-api:<apiVersion>}
     * as both {@code compileOnly} and {@code annotationProcessor} when the {@code java} plugin
     * is present.
     *
     * @return the Spellscroll API version property
     */
    public abstract Property<String> getApiVersion();
}
