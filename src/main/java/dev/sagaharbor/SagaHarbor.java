package dev.sagaharbor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SagaHarbor {
    public static void main(String[] args) { SpringApplication.run(SagaHarbor.class, args); }
}
