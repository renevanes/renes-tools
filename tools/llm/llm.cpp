// Tools: lokale AI-motor (llama.cpp), als los programma in de app (lib/arm64-v8a/libllm.so).
// Praat met de app via stdin/stdout, één JSON-object per regel. Geen netwerk, geen server: alleen deze app
// kan ermee praten (het is haar eigen kindproces).
//
//   libllm.so -m model.gguf [-c 4096] [-t 4] [--embed] [--nothink]
//
// In (stdin):
//   {"op":"chat","messages":[{"role":"system","content":"…"},{"role":"user","content":"…"}],"max":768,"temp":0.7,"top_p":0.8,"top_k":20}
//   {"op":"embed","texts":["…","…"],"dims":512}
//   {"op":"cancel"}            (stopt het lopende antwoord)
// Uit (stdout):
//   {"ready":true,"ctx":4096,"embd":1024,"desc":"…"}
//   {"t":"stukje tekst"} …  {"done":true,"n":123,"prompt":456,"ms":7890,"cut":0}
//   {"e":[[…],[…]]}
//   {"error":"…"}
//
// Licentie: GPL-3.0-or-later (zoals de app); llama.cpp zelf is MIT.

#include "llama.h"
#include "nlohmann/json.hpp"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <clocale>
#include <cmath>
#include <condition_variable>
#include <cstdio>
#include <cstring>
#include <deque>
#include <iostream>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

using json = nlohmann::json;

static std::mutex g_out_mx;
static std::atomic<long> g_id{0}; // "id" van de opdracht die nu loopt: gaat mee in elk antwoord, zodat de app ze kan koppelen
static void emit(json j, bool tag = true) {
    long id = g_id.load();
    if (tag && id != 0) j["id"] = id;
    std::string s = j.dump(-1, ' ', false, json::error_handler_t::replace);
    std::lock_guard<std::mutex> lk(g_out_mx);
    fwrite(s.data(), 1, s.size(), stdout);
    fputc('\n', stdout);
    fflush(stdout);
}
static void emit_error(const std::string & m) { emit(json{{"error", m}}); }

// ---------- invoer: een eigen thread leest stdin, zodat "cancel" tijdens een antwoord binnenkomt ----------
static std::mutex g_in_mx;
static std::condition_variable g_in_cv;
static std::deque<json> g_in;
static std::atomic<bool> g_eof{false};
// "cancel" stopt alles wat vóór die regel binnenkwam (ook als het nog niet begonnen was)
static std::atomic<long> g_seq{0}, g_cancel_upto{0}, g_cur{0};
static bool cancelled() { return g_cur.load() <= g_cancel_upto.load(); }

static void reader() {
    std::string line;
    while (std::getline(std::cin, line)) {
        if (line.empty()) continue;
        json j;
        try { j = json::parse(line); } catch (...) { emit(json{{"error", "Ongeldige opdracht"}}, false); continue; }
        if (j.is_object() && j.value("op", "") == "cancel") { g_cancel_upto = g_seq.load(); continue; }
        std::lock_guard<std::mutex> lk(g_in_mx);
        j["_seq"] = ++g_seq;
        g_in.push_back(std::move(j));
        g_in_cv.notify_one();
    }
    g_eof = true; // de rest van de wachtrij nog afmaken, dan stoppen
    g_in_cv.notify_one();
}

// Hoeveel bytes vooraan een volledige UTF-8-tekst vormen (een token kan midden in een teken eindigen)
static size_t utf8_complete(const std::string & s) {
    size_t n = s.size(), i = n;
    // ga hooguit 3 bytes terug naar het begin van het laatste teken
    for (size_t k = 0; k < 4 && i > 0; k++) {
        unsigned char c = (unsigned char) s[i - 1];
        if ((c & 0xC0) == 0x80) { i--; continue; }      // vervolgbyte
        size_t need = (c & 0x80) == 0 ? 1 : (c & 0xE0) == 0xC0 ? 2 : (c & 0xF0) == 0xE0 ? 3 : (c & 0xF8) == 0xF0 ? 4 : 1;
        return (n - (i - 1) >= need) ? n : i - 1;
    }
    return n;
}

