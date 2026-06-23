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

public class SpellscrollGradlePlugin implements Plugin<Project>
{
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

    private void configureDefaults(Project project, SpellscrollGradleExtension ext)
    {
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");

        ext.getSpellscrollInstallDir().convention(isWindows ? "C:\\Program Files\\Spellscroll" : "~/Applications");
        ext.getUiModuleDir().convention(project.getProjectDir().getAbsolutePath() + "/ui-module");
        ext.getIgnoreDefaultPluginsDir().convention(true);
        // Falls back to project.version if the user doesn't explicitly set pluginVersion
        ext.getPluginVersion().convention(project.provider(() -> project.getVersion().toString()));
    }

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
