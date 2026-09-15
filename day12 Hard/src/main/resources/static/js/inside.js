'use strict';

const $ = id => document.getElementById(id);
const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const short = (value, length = 110) => String(value ?? '').length > length ? String(value).slice(0, length) + '…' : String(value ?? '');
const num = value => Number(value || 0).toLocaleString('ru-RU');
const stages = {IDEA:'Идея', THESIS:'Тезис', PLAN:'План', DRAFT:'Черновик'};
const names = {profile:'Профиль', shortTerm:'Краткосрочная память', working:'Рабочая память', longTerm:'Долговременная память', entry:'Обработка сообщения', prompt:'Сборщик промпта', parser:'Разбор ответа', router:'Маршрутизатор памяти', llm:'LLM-клиент', model:'Модель', output:'Результат шага'};
const fields = {profile:'Профиль', messages:'Сообщения', state:'Этап', idea:'Идея', thesis:'Тезис', plan:'План', draft:'Черновик', notes:'Замечания', styleSuggestion:'Предложение правила', longTerm:'Долговременные правила'};
const sourceNames = {persona:'Роль редактора', profile:'Профиль', rules:'Формат и правила ответа', longTerm:'Долговременные правила', working:'Рабочая память', shortTerm:'Окно диалога', input:'Новое сообщение'};
const eventNames = {input:'Вход', profileSelect:'Выбор профиля', prompt:'Контекст', llm:'LLM-клиент', parser:'Разбор JSON', router:'Запись памяти', output:'Готово'};
const statuses = {RUNNING:'В работе', SUCCESS:'Завершён', ERROR:'Ошибка', INTERRUPTED:'Прерван'};
const tag = (text, tone = '') => `<span class="tag ${tone}">${esc(text)}</span>`;
const paragraph = text => `<p>${esc(text || 'Пока пусто')}</p>`;
const pre = data => `<pre>${esc(typeof data === 'string' ? data : JSON.stringify(data, null, 2))}</pre>`;
const section = (title, body) => `<h3>${esc(title)}</h3>${body}`;
const time = iso => iso ? new Date(iso).toLocaleTimeString('ru-RU', {hour:'2-digit', minute:'2-digit', second:'2-digit'}) : '';
const duration = trace => trace?.finishedAt ? `${((Date.parse(trace.finishedAt) - Date.parse(trace.startedAt)) / 1000).toFixed(2)} с` : trace?.status === 'RUNNING' ? `${Math.max(0, Math.floor((Date.now() - Date.parse(trace.startedAt)) / 1000))} с` : '—';

let sessionId = null;
let mode = 'now';
let selectedTraceId = null;
let view = null;
let trace = null;
let timer = null;
let generation = 0;
let sending = false;
let detailNode = null;
const cache = new Map();

async function api(path, options = {}) {
  const response = await fetch(path, {headers:{'Content-Type':'application/json'}, ...options});
  const body = await response.json();
  if (!response.ok) throw new Error(body.error || `Ошибка ${response.status}`);
  return body;
}

function connection(ok) {
  $('connection').classList.toggle('offline', !ok);
  $('connection').querySelector('span').textContent = ok ? 'Данные обновляются' : 'Связь потеряна';
}

async function chooseSession(id) {
  generation++;
  sessionId = Number(id);
  selectedTraceId = null;
  view = trace = null;
  cache.clear();
  $('error').hidden = true;
  if ($('detail-dialog').open) $('detail-dialog').close();
  localStorage.setItem('day12-session', String(sessionId));
  const url = new URL(location.href);
  url.searchParams.set('session', sessionId);
  history.replaceState(null, '', url);
  $('chat-link').href = `/?session=${sessionId}`;
  $('map-section').hidden = true;
  await refresh();
}

