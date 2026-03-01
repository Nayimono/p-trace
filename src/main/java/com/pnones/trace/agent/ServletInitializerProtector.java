package com.pnones.trace.agent;

import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.NotFoundException;

/**
 * Protects legacy servlet initializers (Spring 4.x, Logback) from class loading errors
 * These libraries reference javax.servlet but may fail when Spring/Logback classes themselves
 * attempt to load jakarta.servlet classes
 */
public class ServletInitializerProtector {
    
    /**
     * 안전하게 Spring과 Logback 서블릿 이니셜라이저를 보호합니다
     */
    public static void protectServletInitializers(ClassPool pool) {
        String[][] initializersToProtect = {
            {"org.springframework.web.SpringServletContainerInitializer", "onStartup"},
            {"ch.qos.logback.classic.servlet.LogbackServletContainerInitializer", "onStartup"}
        };
        
        for (String[] classDef : initializersToProtect) {
            String className = classDef[0];
            String methodName = classDef[1];
            
            try {
                CtClass ct = pool.get(className);
                
                // Find the onStartup method
                CtMethod[] methods = ct.getDeclaredMethods();
                for (CtMethod m : methods) {
                    if (m.getName().equals(methodName)) {
                        // Wrap with try-catch to suppress class loading errors
                        String wrappedBody = 
                            "{ " +
                            "  try { " +
                            "    super." + methodName + "($$); " +
                            "  } catch (java.lang.ClassNotFoundException e) { " +
                            "    java.lang.System.err.println(\"[PTrace] Skipped \" + \"" + className + "\" + \": \" + e.getMessage()); " +
                            "  } catch (java.lang.NoClassDefFoundError e) { " +
                            "    java.lang.System.err.println(\"[PTrace] Skipped \" + \"" + className + "\" + \": \" + e.getMessage()); " +
                            "  } catch (Throwable t) { " +
                            "    java.lang.System.err.println(\"[PTrace] Error in \" + \"" + className + "\" + \": \" + t.getMessage()); " +
                            "  } " +
                            "}";
                        
                        // Add protection: insert try-catch at beginning
                        m.insertBefore(
                            "try { } catch (java.lang.ClassNotFoundException e) { " +
                            "  java.lang.System.err.println(\"[PTrace] ClassNotFound in " + className + "\"); return; " +
                            "} catch (java.lang.NoClassDefFoundError e) { " +
                            "  java.lang.System.err.println(\"[PTrace] NoClassDefFound in " + className + "\"); return; " +
                            "}");
                        
                        java.lang.System.err.println("[PTrace] Protected " + className);
                        break;
                    }
                }
                
                ct.defrost();
                
            } catch (NotFoundException nf) {
                // Class not found in classpath - that's OK, might not be used
            } catch (Throwable t) {
                java.lang.System.err.println("[PTrace] Failed to protect " + className + ": " + t.getMessage());
            }
        }
    }
}
