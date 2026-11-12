package org.openmetadata.mcp.server.auth.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockStatic;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openmetadata.schema.api.configuration.MCPConfiguration;
import org.openmetadata.schema.api.configuration.OpenMetadataBaseUrlConfiguration;
import org.openmetadata.schema.settings.Settings;
import org.openmetadata.schema.settings.SettingsType;
import org.openmetadata.service.Entity;
import org.openmetadata.service.jdbi3.SystemRepository;
import org.openmetadata.service.resources.settings.SettingsCache;
import org.openmetadata.service.security.auth.SecurityConfigurationManager;

class McpPublicBaseUrlTest {

  @Test
  void resolve_prefersNonBlankMcpBaseUrl() {
    MCPConfiguration mcp =
        new MCPConfiguration().withBaseUrl("https://om.example.com/");

    assertThat(McpPublicBaseUrl.resolve(mcp)).isEqualTo("https://om.example.com");
  }

  @Test
  void resolve_treatsBlankMcpBaseUrlAsUnset() {
    MCPConfiguration mcp = new MCPConfiguration().withBaseUrl("  ");

    try (MockedStatic<SettingsCache> cache = mockStatic(SettingsCache.class);
        MockedStatic<SecurityConfigurationManager> security =
            mockStatic(SecurityConfigurationManager.class);
        MockedStatic<org.openmetadata.service.config.source.ConfigSources> sources =
            mockStatic(org.openmetadata.service.config.source.ConfigSources.class);
        MockedStatic<Entity> entity = mockStatic(Entity.class)) {
      security.when(SecurityConfigurationManager::getCurrentMcpConfig).thenReturn(null);
      cache
          .when(
              () ->
                  SettingsCache.getSettingOrDefault(
                      SettingsType.MCP_CONFIGURATION, null, MCPConfiguration.class))
          .thenReturn(null);
      sources
          .when(org.openmetadata.service.config.source.ConfigSources::deployment)
          .thenReturn(java.util.Optional.empty());
      security.when(SecurityConfigurationManager::getCurrentAuthConfig).thenReturn(null);
      SystemRepository repository = org.mockito.Mockito.mock(SystemRepository.class);
      entity.when(Entity::getSystemRepository).thenReturn(repository);
      org.mockito.Mockito.when(repository.getOMBaseUrlConfigInternal())
          .thenReturn(
              new Settings()
                  .withConfigType(SettingsType.OPEN_METADATA_BASE_URL_CONFIGURATION)
                  .withConfigValue(
                      new OpenMetadataBaseUrlConfiguration()
                          .withOpenMetadataUrl("https://system.example.com")));

      assertThat(McpPublicBaseUrl.resolve(mcp)).isEqualTo("https://system.example.com");
    }
  }

  @Test
  void resolveFromStore_readsStoredMcpBaseUrlOverSystemUrl() {
    MCPConfiguration stored =
        new MCPConfiguration().withBaseUrl("https://mcp.example.com");

    try (MockedStatic<SettingsCache> cache = mockStatic(SettingsCache.class);
        MockedStatic<SecurityConfigurationManager> security =
            mockStatic(SecurityConfigurationManager.class)) {
      security.when(SecurityConfigurationManager::getCurrentMcpConfig).thenReturn(null);
      cache
          .when(
              () ->
                  SettingsCache.getSettingOrDefault(
                      SettingsType.MCP_CONFIGURATION, null, MCPConfiguration.class))
          .thenReturn(stored);

      assertThat(McpPublicBaseUrl.resolveFromStore()).isEqualTo("https://mcp.example.com");
    }
  }

  @Test
  void resolveFromStore_usesDeploymentBaseUrlWhenStoredRowLacksIt() {
    MCPConfiguration storedWithoutBaseUrl = new MCPConfiguration().withEnabled(true);
    MCPConfiguration deployment =
        new MCPConfiguration().withBaseUrl("https://from-deployment.example.com/");

    try (MockedStatic<SettingsCache> cache = mockStatic(SettingsCache.class);
        MockedStatic<SecurityConfigurationManager> security =
            mockStatic(SecurityConfigurationManager.class);
        MockedStatic<org.openmetadata.service.config.source.ConfigSources> sources =
            mockStatic(org.openmetadata.service.config.source.ConfigSources.class)) {
      security.when(SecurityConfigurationManager::getCurrentMcpConfig).thenReturn(null);
      cache
          .when(
              () ->
                  SettingsCache.getSettingOrDefault(
                      SettingsType.MCP_CONFIGURATION, null, MCPConfiguration.class))
          .thenReturn(storedWithoutBaseUrl);

      org.openmetadata.service.config.source.DeploymentConfig deploymentConfig =
          org.mockito.Mockito.mock(org.openmetadata.service.config.source.DeploymentConfig.class);
      org.openmetadata.service.config.source.DeploymentSetting deploymentSetting =
          org.mockito.Mockito.mock(org.openmetadata.service.config.source.DeploymentSetting.class);
      sources
          .when(org.openmetadata.service.config.source.ConfigSources::deployment)
          .thenReturn(java.util.Optional.of(deploymentConfig));
      org.mockito.Mockito.when(deploymentConfig.setting(SettingsType.MCP_CONFIGURATION))
          .thenReturn(java.util.Optional.of(deploymentSetting));
      org.mockito.Mockito.when(deploymentSetting.value())
          .thenReturn(org.openmetadata.schema.utils.JsonUtils.valueToTree(deployment));

      assertThat(McpPublicBaseUrl.resolveFromStore())
          .isEqualTo("https://from-deployment.example.com");
    }
  }

