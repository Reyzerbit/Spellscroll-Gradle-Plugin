package app.spellscroll.task;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gradle task that scaffolds a new Spellscroll UI module at the configured directory.
 *
 * <p>This is a one-time setup task. It fetches the latest {@code @spellscroll/ui-sdk}
 * version from the npm registry and generates three files:
 * <ul>
 *   <li>{@code package.json} — npm project with {@code @spellscroll/ui-sdk} dependency
 *       and {@code build}/{@code serve} scripts.</li>
 *   <li>{@code spellscroll.config.js} — esbuild configuration (entry point, output paths,
 *       dev server port 3001).</li>
 *   <li>{@code src/main.jsx} — starter React component using {@code useSpellscrollMessaging()}.</li>
 * </ul>
 *
 * <p>Each file is skipped individually if it already exists, making the task safe to re-run.
 * Caching is disabled because the task performs network I/O and writes project files that
 * should only be generated once.
 *
 * <p>Run with:
 * <pre>{@code
 * ./gradlew spellscrollInitUi
 * }</pre>
 */
@DisableCachingByDefault(because = "One-time scaffolding task that creates project files")
public abstract class SpellscrollInitUiTask extends DefaultTask
{
    /**
     * Path to the directory where the UI module will be scaffolded.
     * The directory is created if it does not exist.
     *
     * @return the UI module directory property
     */
    @Input
    public abstract Property<String> getUiModuleDir();

    /**
     * Human-readable plugin name used to derive the React component name
     * and the npm package name in {@code package.json}.
     *
     * @return the plugin name property
     */
    @Input
    public abstract Property<String> getPluginName();

    /**
     * Entry point: fetches the latest {@code @spellscroll/ui-sdk} version, then writes
     * the three scaffold files into {@link #getUiModuleDir()}.
     *
     * @throws IOException          if a scaffold file cannot be written
     * @throws InterruptedException if the current thread is interrupted during the npm registry request
     */
    @TaskAction
    public void initUi() throws IOException, InterruptedException
    {
        String pluginName = getPluginName().get();
        String componentName = toComponentName(pluginName);
        String uiSdkVersion = fetchLatestUiSdkVersion();

        File uiDir = new File(getUiModuleDir().get());
        Files.createDirectories(uiDir.toPath());

        writePackageJson(uiDir, pluginName, uiSdkVersion);
        writeSpellscrollConfig(uiDir);
        writeMainJsx(uiDir, componentName, pluginName);

        getLogger().lifecycle("Initialized Spellscroll UI module at {}", uiDir.getAbsolutePath());
        getLogger().lifecycle("  @spellscroll/ui-sdk version: ^{}", uiSdkVersion);
    }

