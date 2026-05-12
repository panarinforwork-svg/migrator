package migrator.utils;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import migrator.Application;

/**
 * Класс для чтения и парсинга конфигурационного файла ora2pg.conf
 */
public class Ora2PgConfig {
    
    private static final Logger LOGGER = LogManager.getLogger(Application.class);
    
    // Карта для хранения всех параметров конфигурации
    private static final Map<String, String> properties = new LinkedHashMap<>();
    
    // Карта для хранения множественных значений (например, DELETE, WHERE, REPLACE_QUERY)
    private static final Map<String, List<String>> multiProperties = new LinkedHashMap<>();
    
    // Путь к файлу конфигурации
    private static String configPath;
    
    // Регулярное выражение для парсинга строк конфигурации
    private static final Pattern CONFIG_PATTERN = Pattern.compile(
        "^\\s*([A-Z_][A-Z0-9_]*)\\s+(.+?)\\s*$",
        Pattern.CASE_INSENSITIVE
    );
    
    // Комментарии и пустые строки
    private static final Pattern COMMENT_PATTERN = Pattern.compile("^\\s*#");
    private static final Pattern EMPTY_PATTERN = Pattern.compile("^\\s*$");
    
    /**
     * Конструктор по умолчанию
     */
    public Ora2PgConfig() {
    }
    
    /**
     * Конструктор с указанием пути к файлу конфигурации
     * @param configPath путь к файлу ora2pg.conf
     * @throws IOException если файл не найден или не может быть прочитан
     */
    public Ora2PgConfig(String configPath) throws IOException {
        load(configPath);
    }
    
    /**
     * Загрузить конфигурацию из файла
     * @param configPath путь к файлу конфигурации
     * @throws IOException если файл не найден или не может быть прочитан
     */
    public void load(String configPath) throws IOException {
        this.configPath = configPath;
        Path path = Paths.get(configPath);
        
        if (!Files.exists(path)) {
            throw new FileNotFoundException("Config file not found: " + configPath);
        }
        
        List<String> lines = Files.readAllLines(path);
        parseLines(lines);
    }
    
    /**
     * Загрузить конфигурацию из строк
     * @param lines строки конфигурации
     */
    public void loadFromLines(List<String> lines) {
        parseLines(lines);
    }
    
    /**
     * Парсинг строк конфигурации
     * @param lines список строк
     */
    private void parseLines(List<String> lines) {
        properties.clear();
        multiProperties.clear();
        
        for (String line : lines) {
            // Пропускаем комментарии и пустые строки
            if (COMMENT_PATTERN.matcher(line).find() || EMPTY_PATTERN.matcher(line).find()) {
                continue;
            }
            
            // Проверяем директиву IMPORT (включение другого конфига)
            if (line.toUpperCase().startsWith("IMPORT")) {
                String importPath = line.substring(6).trim();
                if (!importPath.isEmpty() && !importPath.startsWith("#")) {
                    try {
                        load(importPath);
                    } catch (IOException e) {
                        LOGGER.warn("Could not import config: {} - {}", importPath, e.getMessage());
                    }
                }
                continue;
            }
            
            Matcher matcher = CONFIG_PATTERN.matcher(line);
            if (matcher.matches()) {
                String key = matcher.group(1).toUpperCase();
                String value = matcher.group(2).trim();
                
                // Обработка множественных значений (например, REPLACE_QUERY, DELETE, WHERE)
                if (isMultiValueKey(key)) {
                    multiProperties.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
                } else {
                    properties.put(key, value);
                }
            }
        }
    }
    
