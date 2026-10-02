/* Offline companion to this checkout. No runtime connection to a traced JVM. */
'use strict';

const $ = (selector, root = document) => root.querySelector(selector);
const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];
const escapeHtml = value => String(value).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const sourceLink = (path, label) => `<a href="../../${encodeURI(path)}" target="_blank" rel="noopener">${escapeHtml(label || path)} ↗</a>`;

// Screenshots are existing local Compose ScreenshotTest artifacts, converted to WebP.
const screenshots = {
  overview: {file:'overview-light', alt:'coroutree desktop: a top-down concurrency graph, selected-node details at right, and an event log below', caption:'Built-in demo, rendered by ScreenshotTest. The diagnostic banner is synthetic demo data; current supported coroutines versions are 1.9–1.11.'},
  control: {file:'execution-control-dark', alt:'Dark-mode coroutree desktop with the live execution-control bar paused, a receipt coroutine selected, and subtree pacing controls in its details', caption:'The real control surface: Resume and Step above the graph; subtree settings in node details. This is a synthetic demo render, including its diagnostic banner.'},
  scale: {file:'large-light', alt:'coroutree rendering hundreds of nodes in its graph, with a viewport minimap and the event log underneath', caption:'A larger synthetic graph from ScreenshotTest: 700 nodes and 120 generated links. Zoom and the minimap help navigate. Geometry checks enforce spacing independently of visual inspection.'}
};
let currentScreen = 'overview';
$$('[data-screen]').forEach(button => button.addEventListener('click', () => {
  currentScreen = button.dataset.screen;
  const shot = screenshots[currentScreen];
  $('#app-screenshot').src = `assets/${shot.file}.webp`;
  $('#app-screenshot').alt = shot.alt;
  $('#screenshot-caption').textContent = shot.caption;
  $$('[data-screen]').forEach(item => {item.classList.toggle('selected', item === button); item.setAttribute('aria-pressed', String(item === button));});
}));
function enlargeScreenshot() {
  const shot = screenshots[currentScreen];
  $('#dialog-image').src = `assets/${shot.file}.webp`;
  $('#dialog-image').alt = shot.alt;
  $('#dialog-caption').textContent = shot.caption;
  $('#image-dialog').showModal();
}
$('#expand-shot').addEventListener('click', enlargeScreenshot);
$('#screenshot-open').addEventListener('click', enlargeScreenshot);
$('.dialog-close').addEventListener('click', () => $('#image-dialog').close());
$('#image-dialog').addEventListener('click', event => {if (event.target === $('#image-dialog')) {const r = event.target.getBoundingClientRect(); if (event.clientX < r.left || event.clientX > r.right || event.clientY < r.top || event.clientY > r.bottom) event.target.close();}});

