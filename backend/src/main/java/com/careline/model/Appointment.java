package com.careline.model;

public record Appointment(
        String id,
        String slotId,
        String doctorId,
        String date,
        String time,
        String phone,
        String patientName,
        long created
) {
}
