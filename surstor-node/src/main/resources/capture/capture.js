const $ = id => document.getElementById(id);
let token = null;
let busy = false;
const reviewFields = ['reviewPrompt', 'reviewResponse', 'reviewSession', 'reviewModel'];
function invalidate() { token = null; $('approve').checked = false; $('save').disabled = true; }
function message(text) { $('status').textContent = text; }
async function post(path, data) {
  const response = await fetch(path, {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(data), cache: 'no-store'});
  if (!response.ok) throw new Error(await response.text());
  return response.json();
}
async function preview(input) {
  if (busy) return;
  busy = true; invalidate(); message('Filtering…');
  try {
    const result = await post('/api/v1/capture/preview', input);
    $('reviewPrompt').value = result.input.content.prompt;
    $('reviewResponse').value = result.input.content.response;
    $('reviewSession').value = result.input.sessionId;
    $('reviewModel').value = result.input.modelVersion || '';
    $('prompt').value = ''; $('response').value = ''; $('model').value = ''; $('session').value = 'default';
    $('compose').hidden = true; $('review').hidden = false;
    $('findings').textContent = `${result.redactions} redaction(s). ${result.warning}`;
    token = result.token; message('Ready for your review. Nothing has been recorded.');
  } catch (e) { message(e.message || 'Filtering failed. Capture is blocked.'); }
  finally { busy = false; }
}
$('filter').addEventListener('click', () => {
  const input = {content: {prompt: $('prompt').value, response: $('response').value}, contentType: 'conversation', sessionId: $('session').value || 'default'};
  if ($('model').value.trim()) input.modelVersion = $('model').value.trim();
  preview(input);
});
reviewFields.forEach(id => $(id).addEventListener('input', invalidate));
$('recheck').addEventListener('click', () => {
  const input = {content: {prompt: $('reviewPrompt').value, response: $('reviewResponse').value}, contentType: 'conversation', sessionId: $('reviewSession').value || 'default'};
  if ($('reviewModel').value.trim()) input.modelVersion = $('reviewModel').value.trim();
  preview(input);
});
$('approve').addEventListener('change', () => { $('save').disabled = !token || !$('approve').checked || busy; });
$('save').addEventListener('click', async () => {
  if (!token || !$('approve').checked || busy) return;
  const approvedToken = token; invalidate(); busy = true;
  reviewFields.forEach(id => $(id).disabled = true); message('Saving approved version…');
  try {
    const result = await post('/api/v1/capture/commit', {token: approvedToken});
    message(`Saved record: ${result.id}. ${result.warning || ''}`); reviewFields.forEach(id => $(id).value = ''); $('review').hidden = true; $('compose').hidden = false;
  } catch (e) { message(e.message || 'Save could not be confirmed. Check existing records before retrying.'); }
  finally { busy = false; reviewFields.forEach(id => $(id).disabled = false); }
});
$('clear').addEventListener('click', () => {
  if (busy) return;
  invalidate(); ['prompt', 'response', 'model', ...reviewFields].forEach(id => $(id).value = '');
  $('session').value = 'default'; $('review').hidden = true; $('compose').hidden = false; message('Capture cleared.');
});
window.addEventListener('pagehide', () => { invalidate(); ['prompt', 'response', 'model', 'session', ...reviewFields].forEach(id => $(id).value = ''); });
