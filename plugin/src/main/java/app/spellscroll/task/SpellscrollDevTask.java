package app.spellscroll.task;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Gradle task that launches the Spellscroll application with the plugin loaded in dev mode.
 *
 * <p>Builds the command:
 * <pre>
 * Spellscroll[.exe/.app]
 *   --plugindir      &lt;build/libs&gt;
 *   --dev-plugin     &lt;pluginId&gt;
 *   [--ignore-default-plugins-dir]
 * </pre>
 *
 * <p>Gradle blocks until the Spellscroll process exits. Stopping the Gradle task
 * (e.g. Ctrl+C) force-kills the child process. Subprocess stdout and stderr are
 * merged and forwarded to the Gradle console via a daemon thread.
 *
 * <p>This task automatically depends on {@code jar} when the {@code java} plugin is present,
 * ensuring the plugin JAR is up-to-date before launch. Caching is disabled because the task
 * produces no outputs — it runs an interactive application.
 */
@DisableCachingByDefault(because = "Launches an interactive application process")
public abstract class SpellscrollDevTask extends DefaultTask
{
    /**
     * Path to the Spellscroll installation directory.
     * The executable is resolved relative to this directory.
     *
     * @return the Spellscroll install directory property
     */
    @Input
    public abstract Property<String> getSpellscrollInstallDir();

    /**
     * The plugin identifier passed to Spellscroll via {@code --dev-plugin}.
     *
     * @return the plugin ID property
     */
    @Input
    public abstract Property<String> getPluginId();

    /**
     * When {@code true}, appends {@code --ignore-default-plugins-dir} to the Spellscroll
     * command, preventing it from loading plugins from its default directory.
     *
     * @return the ignore-default-plugins-dir flag property
     */
    @Input
    public abstract Property<Boolean> getIgnoreDefaultPluginsDir();

    /**
     * Absolute path to the Gradle {@code build/libs} directory, passed to Spellscroll
     * via {@code --plugindir}. Marked {@code @Internal} because it is derived from the
     * build directory and should not influence task up-to-date checks.
     *
     * @return the build libs directory property
     */
    @Internal
    public abstract Property<String> getBuildLibsDir();

    /**
     * Resolves the Spellscroll executable, builds the launch command, and starts the process.
     *
     * <p>A daemon thread reads the subprocess output and forwards it to the console.
     * The task blocks until the process exits. On {@link InterruptedException}, the process
     * is force-killed before re-throwing.
     *
     * @throws IOException          if the process cannot be started
     * @throws InterruptedException if the current thread is interrupted while waiting for the process
     * @throws org.gradle.api.GradleException if the Spellscroll executable is not found
     */
    @TaskAction
    public void launch() throws IOException, InterruptedException
    {
        boolean isWindows = isWindows();

        File spellscrollExe = resolveExecutable(isWindows);
        if (!spellscrollExe.exists())
        {
            throw new GradleException("Spellscroll executable not found at: " + spellscrollExe.getAbsolutePath() + "\nSet 'spellscrollInstallDir' in your SpellscrollGradle {} block.");
        }

        List<String> command = new ArrayList<>();
        command.add(spellscrollExe.getAbsolutePath());
        command.add("--plugindir");
        command.add(getBuildLibsDir().get());
        command.add("--dev-plugin");
        command.add(getPluginId().get());
        if (Boolean.TRUE.equals(getIgnoreDefaultPluginsDir().get())) command.add("--ignore-default-plugins-dir");

        getLogger().lifecycle("Launching: {}", String.join(" ", command));
        Process spellscroll = new ProcessBuilder(command)
            .directory(spellscrollExe.getParentFile())
            .redirectErrorStream(true)
            .start();

        Thread outputForwarder = new Thread(() ->
        {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(spellscroll.getInputStream())))
            {
                String line;
                while ((line = reader.readLine()) != null) System.out.println(line);
            }
            catch (IOException ignored) {}
        });
        outputForwarder.setDaemon(true);
        outputForwarder.start();

        try { spellscroll.waitFor(); }
        catch (InterruptedException e)
        {
            spellscroll.destroyForcibly();
            Thread.currentThread().interrupt();
            throw e;
        }
    }

    /**
     * Resolves the full path to the Spellscroll executable based on the platform.
     *
     * <ul>
     *   <li>Windows: {@code <spellscrollInstallDir>/Spellscroll.exe}</li>
     *   <li>macOS/Linux: {@code <spellscrollInstallDir>/Spellscroll.app/Contents/MacOS/Spellscroll}</li>
     * </ul>
     *
     * @param isWindows {@code true} when running on Windows
     * @return a {@link File} pointing to the expected executable location
     */
    private File resolveExecutable(boolean isWindows)
    {
        String installDir = getSpellscrollInstallDir().get();
        String relativePath = isWindows ? "Spellscroll.exe" : "Spellscroll.app/Contents/MacOS/Spellscroll";
        return new File(installDir, relativePath);
    }

    /**
     * Returns {@code true} when the current JVM is running on Windows.
     *
     * @return {@code true} on Windows, {@code false} otherwise
     */
    private static boolean isWindows() { return System.getProperty("os.name").toLowerCase().contains("win"); }
}
