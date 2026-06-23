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

/**
 * Gradle task that builds the Spellscroll UI module using npm.
 *
 * <p>Runs {@code npm install} and {@code npm run build} inside the configured
 * {@code uiModuleDir}. The build output is written to the {@link #getOutputDir() outputDir}
 * and automatically bundled into the plugin JAR under {@code /ui} via {@code processResources}.
 *
 * <p>The task skips gracefully (with a lifecycle log message) if any of the following are true:
 * <ul>
 *   <li>The UI module directory does not exist.</li>
 *   <li>No {@code package.json} is found in the UI module directory.</li>
 *   <li>{@code package.json} does not declare a dependency on {@code @spellscroll/ui-sdk}.</li>
 * </ul>
 *
 * <p>Caching is disabled because npm manages its own incremental builds internally.
 * Run {@code ./gradlew spellscrollBuildUi} manually only if needed; it runs automatically
 * as part of the normal build via its {@code processResources} dependency.
 */
@DisableCachingByDefault(because = "Delegates to npm which manages its own incremental builds")
public abstract class SpellscrollBuildUiTask extends DefaultTask
{
    /**
     * Path to the UI module npm project directory (i.e. the directory containing
     * {@code package.json} and {@code spellscroll.config.js}).
     *
     * @return the UI module directory property
     */
    @Input
    public abstract Property<String> getUiModuleDir();

    /**
     * The directory into which npm places its build output.
     * Declared so Gradle's up-to-date checks know what this task produces,
     * and so {@code processResources} can reference it as a source.
     *
     * <p>Defaults to {@code build/generated/frontend}.
     *
     * @return the output directory property
     */
    @OutputDirectory
    public abstract DirectoryProperty getOutputDir();

    /**
     * Runs {@code npm install} and {@code npm run build} in the UI module directory.
     *
     * <p>Silently skips (creating the output directory if necessary) when the UI module
     * is absent or does not use {@code @spellscroll/ui-sdk}.
     *
     * @throws IOException          if the npm process cannot be started or the output directory
     *                              cannot be created
     * @throws InterruptedException if the current thread is interrupted while waiting for npm
     */
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

    /**
     * Starts a process with the given command in the specified working directory,
     * inheriting the current process's stdout/stderr, and waits for it to complete.
     *
     * @param workDir the working directory for the process
     * @param command the command and arguments to run
     * @throws IOException          if the process cannot be started
     * @throws InterruptedException if the current thread is interrupted while waiting
     * @throws org.gradle.api.GradleException if the process exits with a non-zero status
     */
    private void runProcess(File workDir, String... command) throws IOException, InterruptedException
    {
        Process process = new ProcessBuilder(command)
            .directory(workDir)
            .inheritIO()
            .start();
        int exitCode = process.waitFor();
        if (exitCode != 0) throw new org.gradle.api.GradleException("Command failed with exit code " + exitCode + ": " + String.join(" ", command));
    }

    /**
     * Creates the output directory if it does not already exist.
     * Called on skip paths so that downstream tasks always find a valid directory.
     *
     * @throws IOException if the directory cannot be created
     */
    private void ensureOutputDirExists() throws IOException { Files.createDirectories(getOutputDir().get().getAsFile().toPath()); }

    /**
     * Returns {@code true} when the current JVM is running on Windows.
     * Used to select the correct npm executable ({@code npm.cmd} vs {@code npm}).
     *
     * @return {@code true} on Windows, {@code false} otherwise
     */
    private static boolean isWindows() { return System.getProperty("os.name").toLowerCase().contains("win"); }
}
