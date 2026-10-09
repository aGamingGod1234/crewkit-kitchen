// Chef voice lines at key CrewKit beats, built from event data (no LLM). speak(message) delivers one
// proximity line as the agent named Chef and resolves false when no Chef or voice is connected.
const ONES = ['zero', 'one', 'two', 'three', 'four', 'five', 'six', 'seven', 'eight', 'nine', 'ten', 'eleven', 'twelve',
  'thirteen', 'fourteen', 'fifteen', 'sixteen', 'seventeen', 'eighteen', 'nineteen'];
const TENS = ['', '', 'twenty', 'thirty', 'forty', 'fifty', 'sixty', 'seventy', 'eighty', 'ninety'];

// Spoken numbers read better through TTS than digits ("a hundred and five", not "one zero five").
export function words(n) {
  n = Math.round(Number(n));
  if (!Number.isFinite(n) || n < 0 || n > 9999) return String(n);
  if (n < 20) return ONES[n];
  if (n < 100) return TENS[Math.floor(n / 10)] + (n % 10 ? `-${ONES[n % 10]}` : '');
  if (n < 1000) {
    const h = Math.floor(n / 100);
    return `${h === 1 ? 'a' : ONES[h]} hundred${n % 100 ? ` and ${words(n % 100)}` : ''}`;
  }
  const t = Math.floor(n / 1000);
  return `${words(t)} thousand${n % 1000 ? ` ${n % 1000 < 100 ? 'and ' : ''}${words(n % 1000)}` : ''}`;
}

const cap = (s) => s.charAt(0).toUpperCase() + s.slice(1);
// Short product name: the first few words, so a line stays within ~15 words.
const shortName = (name) => String(name || 'That item').split(/\s+/).slice(0, 4).join(' ');

export const CHEF_VOICE_MIN_GAP_MS = 6000;

export const chefVoiceEnabled = (env = process.env) => env.CREWKIT_CHEF_VOICE !== '0';

/** One sink per run. Lines at most once per beat, at least minGapMs apart; dropped while a line is in flight. */
export function createChefVoice({ speak, now = Date.now, minGapMs = CHEF_VOICE_MIN_GAP_MS, log = () => {} }) {
  const said = new Set();
  const names = new Map();
  let last = -Infinity;
  let busy = false;
  const say = (beat, line) => {
    if (said.has(beat)) return;
    said.add(beat); // a dropped beat stays dropped; the kitchen visuals carry it
    const t = now();
    if (busy || t - last < minGapMs) { log(`chef voice: dropped ${beat}`); return; }
    busy = true;
    last = t;
    Promise.resolve().then(() => speak(line))
      .then((ok) => { if (ok === false) log(`chef voice: ${beat} skipped, no Chef voice connected`); })
      .catch((e) => log(`chef voice: ${beat} failed: ${e?.code || e?.message}`))
      .finally(() => { busy = false; });
  };
  return (payload) => {
    const d = payload?.data || {};
    switch (payload?.event) {
      case 'brief': {
        const guests = Array.isArray(d.guests) ? d.guests.length : 0;
        const amount = Number(d.budget?.amount);
        return say('brief', `Order in! ${cap(words(guests))} guests, ${words(amount)} dollar budget.`);
      }
      case 'item_added': names.set(d.id, d.realName); return;
      case 'gate_blocked': {
        const over = Number(d.over?.amount);
        if (!(over > 0)) return;
        const n = Math.ceil(over);
        return say('gate_blocked', `We're ${words(n)} dollar${n === 1 ? '' : 's'} over. My rules say no checkout. Reworking the cart.`);
      }
      case 'item_removed':
        if (d.why !== 'sold_out') return;
        return say('sold_out', `${shortName(names.get(d.id))} is sold out, grabbing another.`);
      case 'checkout': return say('checkout', 'Scan to approve, I won\'t buy without you.');
      case 'completed': return say('completed', 'Order placed! Plating up.');
      default: return;
    }
  };
}
