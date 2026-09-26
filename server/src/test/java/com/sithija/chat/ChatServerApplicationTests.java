package com.sithija.chat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

// chat.ws.port=0 binds any free port, so the test passes while a dev server holds 8081.
// Tomcat isn't a concern: the default MOCK web environment doesn't open a real HTTP port.
// The database is a Testcontainers Postgres, never the dev one.
@SpringBootTest(properties = "chat.ws.port=0")
@Import(TestcontainersConfig.class)
class ChatServerApplicationTests {

	@Test
	void contextLoads() {
	}

}