async function refresh() {
  clearTimeout(timer);
  const epoch = generation;
  try {
    const sessions = await api('/api/sessions');
    if (epoch !== generation) return;
    const sessionOptions = sessions.map(s => `<option value="${s.session.id}">${esc(s.session.title)} · ${esc(s.profile?.name || 'Без профиля')}</option>`).join('');
    if ($('session-select').innerHTML !== sessionOptions) $('session-select').innerHTML = sessionOptions;
    $('empty').hidden = sessions.length > 0;
    $('map-section').hidden = !sessions.length;
    $('session-select').disabled = !sessions.length;
    if (!sessions.length) { connection(true); return; }
    if (!sessions.some(s => s.session.id === sessionId)) {
      await chooseSession(sessions[0].session.id);
      return;
    }
    $('session-select').value = String(sessionId);
    const data = await api(`/api/sessions/${sessionId}/inspection`);
    if (epoch !== generation) return;
    view = data;
    if (!data.traces.some(t => t.id === selectedTraceId)) selectedTraceId = data.traces[0]?.id || null;
    const selected = mode === 'now' ? data.traces[0] : data.traces.find(t => t.id === selectedTraceId);
    let detail = null;
    if (selected) {
      const cached = cache.get(selected.id);
      const signature = JSON.stringify(selected);
      if (cached?.signature === signature && selected.status !== 'RUNNING') detail = cached.detail;
      else {
        detail = await api(`/api/sessions/${sessionId}/inspection/traces/${selected.id}`);
        if (epoch !== generation) return;
        cache.set(selected.id, {signature, detail});
      }
    }
    trace = detail;
    render();
    connection(true);
  } catch (e) {
    if (epoch !== generation) return;
    connection(false);
    $('error').textContent = `Не удалось обновить карту: ${e.message}. Повторяем подключение…`;
    $('error').hidden = false;
  } finally {
    if (epoch === generation) timer = setTimeout(refresh, trace?.status === 'RUNNING' || sending ? 700 : 2500);
  }
}

function state() { return mode === 'step' && trace ? trace.after || trace.before : view?.state; }
function eventNode(node) { return node === 'input' || node === 'profileSelect' ? 'entry' : node; }

