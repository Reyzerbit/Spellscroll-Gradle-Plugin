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

@DisableCachingByDefault(because = "One-time scaffolding task that creates project files")
public abstract class SpellscrollInitUiTask extends DefaultTask
{
    @Input
    public abstract Property<String> getUiModuleDir();

    @Input
    public abstract Property<String> getPluginName();

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
     * Strips spaces and non-identifier characters, PascalCase from words.
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
