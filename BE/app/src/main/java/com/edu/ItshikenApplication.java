package com.edu;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
@EnableCaching
public class ItshikenApplication {

    public static void main(String[] args) {
        SpringApplication.run(ItshikenApplication.class, args);
    }
}
