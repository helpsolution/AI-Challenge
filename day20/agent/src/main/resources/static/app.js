const $ = s => document.querySelector(s);
let sessionId = localStorage.getItem('day20-session') || crypto.randomUUID();
localStorage.setItem('day20-session', sessionId);
let busy = false;
const escapeHtml = s => String(s).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
// A small safe Markdown subset: escape everything first, permit only HTTP(S) links.
function inline(s) {
  return escapeHtml(s).replace(/\[([^\]]+)\]\((https?:\/\/[^\s)]+)\)/g, '<a href="$2" target="_blank" rel="noopener noreferrer">$1</a>')
    .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>').replace(/`([^`]+)`/g,'<code>$1</code>');
}
function markdown(text) {
  let html='', list='', code=false;
  for (const line of String(text).split('\n')) {
    if (line.startsWith('```')) { if(list){html+=`</${list}>`;list='';} html+=code?'</pre>':'<pre>';code=!code;continue; }
    if(code){html+=escapeHtml(line)+'\n';continue;}
    const bullet=line.match(/^\s*([-*]|\d+\.)\s+(.+)/);
    if(bullet){const tag=/\d/.test(bullet[1])?'ol':'ul';if(list!==tag){if(list)html+=`</${list}>`;html+=`<${tag}>`;list=tag;}html+=`<li>${inline(bullet[2])}</li>`;continue;}
    if(list){html+=`</${list}>`;list='';}
    if(/^#{1,4}\s/.test(line))html+='<h3>'+inline(line.replace(/^#{1,4}\s+/,''))+'</h3>';
    else if(line.trim())html+='<p>'+inline(line)+'</p>';
  }
  return html+(list?`</${list}>`:'')+(code?'</pre>':'');
}
async function api(url, options) {
  const r=await fetch(url,options);const data=await r.json();if(!r.ok)throw Error(data.message||`HTTP ${r.status}`);return data;
}
function scrollDown(){const el=$('#scroll-area');el.scrollTop=el.scrollHeight;}
function setBusy(value){busy=value;$('#send').disabled=value;$('#new-chat').disabled=value;document.querySelectorAll('[data-prompt]').forEach(b=>b.disabled=value);$('#city').disabled=value;}
function addMessage(item) {
  $('#welcome').hidden=true;
  const node=document.createElement('article');node.className='message '+item.role;
  if(item.role==='user'){const b=document.createElement('div');b.className='bubble';b.textContent=item.text;node.append(b);}
  else {
    node.innerHTML='<div class="speaker"><span class="small-logo">◈</span> АТЛАС ДНЯ</div><details class="trace" hidden><summary>Ход работы</summary><div class="steps"></div></details><div class="answer"></div><div class="images"></div>';
    (item.steps||[]).forEach(s=>updateStep(node,s));(item.images||[]).forEach(i=>addImage(node,i));
    if(item.text)node.querySelector('.answer').innerHTML=markdown(item.text);
    if(item.error)node.querySelector('.answer').classList.add('error-text');
  }
  $('#messages').append(node);scrollDown();return node;
}
function updateStep(node, step) {
  const trace=node.querySelector('.trace');trace.hidden=false;
  let el=Array.from(node.querySelectorAll('.step')).find(e=>e.dataset.id===step.id);
  if(!el){el=document.createElement('details');el.dataset.id=step.id;node.querySelector('.steps').append(el);}
  el.className='step '+step.state;
  const names={weather:'Погода',news:'Хабр',images:'Изображения'};
  el.innerHTML='<summary><span>'+escapeHtml(names[step.server]||step.server)+'</span><span>· '+escapeHtml(step.tool.split('__').pop())+'</span><time>'+(step.state==='running'?'выполняется':step.state==='error'?'ошибка':(step.durationMs/1000).toFixed(1)+' с ✓')+'</time></summary>';
  for(const [title,value] of [['Параметры',step.arguments],['Результат',step.result]]){
    if(value===undefined)continue;const p=document.createElement('pre');p.textContent=title+'\n'+JSON.stringify(value,null,2);el.append(p);
  }
  trace.querySelector(':scope > summary').textContent='Ход работы · '+node.querySelectorAll('.step').length+' вызов(а)';
}
function addImage(node, image) {
  if(!/^[a-f0-9-]{36}$/.test(image.id)||node.querySelector(`[data-image="${image.id}"]`))return;
  const fig=document.createElement('figure');fig.className='generated';fig.dataset.image=image.id;
  const link=document.createElement('a');link.href='/api/images/'+image.id+'/file';link.target='_blank';link.rel='noopener';
  const img=document.createElement('img');img.src=link.href;img.alt=image.prompt||'Сгенерированное изображение';img.onload=scrollDown;
  img.onerror=()=>{const n=document.createElement('p');n.textContent='Изображение временно недоступно. Проверьте сервис изображений.';fig.append(n);};link.append(img);fig.append(link);
  const caption=document.createElement('figcaption');const meta=document.createElement('span');meta.textContent=`${image.model} · ${image.width} × ${image.height}`;
  const download=document.createElement('a');download.href=link.href;download.download=`atlas-${image.id}.png`;download.textContent='Скачать ↓';caption.append(meta,download);fig.append(caption);node.querySelector('.images').append(fig);
}
function showServers(servers) {
  const labels={weather:['☀','Погода','Open-Meteo'],news:['≋','Повестка','Хабр'],images:['✧','Изображения','OpenRouter']};
  $('#servers').innerHTML=servers.map(s=>{const l=labels[s.name]||['·',s.name,'MCP'];return `<div class="source"><span class="source-symbol">${l[0]}</span><div>${escapeHtml(l[1])}<small>${escapeHtml(l[2])} · ${s.online?'подключён':'недоступен'}</small></div><i class="${s.online?'online':'offline'}" title="${escapeHtml(s.url)}"></i></div>`;}).join('');
}
async function poll(jobId,node) {
  let cursor=0, failures=0;
  while(true){
    let job;
    try{job=await api(`/api/jobs/${jobId}?after=${cursor}`);failures=0;}
    catch(e){if(++failures>=3)throw e;await new Promise(r=>setTimeout(r,1500));continue;}
    cursor=job.cursor;
    for(const e of job.events){
      if(e.type==='servers')showServers(e.servers);
      if(e.type==='step')updateStep(node,e);
      if(e.type==='image')addImage(node,e.image);
      if(e.type==='thinking')node.querySelector('.answer').innerHTML='<div class="working">'+escapeHtml(e.message)+'</div>';
    }
    if(job.done){const result=job.result||{text:'Ответ не сохранён. Попробуйте ещё раз.'};node.querySelector('.answer').innerHTML=markdown(result.text);(result.images||[]).forEach(i=>addImage(node,i));if(result.error)node.querySelector('.answer').classList.add('error-text');scrollDown();return;}
    await new Promise(r=>setTimeout(r,800));
  }
}
async function send(text) {
  if(busy||!text.trim())return;
  const city=$('#city').value.trim();if(city.length<2){$('#notice').textContent='Укажите город для погоды.';$('#city').focus();return;}
  localStorage.setItem('day20-city',city);setBusy(true);$('#notice').textContent='';$('#input').value='';$('#input').style.height='auto';
  addMessage({role:'user',text});const node=addMessage({role:'assistant'});node.querySelector('.answer').innerHTML='<div class="working">Подключаю источники</div>';
  try{const job=await api('/api/chat',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({sessionId,message:text,city})});await poll(job.jobId,node);}
  catch(e){node.querySelector('.answer').textContent=e.message;node.querySelector('.answer').classList.add('error-text');}
  finally{setBusy(false);$('#input').focus();scrollDown();}
}
$('#composer').onsubmit=e=>{e.preventDefault();send($('#input').value);};
$('#input').onkeydown=e=>{if(e.key==='Enter'&&!e.shiftKey&&!e.isComposing){e.preventDefault();send(e.target.value);}};
$('#input').oninput=e=>{e.target.style.height='auto';e.target.style.height=Math.min(e.target.scrollHeight,170)+'px';};
document.querySelectorAll('[data-prompt]').forEach(b=>b.onclick=()=>send(b.dataset.prompt));
$('#new-chat').onclick=()=>{if(busy)return;sessionId=crypto.randomUUID();localStorage.setItem('day20-session',sessionId);$('#messages').replaceChildren();$('#welcome').hidden=false;$('#notice').textContent='';$('#input').focus();};
$('#refresh').onclick=async()=>{try{showServers(await api('/api/servers'));}catch(e){$('#notice').textContent=e.message;}};
$('#date').textContent=new Intl.DateTimeFormat('ru',{day:'numeric',month:'long',year:'numeric'}).format(new Date());
(async()=>{
  try{
    const config=await api('/api/config');$('#city').value=localStorage.getItem('day20-city')||config.defaultCity;$('#swagger').href=config.swaggerUrl;
    if(!config.configured)$('#notice').textContent='Добавьте OPENROUTER_API_KEY в day20/.env и перезапустите приложение.';
    const chat=await api('/api/chats/'+sessionId);chat.messages.forEach(addMessage);
    if(chat.activeJob){setBusy(true);const node=addMessage({role:'assistant'});try{await poll(chat.activeJob,node);}finally{setBusy(false);}}
    $('#refresh').click();
  }catch(e){$('#notice').textContent=e.message;}
})();