static std::vector<llama_token> tokenize(const llama_vocab * vocab, const std::string & text, bool add_special, bool parse_special) {
    int n = -llama_tokenize(vocab, text.data(), (int32_t) text.size(), nullptr, 0, add_special, parse_special);
    if (n <= 0) return {};
    std::vector<llama_token> out(n);
    if (llama_tokenize(vocab, text.data(), (int32_t) text.size(), out.data(), n, add_special, parse_special) < 0) return {};
    return out;
}

static std::string piece(const llama_vocab * vocab, llama_token t) {
    char buf[256];
    int n = llama_token_to_piece(vocab, t, buf, sizeof(buf), 0, false);
    if (n < 0) {
        std::string big(-n, '\0');
        n = llama_token_to_piece(vocab, t, &big[0], (int32_t) big.size(), 0, false);
        return n > 0 ? big.substr(0, n) : std::string();
    }
    return std::string(buf, n);
}

struct Engine {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    const llama_vocab * vocab = nullptr;
    llama_batch_ext * batch = nullptr;
    std::vector<llama_token> cache; // wat er nu in het geheugen van het model staat (voor hergebruik bij een vervolgvraag)
    bool embed = false, nothink = false;
    int n_ctx = 4096, n_batch = 512;
    // Hybride/recurrente modellen (Qwen3.5: lineaire aandacht): hun toestand kan niet achteraan ingekort worden
    // (llama_memory_seq_rm geeft dan false). Daarom een momentopname van die toestand na de eerste ck_n tokens
    // van het geheugen (het begin van het antwoord), zodat een vervolgvraag niet alles opnieuw hoeft te lezen.
    bool recurrent = false;
    std::vector<uint8_t> ck;
    size_t ck_n = 0;
    size_t gen_at = 0; // uit chat_tokens: waar het antwoord begint (vóór het lege denkblok)
};

static void ck_drop(Engine & E) { E.ck.clear(); E.ck.shrink_to_fit(); E.ck_n = 0; }
static void mem_clear(Engine & E) { llama_memory_clear(llama_get_memory(E.ctx), true); E.cache.clear(); ck_drop(E); }

// Momentopname van de recurrente toestand (alleen dat deel; de gewone aandacht is wel in te korten)
static void ck_save(Engine & E) {
    size_t n = llama_state_seq_get_size_ext(E.ctx, 0, LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY);
    if (n == 0) { ck_drop(E); return; }
    try { E.ck.resize(n); } catch (...) { ck_drop(E); return; }
    if (llama_state_seq_get_data_ext(E.ctx, E.ck.data(), n, 0, LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY) != n) { ck_drop(E); return; }
    E.ck_n = E.cache.size();
}

// Het geheugen terugbrengen tot (hooguit) de eerste n tokens; geeft hoeveel er over zijn. Lukt inkorten niet
// (hybride model), dan terug naar de momentopname als die binnen die n valt, anders helemaal leeg.
// force: ook als E.cache al n lang is (het model kan meer verwerkt hebben dan E.cache weet, bijv. na een fout).
static size_t mem_trim(Engine & E, size_t n, bool force = false) {
    if (n > E.cache.size() || (n == E.cache.size() && !force)) return E.cache.size();
    llama_memory_t mem = llama_get_memory(E.ctx);
    if (llama_memory_seq_rm(mem, 0, (llama_pos) n, -1)) E.cache.resize(n);
    else if (E.ck_n > 0 && E.ck_n <= n
             && llama_state_seq_set_data_ext(E.ctx, E.ck.data(), E.ck.size(), 0, LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY) == E.ck.size()
             && llama_memory_seq_rm(mem, 0, (llama_pos) E.ck_n, -1)) E.cache.resize(E.ck_n);
    else mem_clear(E);
    if (E.ck_n > E.cache.size()) ck_drop(E);
    return E.cache.size();
}

