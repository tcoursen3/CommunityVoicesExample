package com.sleeware.communityvoices;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class CommunityVoicesApplication {

    public static void main(String[] args) {
        SpringApplication.run(CommunityVoicesApplication.class, args);
    }

}
