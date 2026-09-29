package com.sithija.chat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import com.sithija.chat.auth.AuthProperties;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ChatServerApplication {

	public static void main(String[] args) {
		SpringApplication.run(ChatServerApplication.class, args);
	}

	// One instance for the REST filter and the WebSocket handshake, so both see the same trust list.
	@Bean
	ClientIpResolver clientIpResolver(AuthProperties auth) {
		return new ClientIpResolver(auth.trustedProxies());
	}

}