// Tokens verwerken vanaf positie pos0; alleen voor het laatste token de uitkomst
// done (als niet null): hoeveel tokens er verwerkt in het geheugen staan, ook als het halverwege stopte
static bool decode(Engine & E, const llama_token * toks, int n, int pos0, bool last_logits, int * done = nullptr) {
    if (done) *done = 0;
    for (int off = 0; off < n; off += E.n_batch) {
        int m = std::min(E.n_batch, n - off);
        llama_batch_ext_clear(E.batch);
        for (int i = 0; i < m; i++) {
            int32_t idx = llama_batch_ext_add_token(E.batch, 0, toks[off + i]);
            if (idx < 0) return false;
            llama_pos p = pos0 + off + i;
            llama_batch_ext_set_pos(E.batch, idx, &p);
        }
        if (last_logits && off + m == n) llama_batch_ext_set_output_logits(E.batch, m - 1, true);
        if (llama_process(E.ctx, LLAMA_PROCESS_TYPE_DECODE, E.batch) != 0) return false;
        if (done) *done = off + m;
        if (cancelled()) return false;
    }
    return true;
}

// Het gesprek in tokens. De tekst van de berichten (van de gebruiker, uit notities of eerdere antwoorden) wordt
// tokenized zonder speciale tokens: "<|im_start|>", "<|im_end|>" en dergelijke in een notitie blijven gewone tekst
// en kunnen het gespreksformaat niet openbreken. Alleen de tekst van het sjabloon zelf mag speciale tokens bevatten.
// Werkwijze: eerst het sjabloon invullen met merktekens (\x1F<nummer>\x1F) in plaats van de berichten, dan stuk
// voor stuk tokenizen. Geeft een lege lijst als het model geen bekend gespreksformaat heeft.
static const char MARK = '\x1F';
static std::vector<llama_token> chat_tokens(Engine & E, const std::vector<std::pair<std::string, std::string>> & msgs) {
    std::vector<std::string> marks;
    std::vector<llama_chat_message> cm;
    for (size_t i = 0; i < msgs.size(); i++) marks.push_back(std::string(1, MARK) + std::to_string(i) + std::string(1, MARK));
    for (size_t i = 0; i < msgs.size(); i++) cm.push_back({msgs[i].first.c_str(), marks[i].c_str()});
    const char * tmpl = llama_model_chat_template(E.model, nullptr);
    std::vector<char> buf(256 + msgs.size() * 64);
    int n = llama_chat_apply_template(tmpl, cm.data(), cm.size(), true, buf.data(), (int32_t) buf.size());
    if (n > (int) buf.size()) { buf.resize(n); n = llama_chat_apply_template(tmpl, cm.data(), cm.size(), true, buf.data(), (int32_t) buf.size()); }
    if (n < 0) return {};
    std::string s(buf.data(), n);

    std::vector<llama_token> out;
    std::vector<bool> used(msgs.size(), false);
    bool first = true;
    auto add = [&](const std::string & text, bool special) {
        if (text.empty()) return;
        std::vector<llama_token> t = tokenize(E.vocab, text, first, special);
        first = false;
        out.insert(out.end(), t.begin(), t.end());
    };
    size_t pos = 0;
    while (pos < s.size()) {
        size_t a = s.find(MARK, pos);
        if (a == std::string::npos) { add(s.substr(pos), true); break; }
        size_t b = s.find(MARK, a + 1);
        if (b == std::string::npos) return {};
        size_t idx = 0;
        try { idx = std::stoul(s.substr(a + 1, b - a - 1)); } catch (...) { return {}; }
        if (idx >= msgs.size() || used[idx]) return {};
        used[idx] = true;
        add(s.substr(pos, a - pos), true);   // sjabloon: speciale tokens wel
        add(msgs[idx].second, false);         // bericht: speciale tokens niet
        pos = b + 1;
    }
    for (bool u : used) if (!u) return {}; // het sjabloon liet een bericht weg: liever een fout dan een half gesprek
    // Hier begint het antwoord. Een vervolgvraag heeft tot hier hetzelfde begin (daarna komt het antwoord zonder
    // denkblok), dus hier maakt een hybride model zijn momentopname.
    E.gen_at = out.size();
    // Qwen3, Qwen3.5 en dergelijke: niet eerst "hardop nadenken", meteen antwoorden (zoals enable_thinking=false)
    if (E.nothink) add("<think>\n\n</think>\n\n", true);
    return out;
}

