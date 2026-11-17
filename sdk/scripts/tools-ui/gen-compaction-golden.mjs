// Golden outputs of llama.cpp's own Llama Chat compaction code (ui/apps/chat/src/lib/utils/compaction.ts) for 400
// seeded random message lists. CompactionGoldenTest replays every case through the Kotlin port; regenerate after a
// llama.cpp upgrade and a diff shows exactly what upstream changed.
//   TOOLS_UI_LIB=<llama.cpp>/ui/apps/chat/src/lib node --experimental-transform-types gen-compaction-golden.mjs > golden.json
import { register } from 'node:module';

register('./hooks.mjs', import.meta.url);
const LIB = process.env.TOOLS_UI_LIB;
const { sliceAtLastCompaction, compactionCut, canCompactMessages } = await import(`${LIB}/utils/compaction.ts`);
const { COMPACTION } = await import(`${LIB}/constants/compaction.constants.ts`);

// mulberry32: small seeded PRNG, so the corpus is the same on every run
let seed = 0x5eed;
const rand = () => {
    seed = (seed + 0x6d2b79f5) | 0;
    let t = seed;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
};
const pick = (weights) => {
    let r = rand() * weights.reduce((a, [, w]) => a + w, 0);
    for (const [v, w] of weights) if ((r -= w) < 0) return v;
    return weights[weights.length - 1][0];
};

const cases = [];
let n = 0;
for (let c = 0; c < 400; c++) {
    const messages = [];
    if (rand() < 0.3) messages.push({ id: `m${n}`, role: 'system', type: 'system', content: `system ${n++}` });
    const len = Math.floor(rand() * 13);
    for (let i = 0; i < len; i++) {
        const kind = pick([['user', 35], ['assistant', 30], ['tool', 15], ['compaction', 20]]);
        const id = `m${n++}`;
        if (kind === 'compaction') {
            const r = rand();
            const summary = r < 0.2 ? '' : r < 0.25 ? `keep $& and $$ literal ${id}` : `summary ${id}`;
            const node = { id, role: 'user', type: 'compaction', content: summary };
            if (rand() >= 0.3) node.kept = Math.floor(rand() * 7);
            messages.push(node);
        } else {
            messages.push({ id, role: kind, type: 'text', content: `${kind} ${id}` });
        }
    }
    const preserve = Math.floor(rand() * 7);
    const view = sliceAtLastCompaction(messages);
    cases.push({
        messages,
        preserve,
        viewIsInput: view === messages,
        view: view.map((m) => ({ type: m.type, role: m.role, content: m.content, kept: m.kept })),
        cut: compactionCut(view, preserve),
        canCompact: canCompactMessages(messages, preserve),
    });
}

process.stdout.write(JSON.stringify({
    source: 'llama.cpp ui/apps/chat/src/lib/utils/compaction.ts',
    contextTemplate: COMPACTION.CONTEXT_TEMPLATE,
    cases,
}, null, 1) + '\n');
