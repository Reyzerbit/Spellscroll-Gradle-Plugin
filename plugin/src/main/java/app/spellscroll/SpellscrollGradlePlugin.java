package app.spellscroll;

import app.spellscroll.task.GenerateBuildConfigTask;
import app.spellscroll.task.SpellscrollBuildUiTask;
import app.spellscroll.task.SpellscrollDevTask;
import app.spellscroll.task.SpellscrollInitUiTask;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.Copy;
import org.gradle.api.tasks.TaskProvider;

/**
 * Main entry point for the Spellscroll Gradle plugin ({@code app.spellscroll.dev}).
 *
 * <p>Applying this plugin to a project:
 * <ul>
 *   <li>Registers the {@code spellscroll} extension ({@link SpellscrollGradleExtension}) for DSL configuration.</li>
 *   <li>Registers the {@code generateSpellscrollBuildConfig}, {@code spellscrollDev},
 *       {@code spellscrollBuildUi}, and {@code spellscrollInitUi} tasks.</li>
 *   <li>When the {@code java} plugin is also applied: automatically adds {@code spellscroll-api}
 *       as {@code compileOnly} + {@code annotationProcessor}, wires the generated constants source
 *       into the main source set, and bundles the UI build output into {@code processResources}.</li>
 * </ul>
 *
 * <p>Three properties are required in the {@code spellscroll} block:
 * {@code pluginId}, {@code pluginName}, and {@code apiVersion}.
 * A {@link org.gradle.api.GradleException} is thrown during {@code afterEvaluate} if any are absent.
 */
public class SpellscrollGradlePlugin implements Plugin<Project>
{
    /**
     * Applies the plugin to the given {@link Project}.
     *
     * <p>This method registers the {@code spellscroll} extension, configures property defaults,
     * registers all tasks, wires Java-plugin integrations, and validates required configuration.
     *
     * @param project the Gradle project to configure
     */
    @Override
    public void apply(Project project)
    {
        SpellscrollGradleExtension ext = project.getExtensions().create("spellscroll", SpellscrollGradleExtension.class);

        configureDefaults(project, ext);

        TaskProvider<GenerateBuildConfigTask> generateConfigTask = registerGenerateBuildConfigTask(project, ext);
        registerSpellscrollGradleTask(project, ext);
        registerSpellscrollBuildUiTask(project, ext);
        registerSpellscrollInitUiTask(project, ext);

        // Wire compileOnly + annotationProcessor for spellscroll-api when java plugin is present
        project.getPluginManager().withPlugin("java", appliedPlugin ->
        {
            project.afterEvaluate(p ->
            {
                if (ext.getApiVersion().isPresent())
                {
                    String dep = "app.spellscroll:spellscroll-api:" + ext.getApiVersion().get();
                    p.getDependencies().add("compileOnly", dep);
                    p.getDependencies().add("annotationProcessor", dep);
                }
            });
        });

        // Wire generated sources into Java compilation if java plugin is present
        project.getPluginManager().withPlugin("java", appliedPlugin ->
        {
            JavaPluginExtension javaExt = project.getExtensions().getByType(JavaPluginExtension.class);
            javaExt.getSourceSets().named("main", sourceSet ->
                sourceSet.getJava().srcDir(generateConfigTask.flatMap(GenerateBuildConfigTask::getOutputDir)));
            project.getTasks().named("compileJava").configure(t -> t.dependsOn(generateConfigTask));
        });

        // Validate required fields after the build script has been evaluated
        project.afterEvaluate(p ->
        {
            if (!ext.getPluginId().isPresent()) throw new GradleException("spellscroll.pluginId is required. Add it to your spellscroll {} block.");
            if (!ext.getPluginName().isPresent()) throw new GradleException("spellscroll.pluginName is required. Add it to your spellscroll {} block.");
            if (!ext.getApiVersion().isPresent()) throw new GradleException("spellscroll.apiVersion is required. Add it to your spellscroll {} block.");
        });
    }

    /**
     * Sets convention values for optional extension properties.
     *
     * <p>Defaults are platform-aware: {@code spellscrollInstallDir} is set to
     * {@code C:\Program Files\Spellscroll} on Windows and {@code ~/Applications} on MacOS.
     * {@code pluginVersion} falls back to the Gradle {@code project.version} if the user has not
     * set it explicitly.
     *
     * @param project the Gradle project (used to read {@code projectDir} and {@code version})
     * @param ext     the extension whose conventions are being configured
     */
    private void configureDefaults(Project project, SpellscrollGradleExtension ext)
    {
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");

        ext.getSpellscrollInstallDir().convention(isWindows ? "C:\\Program Files\\Spellscroll" : "~/Applications");
        ext.getUiModuleDir().convention(project.getProjectDir().getAbsolutePath() + "/ui-module");
        ext.getIgnoreDefaultPluginsDir().convention(true);
        // Falls back to project.version if the user doesn't explicitly set pluginVersion
        ext.getPluginVersion().convention(project.provider(() -> project.getVersion().toString()));
    }

