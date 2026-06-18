package com.lifereviewing;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@MapperScan("com.lifereviewing.mapper")
@SpringBootApplication
public class LifeReviewingApplication {

    public static void main(String[] args) {
        SpringApplication.run(LifeReviewingApplication.class, args);
    }

}
