package com.github.jinba1.cuckoodb.server.web;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.jinba1.cuckoodb.ColumnType;
import com.github.jinba1.cuckoodb.server.catalog.CatalogFacade;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Controller slice for the {@code /tables} endpoints. Covers the read surface, table-name
 * validation, and the upload-disabled / wrong-content-type rejections; the full upload pipeline
 * (streaming, size cap, 409, malformed CSV) is exercised end-to-end in the integration test.
 *
 * <p>{@code CuckooDbProperties} comes from the app's {@code @EnableConfigurationProperties}
 * (bound from {@code application.properties}, where upload is disabled by default) — no extra
 * bean is declared here, to avoid a duplicate-bean clash with that one.
 */
@WebMvcTest(TableController.class)
class TableControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private CatalogFacade catalog;

    @Test
    void listReturnsSortedNames() throws Exception {
        when(catalog.tableNames()).thenReturn(List.of("Course", "Student"));
        mvc.perform(get("/tables"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("Course"))
                .andExpect(jsonPath("$[1]").value("Student"));
    }

    @Test
    void describeReturnsStaticTypedSchema() throws Exception {
        when(catalog.columnsOf("Student")).thenReturn(List.of(
                new CatalogFacade.TableColumn("a", ColumnType.INT),
                new CatalogFacade.TableColumn("name", ColumnType.STRING)));
        mvc.perform(get("/tables/Student"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Student"))
                .andExpect(jsonPath("$.columns[0].name").value("a"))
                .andExpect(jsonPath("$.columns[0].type").value("INT"))
                .andExpect(jsonPath("$.columns[1].type").value("STRING"));
    }

    @Test
    void describeMissingTableReturns404() throws Exception {
        when(catalog.columnsOf("Ghost")).thenReturn(null);
        mvc.perform(get("/tables/Ghost"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("UNKNOWN_TABLE"));
    }

    @Test
    void describeInvalidNameReturns400() throws Exception {
        mvc.perform(get("/tables/bad-name"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }

    @Test
    void uploadWhenDisabledReturns404() throws Exception {
        mvc.perform(post("/tables/foo").contentType("text/csv").content("a,b\n1,2\n"))
                .andExpect(status().isNotFound());
    }

    @Test
    void uploadWrongContentTypeReturns415() throws Exception {
        mvc.perform(post("/tables/foo").contentType("text/plain").content("a,b\n1,2\n"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.errorCode").value("UNSUPPORTED_MEDIA_TYPE"));
    }
}
