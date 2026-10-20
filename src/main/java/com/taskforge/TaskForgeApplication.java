package com.taskforge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class TaskForgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(TaskForgeApplication.class, args);
    }
}