function render() {
  const s = state();
  if (!s) return;
  const task = s.working;
  const prompt = trace?.prompt;
  const isProfile = trace?.events.some(e => e.node === 'profileSelect');
  const running = trace?.status === 'RUNNING';
  $('error').hidden = true;
  $('map-section').hidden = false;
  $('now-mode').classList.toggle('selected', mode === 'now');
  $('now-mode').setAttribute('aria-pressed', mode === 'now');
  $('step-mode').classList.toggle('selected', mode === 'step');
  $('step-mode').setAttribute('aria-pressed', mode === 'step');
  $('trace-select').hidden = mode !== 'step';
  $('mode-note').hidden = mode !== 'now';
  const options = view.traces.map(t => `<option value="${t.id}">${time(t.startedAt)} · ${esc(short(t.input, 42))} · ${statuses[t.status]}</option>`).join('');
  if ($('trace-select').innerHTML !== options) $('trace-select').innerHTML = options || '<option>Шагов пока нет</option>';
  $('trace-select').disabled = !view.traces.length;
  if (selectedTraceId) $('trace-select').value = selectedTraceId;
  $('agent-status').textContent = trace ? statuses[trace.status] : 'Ожидание';
  $('agent-status').className = `status-pill ${running ? 'running' : trace?.error ? 'error' : ''}`;

  $('profile-content').innerHTML = `<p class="hero-value">${esc(s.profile?.name || 'Не выбран')}</p><p class="line-clamp">${esc(short(s.profile?.description || 'Агент ждёт название профиля', 115))}</p>`;
  const contextCount = mode === 'step' && prompt ? prompt.snapshot.shortTerm.length : s.contextMessageIds.length;
  const ratio = s.messageCount ? Math.min(1, contextCount / s.messageCount) : 0;
  $('short-content').innerHTML = `<p><span class="count-value">${num(s.messageCount)}</span> сообщений сохранено</p><div class="memory-bars" aria-hidden="true">${Array.from({length:24}, (_, i) => `<i class="${i >= 24 - Math.round(ratio * 24) ? 'used' : ''}"></i>`).join('')}</div><p class="quiet">${mode === 'step' ? prompt ? `В запрос вошло ${contextCount} из истории до шага` : 'На этом шаге модель не вызывалась' : `Для следующего запроса: последние ${contextCount}`}</p>`;
  $('working-content').innerHTML = `<div class="tag-row">${tag(stages[task?.state] || 'Идея', 'amber')}${task?.plan?.length ? tag(`${task.plan.length} пункта плана`, 'muted') : ''}${task?.draft ? tag('Есть черновик', 'muted') : ''}</div><p class="line-clamp" style="margin-top:5px">${esc(short(task?.thesis || task?.idea || 'Идея поста ещё не появилась', 115))}</p>${task?.styleSuggestion ? '<p class="quiet">+ правило ожидает подтверждения</p>' : ''}`;
  $('long-content').innerHTML = `<p><strong>${s.longTermTotal}</strong> правил всего · доступно контексту ${s.longTerm.length}</p>${s.longTerm.slice(0, 2).map(m => `<p class="rule-line">${esc(short(m.key + ': ' + m.value, 62))}</p>`).join('') || '<p class="quiet">Появятся после «Запомнить»</p>'}${s.longTerm.length > 2 ? `<p class="quiet">Ещё ${s.longTerm.length - 2} — внутри блока</p>` : ''}`;
  $('entry-content').innerHTML = trace ? `<p class="line-clamp">${esc(short(trace.input, 100))}</p>${isProfile ? '<p class="quiet">Локальный выбор профиля → ответ</p>' : ''}` : '<p>Ожидает новое сообщение</p>';
  const sectionSources = [...new Set(prompt?.sections?.map(b => b.source) || [])];
  $('prompt-content').innerHTML = prompt ? `<div class="tag-row">${sectionSources.map(source => tag(sourceNames[source])).join('')}</div>` : isProfile ? '<p>Пропущен: выбор профиля выполняется локально</p>' : trace?.kind === 'MEMORY' ? '<p>Для подтверждения памяти запрос не нужен</p>' : '<div class="tag-row">' + ['Роль + правила','Профиль','3 слоя памяти','Сообщение'].map(t => tag(t, 'muted')).join('') + '</div><p class="quiet">Состав будет зафиксирован при запросе</p>';
  $('prompt-volume').textContent = prompt ? `${prompt.messages.length} блоков · ${num(prompt.charsSent)} симв.` : 'Контекст для модели';
  $('parser-content').innerHTML = trace?.decision ? `<div class="tag-row">${tag('reply ✓')}${tag('taskUpdate' + (trace.decision.taskUpdate ? ' ✓' : ' ∅'))}${tag('styleSuggestion' + (trace.decision.styleSuggestion ? ' ✓' : ' ∅'))}</div>` : trace?.completion ? '<p>Ответ получен · JSON не прошёл проверку</p>' : '<div class="tag-row">' + ['reply','taskUpdate','styleSuggestion'].map(t => tag(t, 'muted')).join('') + '</div><p class="quiet">' + (trace && !prompt ? 'На этом шаге не использовался' : 'Ожидает структурированный ответ') + '</p>';
  const changes = trace?.changes || [];
  const memoryChanges = changes.filter(c => !['messages','profile'].includes(c.field));
  $('router-content').innerHTML = memoryChanges.length ? `<div class="tag-row">${memoryChanges.slice(0, 4).map(c => tag(`${fields[c.field]} ${c.after ? '+' : '−'}`, 'green')).join('')}</div>` : `<p>${trace?.events.some(e => e.node === 'router') && !running ? 'Изменений рабочей и долгой памяти нет' : 'Применяет изменения и проверяет этап'}</p><p class="quiet">Правило стиля сохраняется после подтверждения</p>`;
  const request = trace?.request;
  $('llm-content').innerHTML = request ? `<p><strong>${running && trace.events.at(-1)?.node === 'llm' ? 'Ожидает ответ' : trace.completion ? 'Ответ получен' : trace.error ? 'Вызов завершился ошибкой' : 'Запрос подготовлен'}</strong></p><p class="quiet">temperature ${request.temperature} · max ${num(request.max_tokens)} токенов</p>` : '<p>На этом шаге не вызывался</p><p class="quiet">Передаёт запрос через OpenRouter</p>';
  const modelName = trace?.completion?.model || request?.model || view.agent.model;
  $('model-content').innerHTML = `<p class="model-name">${esc(modelName)}</p><p class="quiet">${request ? trace.completion ? 'Метаданные получены от провайдера' : 'Модель указана в запросе клиента' : 'Модель из настроек агента'}</p>`;
  $('model-usage').textContent = trace?.completion?.usage ? `${num(trace.completion.usage.totalTokens)} токенов` : 'OpenRouter';
  $('output-content').innerHTML = trace?.error ? `<p class="line-clamp">${esc(trace.error)}</p>` : running ? '<p>Шаг выполняется…</p>' : trace ? `<p><strong>${isProfile ? s.profile ? 'Профиль выбран' : 'Нужно уточнить профиль' : trace.kind === 'MEMORY' ? 'Память обработана' : 'Ответ сохранён в чате'}</strong></p><p class="quiet">${memoryChanges.length ? `Изменено полей памяти: ${memoryChanges.length}` : 'Без изменений полей памяти'} · ${duration(trace)}</p>` : '<p>Здесь появится итог действия</p><p class="quiet">Полный ответ доступен в редакторе</p>';

  document.querySelectorAll('[data-node]').forEach(n => n.classList.remove('visited','active','failed','changed'));
  const mark = (id, cls) => document.querySelector(`[data-node="${id}"]`)?.classList.add(cls);
  for (const event of trace?.events || []) mark(eventNode(event.node), 'visited');
  if (prompt) ['profile','working','shortTerm', ...(prompt.snapshot.longTerm.length ? ['longTerm'] : [])].forEach(id => mark(id, 'visited'));
  if (trace?.completion) mark('model', 'visited');
  const last = trace?.events.at(-1);
  if (last && running) mark(eventNode(last.node), 'active');
  if (last && trace?.error) mark(eventNode(last.node), 'failed');
  for (const c of changes) mark(c.field === 'messages' ? 'shortTerm' : c.field === 'profile' ? 'profile' : c.field === 'longTerm' ? 'longTerm' : 'working', 'changed');
  $('activity-title').textContent = trace ? `${mode === 'now' ? 'Последнее действие' : 'Выбранный шаг'} · ${short(trace.input, 105)}` : 'Журнал начнётся с нового действия';
  $('activity-time').textContent = trace ? `${time(trace.startedAt)} · ${duration(trace)}` : '';
  $('event-trail').innerHTML = (trace?.events || []).map((e, i) => `${i ? '<span class="trail-arrow">→</span>' : ''}<span class="event-chip" title="${esc(e.label)}">${eventNames[e.node] || esc(e.node)}<small>+${((Date.parse(e.at) - Date.parse(trace.startedAt)) / 1000).toFixed(2)}с</small></span>`).join('');
  $('change-trail').innerHTML = changes.map(c => `<button class="change-chip" data-change="${esc(c.field)}">${esc(fields[c.field])}${c.field === 'state' ? `: ${esc(stages[c.before])} → ${esc(stages[c.after])}` : c.field === 'messages' ? `: ${esc(c.before)} → ${esc(c.after)}` : ' обновлено'} ↗</button>`).join('');
  $('history-note').textContent = trace?.error ? trace.error : !trace ? 'Для старой переписки доступно текущее состояние. Точные запросы и изменения записываются с момента добавления карты.' : mode === 'now' ? 'Хранилища показывают состояние сейчас. Обработчики и отметки изменений — последнее записанное действие.' : 'Хранилища показывают состояние после шага (во время выполнения — до него). В сборщике сохранён контекст, который участвовал в этом запросе. Доступны последние 100 шагов.';
  $('probe-submit').disabled = sending;
  $('probe-input').disabled = sending;
  $('probe-submit').textContent = sending ? 'В работе…' : 'Отправить ↗';
  requestAnimationFrame(drawConnections);
}

