package nl.llm.storyteller.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApiConfigLoadingTest {
  @TempDir
  Path temporaryDirectory;

  @Test
  @DisplayName("""
    Given a database encryption key in systemprompts/application.config,
    When API configuration is loaded without a local override,
    Then the key should be loaded while other API settings retain their defaults
    """)
  void shouldLoadEncryptionKeyFromStorytellerConfigurationOnly() throws Exception {
    Path directory = Files.createDirectories(temporaryDirectory.resolve("systemprompts"));
    String key = java.util.Base64.getEncoder().encodeToString(new byte[32]);
    Files.writeString(directory.resolve("application.config"),
      "database.encryption.key=" + key + "\napi.port=8089\nmysql=true\napi.database.path=other-database\n");

    ApiConfig config = ApiConfigLoader.load(temporaryDirectory, null);

    assertEquals(key, config.databaseEncryptionKey());
    assertEquals(7090, config.port());
    assertEquals(temporaryDirectory.resolve("memory/storyteller-api"), config.databasePath());
    assertEquals(false, config.mysql().enabled());
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "AQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
  @DisplayName("""
    Given an encryption key in systemprompts/application.config and a local key override,
    When API configuration is loaded,
    Then the local override should take precedence even when explicitly empty
    """)
  void shouldPreferLocalEncryptionKeyOverride(String overrideKey) throws Exception {
    Path directory = Files.createDirectories(temporaryDirectory.resolve("systemprompts"));
    String key = java.util.Base64.getEncoder().encodeToString(new byte[32]);
    Files.writeString(directory.resolve("application.config"), "database.encryption.key=" + key + "\n");
    Path executableDirectory = Files.createDirectories(temporaryDirectory.resolve("bin"));
    Path override = executableDirectory.resolve("application.config");
    Files.writeString(override, "database.encryption.key=" + overrideKey + "\n");

    ApiConfig config = ApiConfigLoader.load(temporaryDirectory, override);

    assertEquals(overrideKey, config.databaseEncryptionKey());
    assertEquals(executableDirectory.resolve("memory/storyteller-api"), config.databasePath());
  }

  @Test
  @DisplayName("""
    Given a local application.config containing a database encryption key,
    When API configuration is loaded and displayed,
    Then the key should be available to storage and absent from its string representation
    """)
  void shouldLoadDatabaseEncryptionKeyWithoutDisplayingIt() throws Exception {
    String key = java.util.Base64.getEncoder().encodeToString(new byte[32]);
    Path file = temporaryDirectory.resolve("application.config");
    Files.writeString(file, "database.encryption.key=" + key + "\n");
    ApiConfig config = ApiConfigLoader.load(temporaryDirectory, file);
    assertEquals(key, config.databaseEncryptionKey());
    org.junit.jupiter.api.Assertions.assertFalse(config.toString().contains(key));
  }

  @Test
  void shouldSelectMysqlWithoutChangingH2Configuration() throws Exception {
    Path file = temporaryDirectory.resolve("application.config");
    Files.writeString(file, "mysql=true\nmysql.username=test-user\nmysql.password=test-secret\n");
    ApiConfig mysql = ApiConfigLoader.load(temporaryDirectory, file);
    assertEquals("jdbc:mysql://localhost:3306/storyteller", mysql.databaseUrl());
    assertEquals("test-user", mysql.databaseUsername());
    assertEquals("test-secret", mysql.databasePassword());
    Files.writeString(file, "mysql=false\nmysql.username=test-user\nmysql.password=test-secret\n");
    ApiConfig h2 = ApiConfigLoader.load(temporaryDirectory, file);
    assertEquals("jdbc:h2:file:" + temporaryDirectory.resolve("memory/storyteller-api"), h2.databaseUrl());
    assertEquals("sa", h2.databaseUsername());
    assertEquals("", h2.databasePassword());
  }

  @Test
  @DisplayName("""
    Given no local API configuration override,
    When the API module configuration is loaded,
    Then it should use its own bundled defaults relative to the application directory
    """)
  void shouldLoadBundledApiDefaults() {
    ApiConfig apiConfig = ApiConfigLoader.load(temporaryDirectory, null);

    assertEquals("0.0.0.0", apiConfig.host());
    assertEquals(7090, apiConfig.port());
    assertEquals(temporaryDirectory.resolve("memory/storyteller-api"), apiConfig.databasePath());
    assertEquals("sa", apiConfig.databaseUsername());
    assertEquals("", apiConfig.databasePassword());
    assertEquals(Duration.ofMinutes(60), apiConfig.sessionInactivityTimeout());
    assertEquals(true, apiConfig.tls().enabled());
    assertEquals(7443, apiConfig.tls().port());
    assertEquals(temporaryDirectory.resolve("memory/tls"), apiConfig.tls().directory());
  }

  @Test
  @DisplayName("""
    Given an application.config for the API module,
    When the API configuration is loaded,
    Then it should override the bundled API defaults
    """)
  void shouldLoadApiOverridesFromApplicationConfig() throws Exception {
    Path configFile = temporaryDirectory.resolve("application.config");
    Files.writeString(configFile, """
      api.host=127.0.0.1
      api.port=8081
      api.database.path=data/storyteller
      api.database.username=storyteller
      api.database.password=secret
      api.sessionTimeoutMinutes=90
      """);

    ApiConfig apiConfig = ApiConfigLoader.load(temporaryDirectory, configFile);

    assertEquals("127.0.0.1", apiConfig.host());
    assertEquals(8081, apiConfig.port());
    assertEquals(temporaryDirectory.resolve("data/storyteller"), apiConfig.databasePath());
    assertEquals("storyteller", apiConfig.databaseUsername());
    assertEquals("secret", apiConfig.databasePassword());
    assertEquals(Duration.ofMinutes(90), apiConfig.sessionInactivityTimeout());
  }
}
