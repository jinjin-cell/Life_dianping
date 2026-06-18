package com.lifedp;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@MapperScan("com.lifedp.mapper")
@SpringBootApplication
public class LifeDpApplication {

    public static void main(String[] args) {
        SpringApplication.run(LifeDpApplication.class, args);
    }

}
