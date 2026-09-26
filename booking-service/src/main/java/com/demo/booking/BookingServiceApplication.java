package com.demo.booking;

import com.demo.booking.twopc.RemoteParticipant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestTemplate;

import java.util.List;

@SpringBootApplication
@EnableDiscoveryClient
public class BookingServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(BookingServiceApplication.class, args);
    }

    /**
     * Plain on purpose. No load balancing and no Hystrix, because a retried
     * prepare would charge twice and a fallback would hide the in-doubt state.
     *
     * @return the template used for every call to a participant
     */
    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    /**
     * @return the participants, in the order the coordinator asks them
     */
    @Bean
    public List<RemoteParticipant> participants(RestTemplate rest,
                                                @Value("${demo.payment-url}") String paymentUrl,
                                                @Value("${demo.accounting-url}") String accountingUrl) {
        return List.of(
                new RemoteParticipant("payment", paymentUrl, rest),
                new RemoteParticipant("accounting", accountingUrl, rest));
    }
}