    /**
     * Проверка, может ли ключ иметь множественные значения
     * @param key ключ конфигурации
     * @return true если ключ может иметь несколько значений
     */
    private boolean isMultiValueKey(String key) {
        return "REPLACE_QUERY".equals(key) || 
               "DELETE".equals(key) || 
               "WHERE".equals(key) ||
               "MODIFY_TYPE".equals(key) ||
               "ORA_INITIAL_COMMAND".equals(key) ||
               "PG_INITIAL_COMMAND".equals(key) ||
               "ALLOW".equals(key) ||
               "EXCLUDE".equals(key) ||
               "VIEW_AS_TABLE".equals(key) ||
               "MVIEW_AS_TABLE".equals(key) ||
               "REPLACE_TABLES".equals(key) ||
               "REPLACE_COLS".equals(key) ||
               "TRANSFORM_VALUE".equals(key);
    }
    
    /**
     * Получить значение параметра по ключу
     * @param key ключ параметра (регистронезависимый)
     * @return значение параметра или null если не найден
     */
    public String get(String key) {
        return properties.get(key.toUpperCase());
    }
    
    /**
     * Получить значение параметра с значением по умолчанию
     * @param key ключ параметра
     * @param defaultValue значение по умолчанию
     * @return значение параметра или defaultValue если не найден
     */
    public String get(String key, String defaultValue) {
        String value = get(key);
        return value != null ? value : defaultValue;
    }
    