  @Test
  void resolveFromStore_prefersOidcCallbackOriginOverLocalhostSystemUrl() {
    try (MockedStatic<SettingsCache> cache = mockStatic(SettingsCache.class);
        MockedStatic<SecurityConfigurationManager> security =
            mockStatic(SecurityConfigurationManager.class);
        MockedStatic<org.openmetadata.service.config.source.ConfigSources> sources =
            mockStatic(org.openmetadata.service.config.source.ConfigSources.class);
        MockedStatic<Entity> entity = mockStatic(Entity.class)) {
      security.when(SecurityConfigurationManager::getCurrentMcpConfig).thenReturn(null);
      cache
          .when(
              () ->
                  SettingsCache.getSettingOrDefault(
                      SettingsType.MCP_CONFIGURATION, null, MCPConfiguration.class))
          .thenReturn(new MCPConfiguration());
      sources
          .when(org.openmetadata.service.config.source.ConfigSources::deployment)
          .thenReturn(java.util.Optional.empty());

      SystemRepository repository = org.mockito.Mockito.mock(SystemRepository.class);
      entity.when(Entity::getSystemRepository).thenReturn(repository);
      org.mockito.Mockito.when(repository.getOMBaseUrlConfigInternal())
          .thenReturn(
              new Settings()
                  .withConfigType(SettingsType.OPEN_METADATA_BASE_URL_CONFIGURATION)
                  .withConfigValue(
                      new OpenMetadataBaseUrlConfiguration()
                          .withOpenMetadataUrl("http://localhost:8585")));

      org.openmetadata.schema.api.security.AuthenticationConfiguration auth =
          new org.openmetadata.schema.api.security.AuthenticationConfiguration()
              .withOidcConfiguration(
                  new org.openmetadata.schema.security.client.OidcClientConfig()
                      .withCallbackUrl("https://openmetadata.example.com/callback")
                      .withServerUrl("http://localhost:8585"));
      security.when(SecurityConfigurationManager::getCurrentAuthConfig).thenReturn(auth);

      assertThat(McpPublicBaseUrl.resolveFromStore())
          .isEqualTo("https://openmetadata.example.com");
    }
  }

  @Test
  void originOf_keepsBasePathBeforeCallback() {
    assertThat(McpPublicBaseUrl.originOf("https://om.example.com/data-catalog/callback"))
        .isEqualTo("https://om.example.com/data-catalog");
  }

  @Test
  void resolveFromStore_fallsBackToLocalDevWhenNothingConfigured() {
    try (MockedStatic<SettingsCache> cache = mockStatic(SettingsCache.class);
        MockedStatic<SecurityConfigurationManager> security =
            mockStatic(SecurityConfigurationManager.class);
        MockedStatic<org.openmetadata.service.config.source.ConfigSources> sources =
            mockStatic(org.openmetadata.service.config.source.ConfigSources.class);
        MockedStatic<Entity> entity = mockStatic(Entity.class)) {
      security.when(SecurityConfigurationManager::getCurrentMcpConfig).thenReturn(null);
      security.when(SecurityConfigurationManager::getCurrentAuthConfig).thenReturn(null);
      cache
          .when(
              () ->
                  SettingsCache.getSettingOrDefault(
                      SettingsType.MCP_CONFIGURATION, null, MCPConfiguration.class))
          .thenReturn(null);
      sources
          .when(org.openmetadata.service.config.source.ConfigSources::deployment)
          .thenReturn(java.util.Optional.empty());
      entity.when(Entity::getSystemRepository).thenReturn(null);

      assertThat(McpPublicBaseUrl.resolveFromStore())
          .isEqualTo(McpPublicBaseUrl.LOCAL_DEV_FALLBACK);
    }
  }
}