function drawConnections() {
  if (!view || $('organism').offsetWidth < 1) return;
  const root = $('organism').getBoundingClientRect();
  const box = id => {
    const r = document.querySelector(`[data-node="${id}"]`).getBoundingClientRect();
    return {left:r.left-root.left, right:r.right-root.left, top:r.top-root.top, bottom:r.bottom-root.top, y:r.top-root.top+r.height/2};
  };
  const prompt = box('prompt'), router = box('router'), llm = box('llm'), parser = box('parser'), output = box('output'), entry = box('entry');
  const paths = [];
  const arrow = '<defs><marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="5" markerHeight="5" orient="auto-start-reverse"><path d="M 0 0 L 10 5 L 0 10 z" style="fill:#9aae9f;stroke:none"/></marker></defs>';
  const line = (d, cls = '') => paths.push(`<path d="${d}" class="${cls}" marker-end="url(#arrow)"/>`);
  const used = trace?.prompt ? 'used-path' : '';
  ['profile','shortTerm','working','longTerm'].forEach((id, i) => {
    const s = box(id), lane = s.right + 14 + i*3;
    line(`M ${s.right} ${s.y-8} H ${lane} V ${prompt.y-10+i*6} H ${prompt.left}`, used);
  });
  const lane = (prompt.right + llm.left)/2;
  line(`M ${prompt.right} ${prompt.y-8} H ${lane-6} V ${llm.y-15} H ${llm.left}`, trace?.status === 'RUNNING' && trace.events.at(-1)?.node === 'llm' ? 'flowing' : used);
  line(`M ${llm.left} ${llm.y+20} H ${lane+6} V ${parser.y} H ${parser.right}`, 'return-path');
  ['shortTerm','working','longTerm'].forEach((id, i) => {
    const s = box(id), writeLane = prompt.left - 10 - i*5;
    line(`M ${router.left} ${router.y+7+i*6} H ${writeLane} V ${s.bottom-17} H ${s.right}`, 'write-path');
  });
  line(`M ${router.right} ${router.y} H ${lane} V ${output.y} H ${output.left}`, trace?.events.some(e=>e.node==='output') && !trace.events.some(e=>e.node==='profileSelect') ? 'used-path' : '');
  if (trace?.events.some(e=>e.node==='profileSelect')) {
    line(`M ${entry.right} ${entry.y} H ${lane+13} V ${output.y-15} H ${output.left}`, 'used-path');
  }
  $('connections').setAttribute('viewBox', `0 0 ${root.width} ${root.height}`);
  $('connections').innerHTML = arrow + paths.join('');
}

