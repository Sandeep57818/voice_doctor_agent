const $ = (id) => document.getElementById(id);
const phone = $("phone"), scrim = $("scrim"), transcript = $("transcript"), card = $("card");
const fmtDate = (d) => new Date(d + "T00:00").toLocaleDateString("en-IN", { weekday: "short", day: "numeric", month: "short" });
const fmtTime = (t) => { const [h, m] = t.split(":").map(Number); return `${((h + 11) % 12) + 1}:${String(m).padStart(2, "0")} ${h < 12 ? "AM" : "PM"}`; };

/* ---------- dashboard data ---------- */
let seenAppts = null, prevSlots = new Set(), inCall = false;
let doctorsSnapshot = "", appointmentsSnapshot = "", latestDoctors = [];

async function refresh() {
  const [docs, appts] = await Promise.all([fetch("/api/doctors").then((r) => r.json()), fetch("/api/appointments").then((r) => r.json())]);
  const nextDoctorsSnapshot = JSON.stringify(docs);
  const nextAppointmentsSnapshot = JSON.stringify(appts);
  if (nextDoctorsSnapshot !== doctorsSnapshot) {
    doctorsSnapshot = nextDoctorsSnapshot;
    latestDoctors = docs;
    $("doctorCount").textContent = docs.length;
    renderDoctors(docs);
  }
  if (nextAppointmentsSnapshot !== appointmentsSnapshot) {
    appointmentsSnapshot = nextAppointmentsSnapshot;
    renderAppts(appts);
  }
}

function renderDoctors(docs) {
  const all = new Set(docs.flatMap((d) => d.slots.map((s) => s.slotId || s.slot_id)));
  const freed = new Set([...all].filter((s) => prevSlots.size && !prevSlots.has(s)));
  $("slotCount").textContent = all.size;
  const query = $("doctorFilter").value.trim().toLowerCase();
  const filtered = docs.filter((d) => `${d.name} ${d.speciality}`.toLowerCase().includes(query));
  $("doctors").innerHTML = filtered.length ? filtered.map((d) => {
    const byDay = {};
    d.slots.forEach((s) => (byDay[s.date] ||= []).push(s));
    const days = Object.entries(byDay).slice(0, 3).map(([date, slots]) => {
      const visibleSlots = slots.slice(0, 4).map((slot) => {
        const slotId = slot.slotId || slot.slot_id;
        return `<span class="chip ${freed.has(slotId) ? "new" : ""}">${fmtTime(slot.time)}</span>`;
      }).join("");
      const more = slots.length > 4 ? `<span class="chip">+${slots.length - 4}</span>` : "";
      return `<div class="day"><i>${fmtDate(date)}</i><div class="day-slots">${visibleSlots}${more}</div></div>`;
    }).join("");
    const initials = d.name.replace(/^Dr\.\s*/i, "").split(/\s+/).slice(0, 2).map((part) => part[0]).join("");
    return `<article class="doc">
      <header><span class="doc-avatar">${initials}</span><b>${d.name}</b><span class="sp">${d.speciality}</span></header>
      <div class="doc-availability"><span>AVAILABLE THIS WEEK</span><b>${d.slots.length} TIMES</b></div>
      <div class="days">${days || `<span class="no-slots">No open times this week</span>`}</div>
    </article>`;
  }).join("") : `<div class="no-results">No specialists match “${query}”. Try a name or specialty.</div>`;
  prevSlots = all;
}

function renderAppts(appts) {
  $("apptCount").textContent = appts.length;
  const ids = new Set(appts.map((a) => a.id));
  if (seenAppts) {
    appts.filter((a) => !seenAppts.has(a.id)).forEach((a) => inCall && showCard("Booked", a.doctor_name, `${fmtDate(a.date)} · ${fmtTime(a.time)} · ${a.id}`));
    [...seenAppts].filter((id) => !ids.has(id)).forEach(() => inCall && showCard("Cancelled", "Appointment cancelled", "Slot released", true));
  }
  $("appts").innerHTML = appts.length ? appts.map((a) =>
    `<article class="row">
      <div class="row-top"><span class="id">${a.id}</span><span class="row-status">Confirmed</span></div>
      <b>${a.patient_name}</b>
      <span class="when">${fmtDate(a.date)}<br>${fmtTime(a.time)}</span>
      <span class="meta">${a.doctor_name} · ${a.speciality} · +91 ${a.phone}</span>
    </article>`).join("")
    : `<div class="empty"><span class="empty-mark">＋</span><b>A little room for care.</b><span>New appointments will appear here.</span></div>`;
  seenAppts = ids;
}

