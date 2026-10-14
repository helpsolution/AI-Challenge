const {createApp, ref, computed, watch, onMounted, nextTick} = Vue;

// Иконки Lucide (stroke 1.75). Шаблон в строке, а не в HTML: иначе браузер приведёт viewBox к нижнему регистру.
const ICONS = {
  lock: '<rect width="18" height="11" x="3" y="11" rx="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/>',
  filePlus: '<path d="M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z"/><path d="M14 2v4a2 2 0 0 0 2 2h4"/><path d="M9 15h6"/><path d="M12 18v-6"/>',
  card: '<rect width="20" height="14" x="2" y="5" rx="2"/><line x1="2" x2="22" y1="10" y2="10"/>',
  upload: '<path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="17 8 12 3 7 8"/><line x1="12" x2="12" y1="3" y2="15"/>',
  chat: '<path d="M7.9 20A9 9 0 1 0 4 16.1L2 22Z"/>',
  database: '<ellipse cx="12" cy="5" rx="9" ry="3"/><path d="M3 5V19A9 3 0 0 0 21 19V5"/><path d="M3 12A9 3 0 0 0 21 12"/>',
  book: '<path d="M2 3h6a4 4 0 0 1 4 4v14a3 3 0 0 0-3-3H2z"/><path d="M22 3h-6a4 4 0 0 0-4 4v14a3 3 0 0 1 3-3h7z"/>',
  bot: '<path d="M12 8V4H8"/><rect width="16" height="12" x="4" y="8" rx="2"/><path d="M2 14h2"/><path d="M20 14h2"/><path d="M15 13v2"/><path d="M9 13v2"/>',
  note: '<path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/>',
  ticket: '<path d="M2 9a3 3 0 0 1 0 6v2a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-2a3 3 0 0 1 0-6V7a2 2 0 0 0-2-2H4a2 2 0 0 0-2 2Z"/><path d="M13 5v2"/><path d="M13 17v2"/><path d="M13 11v2"/>',
  send: '<path d="m5 12 7-7 7 7"/><path d="M12 19V5"/>',
  x: '<path d="M18 6 6 18"/><path d="m6 6 12 12"/>',
  check: '<path d="M20 6 9 17l-5-5"/>',
  rotate: '<path d="M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8"/><path d="M3 3v5h5"/>',
  alert: '<circle cx="12" cy="12" r="10"/><line x1="12" x2="12" y1="8" y2="12"/><line x1="12" x2="12.01" y1="16" y2="16"/>',
};

