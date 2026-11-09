package org.openmetadata.mcp.server.auth.util;

import static org.openmetadata.common.utils.CommonUtil.nullOrEmpty;
import static org.openmetadata.schema.settings.SettingsType.MCP_CONFIGURATION;

import lombok.extern.slf4j.Slf4j;
import org.openmetadata.schema.api.configuration.MCPConfiguration;
import org.openmetadata.schema.api.configuration.OpenMetadataBaseUrlConfiguration;
import org.openmetadata.schema.settings.Settings;
import org.openmetadata.schema.utils.JsonUtils;
import org.openmetadata.service.Entity;
import org.openmetadata.service.config.source.ConfigSources;
import org.openmetadata.service.jdbi3.SystemRepository;
import org.openmetadata.service.resources.settings.SettingsCache;
import org.openmetadata.service.security.auth.SecurityConfigurationManager;

/**
 * External origin MCP OAuth advertises (issuer = this + {@code /mcp}). Prefer the stored MCP
 * setting, then the deployment/YAML {@code mcpConfiguration.baseUrl} (including {@code
 * MCP_BASE_URL}), then the system OpenMetadata URL, then the local-dev fallback.
 *
 * <p>Discovery must keep using this resolver so a saved or deployed {@code baseUrl} cannot leave
 * the startup {@code localhost:8585} issuer in place when a reload listener is missed, or when a
 * database row without {@code baseUrl} would otherwise hide the deployment value.
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
   * Reads the stored MCP setting, the captured deployment value, and the system URL so discovery
   * can self-heal after a PUT or a YAML/{@code MCP_BASE_URL} change even when in-memory OAuth
   * metadata was not rebuilt.
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
    String fromSystem = readSystemOpenMetadataUrl();
    if (fromSystem != null) {
      return fromSystem;
    }
    LOG.error(
        "No base URL configured in MCP settings, deployment mcpConfiguration.baseUrl /"
            + " MCP_BASE_URL, or system OPENMETADATA_SERVER_URL. "
            + "Falling back to {} — this is only suitable for local development. "
            + "Configure a proper base URL for production deployments.",
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
