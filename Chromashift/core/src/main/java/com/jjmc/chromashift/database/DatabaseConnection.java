package com.jjmc.chromashift.database;

import java.sql.*;

public class DatabaseConnection {
    private static final String DB_URL = "jdbc:mysql://localhost:3306/chromashift_db";
    private static final String DB_USER = "root";
    private static final String DB_PASSWORD = "";
    private static Boolean availableCache = null;
    private static long lastCheck = 0;
    
    /**
     * Get a connection to the XAMPP MySQL database
     */
    public static Connection getConnection() throws SQLException {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
            Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
            availableCache = true;
            return conn;
        } catch (ClassNotFoundException e) {
            availableCache = false;
            throw new SQLException("MySQL JDBC Driver not found", e);
        } catch (SQLException e) {
            availableCache = false;
            throw e;
        }
    }
    
    /**
     * Test connection to database - cached for 5 seconds
     */
    public static boolean testConnection() {
        long now = System.currentTimeMillis();
        if (availableCache != null && (now - lastCheck) < 5000) {
            return availableCache;
        }
        lastCheck = now;
        try (Connection conn = getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            System.out.println("✓ Connected to: " + meta.getDatabaseProductName() + " v" + meta.getDatabaseProductVersion());
            availableCache = true;
            return true;
        } catch (SQLException e) {
            System.err.println("✗ Database connection failed (file save fallback active): " + e.getMessage());
            availableCache = false;
            return false;
        }
    }

    public static boolean isAvailable() {
        return testConnection();
    }
    
    /**
     * Close resources safely
     */
    public static void close(AutoCloseable... resources) {
        for (AutoCloseable resource : resources) {
            if (resource != null) {
                try {
                    resource.close();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }
}
