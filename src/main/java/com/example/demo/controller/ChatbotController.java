package com.example.demo.controller;

import com.example.demo.service.ChatbotService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/chatbot")
public class ChatbotController {

    private final ChatbotService chatbotService;

    public ChatbotController(ChatbotService chatbotService) {
        this.chatbotService = chatbotService;
    }

    // Body: {"message": "Which dishes are nut free?"}   Answer: {"reply": "..."}
    @PostMapping("/{qrCodeHash}")
    public Map<String, String> ask(@PathVariable String qrCodeHash, @RequestBody Map<String, String> body) {
        return Map.of("reply", chatbotService.answer(qrCodeHash, body.get("message")));
    }
}