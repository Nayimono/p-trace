package jakarta.servlet;

/**
 * Minimal stub for jakarta.servlet.ServletContainerInitializer
 * Provided by PTrace Agent for Tomcat 9 (javax.servlet) compatibility
 */
public interface ServletContainerInitializer {
    java.util.Set<Class<?>> onStartup(java.util.Set<Class<?>> c, ServletContext ctx) throws java.lang.Exception;
}
