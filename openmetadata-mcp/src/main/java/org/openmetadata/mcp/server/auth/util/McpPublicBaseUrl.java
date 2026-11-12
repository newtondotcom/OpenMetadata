package org.openmetadata.mcp.server.auth.util;

import static org.openmetadata.common.utils.CommonUtil.nullOrEmpty;
import static org.openmetadata.schema.settings.SettingsType.MCP_CONFIGURATION;

import java.net.URI;
import lombok.extern.slf4j.Slf4j;
import org.openmetadata.schema.api.configuration.MCPConfiguration;
import org.openmetadata.schema.api.configuration.OpenMetadataBaseUrlConfiguration;
import org.openmetadata.schema.api.security.AuthenticationConfiguration;
import org.openmetadata.schema.security.client.OidcClientConfig;
import org.openmetadata.schema.settings.Settings;
import org.openmetadata.schema.utils.JsonUtils;
import org.openmetadata.service.Entity;
import org.openmetadata.service.config.source.ConfigSources;
import org.openmetadata.service.jdbi3.SystemRepository;
import org.openmetadata.service.resources.settings.SettingsCache;
import org.openmetadata.service.security.auth.SecurityConfigurationManager;

/**
 * External origin MCP OAuth advertises (issuer = this + {@code /mcp}). Prefer an explicit MCP
 * {@code baseUrl}, then other configured public origins, and only then the local-dev default.
 *
 * <p>{@code authorization_endpoint}, {@code token_endpoint} and {@code issuer} are all built from
 * this value, so a localhost discovery document means every one of those fallbacks was missing or
 * still the shipped {@code http://localhost:8585} default.
 */
@Slf4j
public final class McpPublicBaseUrl {

  public static final String LOCAL_DEV_FALLBACK = "http://localhost:8585";

  private McpPublicBaseUrl() {}

  /** Prefer {@code preferred} when it carries a base URL, otherwise resolve from live settings. */
  public static String resolve(MCPConfiguration preferred) {
    String fromPreferred = nonBlankBaseUrl(preferred);
    if (fromPreferred != null) {
      return fromPreferred;
    }
    return resolveFromStore();
  }

  /**
   * Resolves the public origin for MCP OAuth metadata. Order:
   *
   * <ol>
   *   <li>Stored {@code mcpConfiguration.baseUrl}
   *   <li>Deployment/YAML {@code mcpConfiguration.baseUrl} ({@code MCP_BASE_URL})
   *   <li>Non-localhost system {@code OPENMETADATA_SERVER_URL}
   *   <li>Non-localhost OIDC {@code callbackUrl} origin (often set correctly while {@code
   *       serverUrl} stays on localhost — #26311)
   *   <li>Non-localhost OIDC {@code serverUrl}
   *   <li>Any remaining localhost system/OIDC value, then {@link #LOCAL_DEV_FALLBACK}
   * </ol>
   */
  public static String resolveFromStore() {
    String fromMcp = nonBlankBaseUrl(readStoredMcpConfig());
    if (fromMcp != null) {
      return fromMcp;
    }
    String fromDeployment = nonBlankBaseUrl(readDeploymentMcpConfig());
    if (fromDeployment != null) {
      LOG.info("MCP base URL taken from deployment configuration: {}", fromDeployment);
      return fromDeployment;
    }

    String systemUrl = readSystemOpenMetadataUrl();
    String oidcCallbackOrigin = readOidcCallbackOrigin();
    String oidcServerUrl = readOidcServerUrl();

    String publicUrl = firstNonLocalDev(systemUrl, oidcCallbackOrigin, oidcServerUrl);
    if (publicUrl != null) {
      LOG.info("MCP base URL taken from public origin fallback: {}", publicUrl);
      return publicUrl;
    }

    String localUrl = firstNonBlank(systemUrl, oidcCallbackOrigin, oidcServerUrl);
    if (localUrl != null) {
      return localUrl;
    }

    LOG.error(
        "No base URL configured in MCP settings, deployment mcpConfiguration.baseUrl /"
            + " MCP_BASE_URL, system OPENMETADATA_SERVER_URL, or OIDC callbackUrl/serverUrl. "
            + "Falling back to {} — this is only suitable for local development. "
            + "Configure mcpConfiguration.baseUrl (or MCP_BASE_URL) for production.",
        LOCAL_DEV_FALLBACK);
    return LOCAL_DEV_FALLBACK;
  }

  private static MCPConfiguration readStoredMcpConfig() {
    try {
      MCPConfiguration fromCache =
          SettingsCache.getSettingOrDefault(
              MCP_CONFIGURATION,
              SecurityConfigurationManager.getCurrentMcpConfig(),
              MCPConfiguration.class);
      if (fromCache != null) {
        return fromCache;
      }
    } catch (Exception e) {
      LOG.warn("Failed to read MCP base URL from settings cache: {}", e.getMessage());
    }
    return SecurityConfigurationManager.getCurrentMcpConfig();
  }

