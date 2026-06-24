package app.spellscroll.task;

import app.spellscroll.auth.SpellscrollOAuthClient;
import app.spellscroll.auth.SpellscrollTokenStore;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.zip.*;

/**
 * Downloads Spellscroll dev assets for the current OS and API version from
 * {@code https://spellscroll.app/api/download-dev} and extracts them to
 * {@code ~/.spellscroll/dev-cache/<version>/<os>/}.
 *
 * <p>Authentication uses OAuth2 Authorization Code + PKCE (Google or Microsoft). On the first
 * run, a browser window opens so the developer can log in. Tokens are cached in
 * {@code ~/.spellscroll/auth_cache.properties} and refreshed silently on subsequent runs.
 *
 * <p>The download is skipped when a {@code .downloaded} marker file already exists in the
 * cache directory, so repeated Gradle invocations are cheap. To force a re-download, delete
 * the cache directory or the marker file.
 *
 * <p>Provider selection: defaults to Google. Set the system property
 * {@code -Dspellscroll.auth.provider=microsoft} to use Microsoft instead.
 */
@DisableCachingByDefault(because = "Manages its own version-keyed cache under ~/.spellscroll/dev-cache")
public abstract class SpellscrollDownloadAssetsTask extends DefaultTask
{
    private static final String DOWNLOAD_URL =
        "https://spellscroll.app/api/download-dev?os=%s&version=%s";

    /**
     * Version of the Spellscroll API to download dev assets for.
     * Must match the {@code apiVersion} declared in the {@code spellscroll} extension.
     */
    @Input
    public abstract Property<String> getApiVersion();

    /**
     * The resolved dev-assets cache directory for this OS and API version.
     * Marked {@code @Internal} because it is a persistent user-level cache rather than a
     * conventional Gradle build output — Gradle should not manage or clean this path.
     */
    @Internal
    public abstract Property<String> getDevAssetsCacheDir();

    @TaskAction
    public void download() throws Exception
    {
        String version    = getApiVersion().get();
        String os         = isWindows() ? "win" : "mac";
        File   cacheDir   = new File(getDevAssetsCacheDir().get());
        File   markerFile = new File(cacheDir, ".downloaded");

        if (markerFile.exists())
        {
            getLogger().lifecycle("Spellscroll dev assets already cached at: {}", cacheDir);
            return;
        }

        String accessToken = obtainToken();

        String url = String.format(DOWNLOAD_URL, os, version);
        getLogger().lifecycle("Downloading Spellscroll dev assets (v{}, {})...", version, os);

        HttpClient http = HttpClient.newHttpClient();
        HttpResponse<InputStream> response = http.send(
            HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() == 401)
        {
            // Server rejected the token — clear the stored refresh token so the next run re-authenticates
            new SpellscrollTokenStore().clear();
            throw new GradleException(
                "Authentication rejected by the Spellscroll server. " +
                "Run the task again to re-authenticate.");
        }
        if (response.statusCode() != 200)
        {
            throw new GradleException(
                "Failed to download Spellscroll dev assets (HTTP " + response.statusCode() + ")");
        }

        cacheDir.mkdirs();
        extractZip(response.body(), cacheDir);

        // Set executable bit on the main binary (macOS/Linux only)
        if (!isWindows()) setExecutable(cacheDir);

        markerFile.createNewFile();
        getLogger().lifecycle("Spellscroll dev assets extracted to: {}", cacheDir);
    }

    /** Returns a valid access token, refreshing silently or triggering browser login as needed. */
    private String obtainToken() throws Exception
    {
        SpellscrollTokenStore store = new SpellscrollTokenStore();
        SpellscrollOAuthClient client = new SpellscrollOAuthClient(store);

        String token = client.getValidToken();
        if (token != null) return token;

        getLogger().lifecycle("Spellscroll authentication required. Opening browser...");
        return client.login();
    }

    /** Extracts all entries from {@code zipStream} into {@code destDir}, guarding against zip-slip. */
    private static void extractZip(InputStream zipStream, File destDir) throws IOException
    {
        String destCanonical = destDir.getCanonicalPath() + File.separator;
        try (ZipInputStream zis = new ZipInputStream(zipStream))
        {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null)
            {
                File target = new File(destDir, entry.getName());

                // Zip-slip guard
                if (!target.getCanonicalPath().startsWith(destCanonical))
                    throw new IOException("Zip entry escapes target directory: " + entry.getName());

                if (entry.isDirectory())
                {
                    target.mkdirs();
                }
                else
                {
                    target.getParentFile().mkdirs();
                    try (OutputStream os = new FileOutputStream(target))
                    {
                        zis.transferTo(os);
                    }
                }
                zis.closeEntry();
            }
        }
    }

    /** Marks all regular files in {@code dir} as executable (for macOS/Linux). */
    private static void setExecutable(File dir) throws IOException
    {
        Files.walk(dir.toPath())
            .filter(p -> p.toFile().isFile())
            .forEach(p -> p.toFile().setExecutable(true));
    }

    private static boolean isWindows()
    {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }
}
