package com.security.bank;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BankApplicationTests {

    @Autowired
    MockMvc mockMvc;

    @Test
    void contextLoads() {
    }

    @Test
    void registerThenLoginReturnsJwt() throws Exception {
	mockMvc.perform(post("/user/register").contentType(MediaType.APPLICATION_JSON)
		.content("{\"username\":\"john\",\"password\":\"john123\",\"name\":\"John Doe\"}"))
		.andExpect(status().isCreated());

	mockMvc.perform(post("/user/login").contentType(MediaType.APPLICATION_JSON)
		.content("{\"username\":\"john\",\"password\":\"john123\"}"))
		.andExpect(status().isOk())
		.andExpect(jsonPath("$.jwtToken").isNotEmpty());
    }
}
