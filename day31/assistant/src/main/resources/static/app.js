const {createApp, ref, computed, onMounted, nextTick} = Vue;
createApp({
  setup() {
    const project = ref({scopes: [], index: {}}), scope = ref('day31'), input = ref(''), busy = ref(false);
    const rebuilding = ref(false), turns = ref([]), notice = ref(''), editor = ref(null);
    const branch = ref(''), branchAt = ref('');
    const examples = [
      {icon: '◫', title: 'Структура проекта', subtitle: 'Как связаны модули day31', question: 'Как устроен day31 и какие в нём модули?', scope: 'day31'},
      {icon: '↗', title: 'Запуск сервиса', subtitle: 'Что нужно для day30', question: 'Как запустить day30 и где находится веб-чат?', scope: 'day30'},
      {icon: '⑂', title: 'Текущая ветка', subtitle: 'Актуальное состояние Git', question: 'На какой git-ветке я сейчас работаю?', scope: 'repo'},
      {icon: '⌕', title: 'Поиск по документации', subtitle: 'Как работает RAG в day28', question: 'Какие модели нужны для запуска day28?', scope: 'day28'},
    ];
    try {
      const saved = JSON.parse(localStorage.getItem('day31.chat') || 'null');
      if (saved && Array.isArray(saved.turns)) { turns.value = saved.turns.slice(-15); scope.value = saved.scope || 'day31'; }
    } catch (_) {}
    async function loadProject() {
      const response = await fetch('/api/project');
      if (!response.ok) throw new Error('Не удалось прочитать состояние проекта');
      project.value = await response.json();
      if (scope.value !== 'repo' && !project.value.scopes.includes(scope.value)) scope.value = 'repo';
    }
    function persist() {
      try { localStorage.setItem('day31.chat', JSON.stringify({scope: scope.value, turns: turns.value.filter(t => t.answer).slice(-15)})); }
      catch (_) { notify('История слишком большая для хранения в браузере'); }
    }
    function notify(text) { notice.value = text; setTimeout(() => { if (notice.value === text) notice.value = ''; }, 5000); }
    function changeScope() { persist(); }
    function reset() { if (busy.value) return; turns.value = []; input.value = ''; persist(); nextTick(() => editor.value?.focus()); }
    function useExample(example) { scope.value = example.scope; input.value = example.question; ask(); }
    async function ask() {
      if (busy.value || !input.value.trim()) return;
      const message = input.value.trim().startsWith('/') ? input.value.trim() : '/help ' + input.value.trim();
      // История другого дня не подмешивается после переключения проекта.
      const history = turns.value.filter(t => t.answer?.status === 'answered' && t.answer.scope === scope.value).slice(-3)
        .flatMap(t => [{role: 'user', content: t.message.slice(0, 6000)}, {role: 'assistant', content: t.answer.answer.slice(0, 6000)}]);
      const turn = {key: Date.now(), message, scope: scope.value, steps: [], answer: null, error: null, selected: null, showSources: false};
      turns.value.push(turn);
      const current = turns.value.at(-1);
      busy.value = true; input.value = '';
      await nextTick();
      window.scrollTo({top: document.documentElement.scrollHeight, behavior: 'smooth'});
      try {
        const response = await fetch('/api/help/stream', {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({message, scope: scope.value, history})});
        if (!response.ok) { const error = await response.json().catch(() => ({})); throw new Error(error.message || `Ошибка ${response.status}`); }
        const reader = response.body.getReader(), decoder = new TextDecoder();
        let buffer = '';
        function event(block) {
          const lines = block.split('\n');
          const name = lines.find(l => l.startsWith('event:'))?.slice(6).trim();
          const raw = lines.filter(l => l.startsWith('data:')).map(l => l.slice(5).trimStart()).join('\n');
          if (!raw) return;
          const data = JSON.parse(raw);
          if (name === 'step') {
            current.steps.push(data);
            if (data.name === 'MCP · git_branch' && data.detail?.data) {
              branch.value = data.detail.data.branch || 'detached HEAD'; branchAt.value = data.detail.data.capturedAt;
            }
          }
          if (name === 'answer') {
            current.answer = data; current.scope = data.scope; scope.value = data.scope;
            current.selected = data.sources[0]?.number || null;
          }
        }
        while (true) {
          const {done, value} = await reader.read();
          buffer += decoder.decode(value || new Uint8Array(), {stream: !done}).replace(/\r\n/g, '\n');
          let end;
          while ((end = buffer.indexOf('\n\n')) >= 0) { event(buffer.slice(0, end)); buffer = buffer.slice(end + 2); }
          if (done) { if (buffer.trim()) event(buffer); break; }
        }
        if (!current.answer) throw new Error('Соединение завершилось до получения ответа');
        persist(); await loadProject();
      } catch (error) { current.error = error.message; }
      finally { busy.value = false; await nextTick(); editor.value?.focus(); }
    }
    async function rebuild() {
      if (rebuilding.value || busy.value) return;
      rebuilding.value = true;
      try {
        const response = await fetch('/api/knowledge/rebuild', {method: 'POST'});
        const data = await response.json();
        if (!response.ok) throw new Error(data.message || 'Не удалось обновить индекс');
        await loadProject(); notify(`Индекс обновлён: ${data.documents} документов, ${data.chunks} фрагментов`);
      } catch (error) { notify(error.message); } finally { rebuilding.value = false; }
    }
    function segments(text) {
      const result = []; let pos = 0;
      for (const match of text.matchAll(/\[(\d+(?:\s*[,;]\s*\d+)*)\]/g)) {
        if (match.index > pos) result.push({text: text.slice(pos, match.index)});
        for (const number of match[1].split(/\s*[,;]\s*/)) result.push({number: Number(number)});
        pos = match.index + match[0].length;
      }
      if (pos < text.length) result.push({text: text.slice(pos)});
      return result;
    }
    function openSource(index, number) { const turn = turns.value[index]; turn.showSources = true; turn.selected = number; }
    function selectedSource(turn) { return turn.answer?.sources.find(s => s.number === turn.selected); }
    function cited(turn, number) { return turn.answer?.quotes.some(q => q.number === number) || turn.answer?.answer.includes('[' + number + ']'); }
    function sec(ms) { return ms < 1000 ? ms + ' мс' : (ms / 1000).toFixed(1).replace('.', ',') + ' с'; }
    function time(at) { return at ? new Date(at).toLocaleTimeString('ru-RU', {hour: '2-digit', minute: '2-digit', second: '2-digit'}) : '—'; }
    function plural(n, one, few, many) { return n % 100 >= 11 && n % 100 <= 14 ? many : n % 10 === 1 ? one : n % 10 >= 2 && n % 10 <= 4 ? few : many; }
    function statusLabel(status) { return {answered: 'Подтверждено источниками', help: 'Справка', clarify: 'Нужно уточнение', weak_context: 'Нет ответа в документации', no_answer: 'Нет ответа в источниках', unverified: 'Цитаты не подтверждены', failed: 'Ошибка запроса'}[status] || status; }
    onMounted(() => loadProject().catch(error => notify(error.message)));
    return {project, scope, input, busy, rebuilding, turns, notice, editor, branch, branchAt, examples, ask, useExample, reset, changeScope, rebuild,
      segments, openSource, selectedSource, cited, sec, time, plural, statusLabel, JSON, encodeURIComponent};
  }
}).mount('#app');
