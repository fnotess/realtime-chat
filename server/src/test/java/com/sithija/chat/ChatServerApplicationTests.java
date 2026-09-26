package com.sithija.chat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// chat.ws.port=0 binds any free port, so the test passes while a dev server holds 8081.
// Tomcat isn't a concern: the default MOCK web environment doesn't open a real HTTP port.
@SpringBootTest(properties = "chat.ws.port=0")
class ChatServerApplicationTests {

	@Test
	void contextLoads() {
	}

}
