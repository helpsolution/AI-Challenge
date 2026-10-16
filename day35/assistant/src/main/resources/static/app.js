const { createApp, nextTick } = Vue;
createApp({
  data: () => ({ status: null, project: null, message: '', busy: false, error: '', runs: [], active: null, tab: 'result', selectedFile: '', tabs: [{id:'result',label:'Результат'},{id:'diff',label:'Изменения'},{id:'files',label:'Файлы'}] }),
  computed: {
    current() { return this.runs.find(r => r.key === this.active); },
    changes() { return this.current?.answer?.result?.changes || []; },
    shownFile() { return this.changes.find(f => f.path === this.selectedFile) || this.changes[0]; }
  },
  async mounted() {
    try { const response = await fetch('/api/status'); if (!response.ok) throw new Error('Не удалось подключиться к сервису'); this.status = await response.json(); const project = await fetch('/api/project'); const data = await project.json(); if (!project.ok) throw new Error(data.message || 'Не удалось прочитать проект'); this.project = data; }
    catch (e) { this.error = e.message; }
  },
  methods: {
    choose(kind) { this.message = kind === 'docs' ? 'Сверь документацию проекта с актуальным кодом. Исправь подтверждённые расхождения в README и docs, сохрани отчёт о сверке с доказательствами и ограничениями.' : 'Сверь документацию API с контроллерами, моделями, сервисами и обработкой ошибок. Обнови описание API и README в затронутой части, сохрани отчёт о сверке с доказательствами.'; this.$refs.prompt.focus(); },
    newTask() { this.active = null; this.message = ''; this.error = ''; this.$refs.prompt.focus(); },
    selectRun(r) { this.active = r.key; this.tab = 'result'; this.selectedFile = ''; },
    statusLabel(r) { return ({completed:'Задача выполнена',incomplete:'Нужна доработка',failed:'Ошибка выполнения',interrupted:'Поток прерван'})[r.answer?.status] || 'Выполняется'; },
    seconds(ms) { return ms < 1000 ? ms + ' мс' : (ms / 1000).toFixed(1) + ' с'; },
    pretty(value) { return typeof value === 'string' ? value : JSON.stringify(value, null, 2); },
    stepSummary(step) { const d = step.detail; return d?.arguments?.path || d?.arguments?.query || d?.decision || (typeof d === 'string' ? d.slice(0,160) : ''); },
    diffClass(line) { return line.startsWith('+++') || line.startsWith('---') || line.startsWith('diff ') ? 'diff-meta' : line.startsWith('+') ? 'addition' : line.startsWith('-') ? 'deletion' : line.startsWith('@@') ? 'hunk' : ''; },
    downloadFile(file) { const url = URL.createObjectURL(new Blob([file.content], {type:'text/plain;charset=utf-8'})); const a = document.createElement('a'); a.href = url; a.download = file.path.split('/').pop(); a.click(); setTimeout(() => URL.revokeObjectURL(url), 1000); },
    onKey(event) { if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) { event.preventDefault(); this.submit(); } },
    async submit() {
      if (this.busy || !this.message.trim()) return;
      const run = { key: crypto.randomUUID(), message: this.message.trim(), steps: [], draft: '', workspace: null, answer: null };
      this.runs.push(run); this.active = run.key; this.tab = 'result'; this.error = ''; this.message = ''; this.busy = true;
      // Работаем с реактивной записью, чтобы Vue показывала каждый полученный шаг.
      const live = this.runs.find(r => r.key === run.key);
      try {
        const response = await fetch('/api/tasks', {method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify({message:run.message})});
        if (!response.ok) { const body = await response.json(); throw new Error(body.message || 'Ошибка сервиса: ' + response.status); }
        const reader = response.body.getReader(), decoder = new TextDecoder(); let buffer = '';
        const handle = block => {
          let event = 'message', data = [];
          for (const line of block.split('\n')) { if (line.startsWith('event:')) event = line.slice(6).trim(); else if (line.startsWith('data:')) data.push(line.slice(5).trimStart()); }
          if (!data.length) return;
          const value = JSON.parse(data.join('\n'));
          if (event === 'step') live.steps.push(value);
          if (event === 'workspace') live.workspace = value;
          if (event === 'delta') live.draft += value.text;
          if (event === 'discard') live.draft = '';
          if (event === 'answer') { live.answer = value; live.draft = ''; }
        };
        while (true) { const {value, done} = await reader.read(); buffer += decoder.decode(value || new Uint8Array(), {stream:!done}).replace(/\r\n/g,'\n'); let boundary; while ((boundary = buffer.indexOf('\n\n')) >= 0) { handle(buffer.slice(0,boundary)); buffer = buffer.slice(boundary + 2); } if (done) { if (buffer.trim()) handle(buffer); break; } }
        if (!live.answer) throw new Error('Соединение прервано до завершения задачи');
      } catch (e) {
        this.error = e.message;
        // Даже при обрыве сохранённые файлы можно получить из рабочей копии.
        let result = null;
        if (live.workspace) { try { const response = await fetch('/api/runs/' + live.workspace.workspaceId + '/changes'); if (response.ok) result = await response.json(); } catch (_) { /* Причина обрыва уже показана над формой. */ } }
        live.answer ||= {status:'interrupted',text:e.message,workspaceId:live.workspace?.workspaceId,result,warnings:[],durationMs:0,toolCalls:live.steps.filter(s => ['files','write'].includes(s.kind)).length,costUsd:0};
      } finally { this.busy = false; await nextTick(); }
    }
  }
}).mount('#app');