function detail(id) {
  if (!view) return;
  detailNode = id;
  const s = state(), task = s.working, p = trace?.prompt;
  $('detail-title').textContent = names[id] || 'Изменения памяти';
  $('detail-subtitle').textContent = mode === 'step' ? `СНИМОК ШАГА · ${time(trace?.startedAt)}` : ['profile','shortTerm','working','longTerm'].includes(id) ? 'ТЕКУЩЕЕ СОСТОЯНИЕ' : 'ПОСЛЕДНЕЕ ЗАПИСАННОЕ ДЕЙСТВИЕ';
  let body = '';
  if (id === 'profile') body = section(s.profile?.name || 'Профиль не выбран', paragraph(s.profile?.description || 'Напиши название профиля в редакторе или в строке действия.')) + '<p class="detail-note">Хранится в SQLite → profile. Сессия ссылается на профиль через profile_id. При следующем запросе читается актуальное описание.</p>' + (p?.snapshot.profile ? section('Профиль, использованный в запросе', paragraph(p.snapshot.profile.description)) : '');
  if (id === 'shortTerm') body = section('Хранилище сообщений', paragraph(`Сохранено: ${s.messageCount}. Размер окна: ${s.session.windowSize}.`)) + '<p class="detail-note">SQLite → message. Вне окна сообщения остаются в базе, но не отправляются в очередном запросе. Новое сообщение добавляется к окну отдельно.</p>' + section(mode === 'step' && p ? 'ID сообщений в фактическом запросе' : 'ID сообщений в следующем окне', paragraph((mode === 'step' && p ? p.snapshot.shortTerm.map(m => m.id) : s.contextMessageIds).join(', ') || 'Окно пусто')) + (p ? `<details><summary>Показать только окно последнего запроса (${p.snapshot.shortTerm.length})</summary>${p.snapshot.shortTerm.map(m => section(`${m.role} · #${m.id}`, paragraph(m.content))).join('')}</details>` : '');
  if (id === 'working') body = '<p class="detail-note">SQLite → post_task. Одно рабочее состояние на сессию.</p>' + section('Этап', paragraph(stages[task?.state])) + ['idea','thesis','plan','draft','notes','styleSuggestion'].map(key => section(fields[key], paragraph(Array.isArray(task?.[key]) ? task[key].join('\n') : key === 'styleSuggestion' && task?.styleSuggestion ? `${task.styleSuggestion.key}: ${task.styleSuggestion.value}` : task?.[key]))).join('');
  if (id === 'longTerm') body = `<p class="detail-note">SQLite → memory_item. Общие правила для всех сессий. Всего ${s.longTermTotal}, в выборке контекста ${s.longTerm.length}; лимит ${view.agent.longTermLimit}. Порядок: тип, затем ключ.</p>` + (s.longTerm.map(m => section(m.key, paragraph(m.value) + `<p class="detail-note">${esc(m.kind)} · источник: ${m.sourceSessionId ? 'сессия #' + m.sourceSessionId : 'ручная запись / удалённая сессия'}</p>`)).join('') || paragraph('Подтверждённых правил пока нет.'));
  if (id === 'entry') body = section('Действие', paragraph(trace?.input || 'Ожидает сообщение')) + section('Логика', paragraph('Агент проверяет сообщение и выбранный профиль. Пока профиль не выбран, он сопоставляет текст с названиями профилей локально. После выбора собирает запрос к модели.'));
  if (id === 'prompt') body = p ? `<p class="detail-note">Точный состав отправленного контекста: ${p.messages.length} сообщений / ${num(p.charsSent)} символов. Блоки показаны в исходном порядке.</p>` + p.sections.map(b => `<details><summary>${b.messageIndex + 1}. ${esc(sourceNames[b.source])} · ${esc(p.messages[b.messageIndex].role)} · ${num(b.chars)} симв.</summary>${pre(p.messages[b.messageIndex].content)}</details>`).join('') : paragraph('Сохранённого промпта для этого действия нет. Он появляется при вызове модели.');
  if (id === 'parser') body = section('Контракт ответа', paragraph('reply — текст для пользователя; taskUpdate — предложенные изменения поста; styleSuggestion — предложение правила для будущих постов.')) + (trace?.decision ? section('Разобранный результат', pre(trace.decision)) : '') + (trace?.completion ? `<details><summary>Исходный ответ модели</summary>${pre(trace.completion.content)}</details>` : paragraph('Ответ модели ещё не получен.'));
  if (id === 'router' || id === 'changes') body = '<p class="detail-note">Модель предлагает изменения. Бэкенд проверяет этап, применяет поля и сохраняет результат. При возврате на ранний этап зависимые поля могут очищаться. Предложение стиля становится долгой памятью после подтверждения или явной просьбы «запомни».</p>' + changesDetail() + (trace?.decision?.taskUpdate ? section('Что предложила модель', pre(trace.decision.taskUpdate)) : '');
  if (id === 'llm') body = section('Граница компонента', paragraph('OpenRouterClient получает готовый ChatCompletionRequest, отправляет HTTP-запрос и возвращает текст, модель, провайдера и статистику.')) + (trace?.request ? `<details><summary>Точный запрос к модели</summary>${pre(trace.request)}</details>` : paragraph('Клиент на этом шаге не вызывался.')) + (trace?.error ? section('Ошибка', paragraph(trace.error)) : '');
  if (id === 'model') body = section('Модель', paragraph(trace?.completion?.model || trace?.request?.model || view.agent.model)) + section('Провайдер', paragraph(trace?.completion?.provider || 'Нет метаданных ответа')) + section('Причина остановки', paragraph(trace?.completion?.finishReason || 'Нет данных')) + section('Токены и стоимость', trace?.completion?.usage ? pre(trace.completion.usage) : paragraph('Провайдер ещё не вернул статистику.'));
  if (id === 'output') body = section('Статус', paragraph(trace ? statuses[trace.status] : 'Действий пока нет')) + (trace?.decision ? section('Ответ пользователю', paragraph(trace.decision.reply)) : '') + (trace?.error ? section('Ошибка', paragraph(trace.error)) : '') + changesDetail();
  $('detail-body').innerHTML = body;
  if (!$('detail-dialog').open) $('detail-dialog').showModal();
}

function changesDetail() {
  return trace?.changes.length ? trace.changes.map(c => section(fields[c.field], `<div class="diff"><div><span class="detail-note">До</span><pre class="before">${esc(c.before || 'Пусто')}</pre></div><div><span class="detail-note">После</span><pre class="after">${esc(c.after || 'Пусто')}</pre></div></div>`)).join('') : paragraph('Зафиксированных изменений пока нет.');
}

$('session-select').addEventListener('change', e => chooseSession(e.target.value));
function changeMode(nextMode) { mode = nextMode; generation++; if ($('detail-dialog').open) $('detail-dialog').close(); refresh(); }
$('now-mode').addEventListener('click', () => changeMode('now'));
$('step-mode').addEventListener('click', () => changeMode('step'));
$('trace-select').addEventListener('change', e => { selectedTraceId = e.target.value; generation++; refresh(); });
document.querySelectorAll('[data-node]').forEach(node => node.addEventListener('click', () => detail(node.dataset.node)));
$('change-trail').addEventListener('click', e => { if (e.target.closest('[data-change]')) detail('changes'); });
$('close-detail').addEventListener('click', () => $('detail-dialog').close());
$('detail-dialog').addEventListener('click', e => { if (e.target === $('detail-dialog')) { const r = e.target.getBoundingClientRect(); if (e.clientX < r.left || e.clientX > r.right || e.clientY < r.top || e.clientY > r.bottom) e.target.close(); } });
new ResizeObserver(drawConnections).observe($('organism'));
document.addEventListener('visibilitychange', () => { if (!document.hidden) { generation++; refresh(); } });
$('probe-form').addEventListener('submit', async e => {
  e.preventDefault();
  const input = $('probe-input').value.trim();
  if (!input || sending || !sessionId) return;
  const targetSession = sessionId;
  sending = true;
  mode = 'now';
  generation++;
  render();
  refresh();
  try {
    await api(`/api/sessions/${targetSession}/messages`, {method:'POST', body:JSON.stringify({text:input}), signal:AbortSignal.timeout(135000)});
    $('probe-input').value = '';
  } catch (error) {
    $('error').textContent = error.message;
    $('error').hidden = false;
  } finally {
    sending = false;
    generation++;
    await refresh();
  }
});

sessionId = Number(new URLSearchParams(location.search).get('session') || localStorage.getItem('day12-session')) || null;
if (sessionId) $('chat-link').href = `/?session=${sessionId}`;
refresh();