const app = createApp({
  setup() {
    const status = ref({}), customers = ref([]), customersError = ref(''), customerId = ref(''), tickets = ref([]);
    const turns = ref([]), input = ref(''), busy = ref(false), selected = ref(-1), attached = ref(null);
    const notice = ref(''), editor = ref(null), feed = ref(null), openedSource = ref(null);
    const tones = ['info', 'success', 'warning', 'danger', 'neutral'];
    const suggestions = [
      {icon: 'lock', text: 'Почему не работает авторизация?'},
      {icon: 'filePlus', text: 'Почему я не могу создать задачу?'},
      {icon: 'card', text: 'Сколько стоит тариф Team?'},
      {icon: 'upload', text: 'Как импортировать задачи из Excel?'},
    ];
    const customer = computed(() => customers.value.find(c => c.id === customerId.value));
    const current = computed(() => turns.value[selected.value] || null);

    function notify(text) { notice.value = text; setTimeout(() => { if (notice.value === text) notice.value = ''; }, 5000); }
    async function json(url, options) {
      const response = await fetch(url, options);
      const data = await response.json().catch(() => ({}));
      if (!response.ok) throw new Error(data.message || `Ошибка ${response.status}`);
      return data;
    }
    const storageKey = id => 'day33.chat.' + id;
    function persist() {
      try { localStorage.setItem(storageKey(customerId.value), JSON.stringify(turns.value.filter(t => t.answer).slice(-12))); }
      catch (_) { notify('История слишком большая для хранения в браузере'); }
    }
    function restore(id) {
      try { return JSON.parse(localStorage.getItem(storageKey(id)) || '[]'); } catch (_) { return []; }
    }
    async function loadTickets() {
      tickets.value = customerId.value ? await json(`/api/customers/${customerId.value}/tickets`).catch(e => { notify(e.message); return []; }) : [];
    }
    async function selectCustomer(id) {
      if (busy.value || id === customerId.value) return;
      customerId.value = id; attached.value = null; openedSource.value = null;
      turns.value = restore(id); selected.value = turns.value.length - 1;
      try { localStorage.setItem('day33.customer', id); } catch (_) {}
      await loadTickets();
      scrollDown(); editor.value?.focus();
    }
    function toggleTicket(ticket) { attached.value = attached.value?.id === ticket.id ? null : ticket; editor.value?.focus(); }
    function reset() { if (busy.value) return; turns.value = []; selected.value = -1; persist(); nextTick(() => editor.value?.focus()); }
    function scrollDown() { nextTick(() => { if (feed.value) feed.value.scrollTop = feed.value.scrollHeight; }); }

    async function ask(text) {
      const message = (text ?? input.value).trim();
      if (busy.value || !message || !customerId.value) return;
      // История — только последние подтверждённые обмены этого клиента.
      const history = turns.value.filter(t => t.answer?.status === 'answered').slice(-3)
        .flatMap(t => [{role: 'user', content: t.message}, {role: 'assistant', content: t.answer.text}]);
      const ticketId = attached.value?.id ?? null;
      turns.value.push({key: Date.now(), message, ticketId, steps: [], streaming: '', answer: null, error: null, actionState: null});
      const turn = turns.value.at(-1);
      selected.value = turns.value.length - 1; openedSource.value = null;
      busy.value = true; input.value = ''; attached.value = null;
      scrollDown();
      try {
        const response = await fetch('/api/chat', {method: 'POST', headers: {'Content-Type': 'application/json'},
          body: JSON.stringify({customerId: customerId.value, message, ticketId, history})});
        if (!response.ok) { const error = await response.json().catch(() => ({})); throw new Error(error.message || `Ошибка ${response.status}`); }
        const reader = response.body.getReader(), decoder = new TextDecoder();
        let buffer = '';
        const handle = block => {
          const lines = block.split('\n');
          const name = lines.find(l => l.startsWith('event:'))?.slice(6).trim();
          const raw = lines.filter(l => l.startsWith('data:')).map(l => l.slice(5)).join('\n');
          if (!raw) return;
          const data = JSON.parse(raw);
          if (name === 'step') turn.steps.push(data);
          if (name === 'delta') { turn.streaming += data.text; scrollDown(); }
          if (name === 'discard') turn.streaming = '';
          if (name === 'answer') { turn.answer = data; turn.streaming = ''; }
        };
        while (true) {
          const {done, value} = await reader.read();
          buffer += decoder.decode(value || new Uint8Array(), {stream: !done}).replace(/\r\n/g, '\n');
          let end;
          while ((end = buffer.indexOf('\n\n')) >= 0) { handle(buffer.slice(0, end)); buffer = buffer.slice(end + 2); }
          if (done) { if (buffer.trim()) handle(buffer); break; }
        }
        if (!turn.answer) throw new Error('Соединение оборвалось до ответа');
        persist();
      } catch (error) { turn.error = error.message; }
      finally { busy.value = false; scrollDown(); await nextTick(); editor.value?.focus(); }
    }

    async function confirmAction(turn) {
      turn.actionBusy = true;
      try {
        const ticket = await json(`/api/actions/${turn.answer.action.id}/confirm`, {method: 'POST',
          headers: {'Content-Type': 'application/json'}, body: JSON.stringify({customerId: customerId.value})});
        turn.actionState = {kind: 'done', text: `✓ Обращение #${ticket.id} создано · ${ticket.subject}`};
        await loadTickets();
      } catch (error) { turn.actionState = {kind: 'failed', text: error.message}; }
      finally { turn.actionBusy = false; persist(); }
    }
    async function cancelAction(turn) {
      turn.actionBusy = true;
      try {
        await json(`/api/actions/${turn.answer.action.id}/cancel`, {method: 'POST',
          headers: {'Content-Type': 'application/json'}, body: JSON.stringify({customerId: customerId.value})});
        turn.actionState = {kind: 'cancelled', text: 'Обращение не создано'};
      } catch (error) { turn.actionState = {kind: 'failed', text: error.message}; }
      finally { turn.actionBusy = false; persist(); }
    }

    function segments(text, answer) {
      const result = []; let pos = 0;
      for (const match of text.matchAll(/\[(\d+(?:\s*[,;]\s*\d+)*)\]/g)) {
        if (match.index > pos) result.push({text: text.slice(pos, match.index)});
        for (const n of match[1].split(/\s*[,;]\s*/)) result.push({number: Number(n), valid: answer.sources.some(s => s.number === Number(n))});
        pos = match.index + match[0].length;
      }
      if (pos < text.length) result.push({text: text.slice(pos)});
      return result;
    }
    function openSource(index, number) { selected.value = index; openedSource.value = number; }

    function stepLabel(step) {
      if (step.kind === 'llm') return step.title;
      if (step.kind === 'note') return step.title;
      if (step.kind === 'docs') return 'RAG · search_docs';
      return step.title.startsWith('CRM') || step.title.startsWith('MCP') ? step.title : 'CRM · ' + step.title;
    }
    function compact(value) {
      if (!value || typeof value !== 'object') return String(value ?? '');
      return Object.entries(value).map(([k, v]) => `${k}: ${typeof v === 'string' ? '«' + (v.length > 40 ? v.slice(0, 40) + '…' : v) + '»' : JSON.stringify(v)}`).join(', ');
    }
    function stepSummary(step) {
      const d = step.detail;
      if (step.kind === 'note') return typeof d === 'string' ? '«' + d + '»' : '';
      if (step.kind === 'llm') return `${d.decision} · ${d.promptTokens} → ${d.completionTokens} ток.` + (d.attempts > 1 ? ` · попыток ${d.attempts}` : '');
      if (step.title === 'MCP · tools/list') return `модели: ${d.auto.join(', ')} · с подтверждением: ${d.confirm.join(', ')} · скрыто: ${d.hidden.join(', ')}`;
      if (step.kind === 'docs') {
        const found = d.result?.fragments?.map(f => '[' + f.number + ']').join(' ') || 'ничего не найдено';
        return `${compact(d.arguments)} → ${found}`;
      }
      const call = d.result ?? d;
      const args = compact(Object.fromEntries(Object.entries(d.arguments || call.arguments || {}).filter(([k]) => k !== 'customer_id')));
      if (call?.pending) return `${args ? args + ' → ' : ''}ждёт подтверждения клиента`;
      if (call?.error) return `ошибка: ${call.error}`;
      const data = call?.data || {};
      const result = data.events ? `${data.events.length} из ${data.total} событий`
        : data.tickets ? `${data.tickets.length} ${plural(data.tickets.length, 'обращение', 'обращения', 'обращений')}`
        : data.messages ? `#${data.id} · ${data.messages.length} сообщ.`
        : data.workspace ? `${data.name} · статус ${data.status}`
        : data.plan ? `${data.plan} · ${data.status}` : 'ok';
      return `${args ? args + ' → ' : ''}${result}`;
    }
    function kindIcon(step) { return {crm: 'database', docs: 'book', llm: 'bot', note: step.status === 'error' ? 'alert' : 'note'}[step.kind] || 'note'; }
    function llmSteps(turn) { return turn.steps.filter(s => s.kind === 'llm').length; }
    function toolSteps(turn) { return turn.steps.filter(s => (s.kind === 'crm' || s.kind === 'docs') && !s.title.startsWith('CRM ·') && !s.title.startsWith('MCP ·')).length; }
    function initials(name) { return name.split(' ').map(p => p[0]).join('').slice(0, 2); }
    function firstName(name) { return name.split(' ')[0]; }
    function sec(ms) { return ms < 1000 ? ms + ' мс' : (ms / 1000).toFixed(1).replace('.', ',') + ' с'; }
    function plural(n, one, few, many) { return n % 100 >= 11 && n % 100 <= 14 ? many : n % 10 === 1 ? one : n % 10 >= 2 && n % 10 <= 4 ? few : many; }

    onMounted(async () => {
      json('/api/status').then(s => status.value = s).catch(e => notify(e.message));
      try {
        customers.value = await json('/api/customers');
        let saved = null; try { saved = localStorage.getItem('day33.customer'); } catch (_) {}
        await selectCustomer(customers.value.some(c => c.id === saved) ? saved : customers.value[0]?.id);
      } catch (error) { customersError.value = error.message; }
    });
    return {status, customers, customersError, customerId, customer, tickets, turns, input, busy, selected, current, attached,
      notice, editor, feed, openedSource, tones, suggestions, selectCustomer, toggleTicket, reset, ask, confirmAction, cancelAction,
      segments, openSource, stepLabel, stepSummary, kindIcon, llmSteps, toolSteps, initials, firstName, sec, plural, JSON};
  }
});
app.component('icon', {
  props: {name: String},
  template: '<svg class="icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" v-html="paths"></svg>',
  computed: {paths() { return ICONS[this.name] || ''; }},
});
app.mount('#app');
