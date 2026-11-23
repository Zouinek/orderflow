package com.orderflow.order;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
public class OrderApiIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18");


    @Test
    void createdOrderCanBeFetchedById(@Autowired MockMvc mvc) throws Exception {
        MvcResult result = mvc.perform(post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                "customerName" : "Tila",
                                "item" : "Book",
                                "quantity" : 3,
                                "price" : 14.95
                                 }
                                """))
                .andDo(print())
                .andExpect(status().isCreated())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        Integer id = JsonPath.read(body, "$.id");

        mvc.perform(get("/orders/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerName").value("Tila"));
    }

    @Test
    void postWithClientIdDoesNotOverWriteExistingOrder(@Autowired MockMvc mvc) throws Exception {
        MvcResult aliceResult = mvc.perform(post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                "customerName" : "Alice",
                                "item" : "Book",
                                "quantity" : 3,
                                "price" : 14.95
                                 }
                                """))
                .andDo(print())
                .andExpect(status().isCreated())
                .andReturn();

        String aliceBody = aliceResult.getResponse().getContentAsString();
        Integer aliceId = JsonPath.read(aliceBody, "$.id");

        mvc.perform(post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                "id" : %d,
                                "customerName" : "Eve",
                                "item" : "Book",
                                "quantity" : 3,
                                "price" : 14.95
                                 }
                                """.formatted(aliceId)))
                .andDo(print())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(not(aliceId)));


        mvc.perform(get("/orders/{id}", aliceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerName").value("Alice"));

    }

    @Test
    void postWithoutQuantityReturns400WithFieldError (@Autowired MockMvc mvc) throws Exception {
        mvc.perform(post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                "customerName" : "Alice",
                                "item" : "Book",
                                "price" : 14.95
                                 }
                                """))
                .andDo(print())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.quantity").exists())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void postWithAllFieldsInvalidReturnsAllErrors (@Autowired MockMvc mvc) throws Exception {
        mvc.perform(post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                "customerName" : " ",
                                "quantity" : -3,
                                "price" : -1
                                 }
                                """))
                .andDo(print())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.customerName").exists())
                .andExpect(jsonPath("$.errors.item").exists())
                .andExpect(jsonPath("$.errors.quantity").exists())
                .andExpect(jsonPath("$.errors.price").exists())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void postWithNonNumericPriceReturns400WithFieldError(@Autowired MockMvc mvc) throws Exception {
        mvc.perform(post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                "customerName" : "Alice",
                                "item" : "Book",
                                "quantity" : 3,
                                "price" : "abc"
                                 }
                                """))
                .andDo(print())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.price").value("must be a valid number"))
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }


}
