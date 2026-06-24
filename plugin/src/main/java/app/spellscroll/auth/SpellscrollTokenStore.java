package app.spellscroll.auth;

import com.microsoft.credentialstorage.SecretStore;
import com.microsoft.credentialstorage.StorageProvider;
import com.microsoft.credentialstorage.model.StoredToken;
import com.microsoft.credentialstorage.model.StoredTokenType;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;

/**
 * Persists the OAuth2 refresh token in the OS native credential store
 * (Windows Credential Manager, macOS Keychain, or libsecret on Linux) via
 * {@code com.microsoft:credential-secure-storage}.
 *
 * <p>Only the refresh token is stored — it is the only long-lived secret.
 * Access tokens are obtained fresh each Gradle run by exchanging the refresh token,
 * so they never touch disk.
 *
 * <p>If no secure backend is available the store will be {@code null} and
 * {@link #load()} returns {@code null}, forcing a fresh interactive login each run.
 */
public class SpellscrollTokenStore
{
    private static final Logger LOG = Logging.getLogger(SpellscrollTokenStore.class);
    private static final String CREDENTIAL_KEY = "SpellscrollDev";

    private final SecretStore<StoredToken> store;

    public SpellscrollTokenStore()
    {
        store = StorageProvider.getTokenStorage(true, StorageProvider.SecureOption.REQUIRED);
        if (store == null) LOG.warn("No secure credential storage available — you will be prompted to log in each time.");
        else LOG.info("Spellscroll token storage backend: {}", store.getClass().getSimpleName());
    }

    /** Saves {@code refreshToken} to the OS credential store. */
    public void save(String refreshToken)
    {
        if (store == null) return;
        store.add(CREDENTIAL_KEY, new StoredToken(refreshToken.toCharArray(), StoredTokenType.REFRESH));
    }

    /**
     * Returns the stored refresh token, or {@code null} if none is saved or the store is
     * unavailable.
     */
    public String load()
    {
        if (store == null) return null;
        try
        {
            StoredToken token = store.get(CREDENTIAL_KEY);
            if (token == null || token.getValue() == null) return null;
            return new String(token.getValue());
        }
        catch (Exception e)
        {
            LOG.warn("Failed to read Spellscroll dev refresh token from credential store: {}", e.getMessage());
            return null;
        }
    }

    /** Removes the stored refresh token (e.g. after a 401 from the server). */
    public void clear()
    {
        if (store != null) store.delete(CREDENTIAL_KEY);
    }
}
