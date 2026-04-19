package utils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/**
 * Синглтон класс для управления подключением к Oracle БД.
 * Обеспечивает единое подключение на всё приложение.
 */
public class OracleConnection {

    // Единственный экземпляр класса (volatile для потокобезопасности)
    private static volatile OracleConnection instance;

    // Единственное подключение к БД
    private Connection connection;

    // Параметры подключения (лучше вынести в конфиг-файл)
    private static final String DB_URL = "jdbc:oracle:thin:@localhost:1521/ORCLPDB1";
    private static final String DB_USER = "CMOP";
    private static final String DB_PASSWORD = "CMOP";
    // Приватный конструктор (запрещаем создание экземпляров извне)
    private OracleConnection() {
        // Инициализация подключения при создании синглтона
        initConnection();
    }

    /**
     * Инициализация подключения к БД
     */
    private void initConnection() {
        try {
            // Загружаем JDBC драйвер Oracle (для старых версий Java)
            Class.forName("oracle.jdbc.driver.OracleDriver");

            // Создаем подключение
            this.connection = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);

            // Настраиваем автокоммит (по умолчанию true, можно отключить)
            this.connection.setAutoCommit(true);

            System.out.println("Подключение к Oracle БД успешно установлено");

        } catch (ClassNotFoundException e) {
            System.err.println("Oracle JDBC Driver не найден!");
            e.printStackTrace();
            throw new RuntimeException("Ошибка загрузки драйвера Oracle", e);
        } catch (SQLException e) {
            System.err.println("Ошибка подключения к БД!");
            e.printStackTrace();
            throw new RuntimeException("Ошибка подключения к Oracle БД", e);
        }
    }

    /**
     * Получение единственного экземпляра синглтона (потокобезопасный ленивый метод)
     * Double-Checked Locking
     */
    public static OracleConnection getInstance() {
        if (instance == null) {
            synchronized (OracleConnection.class) {
                if (instance == null) {
                    instance = new OracleConnection();
                }
            }
        }
        return instance;
    }

    /**
     * Получение соединения с БД
     * @return Connection объект
     */
    public Connection getConnection() {
        try {
            // Проверяем, не закрыто ли соединение. Если закрыто - пересоздаем
            if (connection == null || connection.isClosed()) {
                System.out.println("Соединение закрыто или null. Переподключаемся...");
                initConnection();
            }
        } catch (SQLException e) {
            System.err.println("Ошибка при проверке состояния соединения");
            initConnection(); // Пытаемся переподключиться
        }
        return connection;
    }

    /**
     * Проверка, активно ли соединение
     * @return true - соединение активно, false - не активно
     */
    public boolean isConnected() {
        try {
            return connection != null && !connection.isClosed();
        } catch (SQLException e) {
            return false;
        }
    }

    /**
     * Закрытие соединения с БД
     */
    public void closeConnection() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
                System.out.println("Соединение с Oracle БД закрыто");
            }
        } catch (SQLException e) {
            System.err.println("Ошибка при закрытии соединения");
            e.printStackTrace();
        } finally {
            connection = null;
        }
    }

    /**
     * Настройка автокоммита
     * @param autoCommit true/false
     */
    public void setAutoCommit(boolean autoCommit) throws SQLException {
        if (connection != null && !connection.isClosed()) {
            connection.setAutoCommit(autoCommit);
        }
    }

    /**
     * Коммит транзакции
     */
    public void commit() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            connection.commit();
            System.out.println("Транзакция закоммичена");
        }
    }

    /**
     * Откат транзакции
     */
    public void rollback() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            connection.rollback();
            System.out.println("Транзакция откачена");
        }
    }

    /**
     * Сброс синглтона (для тестирования или принудительного пересоздания)
     */
    public static void resetInstance() {
        if (instance != null) {
            instance.closeConnection();
            instance = null;
        }
    }
}