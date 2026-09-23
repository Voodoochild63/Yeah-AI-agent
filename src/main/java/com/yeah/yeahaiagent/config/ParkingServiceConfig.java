package com.yeah.yeahaiagent.config;

import com.yeah.yeahaiagent.service.ParkingService;
import com.yeah.yeahaiagent.service.impl.GooglePlacesParkingService;
import com.yeah.yeahaiagent.service.impl.MockParkingService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 按 parking.provider 选择停车数据源，默认 mock。
 */
@Configuration
public class ParkingServiceConfig {

    @Bean
    @Primary
    public ParkingService parkingService(
            @Value("${parking.provider:mock}") String provider,
            MockParkingService mockParkingService,
            GooglePlacesParkingService googlePlacesParkingService) {
        if ("google".equalsIgnoreCase(provider)) {
            return googlePlacesParkingService;
        }
        return mockParkingService;
    }
}
