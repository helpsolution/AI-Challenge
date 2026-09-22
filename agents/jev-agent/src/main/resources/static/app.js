const samples = {
  outage: "После вчерашнего релиза у нескольких клиентов не открывается отчёт: сервер отвечает 500. Мы воспроизвели ошибку на двух аккаунтах. Клиенты не могут завершить сверку, посмотрите сегодня?",
  docs: "Здравствуйте! Подскажите, где скачать закрывающие документы за август? В кабинете не могу найти нужный раздел.",
  ambiguous: "Клиент пишет, что иногда пропадают данные из отчёта. Поддержка пока не смогла воспроизвести проблему; возможно, дело в фильтрах. Как нам лучше проверить?",
};

const topicLabels = {
  product_bug: "Ошибка в продукте",
  billing: "Оплата и счета",
  how_to: "Вопрос по использованию",
  feature_request: "Пожелание к продукту",
  other: "Другое / неясно",
};
const impactLabels = [
  "Влияние не указано или неизвестно",
  "Неудобство для одного пользователя",
  "Основная задача заблокирована или затронуты несколько пользователей",
  "Критичный массовый сбой или риск для данных и денег",
];

const $ = (id) => document.getElementById(id);
const form = $("analysis-form");
const message = $("message");
let exactRequest = "";
let exactResponse = "";

function percentage(value) {
  return `${Math.round(Math.max(0, Math.min(1, Number(value) || 0)) * 100)}%`;
}

function prettyJson(raw) {
  if (!raw) return "Ответ не получен.";
  try { return JSON.stringify(JSON.parse(raw), null, 2); }
  catch { return raw; }
}

function setError(text) {
  $("empty-state").classList.add("hidden");
  $("result-content").classList.add("hidden");
  $("error-state").textContent = text;
  $("error-state").classList.remove("hidden");
}

function showTrace(data) {
  exactRequest = data.requestJson || "";
  exactResponse = data.responseJson || "";
  $("request-json").textContent = prettyJson(exactRequest);
  $("response-json").textContent = prettyJson(exactResponse);
  $("copy-request").disabled = !exactRequest;
  $("copy-response").disabled = !exactResponse;
  const status = data.upstreamStatus ? `HTTP ${data.upstreamStatus}` : "нет HTTP-ответа";
  $("trace-meta").replaceChildren();
  [data.endpoint ? `POST ${data.endpoint}` : "POST /v1/systemone", status, `${data.durationMs ?? 0} мс`].forEach((label) => {
    const span = document.createElement("span");
    span.textContent = label;
    $("trace-meta").append(span);
  });
}

function appendDistribution(container, entries, selected, labelFor) {
  container.replaceChildren();
  entries.sort((a, b) => Number(b[1]) - Number(a[1])).forEach(([key, value]) => {
    const row = document.createElement("div");
    row.className = `distribution-row${key === selected ? " selected" : ""}`;
    const label = document.createElement("span");
    label.className = "distribution-label";
    label.textContent = labelFor(key);
    const track = document.createElement("span");
    track.className = "distribution-track";
    const fill = document.createElement("span");
    fill.className = "distribution-fill";
    fill.style.width = percentage(value);
    track.append(fill);
    const amount = document.createElement("strong");
    amount.textContent = percentage(value);
    row.append(label, track, amount);
    container.append(row);
  });
}

