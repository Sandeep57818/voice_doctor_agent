package com.careline.data;

import com.careline.model.Appointment;
import com.careline.model.Doctor;
import com.careline.model.Slot;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class ClinicData {
    public final List<Doctor> doctors = List.of(
            new Doctor("d1", "Dr. Ananya Rao", "Cardiology"),
            new Doctor("d2", "Dr. Karthik Menon", "Dermatology"),
            new Doctor("d3", "Dr. Meera Iyer", "Pediatrics"),
            new Doctor("d4", "Dr. Imran Qureshi", "Orthopedics"),
            new Doctor("d5", "Dr. Sneha Kulkarni", "General Medicine")
    );

    private final Map<String, Slot> slots = new LinkedHashMap<>();
    private final Map<String, Appointment> appointments = new LinkedHashMap<>();
    private int counter;

    public ClinicData() {
        seedSlots();
    }

    private void seedSlots() {
        String[] times = {"09:00", "10:00", "11:30", "14:00", "15:30", "17:00"};
        LocalDate start = LocalDate.now().plusDays(1);
        for (int i = 0; i < doctors.size(); i++) {
            for (int day = 0; day < 5; day++) {
                LocalDate date = start.plusDays(day);
                if (date.getDayOfWeek().getValue() == 7) continue;
                for (int j = 0; j < times.length; j++) {
                    if ((i + day + j) % 3 == 0) continue;
                    Doctor doctor = doctors.get(i);
                    String slotId = doctor.id() + "-" + date + "-" + times[j];
                    slots.put(slotId, new Slot(slotId, doctor.id(), date.toString(), times[j]));
                }
            }
        }
    }

    public synchronized List<Map<String, Object>> doctorsWithSlots() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Doctor doctor : doctors) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", doctor.id());
            row.put("name", doctor.name());
            row.put("speciality", doctor.speciality());
            row.put("slots", listSlots(doctor.id(), null));
            result.add(row);
        }
        return result;
    }

    public synchronized List<Slot> listSlots(String doctorId, String date) {
        Set<String> taken = appointments.values().stream().map(Appointment::slotId).collect(java.util.stream.Collectors.toSet());
        return slots.values().stream()
                .filter(slot -> !taken.contains(slot.slotId()))
                .filter(slot -> doctorId == null || slot.doctorId().equals(doctorId))
                .filter(slot -> date == null || slot.date().equals(date))
                .sorted(java.util.Comparator.comparing(Slot::date).thenComparing(Slot::time))
                .toList();
    }

    public Doctor findDoctor(String query) {
        if (query == null || query.isBlank()) return null;
        String q = query.toLowerCase().replace("dr.", "").replace("dr ", "").trim();
        return doctors.stream().filter(d ->
                q.contains(d.speciality().toLowerCase())
                        || d.speciality().toLowerCase().contains(q)
                        || d.name().toLowerCase().contains(q)
                        || q.contains(d.name().toLowerCase())).findFirst().orElse(null);
    }

    public Doctor doctorById(String id) {
        return doctors.stream().filter(d -> d.id().equals(id)).findFirst().orElse(null);
    }

    public synchronized Map<String, Object> book(String slotId, String phone, String patientName) {
        Slot slot = slots.get(slotId);
        if (slot == null) return Map.of("ok", false, "error", "Unknown slot.");
        if (appointments.values().stream().anyMatch(a -> a.slotId().equals(slotId))) {
            return Map.of("ok", false, "error", "That slot was just taken.");
        }
        String normalizedPhone = normalizePhone(phone);
        if (appointments.values().stream().anyMatch(a -> a.phone().equals(normalizedPhone))) {
            return Map.of("ok", false, "error", "This phone number already has an appointment. Cancel it first.");
        }
        counter++;
        String id = "A" + (1000 + counter);
        Appointment appointment = new Appointment(id, slotId, slot.doctorId(), slot.date(), slot.time(),
                normalizedPhone, patientName == null || patientName.isBlank() ? "Caller" : patientName,
                System.currentTimeMillis());
        appointments.put(id, appointment);
        Doctor doctor = doctorById(slot.doctorId());
        return Map.of("ok", true, "appointment_id", id, "doctor", doctor.name(),
                "speciality", doctor.speciality(), "date", slot.date(), "time", slot.time(), "phone", normalizedPhone);
    }

    public synchronized Map<String, Object> cancel(String phone) {
        String normalizedPhone = normalizePhone(phone);
        Appointment found = appointments.values().stream()
                .filter(a -> a.phone().equals(normalizedPhone)).findFirst().orElse(null);
        if (found == null) return Map.of("ok", false, "error", "No appointment found for this phone number.");
        appointments.remove(found.id());
        Doctor doctor = doctorById(found.doctorId());
        return Map.of("ok", true, "appointment_id", found.id(), "doctor", doctor.name(), "speciality", doctor.speciality(),
                "date", found.date(), "time", found.time());
    }

    public synchronized List<Map<String, Object>> appointments() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Appointment appointment : appointments.values()) {
            Doctor doctor = doctorById(appointment.doctorId());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", appointment.id());
            row.put("slot_id", appointment.slotId());
            row.put("doctor_id", appointment.doctorId());
            row.put("date", appointment.date());
            row.put("time", appointment.time());
            row.put("phone", appointment.phone());
            row.put("patient_name", appointment.patientName());
            row.put("created", appointment.created());
            row.put("doctor_name", doctor.name());
            row.put("speciality", doctor.speciality());
            rows.add(row);
        }
        rows.sort((a, b) -> Long.compare(((Number) b.get("created")).longValue(),
                ((Number) a.get("created")).longValue()));
        return rows;
    }

    public static String normalizePhone(String raw) {
        String digits = raw == null ? "" : raw.replaceAll("\\D", "");
        return digits.length() >= 10 ? digits.substring(digits.length() - 10) : digits;
    }
}
