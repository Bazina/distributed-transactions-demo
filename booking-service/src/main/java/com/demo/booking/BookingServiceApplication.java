package com.demo.booking;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestTemplate;

@SpringBootApplication
@EnableDiscoveryClient
public class BookingServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(BookingServiceApplication.class, args);
    }

    /**
     * Deliberately plain. No @LoadBalanced and no Hystrix around it, because the
     * 2PC calls must not be retried or timed out behind our back. A retried
     * prepare is a second charge, and a fallback would hide the in-doubt state.
     */
    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
