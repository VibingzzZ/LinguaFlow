package com.javaee.backend.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
public class TestController {

    @GetMapping("/api/test")
    public Map<String, Object> test() {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "success");
        response.put("message", "Backend service is running");
        return response;
    }
}