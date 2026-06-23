package app.spellscroll.task;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

@DisableCachingByDefault(because = "Delegates to npm which manages its own incremental builds")
public abstract class SpellscrollBuildUiTask extends DefaultTask
{
    @Input
    public abstract Property<String> getUiModuleDir();

    /**
     * Declared so Gradle's up-to-date checks know what this task produces.
     */
    @OutputDirectory
    public abstract DirectoryProperty getOutputDir();

    @TaskAction
    public void buildUi() throws IOException, InterruptedException
    {
        String uiModulePath = getUiModuleDir().get();
        File uiDir = new File(uiModulePath);

        if (!uiDir.exists())
        {
            getLogger().lifecycle("spellscrollBuildUi: UI module directory not found ({}), skipping.", uiModulePath);
            ensureOutputDirExists();
            return;
        }

        File packageJson = new File(uiDir, "package.json");
        if (!packageJson.exists())
        {
            getLogger().lifecycle("spellscrollBuildUi: No package.json found in {}, skipping.", uiModulePath);
            ensureOutputDirExists();
            return;
        }

        String packageContent = new String(Files.readAllBytes(packageJson.toPath()));
        if (!packageContent.contains("\"@spellscroll/ui-sdk\""))
        {
            getLogger().lifecycle("spellscrollBuildUi: package.json does not depend on @spellscroll/ui-sdk, skipping.");
            ensureOutputDirExists();
            return;
        }

        String npm = isWindows() ? "npm.cmd" : "npm";

        getLogger().lifecycle("Running npm install in {}", uiModulePath);
        runProcess(uiDir, npm, "install");

        getLogger().lifecycle("Running npm run build in {}", uiModulePath);
        runProcess(uiDir, npm, "run", "build");
    }

    private void runProcess(File workDir, String... command) throws IOException, InterruptedException
    {
        Process process = new ProcessBuilder(command)
            .directory(workDir)
            .inheritIO()
            .start();
        int exitCode = process.waitFor();
        if (exitCode != 0) throw new org.gradle.api.GradleException("Command failed with exit code " + exitCode + ": " + String.join(" ", command));
    }

    private void ensureOutputDirExists() throws IOException { Files.createDirectories(getOutputDir().get().getAsFile().toPath()); }

    private static boolean isWindows() { return System.getProperty("os.name").toLowerCase().contains("win"); }
}
