package com.pms.pms_trade_capture;

import org.springframework.boot.SpringApplication;

public class TestPmsTradeCaptureApplication {

	public static void main(String[] args) {
		SpringApplication.from(PmsTradeCaptureApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
