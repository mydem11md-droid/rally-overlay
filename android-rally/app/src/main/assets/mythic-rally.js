/* MYTHIC Floating Rally Coordinator */
(function(){
"use strict";
if(document.getElementById("mythic-rally-float"))return;
const L=(a,e)=>typeof lang!=="undefined"&&lang==="en"?e:a;
const esc=v=>String(v??"").replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));
const KEY="mythic-rally-floating-v1",base={enabled:false,open:false,settings:false,target:"",people:[{id:1,name:"",duration:""}],nextId:2,x:null,y:null};
let s={...base},results=null,errors=[],drag=null,moved=false,confirmClose=false;
try{const v=JSON.parse(localStorage.getItem(KEY)||"null");if(v)s={...base,...v,people:Array.isArray(v.people)&&v.people.length?v.people:base.people};}catch{}
const save=()=>{try{localStorage.setItem(KEY,JSON.stringify(s));}catch{}};
const pad=n=>String(n).padStart(2,"0"),clock=n=>(n<0?"−":"")+pad(Math.floor(Math.abs(n)/60))+":"+pad(Math.abs(n)%60);
function seconds(v){const d=String(v??"").replace(/\D/g,"");if(!d)return null;const m=d.length>2?Number(d.slice(0,-2)):0,x=Number(d.length>2?d.slice(-2):d);const total=m*60+x;return total<=3600?total:null;}
const formatTotal=n=>pad(Math.floor(n/60))+":"+pad(n%60);
function fmt(raw){const total=seconds(raw);return total===null?"":formatTotal(total);}
function normalize(el){const value=fmt(el.value);if(value){el.value=value;el.setSelectionRange(el.value.length,el.value.length);}return value;}
const targetSeconds=seconds;
const icons={gear:'<svg viewBox="0 0 24 24"><path d="M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8Zm7 5 1.2 1-1.5 2.6-1.5-.5-1.5.9-.3 1.6h-3l-.3-1.6-1.5-.9-1.5.5-1.5-2.6 1.2-1v-2l-1.2-1 1.5-2.6 1.5.5 1.5-.9.3-1.6h3l.3 1.6 1.5.9 1.5-.5 1.5 2.6-1.2 1Z"/></svg>',edit:'<svg viewBox="0 0 24 24"><path d="m4 16.8-.8 4 4-.8L19.5 7.7a2.1 2.1 0 0 0-3-3L4 16.8Z"/></svg>',trash:'<svg viewBox="0 0 24 24"><path d="M4 7h16M9 7V4h6v3m3 0-.8 13H6.8L6 7m3.2 4v5m4-5v5"/></svg>'};
const root=document.createElement("div");root.id="mythic-rally-float";document.body.append(root);
// Keep the floating tool mounted across MYTHIC's client-side page replacements.
let remountQueued=false;
const ensureMounted=()=>{
 if(root.isConnected||!document.body||remountQueued)return;
 remountQueued=true;
 queueMicrotask(()=>{remountQueued=false;if(!root.isConnected&&document.body)document.body.append(root);if(s.enabled)render();});
};
const mountObserver=new MutationObserver(ensureMounted);
mountObserver.observe(document.documentElement,{childList:true,subtree:true});
window.addEventListener("pagehide",save,{capture:true});
window.addEventListener("pageshow",()=>{
 try{const saved=JSON.parse(localStorage.getItem(KEY)||"null");if(saved)s={...base,...saved,people:Array.isArray(saved.people)&&saved.people.length?saved.people:base.people};}catch{}
 if(s.enabled){ensureMounted();render();refreshUTC();}
});
window.addEventListener("popstate",()=>{if(s.enabled){ensureMounted();render();refreshUTC();}});
window.addEventListener("hashchange",()=>{if(s.enabled){ensureMounted();render();refreshUTC();}});
document.addEventListener("mythic:app-rendered",()=>{if(s.enabled){ensureMounted();render();refreshUTC();}});

