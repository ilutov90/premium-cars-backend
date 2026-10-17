package com.premiumcars.importing;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.premiumcars.importing.extract.GenericPageExtractor;

@Configuration
public class ImportConfig {

    @Bean
    GenericPageExtractor genericPageExtractor(ObjectMapper mapper) {
        return new GenericPageExtractor(mapper);
    }
}
