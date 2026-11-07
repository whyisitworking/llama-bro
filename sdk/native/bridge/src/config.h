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

// Auto context without a memory budget = min(cap or 8192, <arch>.context_length), read from the GGUF header without
// loading tensors (callers that don't know the device's memory).
bool lb_resolve_auto_ctx(common_params & params, std::string & err, uint32_t n_ctx_cap = 0);

// "memory_budget_mib": what the model may use in total (weights, context, compute, on every device), from the app's
// view of available memory. Absent: 0.
int64_t lb_config_memory_budget_mib(const json & cfg);

// Auto context fitted to `budget_bytes` (P2 T7, D33): upstream's own dry-run measurement (common_fit_measure) at the
// minimum (4096) and the training context, summed over every device and the host, since a phone has one RAM, then
// upstream's linear interpolation between them. Sets params.n_ctx; `report` gets the numbers. When even the minimum
// doesn't fit: false with `insufficient` set.
// `n_ctx_cap` (>0) bounds the result: ContextSize.Auto's 16384 or an app's UpTo(n) (D34).
bool lb_fit_auto_ctx(common_params & params, int64_t budget_bytes, json & report, bool & insufficient, std::string & err,
                     uint32_t n_ctx_cap = 0);

// "n_ctx_cap": the ceiling of the automatic context, 0 = the training context
uint32_t lb_config_ctx_cap(const json & cfg);