    /**
     * Получить целочисленное значение параметра
     * @param key ключ параметра
     * @return целочисленное значение или null
     */
    public Integer getInt(String key) {
        String value = get(key);
        if (value == null) return null;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            LOGGER.warn("Failed to parse integer value for key '{}': {}", key, value);
            return null;
        }
    }
    
    /**
     * Получить целочисленное значение параметра с значением по умолчанию
     * @param key ключ параметра
     * @param defaultValue значение по умолчанию
     * @return целочисленное значение
     */
    public int getInt(String key, int defaultValue) {
        Integer value = getInt(key);
        return value != null ? value : defaultValue;
    }
    
    /**
     * Получить логическое значение параметра
     * @param key ключ параметра
     * @return true если значение "1", "true", "yes", "on"
     */
    public Boolean getBoolean(String key) {
        String value = get(key);
        if (value == null) return null;
        return value.matches("(?i)^(1|true|yes|on)$");
    }
    
    /**
     * Получить логическое значение параметра с значением по умолчанию
     * @param key ключ параметра
     * @param defaultValue значение по умолчанию
     * @return логическое значение
     */
    public boolean getBoolean(String key, boolean defaultValue) {
        Boolean value = getBoolean(key);
        return value != null ? value : defaultValue;
    }
    
    /**
     * Получить список значений для множественных параметров
     * @param key ключ параметра
     * @return список значений или пустой список
     */
    public List<String> getMulti(String key) {
        return multiProperties.getOrDefault(key.toUpperCase(), Collections.emptyList());
    }
    
    /**
     * Получить все ключи конфигурации
     * @return набор ключей
     */
    public Set<String> keySet() {
        Set<String> keys = new HashSet<>(properties.keySet());
        keys.addAll(multiProperties.keySet());
        return keys;
    }
    
    /**
     * Получить карту всех параметров
     * @return карта параметров
     */
    public Map<String, String> getAllProperties() {
        return new LinkedHashMap<>(properties);
    }
    
    /**
     * Проверить, существует ли параметр
     * @param key ключ параметра
     * @return true если параметр существует
     */
    public boolean containsKey(String key) {
        return properties.containsKey(key.toUpperCase()) || 
               multiProperties.containsKey(key.toUpperCase());
    }
    
    /**
     * Специализированные геттеры для часто используемых параметров
     */
    
    // Oracle параметры
    public String getOracleHome() { return get("ORACLE_HOME"); }
    public String getOracleDsn() { return get("ORACLE_DSN"); }
    public String getOracleUser() { return get("ORACLE_USER"); }
    public String getOraclePassword() { return get("ORACLE_PWD"); }
    public String getSchema() { return get("SCHEMA"); }
    public boolean isUserGrants() { return getBoolean("USER_GRANTS", false); }
    
    // PostgreSQL параметры
    public String getPgDsn() { return get("PG_DSN"); }
    public String getPgUser() { return get("PG_USER"); }
    public String getPgPassword() { return get("PG_PWD"); }
    public int getPgVersion() { return getInt("PG_VERSION", 18); }
    
    // Экспорт параметры
    public String getExportType() { return get("TYPE", "TABLE"); }
    public String getOutput() { return get("OUTPUT", "output.sql"); }
    public String getOutputDir() { return get("OUTPUT_DIR", "."); }
    public int getJobs() { return getInt("JOBS", 1); }
    public int getOracleCopies() { return getInt("ORACLE_COPIES", 1); }
    public int getDataLimit() { return getInt("DATA_LIMIT", 10000); }
    
    // Флаги
    public boolean isDebug() { return getBoolean("DEBUG", false); }
    public boolean isExportSchema() { return getBoolean("EXPORT_SCHEMA", true); }
    public boolean isCreateSchema() { return getBoolean("CREATE_SCHEMA", true); }
    public boolean isTruncateTable() { return getBoolean("TRUNCATE_TABLE", true); }
    public boolean isDisableSequence() { return getBoolean("DISABLE_SEQUENCE", false); }
    public boolean isDisableTriggers() { return getBoolean("DISABLE_TRIGGERS", false); }
    public boolean isDropIfExists() { return getBoolean("DROP_IF_EXISTS", false); }
    public boolean isPreserveCase() { return getBoolean("PRESERVE_CASE", false); }
    
    // Параметры производительности
    public int getParallelTables() { return getInt("PARALLEL_TABLES", 1); }
    public int getDefaultParallelismDegree() { return getInt("DEFAULT_PARALLELISM_DEGREE", 0); }
    public int getParallelMinRows() { return getInt("PARALLEL_MIN_ROWS", 100000); }
    public boolean isDropIndexes() { return getBoolean("DROP_INDEXES", false); }
    public boolean isSynchronousCommit() { return getBoolean("SYNCHRONOUS_COMMIT", false); }
    
    // Строка подключения для Oracle
    public String getOracleConnectionString() {
        String dsn = getOracleDsn();
        String user = getOracleUser();
        String pwd = getOraclePassword();
        if (dsn != null && user != null && pwd != null) {
            return user + "/" + pwd + "@" + dsn.replace("dbi:Oracle:", "");
        }
        return null;
    }
    
    /**
     * Вывести все параметры конфигурации для отладки
     */
    public void dump() {
        if (!LOGGER.isDebugEnabled()) {
            return;
        }
        
        LOGGER.debug("=== Ora2Pg Configuration ===");
        LOGGER.debug("Config file: {}", configPath);
        LOGGER.debug("--- Simple properties ---");
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            // Скрываем пароли при выводе
            String value = entry.getValue();
            if (entry.getKey().contains("PWD") || entry.getKey().contains("PASSWORD")) {
                value = "***HIDDEN***";
            }
            LOGGER.debug("{} = {}", entry.getKey(), value);
        }
        
        if (!multiProperties.isEmpty()) {
            LOGGER.debug("--- Multi-value properties ---");
            for (Map.Entry<String, List<String>> entry : multiProperties.entrySet()) {
                LOGGER.debug("{}:", entry.getKey());
                for (String value : entry.getValue()) {
                    LOGGER.debug("  - {}", value);
                }
            }
        }
    }
    
    /**
     * Сохранить конфигурацию в файл
     * @param outputPath путь для сохранения
     * @throws IOException при ошибке записи
     */
    public void save(String outputPath) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("# Generated by Ora2PgConfig Java class");
        lines.add("# " + new Date());
        lines.add("");
        
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            lines.add(entry.getKey() + "\t" + entry.getValue());
        }
        
        for (Map.Entry<String, List<String>> entry : multiProperties.entrySet()) {
            for (String value : entry.getValue()) {
                lines.add(entry.getKey() + "\t" + value);
            }
        }
        
        Files.write(Paths.get(outputPath), lines);
        LOGGER.info("Configuration saved to: {}", outputPath);
    }
    
}