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

@DisableCachingByDefault(because = "Launches an interactive application process")
public abstract class SpellscrollDevTask extends DefaultTask
{
    @Input
    public abstract Property<String> getSpellscrollInstallDir();

    @Input
    public abstract Property<String> getPluginId();

    @Input
    public abstract Property<Boolean> getIgnoreDefaultPluginsDir();

    @Internal
    public abstract Property<String> getBuildLibsDir();

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

    private File resolveExecutable(boolean isWindows)
    {
        String installDir = getSpellscrollInstallDir().get();
        String relativePath = isWindows ? "Spellscroll.exe" : "Spellscroll.app/Contents/MacOS/Spellscroll";
        return new File(installDir, relativePath);
    }

    private static boolean isWindows() { return System.getProperty("os.name").toLowerCase().contains("win"); }
}
