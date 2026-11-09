package org.openmetadata.mcp.server.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openmetadata.mcp.auth.OAuthAuthorizationServerProvider;
import org.openmetadata.mcp.auth.OAuthMetadata;
import org.openmetadata.mcp.server.auth.util.McpPublicBaseUrl;
import org.openmetadata.schema.api.configuration.MCPConfiguration;
import org.openmetadata.schema.api.security.AuthenticationConfiguration;
import org.openmetadata.schema.settings.SettingsType;
import org.openmetadata.schema.services.connections.metadata.AuthProvider;
import org.openmetadata.service.resources.settings.SettingsCache;
import org.openmetadata.service.security.auth.SecurityConfigurationManager;
import sun.misc.Unsafe;

/**
 * Regression: saving {@code mcpConfiguration.baseUrl} used to leave discovery advertising the
 * startup {@code http://localhost:8585/mcp} issuer when the in-memory metadata was not rebuilt.
 */
class OAuthMetadataBaseUrlSyncTest {

  @Test
  void handleMetadata_rebuildsIssuerFromStoredBaseUrl() throws Exception {
    OAuthHttpStatelessServerTransportProvider transport = transportWithLocalhostIssuer();
    MCPConfiguration stored =
        new MCPConfiguration().withBaseUrl("https://openmetadata.example.com");

    ByteArrayOutputStream body = new ByteArrayOutputStream();
    HttpServletResponse response = mock(HttpServletResponse.class);
    when(response.getOutputStream()).thenReturn(servletStream(body));

    try (MockedStatic<SettingsCache> cache = mockStatic(SettingsCache.class);
        MockedStatic<SecurityConfigurationManager> security =
            mockStatic(SecurityConfigurationManager.class)) {
      stubAuthConfig(security);
      security.when(SecurityConfigurationManager::getCurrentMcpConfig).thenReturn(stored);
      cache
          .when(
              () ->
                  SettingsCache.getSettingOrDefault(
                      SettingsType.MCP_CONFIGURATION, stored, MCPConfiguration.class))
          .thenReturn(stored);

      transport.handleMetadata(mock(HttpServletRequest.class), response);
    }

    JsonNode metadata = new ObjectMapper().readTree(body.toString(StandardCharsets.UTF_8));
    assertThat(metadata.get("issuer").asText())
        .isEqualTo("https://openmetadata.example.com/mcp");
    assertThat(metadata.get("authorization_endpoint").asText())
        .isEqualTo("https://openmetadata.example.com/mcp/authorize");
  }

  @Test
  void syncMetadata_replacesLocalhostIssuerWithoutWaitingForListener() throws Exception {
    OAuthHttpStatelessServerTransportProvider transport = transportWithLocalhostIssuer();
    MCPConfiguration stored =
        new MCPConfiguration().withBaseUrl("https://rebuilt.example.com");

    try (MockedStatic<SettingsCache> cache = mockStatic(SettingsCache.class);
        MockedStatic<SecurityConfigurationManager> security =
            mockStatic(SecurityConfigurationManager.class)) {
      stubAuthConfig(security);
      security.when(SecurityConfigurationManager::getCurrentMcpConfig).thenReturn(stored);
      cache
          .when(
              () ->
                  SettingsCache.getSettingOrDefault(
                      SettingsType.MCP_CONFIGURATION, stored, MCPConfiguration.class))
          .thenReturn(stored);

      java.lang.reflect.Method sync =
          OAuthHttpStatelessServerTransportProvider.class.getDeclaredMethod(
              "syncMetadataWithStoredBaseUrl");
      sync.setAccessible(true);
      sync.invoke(transport);
    }

    OAuthMetadata metadata = readField(transport, "oauthMetadata");
    assertThat(metadata.getIssuer().toString()).isEqualTo("https://rebuilt.example.com/mcp");
  }

  private static void stubAuthConfig(MockedStatic<SecurityConfigurationManager> security) {
    AuthenticationConfiguration authConfig = mock(AuthenticationConfiguration.class);
    when(authConfig.getProvider()).thenReturn(AuthProvider.BASIC);
    when(authConfig.getTokenValidationAlgorithm())
        .thenReturn(AuthenticationConfiguration.TokenValidationAlgorithm.RS_256);
    security.when(SecurityConfigurationManager::getCurrentAuthConfig).thenReturn(authConfig);
  }

  private static OAuthHttpStatelessServerTransportProvider transportWithLocalhostIssuer()
      throws Exception {
    Unsafe unsafe = unsafe();
    OAuthHttpStatelessServerTransportProvider transport =
        (OAuthHttpStatelessServerTransportProvider)
            unsafe.allocateInstance(OAuthHttpStatelessServerTransportProvider.class);
    setField(transport, "mcpEndpoint", "/mcp");
    setField(transport, "objectMapper", new ObjectMapper());
    setField(transport, "authProvider", mock(OAuthAuthorizationServerProvider.class));
    setField(transport, "allowedOrigins", List.of());
    OAuthMetadata stale = new OAuthMetadata();
    stale.setIssuer(URI.create(McpPublicBaseUrl.LOCAL_DEV_FALLBACK + "/mcp"));
    setField(transport, "oauthMetadata", stale);
    return transport;
  }

  private static ServletOutputStream servletStream(ByteArrayOutputStream body) {
    return new ServletOutputStream() {
      @Override
      public boolean isReady() {
        return true;
      }

      @Override
      public void setWriteListener(WriteListener writeListener) {}

      @Override
      public void write(int b) {
        body.write(b);
      }
    };
  }

  private static void setField(Object target, String name, Object value) throws Exception {
    Field field = findField(target.getClass(), name);
    field.setAccessible(true);
    field.set(target, value);
  }

  @SuppressWarnings("unchecked")
  private static <T> T readField(Object target, String name) throws Exception {
    Field field = findField(target.getClass(), name);
    field.setAccessible(true);
    return (T) field.get(target);
  }

  private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
    Class<?> current = type;
    while (current != null) {
      try {
        return current.getDeclaredField(name);
      } catch (NoSuchFieldException ignored) {
        current = current.getSuperclass();
      }
    }
    throw new NoSuchFieldException(name);
  }

  private static Unsafe unsafe() throws Exception {
    Field field = Unsafe.class.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    return (Unsafe) field.get(null);
  }
}