// Moments condense the sample's golden per-node histories; they are not individual trace seq values.
const moments = [
  {title:'Enter a scope. Establish ownership.', copy:'The main thread runs runBlocking. Its coroutine enters coroutineScope, which becomes the structural parent of the children it will launch.', event:'LAUNCHED', states:['blocked','active','active','not launched','not launched']},
  {title:'Two children, one parent.', copy:'The scope launches “sibling” and “failing”. They share runBlocking’s event-loop thread. Concurrent coroutines do not require separate threads.', event:'LAUNCHED', states:['blocked','suspended','active','active','active']},
  {title:'Suspension frees the event loop.', copy:'Both children call delay. They suspend while waiting: sibling for 10 seconds, failing for 20 milliseconds. The coroutine state is suspended; the main thread still runs the runBlocking event loop.', event:'SUSPENDED', states:['blocked','suspended','active','suspended','suspended']},
  {title:'“failing” resumes—and throws.', copy:'After its delay, failing throws IllegalStateException("boom"). The agent records the throw and its propagation to coroutineScope. The failing child finishes in failure.', event:'EXCEPTION_THROWN', states:['blocked','suspended','cancelling','suspended','failed'], failure:true},
  {title:'The parent cancels the sibling.', copy:'The scope is cancelling because its child failed. Cancellation propagates to sibling; its suspended delay is cancelled and it finishes cancelled. It did not throw the original exception.', event:'CANCELLATION_PROPAGATED', states:['blocked','suspended','cancelling','cancelled','failed'], failure:true, cancel:true},
  {title:'The scope rethrows to its caller.', copy:'With its children finished, coroutineScope fails and propagates the exception to runBlocking. This is the same exception moving to the caller, not a second throw recorded there.', event:'EXCEPTION_PROPAGATED', states:['blocked','suspended','failed','cancelled','failed'], failure:true},
  {title:'Caught here. Completed here.', copy:'The catch block in runBlocking handles the exception and prints “caught boom”. The caller and main thread complete. The scope and failing child remain failed; sibling remains cancelled.', event:'EXCEPTION_HANDLED', states:['completed','completed','failed','cancelled','failed']}
];
let moment = 0;
let replayTimer = null;
const nodeIds = ['main','run','scope','sibling','failing'];
function renderMoment() {
  const frame = moments[moment];
  nodeIds.forEach((id, index) => {const node = $(`#node-${id}`); node.dataset.state = frame.states[index]; $('.node-status', node).textContent = frame.states[index];});
  $$('[data-lines]').forEach(line => line.classList.toggle('highlight', line.dataset.lines.split(' ').includes(String(moment))));
  $('#failure-arrow').style.opacity = $('#failure-label').style.opacity = frame.failure ? '1' : '0';
  $('#cancel-arrow').style.opacity = $('#cancel-label').style.opacity = frame.cancel ? '1' : '0';
  $('#story-title').textContent = frame.title;
  $('#story-copy').textContent = frame.copy;
  $('#story-number').textContent = String(moment + 1).padStart(2, '0');
  $('#story-event').textContent = frame.event;
  $('#trace-count').textContent = `${String(moment + 1).padStart(2, '0')} / 07`;
  $('#trace-progress').value = moment;
  $('#trace-progress').setAttribute('aria-valuetext', `Moment ${moment + 1} of 7: ${frame.title}`);
  $('#trace-description').textContent = `${frame.title} ${frame.copy}`;
  $('#step-trace').disabled = moment === moments.length - 1;
}
function stopReplay() {if (replayTimer) clearInterval(replayTimer); replayTimer = null; $('#play-trace').textContent = moment === 6 ? '↺ Replay' : '▶ Play'; $('#play-trace').setAttribute('aria-label', moment === 6 ? 'Replay illustrative trace' : 'Play illustrative trace');}
$('#play-trace').addEventListener('click', () => {
  if (replayTimer) {stopReplay(); return;}
  if (moment === 6) {moment = 0; renderMoment();}
  $('#play-trace').textContent = 'Ⅱ Pause';
  $('#play-trace').setAttribute('aria-label', 'Pause illustrative trace');
  replayTimer = setInterval(() => {moment++; renderMoment(); if (moment === 6) stopReplay();}, 3000);
});
$('#step-trace').addEventListener('click', () => {stopReplay(); moment = Math.min(6, moment + 1); renderMoment(); stopReplay();});
$('#reset-trace').addEventListener('click', () => {moment = 0; stopReplay(); renderMoment();});
$('#trace-progress').addEventListener('input', event => {moment = Number(event.target.value); stopReplay(); renderMoment();});
document.addEventListener('visibilitychange', () => {if (document.hidden) stopReplay();});
renderMoment();

