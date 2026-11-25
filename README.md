# Careline - Java Voice Doctor Appointment Assistant

A Spring Boot and Vite application that lets a caller check doctor availability and book or cancel appointments.

**Stack:** Java 17, Spring Boot 3.4, Maven, Google Gemini, ElevenLabs TTS, browser speech recognition, and a vanilla JavaScript Vite dashboard.

## Pipeline

During a call, the browser transcribes speech and sends it to the Spring Boot API. Gemini, when configured, interprets the request; the backend checks availability or handles confirmed bookings and cancellations against in-memory clinic data, then returns text and optional ElevenLabs audio for playback, with browser speech synthesis as a fallback.

If Gemini is unavailable or its key is invalid, the app switches to its basic appointment flow and shows that status in the call UI.

## Run

Java 17 or later must be installed:

```powershell
java -version
```

Set the API keys in PowerShell and start the backend:

```powershell
$env:ELEVENLABS_API_KEY="..."
$env:GOOGLE_API_KEY="..."
cd backend
mvn spring-boot:run
```

The backend runs at `http://localhost:8080`.

In a second terminal:

```powershell
cd frontend
npm install
npm run dev
```

Open `http://localhost:5173`, allow microphone access in Chrome or Edge, and click **Try call**.

## Layout

- `backend/pom.xml` - Maven and Spring Boot configuration
- `backend/src/main/java/com/careline/` - Java application, REST API, clinic data, and AI integration
- `frontend/` - Vite dashboard and browser speech-recognition call UI

## API

- `GET /api/doctors`
- `GET /api/appointments`
- `POST /api/chat`
