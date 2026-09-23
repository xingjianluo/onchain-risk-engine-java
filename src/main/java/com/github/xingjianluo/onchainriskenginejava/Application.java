package com.github.xingjianluo.onchainriskenginejava;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {
        "com.github.xingjianluo.onchainriskenginejava",
        "com.github.xingjianluo.riskengine"
})
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