const paths = {
  model:'coroutree-model/src/main/kotlin/kotlinx/coroutree/model/',
  agent:'coroutree-agent/src/main/java/kotlinx/coroutree/agent/',
  runtime:'coroutree-agent/src/runtime/java/kotlinx/coroutree/runtime/',
  plugin:'coroutree-gradle-plugin/src/main/kotlin/kotlinx/coroutree/gradle/',
  gui:'coroutree-gui/src/main/kotlin/kotlinx/coroutree/gui/',
  tests:'coroutree-integration-tests/src/test/kotlin/kotlinx/coroutree/it/'
};
const modules = {
  model: {name:'coroutree-model', title:'The shared language.', symbol:'◇', tag:'KOTLIN · JVM 17', hint:'Events → a concurrency tree', search:'wire protobuf schema snapshot store fold order labels wording source state reader', summary:'Owns the trace schema, stream reader/writer, and the event fold. One thread feeds TraceStore; immutable snapshots can be consumed elsewhere. This module is shared by the GUI and tests.', files:[['Trace.kt','Wire data classes and enum numbers.', paths.model+'Trace.kt'],['TraceIO.kt','COROTREE magic + length-delimited protobuf frames.',paths.model+'TraceIO.kt'],['tree/TraceStore.kt','Order events by seq; resolve placeholders; fold node state.',paths.model+'tree/TraceStore.kt'],['tree/TraceSnapshot.kt','Immutable node histories, structural tree, and cross-links.',paths.model+'tree/TraceSnapshot.kt'],['tree/Labels.kt','All human-facing node and event wording.',paths.model+'tree/Labels.kt']], boundary:'The Java agent does not link to this module. Its handwritten encoder implements the same wire contract.',test:':coroutree-model:test',testPath:'coroutree-model/src/test/kotlin/kotlinx/coroutree/model/TraceStoreTest.kt'},
  agent: {name:'coroutree-agent', title:'The observer inside.',symbol:'↳',tag:'JAVA 21 · ASM · C',hint:'Instrument + capture + control',search:'hook runtime bootstrap premain pace gate monitor jvmti native trace encoder queue writer live server threads exception capture',summary:'Three deliberate layers: main/ installs ASM instrumentation, runtime/ provides dependency-free bootstrap hooks, and native/ supplies the JVMTI monitor probe. A nested runtime JAR is appended to the bootstrap class path.',files:[['main/…/Premain.java','Extract runtime JAR, append bootstrap path, start agent.',paths.agent+'Premain.java'],['main/…/HookTable.java','The single inventory of JDK and coroutine hook targets.',paths.agent+'HookTable.java'],['runtime/…/Hooks.java','Guarded hook entry points; calls into Tracer.',paths.runtime+'Hooks.java'],['runtime/…/Tracer.java','Node discovery, event sequencing, and capture lifecycle.',paths.runtime+'Tracer.java'],['runtime/…/Pace.java','Per-sequence pacing; global and subtree execution gates.',paths.runtime+'Pace.java'],['runtime/…/TraceEncoder.java','Dependency-free protobuf encoding.',paths.runtime+'TraceEncoder.java']],boundary:'Only ASM is shaded. Runtime hooks have no Kotlin or third-party dependencies. No hook may throw into application code.',test:':coroutree-integration-tests:test',testPath:paths.tests+'CorpusTest.kt'},
  plugin: {name:'coroutree-gradle-plugin', title:'The build connection.',symbol:'⌘',tag:'KOTLIN · GRADLE API',hint:'Wire agent + source index + viewer',search:'gradle dsl configuration cache javaexec test tasks source index arguments properties build config attach packages',summary:'Applies org.jetbrains.kotlinx.coroutree, attaches the agent to JavaExec/Test tasks when enabled, indexes sources, and launches the viewer. It deliberately shares no implementation code with the other modules.',files:[['CoroutreePlugin.kt','Registers the extension, source index, agent wiring, and viewer.',paths.plugin+'CoroutreePlugin.kt'],['CoroutreeExtension.kt','The public DSL: packages, live feed, pace, stacks, IDE command.',paths.plugin+'CoroutreeExtension.kt'],['AgentArgumentProvider.kt','Produces agent configuration and JVM arguments.',paths.plugin+'AgentArgumentProvider.kt'],['CoroutreeSourceIndexTask.kt','Source index keyed by package and source filename.',paths.plugin+'CoroutreeSourceIndexTask.kt'],['CoroutreeViewTask.kt','Finds and starts the host-specific desktop application.',paths.plugin+'CoroutreeViewTask.kt']],boundary:'Plugin language/API level is Kotlin 2.2 for Gradle 9.0+ compatibility. Task state must be configuration-cache safe.',test:':coroutree-gradle-plugin:check',testPath:'coroutree-gradle-plugin/src/functionalTest/kotlin/kotlinx/coroutree/gradle/CoroutreePluginFunctionalTest.kt'},
  gui: {name:'coroutree-gui', title:'Make the story visible.',symbol:'⊞',tag:'KOTLIN · COMPOSE',hint:'Feed → view model → graph',search:'compose desktop view layout graph router geometry invariant snapshot screenshot minimap viewport visual source reader feed live ui details event log',summary:'Main.kt starts the desktop app. source/ handles files, live sockets, and demos; view/ owns testable behavior; view/graph/ is pure geometry; ui/ draws it. The graph is a node-link diagram, with an in-house layout engine.',files:[['source/TraceFeed.kt','Reads demo, file, or live session into TraceStore.',paths.gui+'source/TraceFeed.kt'],['view/TraceViewModel.kt','Snapshot, selection, filters, and execution-control state.',paths.gui+'view/TraceViewModel.kt'],['view/GraphBuilder.kt','Converts a snapshot to a visible graph and cross-links.',paths.gui+'view/GraphBuilder.kt'],['view/graph/LayoutEngine.kt','Tidy forest + orthogonal edge routing, without Compose types.',paths.gui+'view/graph/LayoutEngine.kt'],['view/graph/InvariantChecker.kt','Independent checks on rectangles, segments, and labels.',paths.gui+'view/graph/InvariantChecker.kt'],['ui/TraceScreen.kt','The graph, event log, details, and live pace controls.',paths.gui+'ui/TraceScreen.kt']],boundary:'Depends on coroutree-model. State-only changes do not trigger structural layout. No per-node collapse or same-site aggregation.',test:':coroutree-gui:test',testPath:'coroutree-gui/src/test/kotlin/kotlinx/coroutree/gui/graph/GraphInvariantTest.kt'},
  tests: {name:'coroutree-integration-tests',title:'Reality is the contract.',symbol:'✓',tag:'JUNIT · FORKED JVMS',hint:'Real agent, real samples',search:'tests corpus golden end to end protocol compatibility versions integration invariant fail open control coverage',summary:'Runs sample programs under the real agent in forked JVMs, then decodes the bytes with the Kotlin model. Its main source set comes from samples/src. This is where capture semantics meet independently implemented readers and the graph engine.',files:[['AgentRunner.kt','Starts a real JVM with the agent and reads its output.',paths.tests+'AgentRunner.kt'],['GoldenTreeTest.kt','Stable per-node trees compared against the sample goldens.',paths.tests+'GoldenTreeTest.kt'],['CorpusTest.kt','Event coverage and compatibility across coroutine versions.',paths.tests+'CorpusTest.kt'],['GraphInvariantCorpusTest.kt','Layouts of real sample traces must obey the geometry rules.',paths.tests+'GraphInvariantCorpusTest.kt'],['ExecutionControlTest.kt','Exercise pacing, pause, step, and subtree semantics.',paths.tests+'ExecutionControlTest.kt'],['AgentConfigTest.kt','Pins the plugin ↔ runtime config and source-index contract.',paths.tests+'AgentConfigTest.kt']],boundary:'Goldens compare deterministic per-node history, not arbitrary interleavings across parallel threads. Review updates, never bless them blindly.',test:':coroutree-integration-tests:test',testPath:paths.tests+'GoldenTreeTest.kt'},
  samples: {name:'samples/',title:'Small programs, big clues.',symbol:'↻',tag:'STANDALONE BUILD',hint:'20 programs · 17 golden trees',search:'samples examples corpus exception cancellation structured concurrency threads virtual inline jobless monitor interactive stress pace',summary:'One Kotlin program per construct, plus a Java helper. The standalone sample build uses includeBuild("..") to consume this checkout as a real plugin user. Interactive, PaceControl, and Stress have dedicated uses outside the static golden set.',files:[['StructuredConcurrency.kt','runBlocking, launch, async/await, and nested scopes.','samples/src/main/kotlin/samples/StructuredConcurrency.kt'],['ExceptionPropagation.kt','Failure → parent cancellation → caller catch.','samples/src/main/kotlin/samples/ExceptionPropagation.kt'],['PaceControl.kt','The interactive program driven by live-control tests.','samples/src/main/kotlin/samples/PaceControl.kt'],['MonitorContention.kt','Contended synchronized blocks through the native probe.','samples/src/main/kotlin/samples/MonitorContention.kt'],['golden/dynamic/ExceptionPropagation.txt','The stable tree and per-node events expected by tests.','samples/golden/dynamic/ExceptionPropagation.txt'],['settings.gradle.kts','Composite build setup for plugin and artifact substitution.','samples/settings.gradle.kts']],boundary:'No wrapper in samples/. Invoke from the root with -p samples. Prefer deterministic behavior per node when adding a sample.',test:'-p samples run -Psample=StructuredConcurrency -Pcoroutree',testPath:'samples/build.gradle.kts'}
};
let selectedModule = 'model';
function renderModules(query = '') {
  const term = query.trim().toLowerCase();
  const matching = Object.entries(modules).filter(([,mod]) => `${mod.name} ${mod.title} ${mod.hint} ${mod.search} ${mod.summary}`.toLowerCase().includes(term));
  $('#module-list').innerHTML = matching.map(([id, mod]) => `<button class="module-button ${id === selectedModule ? 'selected' : ''}" data-module-choice="${id}" aria-pressed="${id === selectedModule}"><span class="module-symbol" aria-hidden="true">${mod.symbol}</span><span><strong>${mod.name}</strong><small>${mod.hint}</small></span><span class="chevron" aria-hidden="true">↗</span></button>`).join('');
  $('#empty-search').hidden = matching.length > 0;
  $$('[data-module-choice]').forEach(button => button.addEventListener('click', () => selectModule(button.dataset.moduleChoice)));
}
function selectModule(id) {
  selectedModule = id;
  const mod = modules[id];
  const focusedChoice = document.activeElement?.dataset?.moduleChoice;
  renderModules($('#module-search').value);
  if (focusedChoice) $(`[data-module-choice="${focusedChoice}"]`)?.focus({preventScroll:true});
  $('#module-detail').innerHTML = `<div class="eyebrow">${mod.name}</div><div class="module-title-line"><h3>${mod.title}</h3><span>${mod.tag}</span></div><p class="module-summary">${mod.summary}</p><div class="entry-heading">START READING HERE</div><div class="entry-list">${mod.files.map(([file,note,path],index) => `<a href="../../${encodeURI(path)}" target="_blank" rel="noopener"><span>${String(index + 1).padStart(2,'0')}</span><div><strong>${escapeHtml(file)}</strong><small>${escapeHtml(note)}</small></div><b aria-hidden="true">↗</b></a>`).join('')}</div><div class="module-boundary"><strong>Remember / </strong>${mod.boundary}</div><div class="module-test"><span>VERIFY</span><code>${escapeHtml(mod.test)}</code>${sourceLink(mod.testPath,'test / build source')}</div>`;
}
$('#module-search').addEventListener('input', event => renderModules(event.target.value));
$$('[data-module]').forEach(button => button.addEventListener('click', () => {$('#module-search').value = ''; selectModule(button.dataset.module); $('#code-map').scrollIntoView({behavior:matchMedia('(prefers-reduced-motion: reduce)').matches?'instant':'smooth'});}));
selectModule('model');

