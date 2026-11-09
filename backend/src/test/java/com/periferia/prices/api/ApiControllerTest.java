package com.periferia.prices.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.periferia.prices.pricing.PriceController;
import com.periferia.prices.pricing.PriceLookupService;
import com.periferia.prices.pricing.PriceResponse;
import com.periferia.prices.pricing.ProviderUnavailableException;
import com.periferia.prices.pricing.PriceProvider;
import com.periferia.prices.rules.CreateProductRuleRequest;
import com.periferia.prices.rules.DuplicateProductRuleException;
import com.periferia.prices.rules.PreferredProvider;
import com.periferia.prices.rules.ProductRuleController;
import com.periferia.prices.rules.ProductRuleNotFoundException;
import com.periferia.prices.rules.ProductRuleResponse;
import com.periferia.prices.rules.ProductRuleService;
import com.periferia.prices.rules.UpdateProductRuleRequest;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = {ProductRuleController.class, PriceController.class})
@Import(ApiExceptionHandler.class)
class ApiControllerTest {

    private static final String SKU = "ABC-123";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProductRuleService productRuleService;

    @MockitoBean
    private PriceLookupService priceLookupService;

    @Test
    void createsRuleWithCreatedStatusAndLocation() throws Exception {
        when(productRuleService.create(any(CreateProductRuleRequest.class))).thenReturn(rule());

        mockMvc.perform(post("/api/v1/product-rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sku":"ABC-123","name":"Cable","marginPercentage":15,
                                 "preferredProvider":"AUTO","active":true}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/product-rules/ABC-123"))
                .andExpect(jsonPath("$.sku").value(SKU))
                .andExpect(jsonPath("$.marginPercentage").value(15.0));
    }

    @Test
    void returnsFieldErrorsForInvalidCreateRequest() throws Exception {
        mockMvc.perform(post("/api/v1/product-rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sku":"","name":"","marginPercentage":-1,
                                 "preferredProvider":"AUTO","active":true}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.fields.sku").exists())
                .andExpect(jsonPath("$.fields.name").exists())
                .andExpect(jsonPath("$.fields.marginPercentage").exists());

        verifyNoInteractions(productRuleService);
    }

    @Test
    void mapsDuplicateSkuToConflict() throws Exception {
        when(productRuleService.create(any(CreateProductRuleRequest.class)))
                .thenThrow(new DuplicateProductRuleException(SKU));

        mockMvc.perform(post("/api/v1/product-rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sku":"ABC-123","name":"Cable","marginPercentage":15,
                                 "preferredProvider":"AUTO","active":true}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Ya existe una regla para el SKU ABC-123"));
    }

    @Test
    void listsRulesAsJson() throws Exception {
        when(productRuleService.list()).thenReturn(List.of(rule()));

        mockMvc.perform(get("/api/v1/product-rules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sku").value(SKU))
                .andExpect(jsonPath("$[0].active").value(true));
    }

    @Test
    void updatesRuleAndReturnsUpdatedRepresentation() throws Exception {
        when(productRuleService.update(eq(SKU), any(UpdateProductRuleRequest.class))).thenReturn(rule());

        mockMvc.perform(put("/api/v1/product-rules/{sku}", SKU)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Cable","marginPercentage":15,
                                 "preferredProvider":"AUTO","active":true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sku").value(SKU));
    }

    @Test
    void deletesRuleAndReturnsNoContent() throws Exception {
        mockMvc.perform(delete("/api/v1/product-rules/{sku}", SKU))
                .andExpect(status().isNoContent());

        verify(productRuleService).delete(SKU);
    }

    @Test
    void returnsConsolidatedPriceResponse() throws Exception {
        when(priceLookupService.lookup(SKU)).thenReturn(new PriceResponse(
                SKU,
                "Cable",
                new BigDecimal("100.00"),
                new BigDecimal("15.00"),
                new BigDecimal("115.00"),
                "USD",
                "PROVIDER_A",
                false,
                120
        ));

        mockMvc.perform(get("/api/v1/prices").param("sku", SKU))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sku").value(SKU))
                .andExpect(jsonPath("$.providerPrice").value(100.0))
                .andExpect(jsonPath("$.finalPrice").value(115.0))
                .andExpect(jsonPath("$.source").value("PROVIDER_A"))
                .andExpect(jsonPath("$.stale").value(false));
    }

    @Test
    void mapsUnknownPriceSkuToNotFound() throws Exception {
        when(priceLookupService.lookup(SKU)).thenThrow(new ProductRuleNotFoundException(SKU));

        mockMvc.perform(get("/api/v1/prices").param("sku", SKU))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void mapsProviderOutageToServiceUnavailable() throws Exception {
        when(priceLookupService.lookup(SKU)).thenThrow(new ProviderUnavailableException(SKU));

        mockMvc.perform(get("/api/v1/prices").param("sku", SKU))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503));
    }

    private ProductRuleResponse rule() {
        return new ProductRuleResponse(
                UUID.fromString("f5372bc6-3e19-4cd7-8fd2-f62c5667d57a"),
                SKU,
                "Cable",
                new BigDecimal("15.00"),
                PreferredProvider.AUTO,
                true,
                Instant.parse("2026-11-09T12:00:00Z")
        );
    }
}