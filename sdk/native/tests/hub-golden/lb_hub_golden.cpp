// Golden data is read directly from upstream's resolution tests at the pinned llama.cpp revision.
#define main lb_upstream_resolution_main
#include "tests/test-model-resolution.cpp"
#undef main
#include "gguf.h"

static common_json cases_json() {
    common_json result = {{"llama_cpp", "b12052"}};
    result["plan_cases"] = common_json::array();
    for (const auto & c : plan_cases) {
        result["plan_cases"].push_back({
            {"name", c.name}, {"files", c.files}, {"hf_repo", c.hf_repo}, {"hf_file", c.hf_file},
            {"sidecars", c.sidecars}, {"order_dependent", c.order_dependent},
            {"primary", c.primary}, {"model_files", c.model_files}, {"mmproj", c.mmproj},
            {"mtp", c.mtp}, {"dflash", c.dflash}, {"eagle3", c.eagle3}, {"dspark", c.dspark},
        });
    }
    result["real_repos"] = common_json::array();
    for (const auto & r : real_repos) {
        common_json plans = common_json::array();
        for (const auto & p : r.plans) {
            plans.push_back({
                {"tag", p.tag}, {"primary", p.primary}, {"parts", p.parts},
                {"mmproj", p.mmproj}, {"mtp", p.mtp}, {"dflash", p.dflash},
                {"eagle3", p.eagle3}, {"dspark", p.dspark},
            });
        }
        result["real_repos"].push_back({
            {"id", r.id}, {"files", real_files(r)}, {"plans", plans}, {"listing", r.listing},
        });
    }
    return result;
}

static int download(int argc, char ** argv) {
    if (argc < 8 || std::string(argv[2]) != "--endpoint" || std::string(argv[4]) != "--cache" ||
        std::string(argv[6]) != "--") {
        fprintf(stderr, "usage: lb-hub-golden download --endpoint URL --cache DIR -- -hf REF [args]\n");
        return 2;
    }
    const std::string endpoint = argv[3];
    const std::string cache = argv[5];
    if (setenv("MODEL_ENDPOINT", endpoint.c_str(), 1) != 0 || setenv("LLAMA_CACHE", cache.c_str(), 1) != 0) {
        perror("setenv");
        return 1;
    }
    std::filesystem::create_directories(cache);
    std::vector<std::string> args = {"download"};
    for (int i = 7; i < argc; i++) args.emplace_back(argv[i]);
    std::vector<char *> cargv;
    for (auto & arg : args) cargv.push_back(arg.data());
    common_params params;
    try {
        if (!common_params_parse((int) cargv.size(), cargv.data(), params, LLAMA_EXAMPLE_SERVER)) {
            return 2;
        }
        auto handler = common_models_handler_init(params, LLAMA_EXAMPLE_SERVER);
        class quiet_callback final : public common_download_callback {
        public:
            void on_start(const common_download_progress &) override {}
            void on_update(const common_download_progress &) override {}
            void on_done(const common_download_progress &, bool) override {}
        } callback;
        common_models_handler_apply(handler, params, &callback);
        auto relative = [&](const std::string & path) -> std::string {
            if (path.empty()) return "";
            const auto rel = std::filesystem::path(path).lexically_relative(cache);
            if (rel.empty() || rel.native().rfind("..", 0) == 0) {
                throw std::runtime_error("downloaded path outside cache: " + path);
            }
            return rel.generic_string();
        };
        common_json paths = {
            {"model", relative(params.model.path)},
            {"mmproj", relative(params.mmproj.path)},
            {"draft", relative(params.speculative.draft.mparams.path)},
        };
        puts(paths.dump(2).c_str());
        return 0;
    } catch (const std::exception & e) {
        fprintf(stderr, "download failed: %s\n", e.what());
        return 1;
    }
}

int main(int argc, char ** argv) {
    if (argc >= 2 && std::string(argv[1]) == "download") return download(argc, argv);
    if (argc >= 3 && std::string(argv[1]) == "gguf-offset") {
        common_json files = common_json::array();
        for (int i = 2; i < argc; i++) {
            gguf_init_params options = {true, nullptr};
            gguf_context * header = gguf_init_from_file(argv[i], options);
            if (!header) {
                fprintf(stderr, "cannot read GGUF header: %s\n", argv[i]);
                return 1;
            }
            files.push_back({{"path", argv[i]}, {"offset", gguf_get_data_offset(header)}});
            gguf_free(header);
        }
        puts(common_json{{"files", files}}.dump().c_str());
        return 0;
    }
    if (argc != 2) {
        fprintf(stderr, "usage: lb-hub-golden {cases|self-test|download|gguf-offset FILE...}\n");
        return 2;
    }
    const std::string mode = argv[1];
    if (mode == "self-test") return lb_upstream_resolution_main();
    if (mode == "cases") {
        const auto result = cases_json();
        if (result["plan_cases"].size() != 14 || result["real_repos"].size() != 171) {
            fprintf(stderr, "unexpected upstream resolution table sizes\n");
            return 1;
        }
        puts(result.dump(2).c_str());
        return 0;
    }
    fprintf(stderr, "unknown mode: %s\n", argv[1]);
    return 2;
}