const taskAnswers = {
  capture:`<strong>Start at ${sourceLink(paths.agent+'HookTable.java','HookTable')} → ${sourceLink(paths.runtime+'Hooks.java','Hooks')}.</strong> Name the new hook’s safe <code>Pace.await</code> point. If the event format changes, update both encoders and TRACE_FORMAT.md. Add a deterministic sample, register it in GoldenTreeTest.SAMPLES, review its golden, then run integration checks and a real plugin-backed sample.`,
  graph:`<strong>Follow ${sourceLink(paths.gui+'view/GraphBuilder.kt','GraphBuilder')} → ${sourceLink(paths.gui+'view/graph/LayoutEngine.kt','LayoutEngine')} → ${sourceLink(paths.gui+'ui/GraphCanvas.kt','GraphCanvas')}.</strong> Behavior stays in view/, geometry stays Compose-free, and the invariant checker must stay strict. Run GUI and corpus geometry tests; inspect the offscreen screenshots for readability.`,
  build:`<strong>Start at ${sourceLink(paths.plugin+'CoroutreeExtension.kt','CoroutreeExtension')} → ${sourceLink(paths.plugin+'AgentArgumentProvider.kt','AgentArgumentProvider')}.</strong> Preserve command-line precedence and configuration-cache compatibility. Mirror contract changes in AgentConfig/SourceIndexFile as needed. Run plugin check, AgentConfigTest, and a sample through the real plugin.`
};
$$('[data-task]').forEach(button => button.addEventListener('click', () => {
  const wasSelected = button.classList.contains('selected');
  $$('[data-task]').forEach(item => {item.classList.remove('selected'); item.setAttribute('aria-expanded','false'); item.setAttribute('aria-controls','task-answer');});
  if (wasSelected) {$('#task-answer').hidden = true; return;}
  button.classList.add('selected'); button.setAttribute('aria-expanded','true');
  $('#task-answer').innerHTML = taskAnswers[button.dataset.task]; $('#task-answer').hidden = false;
}));