function renderResults(raw) {
  const parsed = JSON.parse(raw);
  const answers = parsed.answers || {};
  const topic = answers.topic;
  const impact = answers.reported_impact;
  const engineering = answers.engineering_needed;
  if (topic?.type !== "choice" || impact?.type !== "score" || engineering?.type !== "noul" ||
      !topic.probabilities || !impact.probabilities || !Number.isFinite(impact.score) || !Number.isFinite(engineering.noul)) {
    throw new Error("TypeSafe вернул ответ без ожидаемых полей. Посмотрите JSON ниже.");
  }

  $("topic-title").textContent = topicLabels[topic.choice] || topic.choice;
  $("topic-confidence").textContent = `Уверенность ${percentage(topic.confidence)}`;
  appendDistribution($("topic-distribution"), Object.entries(topic.probabilities), topic.choice, (key) => topicLabels[key] || key);

  const clampedImpact = Math.max(0, Math.min(3, impact.score));
  $("impact-score").textContent = clampedImpact.toFixed(2);
  $("impact-fill").style.width = `${(clampedImpact / 3) * 100}%`;
  $("impact-marker").style.left = `${(clampedImpact / 3) * 100}%`;
  $("impact-description").textContent = impactLabels[Math.round(clampedImpact)];
  $("impact-confidence").textContent = `Уверенность ${percentage(impact.confidence)}`;
  appendDistribution($("impact-distribution"), Object.entries(impact.probabilities), String(Math.round(clampedImpact)), (key) => `Уровень ${key}`);

  const probability = Math.max(0, Math.min(1, engineering.noul));
  $("engineering-value").textContent = percentage(probability);
  $("engineering-gauge").style.setProperty("--gauge-value", `${probability * 100}%`);
  $("engineering-description").textContent = probability >= 0.7 ? "Вероятно, нужна техническая проверка" : probability >= 0.35 ? "Неоднозначно — стоит уточнить детали" : "Похоже, поддержка сможет помочь сама";

  const tokenCount = parsed.usage?.input_tokens;
  $("result-meta").textContent = `${parsed.model || "JEV"}${Number.isFinite(tokenCount) ? ` · ${tokenCount} входных токенов` : ""}`;
  $("error-state").classList.add("hidden");
  $("empty-state").classList.add("hidden");
  $("result-content").classList.remove("hidden");
}

async function analyze(event) {
  event.preventDefault();
  const text = message.value;
  if (!text.trim()) { message.focus(); return; }
  const button = $("analyze-button");
  button.disabled = true;
  $("button-label").textContent = "Анализируем…";
  $("result-meta").textContent = "Запрос выполняется";
  try {
    const response = await fetch("/api/analyze", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ text }),
    });
    const data = await response.json();
    if (data.requestJson) showTrace(data);
    if (!response.ok) throw new Error(data.error || `Ошибка HTTP ${response.status}`);
    renderResults(data.responseJson);
  } catch (error) {
    setError(error.message || "Не удалось получить ответ.");
    $("result-meta").textContent = "Ошибка запроса";
  } finally {
    button.disabled = false;
    $("button-label").textContent = "Проанализировать";
  }
}

form.addEventListener("submit", analyze);
message.addEventListener("input", () => { $("char-count").textContent = message.value.length; });
document.querySelectorAll("[data-sample]").forEach((button) => {
  button.addEventListener("click", () => {
    message.value = samples[button.dataset.sample];
    message.dispatchEvent(new Event("input"));
    message.focus();
    document.querySelectorAll("[data-sample]").forEach((item) => item.classList.toggle("active", item === button));
  });
});
[["copy-request", () => exactRequest], ["copy-response", () => exactResponse]].forEach(([id, getValue]) => {
  $(id).addEventListener("click", async () => {
    await navigator.clipboard.writeText(getValue());
    const button = $(id);
    button.textContent = "Скопировано ✓";
    setTimeout(() => { button.textContent = "Копировать"; }, 1500);
  });
});

fetch("/api/status").then((response) => response.json()).then((data) => {
  $("model-badge").textContent = data.model;
  $("connection-status").innerHTML = "";
  const dot = document.createElement("span");
  dot.className = "status-dot";
  $("connection-status").append(dot, document.createTextNode(data.configured ? " Ключ настроен" : " Нужен API-ключ"));
  $("connection-status").classList.toggle("unconfigured", !data.configured);
}).catch(() => {
  $("connection-status").textContent = "Сервер недоступен";
  $("connection-status").classList.add("unconfigured");
});