function showCard(kind, title, sub, cx) {
  card.className = "card on" + (cx ? " cx" : "");
  card.innerHTML = `<small>${kind}</small><b>${title}</b><span>${sub}</span>`;
  setTimeout(() => (card.className = "card"), 9000);
}

/* ---------- sounds ---------- */
let ac, ringTimer;
const tone = (f, d, v = 0.08) => { ac ||= new AudioContext(); const o = ac.createOscillator(), g = ac.createGain(); o.frequency.value = f; g.gain.setValueAtTime(v, ac.currentTime); g.gain.exponentialRampToValueAtTime(0.0001, ac.currentTime + d); o.connect(g).connect(ac.destination); o.start(); o.stop(ac.currentTime + d); };
const ring = () => { const r = () => { tone(440, 0.5); tone(480, 0.5); }; r(); ringTimer = setInterval(r, 1800); };
const stopRing = () => clearInterval(ringTimer);

/* ---------- waveform ---------- */
const cv = $("wave"), g = cv.getContext("2d");
let level = 0, target = 0, t0 = 0;
(function draw(t) {
  level += (target - level) * 0.25;
  g.clearRect(0, 0, cv.width, cv.height);
  const n = 40, w = cv.width / n;
  g.fillStyle = "#e8794f";
  for (let i = 0; i < n; i++) {
    const wob = 0.5 + 0.5 * Math.sin(t / 220 + i * 0.6);
    const h = 6 + level * (cv.height - 10) * (0.35 + 0.65 * wob);
    g.beginPath(); g.roundRect(i * w + 3, (cv.height - h) / 2, w - 6, h, 4); g.fill();
  }
  requestAnimationFrame(draw);
})(0);

/* ---------- transcript ---------- */
let interim = null;
function addMsg(role, text, isInterim) {
  let el = role === "user" && interim ? interim : null;
  const last = transcript.lastElementChild;
  if (!el && role === "bot" && last?.classList.contains("bot") && !last.dataset.closed) el = last;
  if (!el) { el = document.createElement("div"); el.className = `msg ${role}`; transcript.appendChild(el); }
  if (role === "bot" && el === last && el.textContent) el.textContent += " " + text; else el.textContent = text;
  el.classList.toggle("interim", !!isInterim);
  interim = role === "user" && isInterim ? el : null;
  if (role === "user" && !isInterim && transcript.lastElementChild?.previousElementSibling?.classList.contains("bot")) {}
  if (role === "user") transcript.querySelectorAll(".bot").forEach((b) => (b.dataset.closed = 1));
  transcript.scrollTop = transcript.scrollHeight;
}

/* ---------- call ---------- */
let recognition, timer, secs = 0, muted = false, recognitionActive = false, messagePending = false;
const setStatus = (s) => ($("status").textContent = s);

async function startCall() {
  if (inCall) return;
  inCall = true; secs = 0; transcript.innerHTML = ""; card.className = "card"; $("timer").textContent = "00:00";
  phone.classList.add("on", "ringing"); scrim.classList.add("on"); setStatus("Calling…"); ring();

  try {
    stopRing();
    tone(880, 0.15);
    phone.classList.remove("ringing");
    setStatus("Connected");
    timer = setInterval(() => { secs++; $("timer").textContent = `${String(Math.floor(secs / 60)).padStart(2, "0")}:${String(secs % 60).padStart(2, "0")}`; }, 1000);

    const SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition;
    if (!SpeechRecognition) {
      setStatus("Speech recognition unavailable");
      return;
    }

    recognition = new SpeechRecognition();
    recognition.continuous = true;
    recognition.interimResults = true;
    recognition.lang = "en-IN";
    recognition.onresult = (event) => {
      if (!inCall || muted || botTalking || messagePending) return;
      let interimText = "";
      const finalTexts = [];
      for (let i = event.resultIndex; i < event.results.length; i++) {
        const text = event.results[i][0].transcript;
        if (event.results[i].isFinal) finalTexts.push(text);
        else interimText += text;
      }
      if (interimText) addMsg("user", interimText, true);
      if (finalTexts.length) {
        const message = finalTexts.join(" ").trim();
        if (message) sendMessage(message);
      }
    };
    recognition.onerror = (event) => {
      if (event.error !== "no-speech" && event.error !== "aborted") setStatus(`Microphone: ${event.error}`);
    };
    recognition.onstart = () => { recognitionActive = true; };
    recognition.onend = () => {
      recognitionActive = false;
      startRecognitionWhenReady();
    };
    await fetch("/api/chat/reset", { method: "POST" });
    await sendMessage("The caller just connected. Greet them.", true);
  } catch (e) { console.error(e); setStatus("Couldn't connect"); setTimeout(endCall, 1500); }
}