static void do_chat(Engine & E, const json & j) {
    auto t0 = std::chrono::steady_clock::now();
    std::vector<std::pair<std::string, std::string>> msgs;
    if (!j.contains("messages") || !j["messages"].is_array()) { emit_error("Geen berichten"); return; }
    for (auto & m : j["messages"]) {
        std::string role = m.value("role", "user"), content = m.value("content", "");
        if (role != "system" && role != "user" && role != "assistant") role = "user";
        content.erase(std::remove(content.begin(), content.end(), MARK), content.end()); // merkteken voor chat_tokens
        msgs.emplace_back(role, content);
    }
    if (msgs.empty()) { emit_error("Geen berichten"); return; }
    int max_new = std::max(1, std::min(j.value("max", 768), E.n_ctx / 2));
    float temp = j.value("temp", 0.7f), top_p = j.value("top_p", 0.8f);
    int top_k = j.value("top_k", 20);

    // Past het niet: oudste berichten (na de systeemtekst) weglaten, het laatste blijft altijd
    std::vector<llama_token> toks;
    int cut = 0, trim = 0;
    while (true) {
        toks = chat_tokens(E, msgs);
        if (toks.empty()) { emit_error("Dit model heeft geen bekend gespreksformaat"); return; }
        if ((int) toks.size() + max_new <= E.n_ctx) break;
        size_t first = (!msgs.empty() && msgs[0].first == "system") ? 1 : 0;
        if (msgs.size() - first <= 1) {
            // alleen nog de laatste vraag: die inkorten
            auto & last = msgs.back().second;
            if (last.size() < 200) { emit_error("Je bericht is te lang voor dit model"); return; }
            // het begin bewaren (daar staat de vraag); op een UTF-8-tekengrens knippen
            size_t n = last.size() * 3 / 4;
            while (n > 0 && (((unsigned char) last[n]) & 0xC0) == 0x80) n--;
            last = last.substr(0, n);
            trim++;
        } else {
            msgs.erase(msgs.begin() + first);
            cut++;
        }
    }

    // Hergebruik wat al in het geheugen staat (zelfde begin van het gesprek)
    size_t keep = 0;
    while (keep < E.cache.size() && keep < toks.size() && E.cache[keep] == toks[keep]) keep++;
    if (keep == toks.size() && keep > 0) keep--; // minstens één token opnieuw, voor de uitkomst
    keep = mem_trim(E, keep);
    const size_t gen_at = E.gen_at;

    // Inlezen; een hybride model maakt onderweg een momentopname waar het antwoord begint
    int got = 0;
    bool ok = true;
    size_t at = keep;
    if (E.recurrent && gen_at > keep && gen_at < toks.size()) {
        ok = decode(E, toks.data() + at, (int) (gen_at - at), (int) at, false, &got);
        if (ok) { E.cache.insert(E.cache.end(), toks.begin() + at, toks.begin() + gen_at); at = gen_at; got = 0; ck_save(E); }
    }
    if (ok) ok = decode(E, toks.data() + at, (int) (toks.size() - at), (int) at, true, &got);
    if (!ok) {
        if (cancelled()) {
            // Gestopt tijdens het inlezen: wat al verwerkt is, blijft (de volgende vraag met hetzelfde begin is dan snel)
            E.cache.insert(E.cache.end(), toks.begin() + at, toks.begin() + at + got);
            mem_trim(E, E.cache.size(), true); // een half verwerkt stuk (fout tijdens het stoppen) weghalen
            emit(json{{"done", true}, {"cancelled", true}, {"n", 0}, {"kept", (int) E.cache.size()}});
            return;
        }
        mem_clear(E);
        emit_error("Het model kon de vraag niet verwerken");
        return;
    }
    E.cache.insert(E.cache.end(), toks.begin() + at, toks.end());
    if (E.recurrent && gen_at == toks.size() && E.ck_n < gen_at) ck_save(E); // zonder denkblok: na de hele vraag
    const int reused = (int) keep;

    llama_sampler * smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(top_k > 0 ? top_k : 40));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(top_p, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(llama_vocab_n_tokens(E.vocab), 64, 1.05f, 0.0f, 0.3f));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temp));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    std::string pending, out;
    bool in_think = false, at_start = true;
    int n = 0;
    bool full = false;
    while (n < max_new) {
        if (cancelled()) break;
        llama_token t = llama_sampler_sample(smpl, E.ctx, -1);
        if (llama_vocab_is_eog(E.vocab, t)) break;
        if ((int) E.cache.size() + 1 >= E.n_ctx) { full = true; break; }
        n++;
        std::string p = piece(E.vocab, t);
        pending += p;
        // een eventueel denkblok (<think>…</think>) niet tonen
        if (at_start) {
            static const std::string TH = "<think>";
            size_t a = pending.find_first_not_of(" \n");
            if (a != std::string::npos) {
                std::string head = pending.substr(a);
                if (head.compare(0, TH.size(), TH) == 0) { in_think = true; at_start = false; }
                else if (head.size() >= TH.size() || TH.compare(0, head.size(), head) != 0) at_start = false;
                // anders: misschien het begin van <think>, nog even wachten
            }
        }
        if (in_think) {
            size_t e = pending.find("</think>");
            if (e != std::string::npos) { pending = pending.substr(e + 8); in_think = false; size_t a = pending.find_first_not_of(" \n"); pending = a == std::string::npos ? "" : pending.substr(a); }
        }
        if (!in_think && !at_start) {
            size_t ok = utf8_complete(pending);
            if (ok > 0) { emit(json{{"t", pending.substr(0, ok)}}); out += pending.substr(0, ok); pending.erase(0, ok); }
        }
        // het gekozen token verwerken voor het volgende
        llama_batch_ext_clear(E.batch);
        int32_t idx = llama_batch_ext_add_token(E.batch, 0, t);
        llama_pos pos = (llama_pos) E.cache.size();
        llama_batch_ext_set_pos(E.batch, idx, &pos);
        llama_batch_ext_set_output_logits(E.batch, idx, true);
        if (llama_process(E.ctx, LLAMA_PROCESS_TYPE_DECODE, E.batch) != 0) { full = true; break; }
        E.cache.push_back(t);
    }
    if (!in_think && !pending.empty()) emit(json{{"t", pending}});
    llama_sampler_free(smpl);
    long ms = (long) std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - t0).count();
    emit(json{{"done", true}, {"n", n}, {"prompt", (int) toks.size()}, {"reused", reused}, {"ms", ms}, {"cut", cut}, {"trim", trim}, {"cancelled", cancelled()}, {"full", full}});
}

