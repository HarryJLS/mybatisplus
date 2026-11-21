package dev.mybatis.assistant.core;

import java.sql.*;

/** Loaded in the driver loader so JDBC's caller-loader check permits removing its registrations. */
public final class JdbcDriverCleanup {
    private JdbcDriverCleanup() {}
    public static void unregister(ClassLoader owner)throws SQLException{
        var drivers=DriverManager.getDrivers();while(drivers.hasMoreElements()){Driver driver=drivers.nextElement();if(driver.getClass().getClassLoader()==owner)DriverManager.deregisterDriver(driver);}
    }
}