const css="#mythic-rally-float{position:fixed;z-index:2147483000;left:12px;top:110px;width:46px;height:46px;font-family:var(--body,'Cairo',system-ui,sans-serif);color:var(--text,#eef2ff);pointer-events:none}#mythic-rally-float *{box-sizing:border-box}#mythic-rally-float .clock{position:absolute;left:50%;top:-33px;transform:translateX(-50%);height:25px;min-width:104px;padding:2px 7px;margin:0;border:1px solid #b9822a;border-radius:7px;background:linear-gradient(135deg,#101a35,#202c4d);color:#f1c56d;display:flex;align-items:center;justify-content:center;gap:5px;font:800 12px system-ui;box-shadow:0 3px 14px #0008;direction:ltr}#mythic-rally-float .clock small{font-size:9px;color:white}#mythic-rally-float .orb{pointer-events:auto;width:46px;height:46px;border-radius:50%;border:1px solid #e9b24a;background:radial-gradient(circle at 32% 25%,#344a75,#111a32 65%,#080d1b);box-shadow:0 3px 16px #000a,0 0 0 3px #e9b24a22;color:#f1c56d;display:grid;place-items:center;touch-action:none;padding:0}#mythic-rally-float .orb svg{width:21px;height:21px;fill:none;stroke:currentColor;stroke-width:1.7}#mythic-rally-float .close{pointer-events:auto;position:absolute;top:-5px;right:-5px;width:17px;height:17px;border:1px solid #53617e;border-radius:50%;background:#111a2e;color:white;font:12px/14px system-ui;padding:0}#mythic-rally-float .menu{pointer-events:auto;position:absolute;top:53px;left:50%;transform:translateX(-50%);width:min(276px,calc(100vw - 24px));max-height:min(66vh,560px);overflow:auto;border:1px solid var(--edge,#26315a);border-radius:13px;background:linear-gradient(145deg,var(--frost,#0e1426),var(--abyss,#070912));box-shadow:0 10px 34px #000b;padding:9px;overscroll-behavior:contain}#mythic-rally-float .menu[hidden]{display:none}#mythic-rally-float .head{display:flex;align-items:center;justify-content:space-between;margin-bottom:8px}#mythic-rally-float .head b{font-size:12px;color:var(--acc,#e9b24a)}#mythic-rally-float button{font-family:inherit;cursor:pointer}#mythic-rally-float .icon{width:27px;height:27px;display:grid;place-items:center;border:1px solid var(--edge,#26315a);border-radius:7px;background:var(--rune,#171f3a);color:var(--muted,#9aa5c8);padding:5px}#mythic-rally-float .icon svg,#mythic-rally-float .actions svg{width:14px;height:14px;fill:none;stroke:currentColor;stroke-width:1.7;stroke-linecap:round;stroke-linejoin:round}#mythic-rally-float label{display:block;font-size:10px;color:var(--muted,#9aa5c8);margin:5px 0 3px}#mythic-rally-float input{width:100%;height:33px;min-width:0;border:1px solid var(--edge,#26315a);border-radius:7px;background:var(--abyss,#070912);color:var(--text,#eef2ff);padding:0 8px;font:600 12px system-ui;outline:none}#mythic-rally-float input:focus{border-color:var(--glacier,#6fd3f2)}#mythic-rally-float .time{direction:ltr;text-align:center;font-variant-numeric:tabular-nums;letter-spacing:.06em}#mythic-rally-float .person{padding:7px 0;border-bottom:1px solid var(--edge,#26315a)}#mythic-rally-float .person-head{display:flex;align-items:center;gap:5px;margin-bottom:5px}#mythic-rally-float .person-head strong{flex:1;font-size:10px;color:var(--glacier,#6fd3f2)}#mythic-rally-float .actions{display:flex;gap:4px}#mythic-rally-float .actions button{width:25px;height:25px;padding:5px;border:1px solid var(--edge,#26315a);border-radius:6px;background:var(--rune,#171f3a);color:var(--muted,#9aa5c8)}#mythic-rally-float .fields{display:grid;grid-template-columns:minmax(0,1fr) 76px;gap:6px}#mythic-rally-float .fields input{height:31px;font-size:11px}#mythic-rally-float .add{width:100%;height:30px;margin-top:7px;border:1px dashed var(--glacier,#6fd3f2);border-radius:7px;background:#6fd3f20c;color:var(--glacier,#6fd3f2);font-size:11px}#mythic-rally-float .calc{position:sticky;bottom:0;width:100%;height:33px;margin-top:8px;border:0;border-radius:7px;background:linear-gradient(180deg,#54d6a5,#279b75);color:#061a13;font-size:12px;font-weight:800}#mythic-rally-float .error,#mythic-rally-float .warn{margin:6px 0;padding:6px 7px;border:1px solid #f0566b66;border-radius:7px;background:#f0566b12;color:#ff9aaa;font-size:10px}#mythic-rally-float .results{display:grid;gap:5px}#mythic-rally-float .result{display:grid;grid-template-columns:minmax(0,1fr) auto auto;gap:5px;align-items:center;padding:6px;border:1px solid var(--edge,#26315a);border-radius:7px}#mythic-rally-float .result b{font-size:11px;overflow-wrap:anywhere}#mythic-rally-float .result time{font:800 13px system-ui;color:var(--ok,#4fcb9a);direction:ltr}#mythic-rally-float .copy,#mythic-rally-float .bottom button{height:25px;padding:0 7px;border:1px solid var(--edge,#26315a);border-radius:6px;background:var(--rune,#171f3a);color:var(--text,#eef2ff);font-size:10px}#mythic-rally-float .warn{border-color:#e9b24a66;color:#f1c56d;background:#e9b24a12}#mythic-rally-float .bottom{display:grid;grid-template-columns:1fr 1fr;gap:5px;margin-top:7px}#mythic-rally-float .bottom .back{border-color:var(--glacier,#6fd3f2);color:var(--glacier,#6fd3f2)}#mythic-rally-float .confirm-backdrop{position:fixed;inset:0;z-index:2;display:grid;place-items:center;pointer-events:auto;background:#0003}#mythic-rally-float .confirm-box{width:min(220px,calc(100vw - 36px));padding:11px;border:1px solid var(--edge,#34415f);border-radius:11px;background:linear-gradient(145deg,#17213b,#090e1c);box-shadow:0 12px 36px #000b;text-align:center;direction:inherit}#mythic-rally-float .confirm-box p{margin:0 0 10px;font-size:12px;font-weight:700;color:#f2f4fa}#mythic-rally-float .confirm-actions{display:flex;justify-content:center;gap:8px}#mythic-rally-float .confirm-actions button{min-width:65px;height:27px;border:0;border-radius:7px;font-size:11px;font-weight:800}#mythic-rally-float .confirm-yes{background:#39b982;color:#062316}#mythic-rally-float .confirm-no{background:#e45b68;color:#fff}";
const style=document.createElement("style");style.textContent=css;document.head.append(style);
function place(){const x=s.x==null?Math.max(12,innerWidth-68):Math.max(6,Math.min(+s.x,innerWidth-52)),y=s.y==null?Math.max(8,Math.min(innerHeight*.32,innerHeight-52)):Math.max(8,Math.min(+s.y,innerHeight-52));s.x=x;s.y=y;root.style.left=x+"px";root.style.top=y+"px";}
function positionMenu(){const menu=root.querySelector(".menu");if(!menu||menu.hidden)return;const gap=7,below=innerHeight-(s.y+46)-gap,above=s.y-gap,desired=Math.min(menu.scrollHeight,560,innerHeight*.78),useBelow=below>=Math.min(desired,180)||below>=above;const room=Math.max(120,(useBelow?below:above)-4);menu.style.top=useBelow?"53px":"auto";menu.style.bottom=useBelow?"auto":"53px";menu.style.maxHeight=Math.min(560,room)+"px";const w=menu.offsetWidth,center=Math.max(12-s.x+w/2,Math.min(23,innerWidth-12-s.x-w/2));menu.style.left=center+"px";menu.style.right="auto";}
function render(){
if(!s.enabled){root.innerHTML="";return;}
const now=new Date(Date.now()).toISOString().slice(11,19);
let body="";
if(s.open){
if(results){
body='<div class="head"><b>'+L("نتائج تنسيق الحشود","RALLY RESULTS")+'</b><button class="icon" data-r="settings">'+icons.gear+'</button></div><div class="results">'+results.map(r=>'<div class="result"><b>'+esc(r.name)+'</b><time>'+clock(r.launch)+'</time><button class="copy" data-r="copy" data-id="'+r.id+'">'+L("نسخ","Copy")+'</button></div>').join("")+'</div>'+results.filter(r=>r.launch<0).map(r=>'<div class="warn">'+L("وقت الإرسال سالب للمشارك: ","Negative launch time for: ")+esc(r.name)+'</div>').join("")+'<div class="bottom"><button data-r="copy-all">'+L("نسخ الكل","Copy all")+'</button><button class="back" data-r="back">'+L("رجوع للتعديل","Back to edit")+'</button></div>';
}else{
body='<div class="head"><b>'+L(s.settings?"الإعدادات · تعديل البيانات":"أداة تنسيق الحشود","Rally Coordinator")+'</b><button class="icon" data-r="settings">'+icons.gear+'</button></div><label>'+L("الوقت المستهدف","Target time")+'</label><input id="rf-target" class="time" inputmode="numeric" enterkeyhint="next" maxlength="8" placeholder="MM:SS" value="'+esc(s.target)+'"><label>'+L("المشاركون","Participants")+' · '+s.people.length+'</label>'+s.people.map((p,i)=>'<div class="person"><div class="person-head"><strong>'+L("مشارك","Participant")+' '+(i+1)+'</strong><span class="actions"><button data-r="edit" data-id="'+p.id+'" aria-label="Edit">'+icons.edit+'</button><button data-r="remove" data-id="'+p.id+'" aria-label="Delete">'+icons.trash+'</button></span></div><div class="fields"><input data-f="name" data-id="'+p.id+'" enterkeyhint="next" maxlength="60" autocomplete="off" placeholder="'+L("اسم اللاعب","Player name")+'" value="'+esc(p.name)+'"><input class="time" data-f="duration" data-id="'+p.id+'" inputmode="numeric" enterkeyhint="next" maxlength="6" autocomplete="off" placeholder="MM:SS" value="'+esc(p.duration)+'"></div></div>').join("")+'<button class="add" data-r="add">'+L("+ إضافة مشارك","+ Add participant")+'</button><button class="calc" data-r="calculate">'+L("حساب","Calculate")+'</button>';
}
if(errors.length)body+=errors.map(x=>'<div class="error">'+esc(x)+'</div>').join("");
}
root.innerHTML='<div class="clock"><span>'+now+'</span><small>UTC</small></div><button class="orb" aria-label="Rally Coordinator"><svg viewBox="0 0 24 24"><path d="M4 18.5h16M6 18.5V9l6-4 6 4v9.5M3 9l9-5 9 5"/></svg></button><button class="close" data-r="close" aria-label="Close">×</button><section class="menu" '+(s.open?"":"hidden")+'>'+body+'</section>'+(confirmClose?'<div class="confirm-backdrop" role="dialog" aria-modal="true"><div class="confirm-box"><p>'+L("هل تريد إغلاق الأداة؟","Close the tool?")+'</p><div class="confirm-actions"><button class="confirm-yes" data-r="confirm-yes">'+L("نعم","Yes")+'</button><button class="confirm-no" data-r="confirm-no">'+L("لا","No")+'</button></div></div></div>':'');
place();positionMenu();
}
function focusNext(){root.querySelector("#rf-target")?.focus();}
function calculate(){
errors=[];const goal=targetSeconds(s.target);
if(goal===null){errors.push(L("الوقت المستهدف مطلوب ويجب ألا يتجاوز 60:00.","Target time is required and must not exceed 60:00."));render();root.querySelector("#rf-target")?.focus();return;}
s.target=formatTotal(goal);
if(!s.people.length){errors.push(L("أضف مشاركاً واحداً على الأقل.","Add at least one participant."));render();return;}
const rows=[];
s.people.forEach((p,i)=>{const name=p.name.trim()||L("المشارك ","Participant ")+(i+1);if(!p.name.trim()){errors.push(L("أدخل اسم اللاعب للمشارك ","Enter a player name for participant ")+(i+1));return;}const d=seconds(p.duration);if(d===null){const hasDigits=/\d/.test(String(p.duration||""));errors.push(hasDigits?L("مدة المسيرة تتجاوز الحد الأقصى 60:00 للمشارك: ","March duration exceeds the 60:00 maximum for participant: ")+name:L("أدخل مدة المسيرة للمشارك: ","Enter a march duration for participant: ")+name);return;}p.duration=formatTotal(d);rows.push({id:p.id,name:p.name.trim(),launch:goal-d});});
results=rows.length?rows:null;s.settings=false;s.open=!!results||errors.length>0;save();render();
}
async function copy(v){try{if(navigator.clipboard?.writeText)await navigator.clipboard.writeText(v);else{const t=document.createElement("textarea");t.value=v;t.style.position="fixed";t.style.opacity="0";document.body.append(t);t.select();document.execCommand("copy");t.remove();}}catch{errors=[L("تعذر النسخ من المتصفح.","Clipboard unavailable.")];}render();}
root.addEventListener("input",e=>{const el=e.target;if(el.id==="rf-target"){s.target=el.value;if(targetSeconds(s.target)!==null)errors=[];save();return;}const p=s.people.find(x=>x.id===+el.dataset.id);if(!p)return;if(el.dataset.f==="name")p.name=el.value;if(el.dataset.f==="duration"){p.duration=el.value;if(seconds(p.duration)!==null)errors=[];}save();});
root.addEventListener("focusout",e=>{const el=e.target;if(el.id==="rf-target"){const value=normalize(el);if(value)s.target=value;save();return;}if(el.matches('[data-f="duration"]')){const p=s.people.find(x=>x.id===+el.dataset.id);if(!p)return;const value=normalize(el);if(value)p.duration=value;save();}});
root.addEventListener("keydown",e=>{if(e.key!=="Enter"||!e.target.matches("#rf-target,[data-f]"))return;e.preventDefault();const a=[...root.querySelectorAll("#rf-target,[data-f]")],n=a[a.indexOf(e.target)+1];if(n){n.focus();n.select?.();}else e.target.blur();});
root.addEventListener("click",e=>{
const close=e.target.closest(".close");if(close){confirmClose=true;render();return;}
if(e.target.closest(".confirm-yes")){confirmClose=false;s.enabled=false;s.open=false;save();render();return;}
if(e.target.closest(".confirm-no")||e.target.classList.contains("confirm-backdrop")){confirmClose=false;render();return;}
if(e.target.closest(".confirm-box"))return;
if(e.target.closest(".orb")){if(moved){moved=false;return;}s.open=!s.open;s.settings=false;save();render();return;}
const b=e.target.closest("[data-r]");if(!b)return;const a=b.dataset.r,id=+b.dataset.id;
if(a==="settings"){s.settings=!s.settings;render();}
else if(a==="add"){s.people.push({id:s.nextId++,name:"",duration:""});errors=[];save();render();focusNext();}
else if(a==="remove"){s.people=s.people.filter(p=>p.id!==id);if(!s.people.length)s.people.push({id:s.nextId++,name:"",duration:""});errors=[];save();render();}
else if(a==="edit"){results=null;render();root.querySelector('[data-f="name"][data-id="'+id+'"]')?.focus();}
else if(a==="calculate")calculate();
else if(a==="back"){results=null;errors=[];save();render();}
else if(a==="copy"){const r=results?.find(x=>x.id===id);if(r)copy(r.name+" — "+clock(r.launch));}
else if(a==="copy-all")copy(results.map(r=>r.name+" — "+clock(r.launch)).join("\n"));
});
root.addEventListener("pointerdown",e=>{if(!e.target.closest(".orb"))return;drag={x:e.clientX,y:e.clientY,ox:s.x,oy:s.y,id:e.pointerId};moved=false;});
root.addEventListener("pointermove",e=>{if(!drag||drag.id!==e.pointerId)return;const dx=e.clientX-drag.x,dy=e.clientY-drag.y;if(Math.abs(dx)+Math.abs(dy)>5)moved=true;if(moved){s.x=Math.max(6,Math.min(drag.ox+dx,innerWidth-52));s.y=Math.max(8,Math.min(drag.oy+dy,innerHeight-52));place();positionMenu();}});
root.addEventListener("pointerup",e=>{if(!drag||drag.id!==e.pointerId)return;drag=null;if(moved){save();setTimeout(()=>moved=false,100);}});
function openRally(){s.enabled=true;s.open=true;s.settings=false;confirmClose=false;errors=[];save();render();}
function deactivateRally(){confirmClose=false;s.enabled=false;s.open=false;save();render();}
window.addEventListener("mythic:rally-open",openRally);
document.addEventListener("click",e=>{const tile=e.target.closest('[data-x="go"][data-v="rally"],[data-v="rally"]');if(!tile)return;e.preventDefault();e.stopPropagation();e.stopImmediatePropagation();if(s.enabled)deactivateRally();else openRally();},true);
window.addEventListener("resize",()=>{place();positionMenu();save();});
function refreshUTC(){if(!s.enabled)return;const c=root.querySelector(".clock span");if(c)c.textContent=new Date(Date.now()).toISOString().slice(11,19);}
document.addEventListener("visibilitychange",()=>{if(!document.hidden){ensureMounted();refreshUTC();}});window.addEventListener("focus",()=>{ensureMounted();refreshUTC();});
setInterval(refreshUTC,1000);
if(s.enabled)render();
})();