static void do_embed(Engine & E, const json & j) {
    if (!j.contains("texts") || !j["texts"].is_array()) { emit_error("Geen teksten"); return; }
    const int n_embd = llama_model_n_embd_out(E.model) > 0 ? llama_model_n_embd_out(E.model) : llama_model_n_embd(E.model);
    int dims = j.value("dims", n_embd);
    if (dims <= 0 || dims > n_embd) dims = n_embd;
    const enum llama_pooling_type pool = llama_pooling_type(E.ctx);
    llama_memory_t mem = llama_get_memory(E.ctx);
    json all = json::array();
    for (auto & tj : j["texts"]) {
        if (cancelled()) break;
        std::string text = tj.is_string() ? tj.get<std::string>() : std::string();
        std::vector<llama_token> toks = tokenize(E.vocab, text, true, false);
        if ((int) toks.size() > E.n_batch) toks.resize(E.n_batch); // lange tekst: het begin (de app knipt teksten zelf in stukken)
        if (pool == LLAMA_POOLING_TYPE_LAST && !llama_vocab_get_add_eos(E.vocab) && llama_vocab_eos(E.vocab) != LLAMA_TOKEN_NULL) {
            if ((int) toks.size() >= E.n_batch) toks.back() = llama_vocab_eos(E.vocab); else toks.push_back(llama_vocab_eos(E.vocab));
        }
        if (toks.empty()) { all.push_back(json::array()); continue; }
        if (mem) llama_memory_clear(mem, true);
        llama_batch_ext_clear(E.batch);
        for (size_t i = 0; i < toks.size(); i++) {
            int32_t idx = llama_batch_ext_add_token(E.batch, 0, toks[i]);
            llama_pos p = (llama_pos) i;
            llama_batch_ext_set_pos(E.batch, idx, &p);
            llama_batch_ext_set_output_embd(E.batch, idx, true);
        }
        if (llama_process(E.ctx, LLAMA_PROCESS_TYPE_DECODE, E.batch) != 0) { emit_error("Het zoekmodel kon de tekst niet verwerken"); return; }
        const float * v = pool == LLAMA_POOLING_TYPE_NONE ? llama_get_embeddings_ith(E.ctx, -1) : llama_get_embeddings_seq(E.ctx, 0);
        if (!v) { emit_error("Het zoekmodel gaf geen uitkomst"); return; }
        // Eerste "dims" waarden (Matryoshka), daarna op lengte 1
        double norm = 0;
        for (int i = 0; i < dims; i++) norm += (double) v[i] * v[i];
        norm = norm > 0 ? 1.0 / std::sqrt(norm) : 0;
        json row = json::array();
        for (int i = 0; i < dims; i++) row.push_back(std::round(v[i] * norm * 1e5) / 1e5);
        all.push_back(std::move(row));
    }
    if (cancelled()) { emit(json{{"error", "Gestopt"}, {"cancelled", true}}); return; }
    emit(json{{"e", all}});
}