    /**
     * Queries the npm registry for the latest published version of {@code @spellscroll/ui-sdk}.
     *
     * <p>Issues a {@code GET} request to
     * {@code https://registry.npmjs.org/@spellscroll/ui-sdk/latest} and extracts the
     * {@code "version"} field from the JSON response.
     *
     * @return the latest version string (e.g. {@code "1.2.3"})
     * @throws IOException          if the HTTP request fails at the transport level
     * @throws InterruptedException if the current thread is interrupted while waiting for the response
     * @throws org.gradle.api.GradleException if the registry returns a non-200 status or the
     *                                         version cannot be parsed from the response
     */
    private String fetchLatestUiSdkVersion() throws IOException, InterruptedException
    {
        getLogger().lifecycle("Fetching latest @spellscroll/ui-sdk version...");
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("https://registry.npmjs.org/@spellscroll/ui-sdk/latest"))
            .header("Accept", "application/json")
            .GET()
            .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200)
            throw new GradleException("Failed to fetch @spellscroll/ui-sdk version (HTTP " + response.statusCode() + "). " +
                "Check your internet connection or set the version manually in package.json.");

        Matcher matcher = Pattern.compile("\"version\"\\s*:\\s*\"([^\"]+)\"").matcher(response.body());
        if (!matcher.find()) throw new GradleException("Could not parse version from npmjs.org response.");

        return matcher.group(1);
    }

    /**
     * Writes a {@code package.json} to {@code uiDir} unless one already exists.
     *
     * <p>The generated file declares the plugin as an ESM npm project with a dependency on
     * {@code @spellscroll/ui-sdk} at {@code ^<uiSdkVersion>}, and includes {@code build}
     * and {@code serve} scripts backed by the Spellscroll CLI tools.
     *
     * @param uiDir         the target UI module directory
     * @param pluginName    the plugin name, used to derive the npm package name
     * @param uiSdkVersion  the {@code @spellscroll/ui-sdk} version to pin in the dependency
     * @throws IOException if the file cannot be written
     */
    private void writePackageJson(File uiDir, String pluginName, String uiSdkVersion) throws IOException
    {
        File packageJson = new File(uiDir, "package.json");
        if (packageJson.exists())
        {
            getLogger().lifecycle("package.json already exists, skipping.");
            return;
        }
        String npmName = pluginName.toLowerCase().replaceAll("[^a-z0-9\\-]", "-");
        try (PrintWriter w = new PrintWriter(packageJson))
        {
            w.println("{");
            w.println("    \"name\": \"" + npmName + "-ui\",");
            w.println("    \"version\": \"1.0.0\",");
            w.println("    \"type\": \"module\",");
            w.println("    \"dependencies\": {");
            w.println("        \"@spellscroll/ui-sdk\": \"^" + uiSdkVersion + "\"");
            w.println("    },");
            w.println("    \"scripts\": {");
            w.println("        \"build\": \"spellscroll-build\",");
            w.println("        \"serve\": \"spellscroll-serve\"");
            w.println("    }");
            w.println("}");
        }
    }

    /**
     * Writes a {@code spellscroll.config.js} esbuild configuration to {@code uiDir}
     * unless one already exists.
     *
     * <p>The generated config specifies:
     * <ul>
     *   <li>{@code entryPoint}: {@code src/main.jsx}</li>
     *   <li>{@code outfile}: {@code ../build/generated/frontend/main.js}</li>
     *   <li>{@code serveOutfile}: {@code ../build/serve/main.js}</li>
     *   <li>{@code servePath}: {@code /main.js}</li>
     *   <li>{@code port}: {@code 3001}</li>
     * </ul>
     *
     * @param uiDir the target UI module directory
     * @throws IOException if the file cannot be written
     */
    private void writeSpellscrollConfig(File uiDir) throws IOException
    {
        File config = new File(uiDir, "spellscroll.config.js");
        if (config.exists())
        {
            getLogger().lifecycle("spellscroll.config.js already exists, skipping.");
            return;
        }
        try (PrintWriter w = new PrintWriter(config))
        {
            w.println("export default {");
            w.println("    entryPoint: 'src/main.jsx',");
            w.println("    outfile: '../build/generated/frontend/main.js',");
            w.println("    serveOutfile: '../build/serve/main.js',");
            w.println("    servePath: '/main.js',");
            w.println("    port: 3001,");
            w.println("};");
        }
    }

    /**
     * Writes a starter {@code src/main.jsx} React component to {@code uiDir/src/}
     * unless one already exists.
     *
     * <p>The generated file exports a {@code register()} function that returns the component
     * class and a tab name, as expected by the Spellscroll UI plugin API. The component uses
     * {@code useSpellscrollMessaging()} from {@code @spellscroll/ui-sdk} for plugin communication.
     *
     * @param uiDir         the target UI module directory (the {@code src/} subdirectory is created if needed)
     * @param componentName PascalCase React component name derived from the plugin name
     * @param pluginName    human-readable plugin name used as the tab label
     * @throws IOException if the file cannot be written
     */
    private void writeMainJsx(File uiDir, String componentName, String pluginName) throws IOException
    {
        File srcDir = new File(uiDir, "src");
        Files.createDirectories(srcDir.toPath());

        File mainJsx = new File(srcDir, "main.jsx");
        if (mainJsx.exists())
        {
            getLogger().lifecycle("src/main.jsx already exists, skipping.");
            return;
        }
        try (PrintWriter w = new PrintWriter(mainJsx))
        {
            w.println("import { useSpellscrollMessaging } from \"@spellscroll/ui-sdk\";");
            w.println();
            w.println("function " + componentName + "() {");
            w.println("    const send = useSpellscrollMessaging();");
            w.println("    // ...");
            w.println("    return (<></>);");
            w.println("}");
            w.println();
            w.println("export function register() {");
            w.println("    return {");
            w.println("        component: " + componentName + ",");
            w.println("        tabName: \"" + pluginName + "\"");
            w.println("    };");
            w.println("}");
        }
    }

    /**
     * Converts a human-readable plugin name to a valid PascalCase React component name.
     *
     * <p>Non-alphanumeric characters act as word separators; each following letter is
     * uppercased. For example, {@code "My Plugin"} → {@code "MyPlugin"}.
     *
     * @param pluginName the plugin name to convert
     * @return a PascalCase identifier suitable for use as a JSX component name
     */
    private static String toComponentName(String pluginName)
    {
        StringBuilder sb = new StringBuilder();
        boolean capNext = true;
        for (char c : pluginName.toCharArray())
        {
            if (Character.isLetterOrDigit(c))
            {
                sb.append(capNext ? Character.toUpperCase(c) : c);
                capNext = false;
            }
            else capNext = true;
        }
        return sb.toString();
    }
}