const commands = {
  demo:{title:'See the desktop in one command.',copy:'Start with the built-in synthetic session. It streams a demo trace and exposes the pace controls without needing a separate instrumented application.',blocks:[['TERMINAL · REPOSITORY ROOT','./gradlew :coroutree-gui:run --args=--demo=live']],expected:'A desktop graph fills as the demo plays. Use Pause or Step before it finishes. For a static demo, use --args=--demo.'},
  recorded:{title:'Capture a real failure, then inspect it.',copy:'Run the same sample used in the trace lab. samples/ is a standalone composite build that resolves the plugin, agent, and GUI from this checkout.',blocks:[['1 · RUN THE SAMPLE','./gradlew -p samples run -Psample=ExceptionPropagation -Pcoroutree'],['2 · OPEN THE NEWEST TRACE','./gradlew -p samples coroutreeView']],expected:'The program prints “caught boom”. The graph retains the failed scope and child, the cancelled sibling, and the completed caller.'},
  live:{title:'Hold a JVM at its first event.',copy:'Use two terminals. The first runs the sample with tracing and start-paused enabled. The second opens the GUI so you can resume or step the real program.',blocks:[['TERMINAL 1 · KEEP IT RUNNING','./gradlew -p samples run -Psample=PaceControl -Pcoroutree -Pcoroutree.pace.startPaused'],['TERMINAL 2 · OPEN THE CONTROLLER','./gradlew -p samples coroutreeView']],expected:'The live GUI shows a paused session. Resume it, adjust the speed, or step. Enter “stop” in the sample’s terminal to finish. Space pauses/resumes; → steps in the GUI.'}
};
function showCommands(key) {
  const item = commands[key];
  $$('[data-command]').forEach(button => {const selected=button.dataset.command === key; button.classList.toggle('selected',selected); button.setAttribute('aria-selected',String(selected)); button.tabIndex = selected ? 0 : -1;});
  $('#command-panel').setAttribute('aria-labelledby',`tab-${key}`);
  $('#command-panel').innerHTML = `<h3>${item.title}</h3><p>${item.copy}</p>${item.blocks.map(([label,command],index) => `<div class="command-box"><div class="code-header"><span>${label}</span><button class="copy-button" data-copy="command-${index}">Copy</button></div><pre><code id="command-${index}">${escapeHtml(command)}</code></pre></div>`).join('')}<div class="command-expected"><strong>Look for / </strong>${escapeHtml(item.expected)}</div>`;
}
$$('[data-command]').forEach(button => {
  button.addEventListener('click', () => showCommands(button.dataset.command));
  button.addEventListener('keydown', event => {
    const list = $$('[data-command]'); let next;
    if (event.key === 'ArrowRight') next = (list.indexOf(button) + 1) % list.length;
    if (event.key === 'ArrowLeft') next = (list.indexOf(button) + list.length - 1) % list.length;
    if (event.key === 'Home') next = 0;
    if (event.key === 'End') next = list.length - 1;
    if (next !== undefined) {event.preventDefault(); showCommands(list[next].dataset.command); list[next].focus();}
  });
});
showCommands('demo');
let toastTimeout;
function notify(message) {$('#toast').textContent = message; $('#toast').classList.add('show'); clearTimeout(toastTimeout); toastTimeout = setTimeout(() => $('#toast').classList.remove('show'),2200);}
document.addEventListener('click', async event => {
  const button = event.target.closest('[data-copy]'); if (!button) return;
  const text = document.getElementById(button.dataset.copy)?.textContent || '';
  try {
    if (navigator.clipboard?.writeText) await navigator.clipboard.writeText(text);
    else throw new Error('Clipboard API not available');
    notify('Copied. Run from the repository root.');
  } catch {
    const area = document.createElement('textarea'); area.value = text; area.style.cssText = 'position:fixed;left:-9999px'; document.body.append(area); area.select();
    let copied=false; try {copied=document.execCommand('copy');} finally {area.remove();button.focus({preventScroll:true});}
    notify(copied ? 'Copied to clipboard.' : 'Select the command text to copy it.');
  }
});