int main(int argc, char ** argv) {
    std::setlocale(LC_NUMERIC, "C");
    std::string path;
    Engine E;
    int threads = 4;
    for (int i = 1; i < argc; i++) {
        std::string a = argv[i];
        if (a == "-m" && i + 1 < argc) path = argv[++i];
        else if (a == "-c" && i + 1 < argc) E.n_ctx = std::max(256, atoi(argv[++i]));
        else if (a == "-t" && i + 1 < argc) threads = std::max(1, atoi(argv[++i]));
        else if (a == "--embed") E.embed = true;
        else if (a == "--nothink") E.nothink = true;
    }
    if (path.empty()) { emit_error("Geen model opgegeven"); return 2; }

    llama_log_set([](enum ggml_log_level level, const char * text, void *) { if (level >= GGML_LOG_LEVEL_ERROR) fputs(text, stderr); }, nullptr);
    llama_backend_init();

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    mp.progress_callback = [](float, void *) { return true; }; // geen voortgangspuntjes op stderr
    E.model = llama_model_load_from_file(path.c_str(), mp);
    if (!E.model) { emit_error("Het model kon niet geladen worden"); return 1; }
    E.vocab = llama_model_get_vocab(E.model);

    llama_context_params cp = llama_context_default_params();
    if (E.embed) {
        E.n_ctx = std::min(E.n_ctx, 2048);
        E.n_batch = E.n_ctx;
        cp.embeddings = true;
        cp.n_ubatch = E.n_ctx;
    } else {
        E.n_batch = 512;
    }
    cp.n_ctx = E.n_ctx;
    cp.n_batch = E.n_batch;
    cp.n_seq_max = 1;
    cp.n_threads = threads;
    cp.n_threads_batch = threads;
    E.ctx = llama_init_from_model(E.model, cp);
    if (!E.ctx) { emit_error("Te weinig geheugen voor dit model"); llama_model_free(E.model); return 1; }
    E.n_ctx = (int) llama_n_ctx(E.ctx);
    E.batch = llama_batch_ext_init(E.ctx);
    E.recurrent = llama_model_is_recurrent(E.model) || llama_model_is_hybrid(E.model);
    if (!E.batch) { emit_error("Te weinig geheugen voor dit model"); return 1; }

    char desc[256] = {0};
    llama_model_desc(E.model, desc, sizeof(desc));
    emit(json{{"ready", true}, {"ctx", E.n_ctx}, {"embd", llama_model_n_embd(E.model)}, {"desc", desc}, {"embed", E.embed}, {"recurrent", E.recurrent}});

    std::thread rt(reader);
    rt.detach();
    while (true) {
        json j;
        {
            std::unique_lock<std::mutex> lk(g_in_mx);
            g_in_cv.wait(lk, [] { return !g_in.empty() || g_eof; });
            if (g_in.empty()) break; // stdin dicht: de app wil dat we stoppen
            j = std::move(g_in.front());
            g_in.pop_front();
        }
        std::string op = j.is_object() ? j.value("op", "") : "";
        g_cur = j.value("_seq", 0L);
        g_id = j.contains("id") && j["id"].is_number_integer() ? j["id"].get<long>() : 0L;
        try {
            if (op == "chat" && !E.embed) do_chat(E, j);
            else if (op == "embed" && E.embed) do_embed(E, j);
            else if (op == "quit") break;
            else emit_error("Onbekende opdracht");
        } catch (const std::exception & e) {
            emit_error(std::string("Fout: ") + e.what());
        }
    }
    llama_batch_ext_free(E.batch);
    llama_free(E.ctx);
    llama_model_free(E.model);
    llama_backend_free();
    return 0;
}
