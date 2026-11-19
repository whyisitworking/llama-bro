# Writes a variant of mul_mat_vec_q4_k.comp for the bisection in F174: each variant keeps or drops one part of the
# inner loop, to find what Qualcomm's driver fails to compile. Rebuild ggml-vulkan with the variant in place, copy the
# library over the one in the phone's build directory and run test-backend-ops -o MUL_MAT -p "type_a=q4_K,".
# usage: python3 q4k-variant.py BASE.comp OUT.comp VARIANT   (empty, dmonly, aonly, a16only, bonly, b2, b1)
import sys, re
base, out, v = sys.argv[1], sys.argv[2], sys.argv[3]
s = open(base).read()

def body(new):
    """Replace calc_superblock's body (between y1_idx and the closing of the function)."""
    global s
    a = s.index("    const uint y1_idx")
    b = s.index("void compute_outputs")
    s = s[:a] + new + "}\n\n" + s[b:]

if v == 'empty':
    body("    [[unroll]] for (uint n = 0; n < num_rows; ++n) { [[unroll]] for (uint j = 0; j < NUM_COLS; ++j) { temp[j][n] += FLOAT_TYPE(i); } }\n")
elif v == 'dmonly':
    body("""    const uint y1_idx = i * QUANT_K + y_offset;
    [[unroll]] for (uint n = 0; n < num_rows; ++n) {
        const uint ib0 = a_offset + (first_row+n)*num_blocks_per_row;
        const FLOAT_TYPEV2 dm = FLOAT_TYPEV2(data_a[ib0 + i].dm);
        [[unroll]] for (uint j = 0; j < NUM_COLS; ++j) { temp[j][n] += dm.x * FLOAT_TYPE(data_b_v4[(j*p.batch_stride_b + b_offset + y1_idx) / 4].x); }
    }
""")
elif v == 'aonly':
    body("""    [[unroll]] for (uint n = 0; n < num_rows; ++n) {
        const uint ib0 = a_offset + (first_row+n)*num_blocks_per_row;
        const FLOAT_TYPEV2 dm = FLOAT_TYPEV2(data_a[ib0 + i].dm);
        [[unroll]] for (uint j = 0; j < NUM_COLS; ++j) { temp[j][n] += dm.x; }
    }
""")
elif v == 'a16only':
    body("""    [[unroll]] for (uint n = 0; n < num_rows; ++n) {
        const uint ib0 = a_offset + (first_row+n)*num_blocks_per_row;
        const FLOAT_TYPE s0 = FLOAT_TYPE(data_a_packed16[ib0 + i].scales[0]);
        [[unroll]] for (uint j = 0; j < NUM_COLS; ++j) { temp[j][n] += s0; }
    }
""")
elif v == 'bonly':
    body("""    const uint y1_idx = i * QUANT_K + y_offset;
    [[unroll]] for (uint n = 0; n < num_rows; ++n) {
        [[unroll]] for (uint j = 0; j < NUM_COLS; ++j) { temp[j][n] += FLOAT_TYPE(data_b_v4[(j*p.batch_stride_b + b_offset + y1_idx) / 4].x); }
    }
""")
elif v == 'b2':
    s = re.sub(r"vec4\(data_b_v4\[\((.*?)\) / 4(?: *\+ *(\d+))?\s*\]\)",
               lambda m: "vec4(data_b_v2[(%s) / 2 + %d], data_b_v2[(%s) / 2 + %d])" % (m.group(1), 2*int(m.group(2) or 0), m.group(1), 2*int(m.group(2) or 0)+1), s)
elif v == 'b1':
    s = re.sub(r"vec4\(data_b_v4\[\((.*?)\) / 4(?: *\+ *(\d+))?\s*\]\)",
               lambda m: "vec4(" + ", ".join("data_b[(%s) + %d]" % (m.group(1), 4*int(m.group(2) or 0)+k) for k in range(4)) + ")", s)
open(out, 'w').write(s)