const storageKey = 'coroutree-field-guide-waypoints-v1';
let checked = {};
try {checked = JSON.parse(localStorage.getItem(storageKey) || '{}') || {};} catch {}
function renderWaypoints() {
  const count = $$('[data-waypoint]').filter(box => box.checked).length;
  $('#route-status').textContent = count === 4 ? '4 of 4. You know your way around.' : `${count} of 4 waypoints explored`;
}
$$('[data-waypoint]').forEach(box => {box.checked = checked[box.dataset.waypoint] === true; box.addEventListener('change', () => {checked[box.dataset.waypoint] = box.checked; try {localStorage.setItem(storageKey,JSON.stringify(checked));} catch {} renderWaypoints();});});
renderWaypoints();

// Small, passive scroll updates keep the chapter marker and progress in sync.
let scrollPending = false;
function updateNavigation() {
  const sections = $$('.chapter');
  const threshold = innerWidth <= 760 ? 150 : 160;
  let active = sections[0];
  for (const section of sections) if (section.getBoundingClientRect().top <= threshold) active=section;
  $$('nav a').forEach(link => {const current = link.hash === `#${active.id}`; link.classList.toggle('active',current); if (current) link.setAttribute('aria-current','location'); else link.removeAttribute('aria-current');});
  const max = document.documentElement.scrollHeight - innerHeight;
  $('#reading-progress').style.width = `${max > 0 ? Math.min(100,scrollY / max * 100) : 0}%`;
  scrollPending=false;
}
addEventListener('scroll', () => {if (!scrollPending) {scrollPending=true;requestAnimationFrame(updateNavigation);}}, {passive:true});
addEventListener('resize',updateNavigation);
updateNavigation();
