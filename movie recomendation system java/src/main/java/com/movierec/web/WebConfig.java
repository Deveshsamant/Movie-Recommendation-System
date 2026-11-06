package com.movierec.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** CORS, the front-end's entry page and its cache policy. */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**").allowedOrigins("*").allowedMethods("*").allowedHeaders("*");
    }

    @Controller
    static class IndexController {
        @GetMapping("/")
        public ResponseEntity<Resource> index() {
            return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(new ClassPathResource("static/index.html"));
        }
    }

    /**
     * Makes the browser revalidate front-end assets.
     *
     * <p>ES modules import each other by bare path, so bumping a ?v= on the entry point does nothing
     * for its imports: the browser happily serves a stale ui.js against a fresh app.js.
     */
    @Component
    static class NoCacheStaticFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            String path = request.getRequestURI();
            if (path.startsWith("/static/") || path.equals("/") || path.endsWith(".html")) {
                response.setHeader("Cache-Control", "no-cache, must-revalidate");
            }
            chain.doFilter(request, response);
        }
    }
}
