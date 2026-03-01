package com.pnones.trace.test;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.startup.Tomcat;

import java.io.IOException;
import java.io.PrintWriter;
import java.sql.*;

/**
 * Simple test application for testing SQL and response body tracing
 */
public class TestApp {
    
    public static void main(String[] args) throws LifecycleException {
        Tomcat tomcat = new Tomcat();
        tomcat.setPort(8080);
        tomcat.getConnector();
        
        Context ctx = tomcat.addContext("", null);
        
        // Test endpoint with SQL query
        Tomcat.addServlet(ctx, "testSql", new HttpServlet() {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) 
                    throws ServletException, IOException {
                resp.setContentType("application/json; charset=UTF-8");
                PrintWriter out = resp.getWriter();
                
                try {
                    // Create in-memory H2 database
                    Connection conn = DriverManager.getConnection("jdbc:h2:mem:testdb", "sa", "");
                    
                    // Create table
                    Statement stmt = conn.createStatement();
                    stmt.execute("CREATE TABLE IF NOT EXISTS users (id INT PRIMARY KEY, name VARCHAR(100))");
                    
                    // Insert data
                    PreparedStatement pstmt = conn.prepareStatement("INSERT INTO users VALUES (?, ?)");
                    pstmt.setInt(1, 1);
                    pstmt.setString(2, "Alice");
                    pstmt.executeUpdate();
                    
                    pstmt.setInt(1, 2);
                    pstmt.setString(2, "Bob");
                    pstmt.executeUpdate();
                    
                    // Query data
                    PreparedStatement query = conn.prepareStatement("SELECT * FROM users WHERE id = ?");
                    query.setInt(1, 1);
                    ResultSet rs = query.executeQuery();
                    
                    StringBuilder result = new StringBuilder();
                    result.append("{\"users\":[");
                    boolean first = true;
                    while (rs.next()) {
                        if (!first) result.append(",");
                        first = false;
                        result.append("{");
                        result.append("\"id\":").append(rs.getInt("id")).append(",");
                        result.append("\"name\":\"").append(rs.getString("name")).append("\"");
                        result.append("}");
                    }
                    result.append("]}");
                    
                    out.println(result.toString());
                    
                    rs.close();
                    query.close();
                    pstmt.close();
                    stmt.close();
                    conn.close();
                    
                } catch (SQLException e) {
                    out.println("{\"error\":\"" + e.getMessage() + "\"}");
                }
            }
        });
        ctx.addServletMappingDecoded("/test", "testSql");
        
        // Simple JSON response endpoint
        Tomcat.addServlet(ctx, "hello", new HttpServlet() {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) 
                    throws ServletException, IOException {
                resp.setContentType("application/json; charset=UTF-8");
                PrintWriter out = resp.getWriter();
                out.println("{\"message\":\"Hello from Test App!\",\"timestamp\":" + System.currentTimeMillis() + "}");
            }
        });
        ctx.addServletMappingDecoded("/hello", "hello");
        
        // Binary response endpoint (simulated image)
        Tomcat.addServlet(ctx, "binary", new HttpServlet() {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) 
                    throws ServletException, IOException {
                resp.setContentType("application/octet-stream");
                resp.setHeader("Content-Disposition", "attachment; filename=\"test.bin\"");
                // Generate some binary data (simulating a small file)
                byte[] binaryData = new byte[256];
                for (int i = 0; i < binaryData.length; i++) {
                    binaryData[i] = (byte) i;
                }
                resp.getOutputStream().write(binaryData);
                resp.getOutputStream().flush();
            }
        });
        ctx.addServletMappingDecoded("/binary", "binary");
        
        tomcat.start();
        System.out.println("Test application started on http://localhost:8080");
        System.out.println("Try: http://localhost:8080/test or http://localhost:8080/hello or http://localhost:8080/binary");
        tomcat.getServer().await();
    }
}