  /**
   * YAML / env value captured at process start. Used when the database row exists but has no {@code
   * baseUrl} (common before {@code mcpConfiguration} was in the shipped file), so a deployed {@code
   * MCP_BASE_URL} still reaches OAuth discovery after restart.
   */
  private static MCPConfiguration readDeploymentMcpConfig() {
    try {
      return ConfigSources.deployment()
          .flatMap(deployment -> deployment.setting(MCP_CONFIGURATION))
          .map(setting -> JsonUtils.convertValue(setting.value(), MCPConfiguration.class))
          .orElse(null);
    } catch (Exception e) {
      LOG.warn("Failed to read MCP base URL from deployment configuration: {}", e.getMessage());
      return null;
    }
  }

  private static String readSystemOpenMetadataUrl() {
    try {
      SystemRepository systemRepository = Entity.getSystemRepository();
      if (systemRepository == null) {
        return null;
      }
      Settings settings = systemRepository.getOMBaseUrlConfigInternal();
      if (settings == null || settings.getConfigValue() == null) {
        return null;
      }
      OpenMetadataBaseUrlConfiguration urlConfig =
          (OpenMetadataBaseUrlConfiguration) settings.getConfigValue();
      if (urlConfig == null || nullOrEmpty(urlConfig.getOpenMetadataUrl())) {
        return null;
      }
      return UriUtils.withoutTrailingSlash(urlConfig.getOpenMetadataUrl().trim());
    } catch (Exception e) {
      LOG.warn("Failed to read OpenMetadata base URL from system settings: {}", e.getMessage());
      return null;
    }
  }

  private static String readOidcCallbackOrigin() {
    try {
      AuthenticationConfiguration auth = SecurityConfigurationManager.getCurrentAuthConfig();
      if (auth == null) {
        return null;
      }
      // Prefer the top-level auth callback when set; otherwise the OIDC client callback.
      String callback = auth.getCallbackUrl();
      if (nullOrEmpty(callback)) {
        OidcClientConfig oidc = auth.getOidcConfiguration();
        callback = oidc == null ? null : oidc.getCallbackUrl();
      }
      if (nullOrEmpty(callback)) {
        return null;
      }
      return originOf(callback.trim());
    } catch (Exception e) {
      LOG.warn("Failed to read OIDC callbackUrl for MCP base URL: {}", e.getMessage());
      return null;
    }
  }

  private static String readOidcServerUrl() {
    try {
      AuthenticationConfiguration auth = SecurityConfigurationManager.getCurrentAuthConfig();
      OidcClientConfig oidc = auth == null ? null : auth.getOidcConfiguration();
      if (oidc == null || nullOrEmpty(oidc.getServerUrl())) {
        return null;
      }
      return UriUtils.withoutTrailingSlash(oidc.getServerUrl().trim());
    } catch (Exception e) {
      LOG.warn("Failed to read OIDC serverUrl for MCP base URL: {}", e.getMessage());
      return null;
    }
  }

  /** Host origin of a callback URL such as {@code https://om.example/callback}. */
  static String originOf(String url) {
    URI uri = URI.create(url);
    if (uri.getScheme() == null || uri.getHost() == null) {
      return null;
    }
    StringBuilder origin = new StringBuilder();
    origin.append(uri.getScheme()).append("://").append(uri.getHost());
    if (uri.getPort() > 0) {
      origin.append(':').append(uri.getPort());
    }
    String path = uri.getPath();
    // Keep a non-root context path (BASE_PATH) that precedes /callback.
    if (path != null && path.endsWith("/callback")) {
      String basePath = path.substring(0, path.length() - "/callback".length());
      if (!basePath.isEmpty() && !basePath.equals("/")) {
        origin.append(UriUtils.withoutTrailingSlash(basePath));
      }
    } else if (path != null && path.endsWith("/mcp/callback")) {
      String basePath = path.substring(0, path.length() - "/mcp/callback".length());
      if (!basePath.isEmpty() && !basePath.equals("/")) {
        origin.append(UriUtils.withoutTrailingSlash(basePath));
      }
    }
    return origin.toString();
  }

  static boolean isLocalDevUrl(String url) {
    if (nullOrEmpty(url)) {
      return true;
    }
    try {
      String host = URI.create(url).getHost();
      return host == null
          || host.equalsIgnoreCase("localhost")
          || host.equals("127.0.0.1")
          || host.equals("::1");
    } catch (Exception e) {
      return true;
    }
  }

  private static String firstNonLocalDev(String... urls) {
    for (String url : urls) {
      if (!nullOrEmpty(url) && !isLocalDevUrl(url)) {
        return url;
      }
    }
    return null;
  }

  private static String firstNonBlank(String... urls) {
    for (String url : urls) {
      if (!nullOrEmpty(url)) {
        return url;
      }
    }
    return null;
  }

  private static String nonBlankBaseUrl(MCPConfiguration mcpConfig) {
    if (mcpConfig == null || mcpConfig.getBaseUrl() == null) {
      return null;
    }
    String trimmed = mcpConfig.getBaseUrl().trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    return UriUtils.withoutTrailingSlash(trimmed);
  }
}
