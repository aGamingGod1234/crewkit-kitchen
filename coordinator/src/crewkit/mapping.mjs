// Maps a real Reap product to a vanilla Minecraft stand-in from docs/crewkit/items-allowlist.json.
// Keyword match on the product name first, then the brief's need label. Cached per product id.
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
export const ALLOWLIST_PATH = join(here, '..', '..', '..', 'docs', 'crewkit', 'items-allowlist.json');

let allowlist = null;
export function loadAllowlist(path = ALLOWLIST_PATH) {
  if (!allowlist || path !== ALLOWLIST_PATH) {
    const parsed = JSON.parse(readFileSync(path, 'utf8'));
    if (path !== ALLOWLIST_PATH) return parsed;
    allowlist = parsed;
  }
  return allowlist;
}

const escape = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
const cache = new Map();

function bestMatch(text, items) {
  const hay = String(text || '').toLowerCase();
  let best = null;
  for (const item of items) {
    for (const phrase of item.useFor) {
      // Word boundaries so "pen" does not match "open" and "tea" does not match "steam".
      if (new RegExp(`\\b${escape(phrase.toLowerCase())}s?\\b`).test(hay)) {
        if (!best || phrase.length > best.score) best = { mcItem: item.mcItem, score: phrase.length };
      }
    }
  }
  return best?.mcItem ?? null;
}

export function mapToMcItem({ productId, productName, needLabel }, list = loadAllowlist()) {
  if (productId && cache.has(productId)) return cache.get(productId);
  const mcItem = bestMatch(productName, list.items)
    ?? bestMatch(needLabel, list.items)
    ?? list.fallbacks?.stationery_or_paper
    ?? 'minecraft:paper';
  if (productId) cache.set(productId, mcItem);
  return mcItem;
}

export const ALLOWED_MC_ITEMS = () => new Set(loadAllowlist().items.map((i) => i.mcItem));
