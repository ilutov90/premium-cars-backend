package com.premiumcars.autoimport;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SearchRefusalMessageTest {

    @Test
    void testRefusalDetail_PageWithTitle_QuotesTheTitle() {
        String page = "<!DOCTYPE html><html><head><title>\n Security Verification </title></head><body>x</body></html>";
        assertThat(SearchHttp.refusalDetail(page)).isEqualTo("\"Security Verification\"");
    }

    @Test
    void testRefusalDetail_PageWithoutTitle_FirstEightyCharactersWithoutTags() {
        String page = "<html><body><p>" + "a".repeat(200) + "</p></body></html>";
        assertThat(SearchHttp.refusalDetail(page)).isEqualTo("a".repeat(80));
    }

    @Test
    void testRefusalDetail_BlankTitle_FallsBackToText() {
        assertThat(SearchHttp.refusalDetail("<html><title> </title>Oops, limited</html>")).isEqualTo("Oops, limited");
    }

    @Test
    void testRefusalDetail_ScriptInside_IsLeftOut() {
        assertThat(SearchHttp.refusalDetail("<script>var x=1;</script><b>Hello</b>")).isEqualTo("Hello");
    }

    @Test
    void testRefusalDetail_Null_IsEmpty() {
        assertThat(SearchHttp.refusalDetail(null)).isEmpty();
    }
}