    /**
     * Registers the {@code generateSpellscrollBuildConfig} task.
     *
     * <p>The task generates a {@code {PluginName}Constants.java} file containing {@code PLUGIN_ID},
     * {@code PLUGIN_VERSION}, and {@code PLUGIN_NAME} as {@code static final String} fields.
     * Output is written to {@code build/generated/sources/spellscroll/main/java}.
     *
     * @param project the target Gradle project
     * @param ext     the configured Spellscroll extension
     * @return a provider for the registered task, used to wire source-set and compile dependencies
     */
    private TaskProvider<GenerateBuildConfigTask> registerGenerateBuildConfigTask(Project project, SpellscrollGradleExtension ext)
    {
        return project.getTasks().register("generateSpellscrollBuildConfig", GenerateBuildConfigTask.class, task ->
        {
            task.setGroup("spellscroll");
            task.setDescription("Generates the {PluginName}Constants build-config source file.");
            task.getPluginId().set(ext.getPluginId());
            task.getPluginName().set(ext.getPluginName());
            task.getPluginVersion().set(ext.getPluginVersion());
            task.getProjectGroup().set(project.provider(() -> project.getGroup().toString()));
            task.getBuildFile().set(project.getBuildFile());
            task.getOutputDir().set(project.getLayout().getBuildDirectory().dir("generated/sources/spellscroll/main/java"));
        });
    }

    /**
     * Registers the {@code spellscrollDev} task and wires it to depend on {@code jar}
     * when the {@code java} plugin is present.
     *
     * <p>The task launches Spellscroll with the built plugin JAR, passing {@code --plugindir},
     * {@code --dev-plugin}, and (conditionally) {@code --ignore-default-plugins-dir}.
     *
     * @param project the target Gradle project
     * @param ext     the configured Spellscroll extension
     */
    private void registerSpellscrollGradleTask(Project project, SpellscrollGradleExtension ext)
    {
        project.getTasks().register("spellscrollDev", SpellscrollDevTask.class, task ->
        {
            task.setGroup("spellscroll");
            task.setDescription("Launches Spellscroll with the plugin loaded in dev mode.");
            task.getSpellscrollInstallDir().set(ext.getSpellscrollInstallDir());
            task.getPluginId().set(ext.getPluginId());
            task.getIgnoreDefaultPluginsDir().set(ext.getIgnoreDefaultPluginsDir());
            task.getBuildLibsDir().set(project.getLayout().getBuildDirectory().dir("libs").map(d -> d.getAsFile().getAbsolutePath()));
        });

        // Depend on jar only when the java plugin is present
        project.getPluginManager().withPlugin("java", p -> project.getTasks().named("spellscrollDev").configure(t -> t.dependsOn("jar")));
    }

    /**
     * Registers the {@code spellscrollBuildUi} task and wires it into {@code processResources}
     * when the {@code java} plugin is present.
     *
     * <p>The task runs {@code npm install} and {@code npm run build} in the configured UI module
     * directory. Its output is copied into the JAR under {@code /ui} via {@code processResources}.
     * The task is skipped silently if the UI module directory, {@code package.json}, or the
     * {@code @spellscroll/ui-sdk} dependency is absent.
     *
     * @param project the target Gradle project
     * @param ext     the configured Spellscroll extension
     */
    private void registerSpellscrollBuildUiTask(Project project, SpellscrollGradleExtension ext)
    {
        TaskProvider<SpellscrollBuildUiTask> buildUiTask = project.getTasks().register(
            "spellscrollBuildUi", SpellscrollBuildUiTask.class, task ->
            {
                task.setGroup("spellscroll");
                task.setDescription("Builds the UI module and bundles the output into the plugin JAR under /ui.");
                task.getUiModuleDir().set(ext.getUiModuleDir());
                task.getOutputDir().set(project.getLayout().getBuildDirectory().dir("generated/frontend"));
            });

        // When the java plugin is present, wire the UI output into processResources → /ui
        project.getPluginManager().withPlugin("java", p ->
            project.getTasks().named("processResources").configure(processResources ->
            {
                processResources.dependsOn(buildUiTask);
                if (processResources instanceof Copy)
                {
                    ((Copy) processResources).from(buildUiTask.flatMap(SpellscrollBuildUiTask::getOutputDir), spec -> spec.into("ui"));
                }
            }));
    }

    /**
     * Registers the {@code spellscrollInitUi} task.
     *
     * <p>The task is a one-time scaffolding operation that creates {@code package.json},
     * {@code spellscroll.config.js}, and {@code src/main.jsx} inside {@code uiModuleDir}.
     * Existing files are skipped, so the task is safe to re-run.
     *
     * @param project the target Gradle project
     * @param ext     the configured Spellscroll extension
     */
    private void registerSpellscrollInitUiTask(Project project, SpellscrollGradleExtension ext)
    {
        project.getTasks().register("spellscrollInitUi", SpellscrollInitUiTask.class, task ->
        {
            task.setGroup("spellscroll");
            task.setDescription("Scaffolds a new Spellscroll UI module (package.json, spellscroll.config.js, src/main.jsx).");
            task.getUiModuleDir().set(ext.getUiModuleDir());
            task.getPluginName().set(ext.getPluginName());
        });
    }
}
