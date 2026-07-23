package com.kcet.analyzer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * KCET Cutoff Analyzer — Spring Boot Entry Point
 *
 * Starts the embedded Tomcat server on port 8080.
 * MySQL tables are auto-created by Hibernate on first run.
 *
 * Run with:  mvn spring-boot:run
 * Build JAR: mvn clean package && java -jar target/kcet-cutoff-analyzer-1.0.0.jar
 */
@SpringBootApplication
public class KcetAnalyzerApplication {

    public static void main(String[] args) {
        SpringApplication.run(KcetAnalyzerApplication.class, args);
    }
}
