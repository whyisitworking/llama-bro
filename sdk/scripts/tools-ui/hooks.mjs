// Node module hooks that let Llama Chat's TypeScript (llama.cpp ui/apps/chat; tools/ui before b12479) run without its
// build (the differential tests): `#lib/...` aliases point at the one module each needs, and extensionless relative
// imports get `.ts`. Read-only: the llama.cpp checkout is never written to.
import { existsSync } from 'node:fs';
import { fileURLToPath, pathToFileURL } from 'node:url';

const LIB = process.env.TOOLS_UI_LIB; // <llama.cpp>/ui/apps/chat/src/lib
// the barrel files (#lib/constants, #lib/enums) import the whole UI; compaction.ts needs one module of each
const ALIASES = {
    '#lib/constants/index.ts': 'constants/compaction.constants.ts',
    '#lib/enums/index.ts': 'enums/chat.enums.ts',
};

export async function resolve(specifier, context, next) {
    if (ALIASES[specifier]) return { url: pathToFileURL(`${LIB}/${ALIASES[specifier]}`).href, shortCircuit: true };
    if (specifier.startsWith('#lib/')) throw new Error(`tools-ui hooks: no alias for ${specifier}`);
    if (specifier.startsWith('.') && context.parentURL?.endsWith('.ts')) {
        const base = fileURLToPath(new URL(specifier, context.parentURL));
        if (!existsSync(base) && existsSync(`${base}.ts`)) return { url: pathToFileURL(`${base}.ts`).href, shortCircuit: true };
    }
    return next(specifier, context);
}
