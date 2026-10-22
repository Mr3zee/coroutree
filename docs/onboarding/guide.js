'use strict';

const $ = (selector, root = document) => root.querySelector(selector);
const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];
const escapeHtml = value => String(value).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const sourceLink = (path, label) => `<a href="../../${encodeURI(path)}" target="_blank" rel="noopener">${escapeHtml(label || path)} ↗</a>`;

// The screenshots are ScreenshotTest renders, converted to WebP.
const screenshots = {
  overview: {file:'overview-light', alt:'The coroutree GUI: a top-down graph, the details of the selected node at the right, the event log below', caption:'Demo render from ScreenshotTest. The diagnostic banner is sample data.'},
  control: {file:'execution-control-dark', alt:'The coroutree GUI in dark mode: the execution control bar of a paused live session. A coroutine named receipt is selected, with the subtree controls in its details', caption:'Demo render: global controls above the graph, subtree controls in the details pane.'},
  scale: {file:'large-light', alt:'The coroutree GUI with hundreds of nodes in the graph, the minimap, and the event log below', caption:'Demo render: 700 nodes and 120 links. Use zoom and the minimap to navigate.'}
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

// A moment groups several events of the sample's golden. It isn't one seq.
const moments = [
  {title:'The scope becomes the parent.', copy:'runBlocking enters coroutineScope, which becomes the structural parent of its children.', event:'LAUNCHED', states:['blocked','active','active','not launched','not launched']},
  {title:'Two children, one parent.', copy:'“sibling” and “failing” share the runBlocking event-loop thread.', event:'LAUNCHED', states:['blocked','suspended','active','active','active']},
  {title:'Both children suspend.', copy:'sibling delays for 10 seconds, failing for 20 milliseconds. Both suspend while the main thread runs the event loop.', event:'SUSPENDED', states:['blocked','suspended','active','suspended','suspended']},
  {title:'“failing” resumes and throws.', copy:'failing throws IllegalStateException("boom"). The trace records failure and propagation to coroutineScope.', event:'EXCEPTION_THROWN', states:['blocked','suspended','cancelling','suspended','failed'], failure:true},
  {title:'The parent cancels the sibling.', copy:'The failed child causes scope cancellation. sibling ends cancelled, without the original exception.', event:'CANCELLATION_PROPAGATED', states:['blocked','suspended','cancelling','cancelled','failed'], failure:true, cancel:true},
  {title:'The scope rethrows to its caller.', copy:'coroutineScope propagates the same exception to runBlocking. The trace records propagation without a second throw.', event:'EXCEPTION_PROPAGATED', states:['blocked','suspended','failed','cancelled','failed'], failure:true},
  {title:'The caller catches and completes.', copy:'runBlocking catches the exception and completes, as does the main thread. The failed and cancelled nodes retain their final states.', event:'EXCEPTION_HANDLED', states:['completed','completed','failed','cancelled','failed']}
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
function stopReplay() {if (replayTimer) clearInterval(replayTimer); replayTimer = null; $('#play-trace').textContent = moment === 6 ? '↺ Replay' : '▶ Play'; $('#play-trace').setAttribute('aria-label', moment === 6 ? 'Replay the moments' : 'Play the replay');}
$('#play-trace').addEventListener('click', () => {
  if (replayTimer) {stopReplay(); return;}
  if (moment === 6) {moment = 0; renderMoment();}
  $('#play-trace').textContent = 'Ⅱ Pause';
  $('#play-trace').setAttribute('aria-label', 'Pause the replay');
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
  model: {name:'coroutree-model', title:'The trace model.', symbol:'◇', tag:'KOTLIN · JVM 17', hint:'Events → a concurrency tree', search:'wire protobuf schema snapshot store fold order labels wording source state reader', summary:'One thread feeds TraceStore. Any thread can read its immutable snapshots.', files:[['Trace.kt','Wire data classes and enum numbers.', paths.model+'Trace.kt'],['TraceIO.kt','COROTREE magic + length-delimited protobuf frames.',paths.model+'TraceIO.kt'],['tree/TraceStore.kt','Orders events by seq, resolves placeholders and folds node state.',paths.model+'tree/TraceStore.kt'],['tree/TraceSnapshot.kt','Immutable node histories, structural tree and cross-links.',paths.model+'tree/TraceSnapshot.kt'],['tree/Labels.kt','All wording of nodes and events.',paths.model+'tree/Labels.kt']], boundary:'The agent doesn\'t depend on this module. Its hand-written encoder implements the same format.',test:':coroutree-model:test',testPath:'coroutree-model/src/test/kotlin/kotlinx/coroutree/model/TraceStoreTest.kt'},
  agent: {name:'coroutree-agent', title:'The Java agent.',symbol:'↳',tag:'JAVA 21 · ASM · C',hint:'Instrument + capture + control',search:'hook runtime bootstrap premain pace gate monitor jvmti native trace encoder queue writer live server threads exception capture',summary:'Premain loads the nested runtime jar onto the bootstrap class path. main/ instruments classes, runtime/ captures events, and native/ detects monitor contention.',files:[['main/…/Premain.java','Extracts the runtime jar, appends it to the bootstrap class path, starts the agent.',paths.agent+'Premain.java'],['main/…/HookTable.java','The one list of JDK and kotlinx.coroutines hook targets.',paths.agent+'HookTable.java'],['runtime/…/Hooks.java','The guarded hook entry points. They call Tracer.',paths.runtime+'Hooks.java'],['runtime/…/Tracer.java','Node discovery, event order and the capture lifecycle.',paths.runtime+'Tracer.java'],['runtime/…/Pace.java','The gate: a pace for each sequence, globally and for a subtree.',paths.runtime+'Pace.java'],['runtime/…/TraceEncoder.java','Protobuf encoding without dependencies.',paths.runtime+'TraceEncoder.java']],boundary:'The build shades only ASM. The runtime has no Kotlin and no third-party dependency. A hook never throws into application code.',test:':coroutree-integration-tests:test',testPath:paths.tests+'CorpusTest.kt'},
  plugin: {name:'coroutree-gradle-plugin', title:'The Gradle plugin.',symbol:'⌘',tag:'KOTLIN · GRADLE API',hint:'Agent arguments + source index + viewer',search:'gradle dsl configuration cache javaexec test tasks source index arguments properties build config attach packages',summary:'Attaches the agent to JavaExec and Test tasks, indexes sources and provides the GUI launcher. Shares no code with other modules.',files:[['CoroutreePlugin.kt','Registers the extension, the source index task, the agent arguments and the view task.',paths.plugin+'CoroutreePlugin.kt'],['CoroutreeExtension.kt','The DSL: packages, live, pace, stack depth, IDE command.',paths.plugin+'CoroutreeExtension.kt'],['AgentArgumentProvider.kt','Writes the agent configuration and the JVM arguments.',paths.plugin+'AgentArgumentProvider.kt'],['CoroutreeSourceIndexTask.kt','The source index, keyed by package and file name.',paths.plugin+'CoroutreeSourceIndexTask.kt'],['CoroutreeViewTask.kt','Resolves and starts the GUI jar of the host platform.',paths.plugin+'CoroutreeViewTask.kt']],boundary:'Kotlin language and API level 2.2, so the plugin runs in Gradle 9.0 and later. Task state must survive the configuration cache.',test:':coroutree-gradle-plugin:check',testPath:'coroutree-gradle-plugin/src/functionalTest/kotlin/kotlinx/coroutree/gradle/CoroutreePluginFunctionalTest.kt'},
  gui: {name:'coroutree-gui', title:'The desktop GUI.',symbol:'⊞',tag:'KOTLIN · COMPOSE',hint:'Feed → view model → graph',search:'compose desktop view layout graph router geometry invariant snapshot screenshot minimap viewport visual source reader feed live ui details event log',summary:'source/ reads traces, view/ holds logic, view/graph/ computes geometry, and ui/ draws.',files:[['source/TraceFeed.kt','Feeds the demo, a file or a live session into TraceStore.',paths.gui+'source/TraceFeed.kt'],['view/TraceViewModel.kt','Snapshot, selection, filters and execution control state.',paths.gui+'view/TraceViewModel.kt'],['view/GraphBuilder.kt','Builds the visible graph and its cross-links from a snapshot.',paths.gui+'view/GraphBuilder.kt'],['view/graph/LayoutEngine.kt','Tidy forest + orthogonal edge routing, without Compose types.',paths.gui+'view/graph/LayoutEngine.kt'],['view/graph/InvariantChecker.kt','Checks the invariant on rectangles, segments and labels.',paths.gui+'view/graph/InvariantChecker.kt'],['ui/TraceScreen.kt','The graph, the event log, the details pane and the controls of a live session.',paths.gui+'ui/TraceScreen.kt']],boundary:'Depends on coroutree-model. A change of state alone doesn\'t lay the graph out again. No per-node collapse and no same-site aggregation.',test:':coroutree-gui:test',testPath:'coroutree-gui/src/test/kotlin/kotlinx/coroutree/gui/graph/GraphInvariantTest.kt'},
  tests: {name:'coroutree-integration-tests',title:'The agent end to end.',symbol:'✓',tag:'JUNIT · FORKED JVMS',hint:'The real agent on the samples',search:'tests corpus golden end to end protocol compatibility versions integration invariant fail open control coverage',summary:'Runs samples under the real agent in forked JVMs, then checks traces with the independent model and graph engine.',files:[['AgentRunner.kt','Starts a real JVM with the agent and reads its output.',paths.tests+'AgentRunner.kt'],['GoldenTreeTest.kt','Compares the tree of each sample with its golden.',paths.tests+'GoldenTreeTest.kt'],['CorpusTest.kt','Event coverage and the other kotlinx.coroutines versions.',paths.tests+'CorpusTest.kt'],['GraphInvariantCorpusTest.kt','Checks the drawing invariant on the trace of every sample.',paths.tests+'GraphInvariantCorpusTest.kt'],['ExecutionControlTest.kt','Pace, pause, step and subtrees on a real JVM.',paths.tests+'ExecutionControlTest.kt'],['AgentConfigTest.kt','Pins the agent properties and the source index format between plugin and agent.',paths.tests+'AgentConfigTest.kt']],boundary:'A golden holds the per-node history, which is deterministic, not the interleaving of parallel threads. Read every change of a golden.',test:':coroutree-integration-tests:test',testPath:paths.tests+'GoldenTreeTest.kt'},
  samples: {name:'samples/',title:'The corpus.',symbol:'↻',tag:'STANDALONE BUILD',hint:'36 programs · 30 golden trees',search:'samples examples corpus exception cancellation structured concurrency threads virtual inline jobless monitor interactive stress pace',summary:'The standalone build consumes this checkout through includeBuild(".."). Interactive, PaceControl and Stress are outside the golden set.',files:[['StructuredConcurrency.kt','runBlocking, launch, async/await and nested scopes.','samples/src/main/kotlin/samples/StructuredConcurrency.kt'],['ExceptionPropagation.kt','Failure → parent cancellation → caller catch.','samples/src/main/kotlin/samples/ExceptionPropagation.kt'],['PaceControl.kt','The program that the execution control tests drive.','samples/src/main/kotlin/samples/PaceControl.kt'],['MonitorContention.kt','Contended synchronized blocks through the native probe.','samples/src/main/kotlin/samples/MonitorContention.kt'],['golden/dynamic/ExceptionPropagation.txt','The tree and the per-node events that the tests expect.','samples/golden/dynamic/ExceptionPropagation.txt'],['settings.gradle.kts','The composite build that substitutes the plugin and the artifacts.','samples/settings.gradle.kts']],boundary:'samples/ has no wrapper. Run it from the root with -p samples. A new sample must be deterministic for each node.',test:'-p samples run -Psample=StructuredConcurrency -Pcoroutree',testPath:'samples/build.gradle.kts'}
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
  $('#module-detail').innerHTML = `<div class="eyebrow">${mod.name}</div><div class="module-title-line"><h3>${mod.title}</h3><span>${mod.tag}</span></div><p class="module-summary">${mod.summary}</p><div class="entry-heading">START READING HERE</div><div class="entry-list">${mod.files.map(([file,note,path],index) => `<a href="../../${encodeURI(path)}" target="_blank" rel="noopener"><span>${String(index + 1).padStart(2,'0')}</span><div><strong>${escapeHtml(file)}</strong><small>${escapeHtml(note)}</small></div><b aria-hidden="true">↗</b></a>`).join('')}</div><div class="module-boundary"><strong>Rule / </strong>${mod.boundary}</div><div class="module-test"><span>VERIFY</span><code>${escapeHtml(mod.test)}</code>${sourceLink(mod.testPath,'test / build source')}</div>`;
}
$('#module-search').addEventListener('input', event => renderModules(event.target.value));
$$('[data-module]').forEach(button => button.addEventListener('click', () => {$('#module-search').value = ''; selectModule(button.dataset.module); $('#code-map').scrollIntoView({behavior:matchMedia('(prefers-reduced-motion: reduce)').matches?'instant':'smooth'});}));
selectModule('model');

const taskAnswers = {
  capture:`<strong>Start at ${sourceLink(paths.agent+'HookTable.java','HookTable')} → ${sourceLink(paths.runtime+'Hooks.java','Hooks')}.</strong> Name the <code>Pace.await</code> point of the new hook. If the event format changes, change both trace implementations and TRACE_FORMAT.md. Add a deterministic sample and add it to GoldenTreeTest.SAMPLES. Read its golden. Then run the integration tests and a sample through the plugin.`,
  graph:`<strong>Follow ${sourceLink(paths.gui+'view/GraphBuilder.kt','GraphBuilder')} → ${sourceLink(paths.gui+'view/graph/LayoutEngine.kt','LayoutEngine')} → ${sourceLink(paths.gui+'ui/GraphCanvas.kt','GraphCanvas')}.</strong> Logic goes in view/. Geometry has no Compose types. Don’t relax the invariant checker. Run the GUI tests and the corpus invariant test, then look at the screenshots.`,
  build:`<strong>Start at ${sourceLink(paths.plugin+'CoroutreeExtension.kt','CoroutreeExtension')} → ${sourceLink(paths.plugin+'AgentArgumentProvider.kt','AgentArgumentProvider')}.</strong> The command line wins over the DSL, and task state must survive the configuration cache. A change of a contract goes into AgentConfig or SourceIndexFile on the agent side too. Run the plugin check, AgentConfigTest and a sample through the plugin.`
};
$$('[data-task]').forEach(button => button.addEventListener('click', () => {
  const wasSelected = button.classList.contains('selected');
  $$('[data-task]').forEach(item => {item.classList.remove('selected'); item.setAttribute('aria-expanded','false'); item.setAttribute('aria-controls','task-answer');});
  if (wasSelected) {$('#task-answer').hidden = true; return;}
  button.classList.add('selected'); button.setAttribute('aria-expanded','true');
  $('#task-answer').innerHTML = taskAnswers[button.dataset.task]; $('#task-answer').hidden = false;
}));

const commands = {
  demo:{title:'The GUI on a demo trace.',copy:'Try live controls without a traced application.',blocks:[['TERMINAL · REPOSITORY ROOT','./gradlew :coroutree-gui:run --args=--demo=live']],expected:'The graph grows while the demo plays. Pause or step it before it ends. For the recorded demo, use --args=--demo.'},
  recorded:{title:'Record a sample, then open it.',copy:'Record ExceptionPropagation, then select its recent trace in the GUI.',blocks:[['1 · RUN THE SAMPLE','./gradlew -p samples run -Psample=ExceptionPropagation -Pcoroutree'],['2 · OPEN THE NEWEST TRACE','./gradlew -p samples coroutreeView']],expected:'The program prints “caught boom”. The graph shows the failed scope and child, the cancelled sibling and the completed caller.'},
  live:{title:'Hold a JVM at its first event.',copy:'Use two terminals: start the paused sample, then open the GUI and select its JVM.',blocks:[['TERMINAL 1 · THE PROGRAM','./gradlew -p samples run -Psample=PaceControl -Pcoroutree -Pcoroutree.pace.startPaused'],['TERMINAL 2 · THE GUI','./gradlew -p samples coroutreeView']],expected:'Space toggles pause, → steps. Type “stop” and Enter in the program terminal to end it.'}
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
  $('#route-status').textContent = count === 4 ? '4 of 4 steps done.' : `${count} of 4 steps done`;
}
$$('[data-waypoint]').forEach(box => {box.checked = checked[box.dataset.waypoint] === true; box.addEventListener('change', () => {checked[box.dataset.waypoint] = box.checked; try {localStorage.setItem(storageKey,JSON.stringify(checked));} catch {} renderWaypoints();});});
renderWaypoints();

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
