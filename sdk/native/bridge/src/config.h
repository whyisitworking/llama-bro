// Model config JSON -> common_params, with the phone defaults (P1 T3, design §8, D17).
#pragma once

#include "lb_common.h"

#include "common.h"

#include <string>

// Fills `out` from `cfg` exactly as llama-server's flags would (each key goes through the matching
// common/arg.cpp option handler). n_ctx "auto" is left at 0 here and resolved at load time by
// lb_resolve_auto_ctx. Unknown key: returns false, `err` names it.
bool lb_parse_model_config(const json & cfg, common_params & out, std::string & err);

// true when cfg asks for the automatic context size ("n_ctx" absent or "auto")
bool lb_config_wants_auto_ctx(const json & cfg);

// Auto context = min(8192, <arch>.context_length), read from the GGUF header without loading tensors.
// P2 replaces this with common/fit.h.
bool lb_resolve_auto_ctx(common_params & params, std::string & err);
