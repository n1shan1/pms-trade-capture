package com.pms.pms_trade_capture;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PmsTradeCaptureApplication {

	public static void main(String[] args) {
		SpringApplication.run(PmsTradeCaptureApplication.class, args);
	}

}
