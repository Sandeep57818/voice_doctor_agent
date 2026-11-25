package com.careline.service;

import com.careline.data.ClinicData;
import com.careline.model.Doctor;
import com.careline.model.Slot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class VoiceAssistantService {
    private static final Logger LOGGER = LoggerFactory.getLogger(VoiceAssistantService.class);
    private final ClinicData clinicData;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    @Value("${careline.google-api-key:}")
    private String googleApiKey;

    @Value("${careline.gemini-model:gemini-2.5-flash}")
    private String geminiModel;

    @Value("${careline.elevenlabs-api-key:}")
    private String elevenLabsApiKey;

    @Value("${careline.elevenlabs-voice-id:21m00Tcm4TlvDq8ikWAM}")
    private String elevenLabsVoiceId;

    private enum ConversationState {
        IDLE,
        SELECT_SLOT,
        ASK_NAME,
        ASK_PHONE,
        ASK_CANCEL_PHONE,
        CONFIRM_PHONE,
        CONFIRM_CANCEL_PHONE,
        POST_ACTION
    }

    private ConversationState conversationState = ConversationState.IDLE;
    private List<Slot> pendingSlots = List.of();
    private Slot selectedSlot;
    private String pendingPatientName;
    private String pendingPhone;

    public VoiceAssistantService(ClinicData clinicData, ObjectMapper objectMapper) {
        this.clinicData = clinicData;
        this.objectMapper = objectMapper;
    }

    public synchronized Map<String, Object> chat(String message) {
        String callerMessage = message == null ? "" : message;
        if (conversationState == ConversationState.POST_ACTION) {
            if (isClosingConversation(callerMessage)) {
                return handleOfflineConversation(callerMessage);
            }
            resetConversation();
        }
        if (googleApiKey == null || googleApiKey.isBlank()) {
            return offlineFallback(callerMessage);
        }
        Optional<JsonNode> geminiAction = callGemini(callerMessage);
        if (geminiAction.isEmpty()) {
            return offlineFallback(callerMessage);
        }
        JsonNode action = geminiAction.get();
        String actionType = text(action, "action", "reply");
        Map<String, Object> toolResult = new LinkedHashMap<>();

        if ("check".equalsIgnoreCase(actionType)) {
            Doctor doctor = clinicData.findDoctor(text(action, "doctor", null));
            String date = text(action, "date", null);
            List<Slot> slots = clinicData.listSlots(doctor == null ? null : doctor.id(), date);
            toolResult.put("slots", slots);
            toolResult.put("count", slots.size());
        } else if ("book".equalsIgnoreCase(actionType)) {
            toolResult.putAll(clinicData.book(
                    text(action, "slot_id", ""),
                    text(action, "phone_number", ""),
                    text(action, "patient_name", "")
            ));
            if (Boolean.TRUE.equals(toolResult.get("ok"))) {
                pendingPatientName = text(action, "patient_name", "caller");
                conversationState = ConversationState.POST_ACTION;
            }
        } else if ("cancel".equalsIgnoreCase(actionType)) {
            toolResult.putAll(clinicData.cancel(text(action, "phone_number", "")));
            if (Boolean.TRUE.equals(toolResult.get("ok"))) {
                conversationState = ConversationState.POST_ACTION;
            }
        }

        String reply = buildReply(action, toolResult);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("reply", reply);
        response.put("tool_result", toolResult);

        String audio = synthesizeSpeech(reply);
        if (audio != null) {
            response.put("audioBase64", audio);
            response.put("mimeType", "audio/mpeg");
        }
        return response;
    }

    public Map<String, Boolean> status() {
        return Map.of(
                "geminiConfigured", googleApiKey != null && !googleApiKey.isBlank(),
                "elevenLabsConfigured", elevenLabsApiKey != null && !elevenLabsApiKey.isBlank()
        );
    }

    public synchronized void startConversation() {
        resetConversation();
    }

    private Map<String, Object> handleOfflineConversation(String message) {
        String text = message == null ? "" : message.trim();
        String normalized = text.toLowerCase();
        Map<String, Object> toolResult = new LinkedHashMap<>();
        String reply;

        if (conversationState == ConversationState.POST_ACTION) {
            if (isClosingConversation(normalized)) {
                reply = pendingPatientName == null
                        ? "You're welcome. Thank you for calling Careline Clinic. Have a nice day."
                        : "You're welcome, " + pendingPatientName
                        + ". Thank you for calling Careline Clinic. Have a nice day.";
                resetConversation();
                return response(reply, toolResult);
            }
            resetConversation();
        }

        if (conversationState != ConversationState.ASK_NAME
                && normalized.matches(".*\\b(name|names|list|which)\\b.*")
                && normalized.contains("doctor")) {
            reply = doctorDirectory();
        } else if (conversationState == ConversationState.CONFIRM_CANCEL_PHONE
                && normalized.matches(".*\\b(yes|correct|confirm|cancel it|that is right|that.s right)\\b.*")) {
            Map<String, Object> cancellation = clinicData.cancel(pendingPhone);
            toolResult.putAll(cancellation);
            if (Boolean.TRUE.equals(cancellation.get("ok"))) {
                reply = "Confirmed. Appointment " + cancellation.get("appointment_id")
                        + " with " + cancellation.get("doctor") + " (" + cancellation.get("speciality")
                        + ") has been cancelled. Thank you. Is there anything else I can help you with?";
                conversationState = ConversationState.POST_ACTION;
            } else {
                reply = String.valueOf(cancellation.getOrDefault("error", "I could not cancel the appointment."));
                resetConversation();
            }
        } else if (conversationState == ConversationState.CONFIRM_CANCEL_PHONE) {
            conversationState = ConversationState.ASK_CANCEL_PHONE;
            pendingPhone = null;
            reply = "Please say the phone number again.";
        } else if (conversationState == ConversationState.CONFIRM_PHONE
                && (normalized.matches(".*\\b(yes|correct|confirm|book it|that is right|that.s right)\\b.*"))) {
            Map<String, Object> booking = clinicData.book(
                    selectedSlot.slotId(), pendingPhone, pendingPatientName);
            toolResult.putAll(booking);
            if (Boolean.TRUE.equals(booking.get("ok"))) {
                reply = "Thank you, " + pendingPatientName + ". Your appointment with "
                        + booking.get("doctor") + ", " + booking.get("speciality") + ", is booked for "
                        + booking.get("date") + " at " + booking.get("time") + ". Your appointment ID is "
                        + booking.get("appointment_id") + ". Is there anything else I can help you with?";
                conversationState = ConversationState.POST_ACTION;
            } else {
                reply = String.valueOf(booking.getOrDefault("error", "I could not book the appointment."));
                resetConversation();
            }
        } else if (conversationState == ConversationState.CONFIRM_PHONE) {
            conversationState = ConversationState.ASK_PHONE;
            pendingPhone = null;
            reply = "No problem. Please say the phone number again, and I will read it back.";
        } else if (conversationState == ConversationState.ASK_CANCEL_PHONE) {
            String phone = (pendingPhone == null ? "" : pendingPhone) + extractPhoneDigits(text);
            if (phone.length() < 10) {
                pendingPhone = phone;
                reply = phone.isEmpty()
                        ? "I didn't catch the number. Please say all 10 digits slowly, one digit at a time."
                        : "I heard " + phone.length() + " digits: " + spacedDigits(phone)
                        + ". Please say the remaining " + (10 - phone.length()) + " digit"
                        + (phone.length() == 9 ? "" : "s") + ", or repeat the full 10 digit number.";
            } else {
                pendingPhone = ClinicData.normalizePhone(phone);
                conversationState = ConversationState.CONFIRM_CANCEL_PHONE;
                reply = "I heard " + spacedDigits(pendingPhone) + ". Is that phone number correct?";
            }
        } else if (conversationState == ConversationState.ASK_PHONE) {
            String phone = (pendingPhone == null ? "" : pendingPhone) + extractPhoneDigits(text);
            if (phone.length() < 10) {
                pendingPhone = phone;
                reply = phone.isEmpty()
                        ? "I didn't catch the number. Please say all 10 digits slowly, one digit at a time."
                        : "I heard " + phone.length() + " digits: " + spacedDigits(phone)
                        + ". Please say the remaining " + (10 - phone.length()) + " digit"
                        + (phone.length() == 9 ? "" : "s") + ", or repeat the full 10 digit number.";
            } else {
                pendingPhone = ClinicData.normalizePhone(phone);
                conversationState = ConversationState.CONFIRM_PHONE;
                reply = "I heard " + spacedDigits(pendingPhone) + ". Is that phone number correct?";
            }
        } else if (conversationState == ConversationState.ASK_NAME) {
            String name = text.replaceAll("(?i)^my name is\\s*", "").replaceAll("[,.]$", "").trim();
            if (name.isBlank()) {
                reply = "Please tell me the patient's name.";
            } else {
                pendingPatientName = name;
                conversationState = ConversationState.ASK_PHONE;
                reply = "Thank you, " + name + ". What 10 digit phone number should I use?";
            }
        } else if (conversationState == ConversationState.SELECT_SLOT) {
            selectedSlot = selectSlot(normalized);
            if (selectedSlot == null) {
                reply = "Please choose a slot by saying first, morning, afternoon, evening, or one of the listed times.";
            } else {
                conversationState = ConversationState.ASK_NAME;
                Doctor doctor = clinicData.doctorById(selectedSlot.doctorId());
                reply = doctor.name() + " is a " + doctor.speciality() + " specialist. The "
                        + selectedSlot.time() + " slot on " + selectedSlot.date()
                        + " is selected. What is the patient's name?";
            }
        } else if (normalized.contains("cancel")) {
            conversationState = ConversationState.ASK_CANCEL_PHONE;
            reply = "Please tell me the 10 digit phone number used for the appointment.";
        } else if (normalized.contains("appointment") && normalized.matches(".*\\b(book|booking|schedule|reserve)\\b.*")) {
            reply = "Which doctor or specialty would you like to book?";
        } else if (normalized.matches(".*\\b(name|names|list|doctors)\\b.*")
                && !normalized.contains("name is")) {
            reply = doctorDirectory();
        } else if (normalized.contains("cardio")
                || normalized.contains("dermat")
                || normalized.contains("pedi")
                || normalized.contains("ortho")
                || normalized.contains("general")
                || normalized.contains("doctor")
                || normalized.contains("available")
                || normalized.contains("availability")
                || normalized.contains("slot")) {
            Doctor doctor = findDoctor(normalized);
            pendingSlots = clinicData.listSlots(doctor == null ? null : doctor.id(), null);
            toolResult.put("count", pendingSlots.size());
            toolResult.put("slots", pendingSlots);

            if (pendingSlots.isEmpty()) {
                reply = "There are no open slots for that request.";
                conversationState = ConversationState.IDLE;
            } else {
                conversationState = ConversationState.SELECT_SLOT;
                reply = "I found " + pendingSlots.size() + " open slots. "
                        + pendingSlots.stream().limit(3).map(slot ->
                                describeSlot(slot)).reduce((a, b) -> a + "; " + b)
                                .orElse("") + ". Please choose a doctor and a listed time, or say morning, afternoon, or evening.";
            }
        } else if (normalized.contains("hello") || normalized.contains("hi")
                || normalized.contains("connected") || normalized.isBlank()) {
            reply = "Hello, welcome to Careline Clinic. I can help you check availability, book an appointment, or cancel an appointment.";
        } else {
            reply = "I can help with doctor availability, booking, or cancellation. Please say the doctor specialty or name.";
        }

        return response(reply, toolResult);
    }

    private Map<String, Object> response(String reply, Map<String, Object> toolResult) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("reply", reply);
        response.put("tool_result", toolResult);
        String audio = synthesizeSpeech(reply);
        if (audio != null) {
            response.put("audioBase64", audio);
            response.put("mimeType", "audio/mpeg");
        }
        return response;
    }

    private Map<String, Object> offlineFallback(String message) {
        Map<String, Object> response = handleOfflineConversation(message);
        response.put("aiFallback", true);
        return response;
    }

    private String doctorDirectory() {
        return "Our doctors are " + clinicData.doctors.stream()
                .map(doctor -> doctor.name() + ", " + doctor.speciality())
                .reduce((first, next) -> first + "; " + next)
                .orElse("no doctors are currently listed") + ".";
    }

    private String describeSlot(Slot slot) {
        Doctor doctor = clinicData.doctorById(slot.doctorId());
        return doctor.name() + " (" + doctor.speciality() + "), " + slot.date() + " at " + slot.time();
    }

    private boolean isClosingConversation(String message) {
        return message.toLowerCase().matches(
                ".*\\b(no|no thanks|that is all|that’s all|thats all|goodbye|bye|thank you)\\b.*");
    }

    private Doctor findDoctor(String normalized) {
        if (normalized.contains("cardio")) return clinicData.findDoctor("Cardiology");
        if (normalized.contains("dermat")) return clinicData.findDoctor("Dermatology");
        if (normalized.contains("pedi")) return clinicData.findDoctor("Pediatrics");
        if (normalized.contains("ortho")) return clinicData.findDoctor("Orthopedics");
        if (normalized.contains("general")) return clinicData.findDoctor("General Medicine");
        for (Doctor doctor : clinicData.doctors) {
            if (normalized.contains(doctor.name().toLowerCase())) return doctor;
        }
        return null;
    }

    private Slot selectSlot(String normalized) {
        if (pendingSlots.isEmpty()) return null;

        if (normalized.contains("morning")) {
            return pendingSlots.stream()
                    .filter(slot -> Integer.parseInt(slot.time().substring(0, 2)) < 12)
                    .findFirst().orElse(null);
        }
        if (normalized.contains("afternoon")) {
            return pendingSlots.stream()
                    .filter(slot -> {
                        int hour = Integer.parseInt(slot.time().substring(0, 2));
                        return hour >= 12 && hour < 17;
                    })
                    .findFirst().orElse(null);
        }
        if (normalized.contains("evening") || normalized.contains("night")) {
            return pendingSlots.stream()
                    .filter(slot -> Integer.parseInt(slot.time().substring(0, 2)) >= 17)
                    .findFirst().orElse(null);
        }

        Matcher timeMatcher = Pattern.compile("\\b(\\d{1,2})(?:[:.]?([0-9]{2}))?\\b").matcher(normalized);
        while (timeMatcher.find()) {
            int hour = Integer.parseInt(timeMatcher.group(1));
            String minutes = timeMatcher.group(2);
            for (Slot slot : pendingSlots) {
                String[] parts = slot.time().split(":");
                int slotHour = Integer.parseInt(parts[0]);
                int slotMinute = Integer.parseInt(parts[1]);
                if (slotHour == hour && (minutes == null || slotMinute == Integer.parseInt(minutes))) {
                    return slot;
                }
            }
        }

        if (normalized.matches(".*\\b(first|first one|first slot|go|take it|okay|ok|choose)\\b.*")
                || normalized.contains("that slot") || normalized.contains("the slot")) {
            return pendingSlots.get(0);
        }
        return null;
    }

    private void resetConversation() {
        conversationState = ConversationState.IDLE;
        pendingSlots = List.of();
        selectedSlot = null;
        pendingPatientName = null;
        pendingPhone = null;
    }

    private String extractPhoneDigits(String spoken) {
        String normalized = spoken.toLowerCase().replaceAll("[^a-z0-9+]", " ");
        StringBuilder digits = new StringBuilder();
        Map<String, String> numberWords = Map.ofEntries(
                Map.entry("zero", "0"), Map.entry("oh", "0"), Map.entry("one", "1"),
                Map.entry("two", "2"), Map.entry("three", "3"), Map.entry("four", "4"),
                Map.entry("five", "5"), Map.entry("six", "6"), Map.entry("seven", "7"),
                Map.entry("eight", "8"), Map.entry("nine", "9")
        );
        for (String token : normalized.split("\\s+")) {
            if (token.matches("\\d+")) {
                digits.append(token);
            } else if (numberWords.containsKey(token)) {
                digits.append(numberWords.get(token));
            }
        }
        String result = digits.toString();
        if (result.length() > 10 && result.startsWith("91")) {
            return result.substring(result.length() - 10);
        }
        return result;
    }

    private String spacedDigits(String digits) {
        return digits.chars()
                .mapToObj(Character::toString)
                .reduce("", (left, right) -> left + right + " ")
                .trim();
    }

    private Optional<JsonNode> callGemini(String message) {
        if (googleApiKey == null || googleApiKey.isBlank()) {
            String normalized = message.toLowerCase();
            ObjectNode fallback = objectMapper.createObjectNode();
            fallback.put("action", "reply");
            if (normalized.contains("cardio")) {
                fallback.put("action", "check").put("doctor", "Cardiology").putNull("date");
            } else if (normalized.contains("dermat")) {
                fallback.put("action", "check").put("doctor", "Dermatology").putNull("date");
            } else if (normalized.contains("pedi")) {
                fallback.put("action", "check").put("doctor", "Pediatrics").putNull("date");
            } else if (normalized.contains("ortho")) {
                fallback.put("action", "check").put("doctor", "Orthopedics").putNull("date");
            } else if (normalized.contains("general") || normalized.contains("doctor")) {
                fallback.put("action", "check").putNull("doctor").putNull("date");
            } else if (normalized.contains("cancel")) {
                fallback.put("reply", "Please tell me the phone number you used for your appointment.");
            } else if (normalized.contains("hello") || normalized.contains("hi")
                    || normalized.contains("connected") || normalized.isBlank()) {
                fallback.put("reply", "Hello, welcome to Careline Clinic. I can help you check a doctor, book an appointment, or cancel an appointment.");
            } else {
                fallback.put("reply", "I am ready to help with availability, booking, or cancellation.");
            }
            return Optional.of(fallback);
        }

        ObjectNode request = objectMapper.createObjectNode();
        ArrayNode contents = request.putArray("contents");
        ObjectNode content = contents.addObject();
        ArrayNode parts = content.putArray("parts");

        String prompt = """
                You are Maya, the voice receptionist for Careline Clinic.
                Today is %s.
                Speak warmly in 1-2 short sentences. Return one JSON object and no markdown.
                Required keys: action, reply.
                action must be reply, check, book, or cancel.
                For check, include doctor and date (YYYY-MM-DD or null).
                For book, include slot_id, phone_number, patient_name.
                For cancel, include phone_number.
                Before booking or cancelling, the caller must confirm their phone number.
                Available doctors: Dr. Ananya Rao Cardiology, Dr. Karthik Menon Dermatology,
                Dr. Meera Iyer Pediatrics, Dr. Imran Qureshi Orthopedics,
                Dr. Sneha Kulkarni General Medicine.
                The caller said: %s
                """.formatted(LocalDate.now(), message.replace("\"", "\\\""));
        parts.addObject().put("text", prompt);
        ObjectNode generationConfig = request.putObject("generationConfig");
        generationConfig.put("temperature", 0.2);
        generationConfig.put("responseMimeType", "application/json");

        try {
            HttpRequest requestBody = HttpRequest.newBuilder()
                    .uri(URI.create("https://generativelanguage.googleapis.com/v1beta/models/"
                            + geminiModel + ":generateContent"))
                    .header("x-goog-api-key", googleApiKey)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(request)))
                    .build();
            HttpResponse<String> response = httpClient.send(requestBody, HttpResponse.BodyHandlers.ofString());
            JsonNode root = objectMapper.readTree(response.body());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String apiMessage = root.path("error").path("message").asText("");
                LOGGER.warn("Gemini request failed with HTTP {}: {}", response.statusCode(), apiMessage);
                return Optional.empty();
            }
            String generated = root.path("candidates").path(0).path("content").path("parts").path(0)
                    .path("text").asText("");
            generated = generated.replace("```json", "").replace("```", "").trim();
            int start = generated.indexOf('{');
            int end = generated.lastIndexOf('}');
            if (start >= 0 && end > start) {
                return Optional.of(objectMapper.readTree(generated.substring(start, end + 1)));
            }
            LOGGER.warn("Gemini returned no usable action; falling back to the basic assistant");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Gemini request was interrupted; falling back to the basic assistant", exception);
        } catch (Exception exception) {
            LOGGER.warn("Gemini request could not be completed; falling back to the basic assistant", exception);
        }
        return Optional.empty();
    }

    private String buildReply(JsonNode action, Map<String, Object> toolResult) {
        String actionType = text(action, "action", "reply");
        if ("check".equalsIgnoreCase(actionType)) {
            @SuppressWarnings("unchecked")
            List<Slot> slots = (List<Slot>) toolResult.getOrDefault("slots", List.of());
            if (slots.isEmpty()) return "There are no open slots matching that request.";
            return "I found " + slots.size() + " open slots. " +
                    slots.stream().limit(3).map(this::describeSlot).reduce((a, b) -> a + "; " + b)
                            .orElse("") + ".";
        }
        if ("book".equalsIgnoreCase(actionType)) {
            if (Boolean.TRUE.equals(toolResult.get("ok"))) {
                return "Thank you, " + text(action, "patient_name", "caller") + ". Your appointment with "
                        + toolResult.get("doctor") + ", " + toolResult.get("speciality") + ", is booked for "
                        + toolResult.get("date") + " at " + toolResult.get("time")
                        + ". Your appointment ID is " + toolResult.get("appointment_id")
                        + ". Is there anything else I can help you with?";
            }
            return String.valueOf(toolResult.getOrDefault("error", "I could not book that appointment."));
        }
        if ("cancel".equalsIgnoreCase(actionType)) {
            if (Boolean.TRUE.equals(toolResult.get("ok"))) {
                return "Your appointment with " + toolResult.get("doctor") + " ("
                        + toolResult.get("speciality") + ") has been cancelled. Thank you. "
                        + "Is there anything else I can help you with?";
            }
            return String.valueOf(toolResult.getOrDefault("error", "I could not find that appointment."));
        }
        return text(action, "reply", "How can I help?");
    }

    private String synthesizeSpeech(String text) {
        if (elevenLabsApiKey == null || elevenLabsApiKey.isBlank()) return null;
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("text", text);
            body.put("model_id", "eleven_turbo_v2_5");

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.elevenlabs.io/v1/text-to-speech/" + elevenLabsVoiceId))
                    .header("xi-api-key", elevenLabsApiKey)
                    .header("Content-Type", "application/json")
                    .header("Accept", "audio/mpeg")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return Base64.getEncoder().encodeToString(response.body());
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private String text(JsonNode node, String field, String fallback) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) return fallback;
        String text = value.asText("");
        return text.isBlank() ? fallback : text;
    }
}
