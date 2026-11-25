package com.careline.controller;

import com.careline.data.ClinicData;
import com.careline.service.VoiceAssistantService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class ApiController {
    private final ClinicData clinicData;
    private final VoiceAssistantService voiceAssistantService;

    public ApiController(ClinicData clinicData, VoiceAssistantService voiceAssistantService) {
        this.clinicData = clinicData;
        this.voiceAssistantService = voiceAssistantService;
    }

    @GetMapping("/doctors")
    public Object doctors() {
        return clinicData.doctorsWithSlots();
    }

    @GetMapping("/appointments")
    public Object appointments() {
        return clinicData.appointments();
    }

    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestBody Map<String, String> body) {
        return voiceAssistantService.chat(body.getOrDefault("message", ""));
    }

    @PostMapping("/chat/reset")
    public Map<String, String> resetConversation() {
        voiceAssistantService.startConversation();
        return Map.of("status", "reset");
    }

    @GetMapping("/status")
    public Map<String, Boolean> status() {
        return voiceAssistantService.status();
    }
}