let speaking = null;

function startRecognitionWhenReady() {
  if (!inCall || muted || botTalking || messagePending || !recognition || recognitionActive) return;
  try { recognition.start(); } catch (error) {
    if (error.name !== "InvalidStateError") console.error(error);
  }
}

async function sendMessage(message, greeting = false) {
  if (messagePending || !inCall) return;
  messagePending = true;
  if (recognitionActive) {
    try { recognition.stop(); } catch {}
  }
  if (!greeting) addMsg("user", message, false);
  setStatus("Thinking…");
  let data;
  try {
    const response = await fetch("/api/chat", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ message }),
    });
    if (!response.ok) throw new Error(`Chat failed: ${response.status}`);
    data = await response.json();
  } catch (error) {
    console.error(error);
    data = { reply: "The Careline backend is unavailable. Please restart the Java server." };
  }
  addMsg("bot", data.reply);
  setStatus(data.aiFallback ? "AI unavailable · basic mode" : "Connected");

  try {
    if (data.audioBase64) {
      if (speaking) speaking.pause();
      const bytes = Uint8Array.from(atob(data.audioBase64), (c) => c.charCodeAt(0));
      const url = URL.createObjectURL(new Blob([bytes], { type: data.mimeType || "audio/mpeg" }));
      speaking = new Audio(url);
      botTalking = true;
      await new Promise((resolve) => {
        speaking.onended = () => { URL.revokeObjectURL(url); resolve(); };
        speaking.onerror = () => { URL.revokeObjectURL(url); resolve(); };
        speaking.play().catch(() => resolve());
      });
    } else if ("speechSynthesis" in window) {
      await new Promise((resolve) => {
        const utterance = new SpeechSynthesisUtterance(data.reply);
        utterance.lang = "en-IN";
        botTalking = true;
        utterance.onend = resolve;
        utterance.onerror = resolve;
        speechSynthesis.cancel();
        speechSynthesis.speak(utterance);
      });
    }
  } finally {
    botTalking = false;
    messagePending = false;
    startRecognitionWhenReady();
  }
}
let botTalking = false;

async function endCall() {
  if (!inCall) return;
  stopRing(); clearInterval(timer); tone(330, 0.35); target = 0;
  inCall = false; muted = false;
  $("muteBtn").classList.remove("on"); $("muteBtn").textContent = "Mute";
  setStatus(`Call ended · ${$("timer").textContent}`); phone.classList.remove("ringing");
  try { recognition?.stop(); } catch {}
  try { speaking?.pause(); } catch {}
  if ("speechSynthesis" in window) speechSynthesis.cancel();
  fetch("/api/chat/reset", { method: "POST" }).catch(console.error);
  setTimeout(() => { phone.classList.remove("on"); scrim.classList.remove("on"); refresh(); }, 1400);
}

$("callBtn").onclick = startCall;
$("welcomeCall").onclick = startCall;
$("endBtn").onclick = endCall;
$("closeCall").onclick = endCall;
$("doctorFilter").addEventListener("input", () => renderDoctors(latestDoctors));
$("muteBtn").onclick = () => {
  if (!inCall) return;
  muted = !muted;
  $("muteBtn").classList.toggle("on", muted);
  $("muteBtn").textContent = muted ? "Unmute" : "Mute";
  if (muted) {
    try { recognition?.stop(); } catch {}
  } else {
    startRecognitionWhenReady();
  }
};

$("todayDate").textContent = new Intl.DateTimeFormat("en-IN", {
  weekday: "long", day: "numeric", month: "long",
}).format(new Date());

refresh();
setInterval(refresh, 2500);
