# Gebruik: python3 tools/llm/mk-testmodel.py uit.gguf [pooling]   (na ./tools/llm-build.sh, dat tools/lcpp ophaalt)
# Maakt een piepklein qwen3-model met willekeurige gewichten (om de motor te testen; geen zinnige tekst)
import sys, numpy as np
import os
LCPP = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'lcpp')
sys.path.insert(0, os.path.join(LCPP, 'gguf-py'))
import gguf
src = gguf.GGUFReader(os.path.join(LCPP, 'models', 'ggml-vocab-qwen2.gguf'))
out, pooling = sys.argv[1], int(sys.argv[2]) if len(sys.argv) > 2 else -1
w = gguf.GGUFWriter(out, 'qwen3')
for k, f in src.fields.items():
    if not k.startswith('tokenizer.'): continue
    t = f.types[0]
    if t == gguf.GGUFValueType.ARRAY:
        st = f.types[1]
        vals = [bytes(f.parts[i]).decode('utf-8', 'replace') if st == gguf.GGUFValueType.STRING else f.parts[i][0].item() for i in f.data]
        w.add_array(k, vals)
    elif t == gguf.GGUFValueType.STRING: w.add_string(k, bytes(f.parts[f.data[0]]).decode())
    elif t == gguf.GGUFValueType.BOOL: w.add_bool(k, bool(f.parts[f.data[0]][0]))
    elif t == gguf.GGUFValueType.UINT32: w.add_uint32(k, int(f.parts[f.data[0]][0]))
    elif t == gguf.GGUFValueType.INT32: w.add_int32(k, int(f.parts[f.data[0]][0]))
    else: print('skip', k, t)
nv = len(src.fields['tokenizer.ggml.tokens'].data)
if 'tokenizer.chat_template' not in src.fields:
    w.add_string('tokenizer.chat_template', "{% for message in messages %}{{'<|im_start|>' + message['role'] + '\n' + message['content'] + '<|im_end|>' + '\n'}}{% endfor %}{% if add_generation_prompt %}{{ '<|im_start|>assistant\n' }}{% endif %}")
E, H, KV, HD, FF, L = 64, 4, 2, 16, 128, 2
w.add_context_length(2048); w.add_embedding_length(E); w.add_block_count(L); w.add_feed_forward_length(FF)
w.add_head_count(H); w.add_head_count_kv(KV); w.add_key_length(HD); w.add_value_length(HD)
w.add_layer_norm_rms_eps(1e-6); w.add_rope_freq_base(1e6)
if pooling >= 0: w.add_pooling_type(gguf.PoolingType(pooling))
r = np.random.default_rng(1)
def T(n, *shape): w.add_tensor(n, (r.standard_normal(shape) * 0.05).astype(np.float32))
T('token_embd.weight', nv, E); w.add_tensor('output_norm.weight', np.ones(E, np.float32))
for i in range(L):
    p = f'blk.{i}.'
    w.add_tensor(p + 'attn_norm.weight', np.ones(E, np.float32)); w.add_tensor(p + 'ffn_norm.weight', np.ones(E, np.float32))
    T(p + 'attn_q.weight', H * HD, E); T(p + 'attn_k.weight', KV * HD, E); T(p + 'attn_v.weight', KV * HD, E); T(p + 'attn_output.weight', E, H * HD)
    w.add_tensor(p + 'attn_q_norm.weight', np.ones(HD, np.float32)); w.add_tensor(p + 'attn_k_norm.weight', np.ones(HD, np.float32))
    T(p + 'ffn_gate.weight', FF, E); T(p + 'ffn_up.weight', FF, E); T(p + 'ffn_down.weight', E, FF)
w.write_header_to_file(); w.write_kv_data_to_file(); w.write_tensors_to_file(); w.close()
print('ok', out, nv)
